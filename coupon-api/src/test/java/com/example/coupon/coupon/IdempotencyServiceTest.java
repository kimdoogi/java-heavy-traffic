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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /**
     * D4 회귀: 액션은 성공했는데 그 뒤 결과 캐시 저장 단계가 실패해도 클레임을 지우면 안 된다.
     * 지우면 재시도가 재생 대신 ALREADY_ISSUED를 받아 응답 일관성이 깨진다(과거 버그, 커밋 7285cab).
     * 캐시 저장(serialize + redis.set)은 좁혀진 try 밖에 있어야 하며, 여기서는 직렬화 불가능한 body로
     * 그 단계만 실패시킨다 — serialize와 redis.set이 같은 문장(try 밖)이라 이 경로가 곧 D4 경계다.
     */
    @Test
    void 액션_성공_후_결과_저장_실패는_클레임을_지우지_않는다() {
        String key = "cachefail-" + System.nanoTime();
        String redisKey = "idempotency:" + key;
        AtomicInteger calls = new AtomicInteger();
        // 빈 POJO는 Jackson이 직렬화하지 못한다(FAIL_ON_EMPTY_BEANS) → serialize()가 던진다.
        // 액션 자체는 정상 반환하므로, 실패 지점은 오직 "액션 성공 뒤 캐시 저장"뿐이다.
        Supplier<ResponseEntity<Map<String, Object>>> action = () -> {
            calls.incrementAndGet();
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("bad", new Object()));
        };

        assertThatThrownBy(() -> idempotencyService.execute(key, action))
                .isInstanceOf(IllegalStateException.class);

        assertThat(calls.get()).isEqualTo(1); // 액션은 실행됐다(성공)
        // 수정 전이라면 catch가 delete를 호출해 null이 된다. 수정 후엔 클레임(PROCESSING)이 그대로 남는다.
        assertThat(redisTemplate.opsForValue().get(redisKey)).isEqualTo("__PROCESSING__");
    }

    /**
     * 위 테스트의 거울: 액션 "자체"가 실패하면(아무것도 커밋 안 된 상태) 클레임을 지워 재시도를 허용해야 한다.
     * try 밖 실패(캐시 저장)는 클레임을 남기고, try 안 실패(액션)는 클레임을 지운다 — 이 비대칭이 계약이다.
     */
    @Test
    void 액션이_예외를_던지면_클레임을_지워_재시도를_허용한다() {
        String key = "actionfail-" + System.nanoTime();
        String redisKey = "idempotency:" + key;
        AtomicInteger calls = new AtomicInteger();
        // 첫 호출만 던지고 이후는 성공 — 클레임이 실제로 비워져 재시도가 새로 실행되는지까지 본다.
        Supplier<ResponseEntity<Map<String, Object>>> action = () -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                throw new RuntimeException("action boom");
            }
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("n", n));
        };

        // 1) 액션이 던진 예외가 그대로 전파된다(삼켜지지 않음).
        assertThatThrownBy(() -> idempotencyService.execute(key, action))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("action boom");

        // 2) 클레임이 지워졌다 — 그 자리를 비워 재시도가 처음부터 가능해진다.
        assertThat(redisTemplate.opsForValue().get(redisKey)).isNull();

        // 3) 같은 키 재시도는 막히지 않고 새로 실행된다(재생이 아니라 실제 재실행).
        ResponseEntity<Map<String, Object>> retry = idempotencyService.execute(key, action);
        assertThat(calls.get()).isEqualTo(2);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
