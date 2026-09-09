package com.example.coupon.coupon.infra;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 멱등성 키의 Redis 접근 (E7). claim(SET NX)·조회·저장을 캡슐화하고 Redis 서킷브레이커로 감싼다(E8) —
 * 재고 레포(RedisCouponStockRepository)와 같은 계층(infra)·같은 브레이커를 공유한다.
 * 키 네임스페이스(idempotency:)는 여기서 소유한다. PROCESSING 마커·직렬화 등 "의미"는 IdempotencyService의 몫.
 */
@Repository
public class IdempotencyRepository {

    private static final String KEY_PREFIX = "idempotency:";

    private final StringRedisTemplate redis;
    private final RedisCircuitBreaker redisCb;

    public IdempotencyRepository(StringRedisTemplate redis, RedisCircuitBreaker redisCb) {
        this.redis = redis;
        this.redisCb = redisCb;
    }

    /** SET NX — 이 키를 처음 잡으면 true. 브레이커로 감쌈. */
    public boolean claim(String key, String marker, Duration ttl) {
        return Boolean.TRUE.equals(redisCb.call(() -> redis.opsForValue().setIfAbsent(KEY_PREFIX + key, marker, ttl)));
    }

    /** 현재 값(마커 또는 캐시된 응답) 조회. 브레이커로 감쌈. */
    public String find(String key) {
        return redisCb.call(() -> redis.opsForValue().get(KEY_PREFIX + key));
    }

    /** 결과 응답을 캐시(덮어쓰기). 브레이커로 감쌈. */
    public void store(String key, String value, Duration ttl) {
        redisCb.run(() -> redis.opsForValue().set(KEY_PREFIX + key, value, ttl));
    }

    /** 액션 실패 시 클레임 해제(best-effort). 브레이커 밖 — OPEN이어도 이미 실패 중인 원 예외를 마스킹하지 않는다. */
    public void release(String key) {
        redis.delete(KEY_PREFIX + key);
    }
}
