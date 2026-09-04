package com.example.coupon.coupon.infra;

import java.util.concurrent.atomic.AtomicInteger;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RedisCircuitBreaker 순수 단위 테스트 — Spring 컨텍스트·실제 Redis 없이 브레이커 거동만 검증한다.
 * 라이브 실험(toxiproxy +400ms)이 "실제로 발동함"을 보였고, 여기선 그 발동 규약을 CI에서 반복검증한다.
 * 생성자 인자: (window, minCalls, failureRate%, slowRate%, slowDurationMs, waitInOpenMs, permittedHalfOpen)
 */
class RedisCircuitBreakerTest {

    // 5회만 쌓이면 판정, 실패/느림 50%면 OPEN, OPEN은 10초 유지(테스트 중 half-open 안 넘어가게).
    private RedisCircuitBreaker breaker() {
        return new RedisCircuitBreaker(10, 5, 50, 50, 30, 10_000, 2);
    }

    @Test
    void 실패가_쌓이면_OPEN되어_이후_호출은_실행없이_CallNotPermitted를_던진다() {
        RedisCircuitBreaker cb = breaker();
        // 실패(Redis 단절·timeout에 해당)를 넉넉히 주입 → minCalls(5) 넘고 실패율 100% → OPEN.
        for (int i = 0; i < 20; i++) {
            try {
                cb.call(() -> { throw new RuntimeException("redis down"); });
            } catch (RuntimeException ignored) {
                // CLOSED 구간의 원 예외 또는 OPEN 후 CallNotPermitted — 둘 다 무시하고 계속 주입
            }
        }
        // OPEN이면 supplier를 아예 실행하지 않고 즉시 CallNotPermittedException.
        AtomicInteger invoked = new AtomicInteger();
        assertThatThrownBy(() -> cb.call(() -> {
            invoked.incrementAndGet();
            return "should-not-run";
        })).isInstanceOf(CallNotPermittedException.class);
        assertThat(invoked.get()).isZero();   // OPEN이라 실행 안 됨 = 부하 차단
    }

    @Test
    void 느린_호출이_쌓이면_OPEN된다() {
        RedisCircuitBreaker cb = breaker();   // slowDuration 30ms
        // 성공하지만 느린(60ms > 30ms) 호출 — timeout이 못 잡는 "느린-성공"에 해당. slowRate 100% → OPEN.
        for (int i = 0; i < 20; i++) {
            try {
                cb.call(() -> {
                    sleep(60);
                    return "slow-ok";
                });
            } catch (RuntimeException ignored) {
                // OPEN 후엔 CallNotPermitted — 무시하고 계속
            }
        }
        AtomicInteger invoked = new AtomicInteger();
        assertThatThrownBy(() -> cb.call(() -> {
            invoked.incrementAndGet();
            return "x";
        })).isInstanceOf(CallNotPermittedException.class);
        assertThat(invoked.get()).isZero();
    }

    @Test
    void 정상_빠른_호출은_통과하고_값을_반환한다() {
        RedisCircuitBreaker cb = breaker();
        assertThat(cb.<String>call(() -> "hello")).isEqualTo("hello");

        AtomicInteger ran = new AtomicInteger();
        cb.run(ran::incrementAndGet);
        assertThat(ran.get()).isEqualTo(1);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
