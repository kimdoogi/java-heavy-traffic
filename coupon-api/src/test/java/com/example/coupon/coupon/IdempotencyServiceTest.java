package com.example.coupon.coupon;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.example.coupon.TestcontainersConfiguration;
import com.example.coupon.coupon.application.IdempotencyInProgressException;
import com.example.coupon.coupon.application.IdempotencyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E7(멱등성)의 코드 레벨 사전 검증. 실제 쿠폰 도메인과 무관하게 IdempotencyService 자체의
 * 클레임/재생 규약을 확인한다 — 컨트롤러 배선(/issue)의 응답 일관성은 CouponApiContractTest에서.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class IdempotencyServiceTest {

    @Autowired IdempotencyService idempotencyService;
    @Autowired StringRedisTemplate redisTemplate;

    @Test
    void 같은_키_순차_재시도는_액션을_다시_실행하지_않고_응답을_재생한다() {
        String key = "seq-" + System.nanoTime();
        AtomicInteger calls = new AtomicInteger();
        Supplier<ResponseEntity<Map<String, Object>>> action = () ->
                ResponseEntity.status(HttpStatus.CREATED).body(Map.of("n", calls.incrementAndGet()));

        ResponseEntity<Map<String, Object>> first = idempotencyService.execute(key, action);
        ResponseEntity<Map<String, Object>> second = idempotencyService.execute(key, action);

        assertThat(calls.get()).isEqualTo(1);
        assertThat(second.getStatusCode()).isEqualTo(first.getStatusCode());
        assertThat(second.getBody()).isEqualTo(first.getBody());
    }

    @Test
    void 서로_다른_키는_독립적으로_실행된다() {
        AtomicInteger calls = new AtomicInteger();
        Supplier<ResponseEntity<Map<String, Object>>> action = () -> {
            calls.incrementAndGet();
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("ok", true));
        };

        idempotencyService.execute("key-a-" + System.nanoTime(), action);
        idempotencyService.execute("key-b-" + System.nanoTime(), action);

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void 캐시_키가_만료된_후에는_재시도가_새로_실행된다() throws InterruptedException {
        String key = "ttl-" + System.nanoTime();
        AtomicInteger calls = new AtomicInteger();
        Supplier<ResponseEntity<Map<String, Object>>> action = () ->
                ResponseEntity.status(HttpStatus.CREATED).body(Map.of("n", calls.incrementAndGet()));

        idempotencyService.execute(key, action);
        assertThat(calls.get()).isEqualTo(1);

        // 실제 상한은 coupon.idempotency.*-ttl-seconds — 테스트에서만 강제로 짧게 당겨 만료 후 상태를 시뮬레이션.
        redisTemplate.expire("idempotency:" + key, Duration.ofMillis(200));
        Thread.sleep(400);

        idempotencyService.execute(key, action);
        assertThat(calls.get()).isEqualTo(2);
    }

    /**
     * 동시에 같은 키로 N번 재시도. VT executor + latch로 동시 출발시킨다(IssueStrategyConcurrencyTest와 동일 패턴).
     * 액션에 짧은 sleep을 넣어 첫 실행이 끝나기 전에 나머지가 반드시 클레임 경합에 부딪히게 한다.
     */
    @Test
    void 동시_같은_키_요청은_액션이_정확히_1번만_실행된다() throws InterruptedException {
        String key = "concurrent-" + System.nanoTime();
        AtomicInteger calls = new AtomicInteger();
        int n = 20;
        Supplier<ResponseEntity<Map<String, Object>>> action = () -> {
            calls.incrementAndGet();
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("ok", true));
        };

        List<Future<ResponseEntity<Map<String, Object>>>> futures = new ArrayList<>(n);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            for (int i = 0; i < n; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return idempotencyService.execute(key, action);
                }));
            }
            start.countDown();
            executor.shutdown();
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent idempotency test did not finish in 30s");
            }
        } finally {
            executor.shutdownNow();
        }

        long replayed = 0;
        long inProgress = 0;
        for (Future<ResponseEntity<Map<String, Object>>> future : futures) {
            try {
                future.get();
                replayed++;
            } catch (ExecutionException e) {
                if (e.getCause() instanceof IdempotencyInProgressException) {
                    inProgress++;
                } else {
                    throw new AssertionError("worker failed: " + e.getCause(), e.getCause());
                }
            }
        }

        assertThat(calls.get()).isEqualTo(1);
        assertThat(replayed).isGreaterThanOrEqualTo(1);
        assertThat(replayed + inProgress).isEqualTo(n);
    }
}
