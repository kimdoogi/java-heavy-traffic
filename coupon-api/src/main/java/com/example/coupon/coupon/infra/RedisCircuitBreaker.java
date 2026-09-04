package com.example.coupon.coupon.infra;

import java.time.Duration;
import java.util.function.Supplier;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Redis 접근 공용 서킷브레이커 (E8). 실측(SLOW: Redis +400ms/명령)에서 timeout이 못 잡는 구멍을 메운다 —
 * 명령당 지연이 command timeout(1s) 아래면 매 요청이 "느린 성공"으로 3~5s까지 저하되는데, timeout은
 * 이걸 못 본다. 브레이커는 느린 호출(slowCallDuration 초과) 비율이 임계치를 넘으면 회로를 OPEN해
 * 이후 호출을 즉시 CallNotPermittedException으로 끊는다 — 느린 성공 대신 빠른 503으로 바꾸고(posture),
 * 죽어가는 Redis에 부하를 덜어 회복을 돕는다. Redis 완전 다운은 timeout 예외가 failureRate로 잡혀 같이 OPEN된다.
 *
 * Redis 하나 = 브레이커 하나. idempotency(IdempotencyRepository)와 재고(RedisCouponStockRepository)가
 * 같은 인스턴스를 공유한다. 두 repo 모두 infra라 여기(infra)에 둔다(application→infra 순환 방지).
 * DB 등 비-Redis 작업은 감싸지 않는다 — 감싸면 DB 지연을 Redis 실패로 오발한다(호출부에서 Redis 연산만 감쌀 것).
 */
@Component
public class RedisCircuitBreaker {

    private final CircuitBreaker breaker;

    public RedisCircuitBreaker(
            @Value("${coupon.redis.cb.sliding-window-size:50}") int windowSize,
            @Value("${coupon.redis.cb.minimum-calls:20}") int minimumCalls,
            @Value("${coupon.redis.cb.failure-rate-threshold:50}") float failureRateThreshold,
            @Value("${coupon.redis.cb.slow-call-rate-threshold:50}") float slowCallRateThreshold,
            @Value("${coupon.redis.cb.slow-call-duration-ms:300}") long slowCallDurationMs,
            @Value("${coupon.redis.cb.wait-in-open-ms:5000}") long waitInOpenMs,
            @Value("${coupon.redis.cb.permitted-in-half-open:5}") int permittedInHalfOpen) {
        // 정상 Redis <50ms, 실측 저하 시 명령당 ~400ms → slowCallDuration 300ms면 저하를 깔끔히 slow로 분류.
        // minimumCalls는 windowSize 이하여야 한다 — 기본값(100)이면 창(50)이 못 차 평가 자체가 안 될 수 있다.
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(windowSize)
                .minimumNumberOfCalls(minimumCalls)             // 이 수만큼 쌓여야 비율 평가 시작
                .failureRateThreshold(failureRateThreshold)     // 실패(timeout·단절) 비율 임계
                .slowCallRateThreshold(slowCallRateThreshold)   // 느린-성공 비율 임계
                .slowCallDurationThreshold(Duration.ofMillis(slowCallDurationMs))
                .waitDurationInOpenState(Duration.ofMillis(waitInOpenMs))
                .permittedNumberOfCallsInHalfOpenState(permittedInHalfOpen)
                .build();
        this.breaker = CircuitBreaker.of("redis", config);
    }

    /** 값 반환 Redis 연산을 브레이커로 감싼다. OPEN이면 실행 없이 CallNotPermittedException을 던진다. */
    public <T> T call(Supplier<T> redisOp) {
        return breaker.executeSupplier(redisOp);
    }

    /** void Redis 연산용. */
    public void run(Runnable redisOp) {
        breaker.executeRunnable(redisOp);
    }
}
