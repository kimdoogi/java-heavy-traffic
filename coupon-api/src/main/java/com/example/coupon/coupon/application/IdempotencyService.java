package com.example.coupon.coupon.application;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/**
 * Idempotency-Key 재시도 방어 (PLAN E7, §1.2.1). SET NX로 키를 클레임한 요청만 액션을 실행하고,
 * 결과(상태코드+본문)를 같은 키에 캐시한다. 이후 같은 키 재시도는 액션을 다시 실행하지 않고
 * 캐시된 응답을 그대로 재생 — 중복 발급을 구조적으로 막고 응답을 일관되게 만든다.
 *
 * /issue 전용으로만 쓴다: 액션 실행 중 예외가 나면 클레임을 지우고 재시도를 허용하는데,
 * 이 규칙은 액션이 "전부 실행되거나 전부 안 되거나"일 때만 안전하다. issue-and-notify처럼
 * DB 커밋 후 별도 비트랜잭션 호출이 실패할 수 있는 액션에 쓰면, 재시도가 재생이 아니라
 * 새 응답(already_issued 409)을 만들어버려 오히려 응답 일관성이 깨진다.
 */
@Service
public class IdempotencyService {

    private static final String KEY_PREFIX = "idempotency:";
    private static final String PROCESSING_MARKER = "__PROCESSING__";

    // 이 서비스가 직렬화하는 건 {status, body}뿐이고 body는 컨트롤러가 만드는 String/Long/boolean 값의
    // Map이라 날짜·커스텀 타입이 없다 — 앱 전역 설정(HTTP 메시지 컨버터용)에 얽매일 이유가 없어 직접 소유한다.
    // (Spring Boot 4/Spring 7은 Jackson 3(tools.jackson.databind.ObjectMapper)을 기본 빈으로 등록한다.
    //  Jackson 2(com.fasterxml.jackson.databind.ObjectMapper, 이 클래스가 쓰는 타입)는 클래스패스엔
    //  있어도 빈이 없어 DI가 NoSuchBeanDefinitionException으로 깨짐 — P-002 참고. 직접 생성으로 회피.)
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final StringRedisTemplate redis;
    private final Duration lockTtl;
    private final Duration resultTtl;

    public IdempotencyService(StringRedisTemplate redis,
                              @Value("${coupon.idempotency.lock-ttl-seconds}") long lockTtlSeconds,
                              @Value("${coupon.idempotency.result-ttl-seconds}") long resultTtlSeconds) {
        this.redis = redis;
        this.lockTtl = Duration.ofSeconds(lockTtlSeconds);
        this.resultTtl = Duration.ofSeconds(resultTtlSeconds);
    }

    public ResponseEntity<Map<String, Object>> execute(String idempotencyKey,
                                                        Supplier<ResponseEntity<Map<String, Object>>> action) {
        String redisKey = KEY_PREFIX + idempotencyKey;
        boolean claimed = Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(redisKey, PROCESSING_MARKER, lockTtl));
        if (claimed) {
            try {
                ResponseEntity<Map<String, Object>> response = action.get();
                redis.opsForValue().set(redisKey, serialize(response), resultTtl);
                return response;
            } catch (RuntimeException e) {
                redis.delete(redisKey);
                throw e;
            }
        }

        String cached = redis.opsForValue().get(redisKey);
        if (cached == null || PROCESSING_MARKER.equals(cached)) {
            throw new IdempotencyInProgressException(idempotencyKey);
        }
        return deserialize(cached);
    }

    private String serialize(ResponseEntity<Map<String, Object>> response) {
        try {
            return objectMapper.writeValueAsString(new CachedResponse(response.getStatusCode().value(), response.getBody()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize idempotent response", e);
        }
    }

    private ResponseEntity<Map<String, Object>> deserialize(String json) {
        try {
            CachedResponse cached = objectMapper.readValue(json, CachedResponse.class);
            return ResponseEntity.status(HttpStatus.valueOf(cached.status())).body(cached.body());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize idempotent response", e);
        }
    }

    private record CachedResponse(int status, Map<String, Object> body) {
    }
}
