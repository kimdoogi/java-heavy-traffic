package com.example.coupon.coupon;

import java.util.concurrent.atomic.AtomicLong;

import com.example.coupon.TestcontainersConfiguration;
import com.example.coupon.coupon.application.CouponReconciliationService;
import com.example.coupon.coupon.application.CouponService;
import com.example.coupon.coupon.domain.Coupon;
import com.example.coupon.coupon.infra.CouponIssueRepository;
import com.example.coupon.coupon.infra.RedisCouponStockRepository;
import com.example.coupon.coupon.strategy.IssueResult;
import com.example.coupon.coupon.strategy.RedisIssueStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * redis 전략의 키 유실(flush/재시작) 복구 경로 검증 — 코드리뷰에서 확정된 결함들의 회귀 테스트:
 * 발급자 set 재구축(기발급자 sold_out 오분류), 복구 구간 DB 백스톱(초과 발급), 고아 키(FK 위반 → 404 + 정리).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RedisIssueRecoveryTest {

    private static final AtomicLong USER_SEQ = new AtomicLong(900_000);

    @Autowired RedisIssueStrategy redisStrategy;
    @Autowired CouponService couponService;
    @Autowired CouponReconciliationService reconciliationService;
    @Autowired CouponIssueRepository issueRepository;
    @Autowired RedisCouponStockRepository stockRepository;

    @Test
    void 키_유실_후_발급자_set과_재고가_DB_기준으로_복구된다() {
        Coupon coupon = couponService.create("recovery", 3);
        long u1 = USER_SEQ.incrementAndGet();
        long u2 = USER_SEQ.incrementAndGet();
        assertThat(redisStrategy.issue(coupon.getId(), u1)).isEqualTo(IssueResult.ISSUED);
        assertThat(redisStrategy.issue(coupon.getId(), u2)).isEqualTo(IssueResult.ISSUED);

        stockRepository.deleteKeys(coupon.getId());   // flush/재시작 시뮬레이션

        // 기발급자는 sold_out이 아니라 already_issued (set이 DB에서 재구축됨)
        assertThat(redisStrategy.issue(coupon.getId(), u1)).isEqualTo(IssueResult.ALREADY_ISSUED);
        // 신규 발급은 복구 마커 때문에 DB 백스톱(advisory lock + count)을 거쳐 성공
        assertThat(redisStrategy.issue(coupon.getId(), USER_SEQ.incrementAndGet())).isEqualTo(IssueResult.ISSUED);
        // 재고 소진 후 초과 발급 없음
        assertThat(redisStrategy.issue(coupon.getId(), USER_SEQ.incrementAndGet())).isEqualTo(IssueResult.SOLD_OUT);
        assertThat(issueRepository.countByCouponId(coupon.getId())).isEqualTo(3);
        assertThat(stockRepository.getStock(coupon.getId())).isZero();
    }

    @Test
    void 완판_후_키_유실_시_기발급자는_sold_out이_아니라_already_issued를_받는다() {
        Coupon coupon = couponService.create("soldout-recovery", 1);
        long user = USER_SEQ.incrementAndGet();
        assertThat(redisStrategy.issue(coupon.getId(), user)).isEqualTo(IssueResult.ISSUED);

        stockRepository.deleteKeys(coupon.getId());

        assertThat(redisStrategy.issue(coupon.getId(), user)).isEqualTo(IssueResult.ALREADY_ISSUED);
        assertThat(redisStrategy.issue(coupon.getId(), USER_SEQ.incrementAndGet())).isEqualTo(IssueResult.SOLD_OUT);
        assertThat(issueRepository.countByCouponId(coupon.getId())).isEqualTo(1);
    }

    @Test
    void 고아_redis_키는_NOT_FOUND와_함께_정리된다() {
        long ghostCouponId = 987_654_321L;   // DB에 없는 쿠폰 id에 재고 키만 존재하는 상태 (생성 롤백/DB 단독 리셋 잔재)
        stockRepository.resetStock(ghostCouponId, 5);

        // Lua는 통과하지만 INSERT가 FK 위반 → already_issued가 아니라 NOT_FOUND, 고아 키는 정리된다
        assertThat(redisStrategy.issue(ghostCouponId, USER_SEQ.incrementAndGet())).isEqualTo(IssueResult.NOT_FOUND);
        assertThat(stockRepository.getStock(ghostCouponId)).isNull();

        // 키가 아예 없는 미존재 쿠폰도 NOT_FOUND (lazy init 경로)
        assertThat(redisStrategy.issue(ghostCouponId, USER_SEQ.incrementAndGet())).isEqualTo(IssueResult.NOT_FOUND);
    }

    @Test
    void reconcile가_redis에만_있는_유령_발급을_DB로_복구하고_재실행은_0건이다() {
        Coupon coupon = couponService.create("reconcile", 5);
        long couponId = coupon.getId();
        long ghost = USER_SEQ.incrementAndGet();

        // redis에만 발급(Lua: SADD + DECR) — DB INSERT 전 크래시로 붕 뜬(orphan) 상태를 흉내
        stockRepository.tryIssue(couponId, ghost);
        assertThat(issueRepository.existsByCouponIdAndUserId(couponId, ghost)).isFalse();

        // 조정 → redis-only 발급을 DB로 전진 복구
        assertThat(reconciliationService.reconcile(couponId)).isEqualTo(1);
        assertThat(issueRepository.existsByCouponIdAndUserId(couponId, ghost)).isTrue();

        // 재실행 → 멱등(복구할 것 없음)
        assertThat(reconciliationService.reconcile(couponId)).isZero();
    }

    @Test
    void 잔여수량은_redis_재고를_반영하고_키_유실시_count로_폴백한다() {
        Coupon coupon = couponService.create("remaining", 5);
        long couponId = coupon.getId();
        redisStrategy.issue(couponId, USER_SEQ.incrementAndGet());
        redisStrategy.issue(couponId, USER_SEQ.incrementAndGet());

        // getStock 경로: redis 재고 = 3
        assertThat(couponService.remaining(couponId, 5)).isEqualTo(3);

        // 키 유실 → count 폴백: total 5 - 발급 2 = 3
        stockRepository.deleteKeys(couponId);
        assertThat(couponService.remaining(couponId, 5)).isEqualTo(3);
    }
}
