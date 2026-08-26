package com.example.coupon.coupon;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.example.coupon.TestcontainersConfiguration;
import com.example.coupon.coupon.application.CouponService;
import com.example.coupon.coupon.domain.Coupon;
import com.example.coupon.coupon.infra.CouponIssueRepository;
import com.example.coupon.coupon.infra.CouponRepository;
import com.example.coupon.coupon.infra.RedisCouponStockRepository;
import com.example.coupon.coupon.strategy.DbOptimisticIssueStrategy;
import com.example.coupon.coupon.strategy.DbPessimisticIssueStrategy;
import com.example.coupon.coupon.strategy.IssueResult;
import com.example.coupon.coupon.strategy.IssueStrategy;
import com.example.coupon.coupon.strategy.NoneIssueStrategy;
import com.example.coupon.coupon.strategy.RedisIssueStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E6(선착순 정합성)의 코드 레벨 사전 검증. 전략 빈 4종을 직접 주입해
 * "none은 초과 발급이 가능하고, 나머지 3종은 초과 발급 0건"을 동시성 부하로 확인한다.
 * (k6 부하에서의 처리량 비교는 E6 실험에서 수행)
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class IssueStrategyConcurrencyTest {

    private static final AtomicLong USER_SEQ = new AtomicLong(100_000);

    @Autowired NoneIssueStrategy noneStrategy;
    @Autowired DbPessimisticIssueStrategy pessimisticStrategy;
    @Autowired DbOptimisticIssueStrategy optimisticStrategy;
    @Autowired RedisIssueStrategy redisStrategy;

    @Autowired CouponService couponService;
    @Autowired CouponRepository couponRepository;
    @Autowired CouponIssueRepository issueRepository;
    @Autowired RedisCouponStockRepository stockRepository;

    @Test
    void none_전략은_동시_요청에서_초과_발급이_발생할_수_있다() {
        Coupon coupon = couponService.create("none-oversell", 50);

        Map<IssueResult, Long> results = runConcurrent(noneStrategy, coupon.getId(), 200);

        long issuedRows = issueRepository.countByCouponId(coupon.getId());
        // 무방비 read-modify-write: 최소 수량만큼은 발급되고, 경합이 있으면 수량을 초과한다(lost update).
        // 초과 자체는 확률적이라 단정하지 않는다 — 수치는 출력으로 남긴다.
        System.out.printf("[none] total=50, issuedRows=%d (oversell=%d), results=%s%n",
                issuedRows, issuedRows - 50, results);
        assertThat(issuedRows).isGreaterThanOrEqualTo(50);
        assertThat(results.getOrDefault(IssueResult.ISSUED, 0L)).isEqualTo(issuedRows);
    }

    @Test
    void dbPessimistic_전략은_정확히_수량만큼_발급된다() {
        Coupon coupon = couponService.create("pessimistic", 100);

        Map<IssueResult, Long> results = runConcurrent(pessimisticStrategy, coupon.getId(), 300);

        long issuedRows = issueRepository.countByCouponId(coupon.getId());
        int remaining = couponRepository.findById(coupon.getId()).orElseThrow().getRemainingQuantity();
        assertThat(issuedRows).isEqualTo(100);
        assertThat(remaining).isZero();
        assertThat(results.getOrDefault(IssueResult.ISSUED, 0L)).isEqualTo(100);
        assertThat(results.getOrDefault(IssueResult.SOLD_OUT, 0L)).isEqualTo(200);
    }

    @Test
    void dbOptimistic_전략은_초과_발급_없이_발급수와_잔여수량이_일치한다() {
        Coupon coupon = couponService.create("optimistic", 100);

        Map<IssueResult, Long> results = runConcurrent(optimisticStrategy, coupon.getId(), 300);

        long issuedRows = issueRepository.countByCouponId(coupon.getId());
        int remaining = couponRepository.findById(coupon.getId()).orElseThrow().getRemainingQuantity();
        // 재시도 소진으로 100개를 다 못 팔 수는 있어도(RETRY_EXHAUSTED), 초과 발급과 불변식 위반은 없어야 한다.
        System.out.printf("[db-optimistic] issuedRows=%d, remaining=%d, results=%s%n", issuedRows, remaining, results);
        assertThat(issuedRows).isLessThanOrEqualTo(100);
        assertThat(issuedRows).isEqualTo(100 - remaining);
        assertThat(results.getOrDefault(IssueResult.ISSUED, 0L)).isEqualTo(issuedRows);
    }

    @Test
    void redis_전략은_정확히_수량만큼_발급되고_DB에는_INSERT만_남는다() {
        Coupon coupon = couponService.create("redis", 100);

        Map<IssueResult, Long> results = runConcurrent(redisStrategy, coupon.getId(), 300);

        long issuedRows = issueRepository.countByCouponId(coupon.getId());
        assertThat(issuedRows).isEqualTo(100);
        assertThat(stockRepository.getStock(coupon.getId())).isZero();
        assertThat(results.getOrDefault(IssueResult.ISSUED, 0L)).isEqualTo(100);
        assertThat(results.getOrDefault(IssueResult.SOLD_OUT, 0L)).isEqualTo(200);
        // 문서화된 특성: redis 전략은 coupon 행(hot row)을 갱신하지 않는다 → DB remaining은 총량 그대로.
        assertThat(couponRepository.findById(coupon.getId()).orElseThrow().getRemainingQuantity()).isEqualTo(100);
    }

    @Test
    void 모든_전략은_같은_사용자의_재요청을_거부한다() {
        for (IssueStrategy strategy : List.of(noneStrategy, pessimisticStrategy, optimisticStrategy, redisStrategy)) {
            Coupon coupon = couponService.create("dup-" + strategy.name(), 10);
            long userId = USER_SEQ.incrementAndGet();

            assertThat(strategy.issue(coupon.getId(), userId)).as(strategy.name()).isEqualTo(IssueResult.ISSUED);
            assertThat(strategy.issue(coupon.getId(), userId)).as(strategy.name()).isEqualTo(IssueResult.ALREADY_ISSUED);
        }
    }

    @Test
    void 모든_전략은_없는_쿠폰에_NOT_FOUND를_반환한다() {
        for (IssueStrategy strategy : List.of(noneStrategy, pessimisticStrategy, optimisticStrategy, redisStrategy)) {
            assertThat(strategy.issue(999_999_999L, USER_SEQ.incrementAndGet()))
                    .as(strategy.name()).isEqualTo(IssueResult.NOT_FOUND);
        }
    }

    /** 서로 다른 userId로 동시에 users명 발급 시도. VT executor + latch로 동시 출발시킨다. */
    private Map<IssueResult, Long> runConcurrent(IssueStrategy strategy, long couponId, int users) {
        Map<IssueResult, AtomicLong> counts = new ConcurrentHashMap<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(users);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < users; i++) {
                long userId = USER_SEQ.incrementAndGet();
                executor.execute(() -> {
                    try {
                        start.await();
                        IssueResult r = strategy.issue(couponId, userId);
                        counts.computeIfAbsent(r, k -> new AtomicLong()).incrementAndGet();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            if (!done.await(120, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent issue did not finish in 120s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return counts.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));
    }
}
