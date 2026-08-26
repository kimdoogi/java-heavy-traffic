package com.example.coupon.coupon.strategy;

import com.example.coupon.coupon.domain.Coupon;
import com.example.coupon.coupon.domain.CouponIssue;
import com.example.coupon.coupon.infra.CouponIssueRepository;
import com.example.coupon.coupon.infra.CouponRepository;
import com.example.coupon.coupon.infra.RedisCouponStockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Redis Lua 원자 차감 + 후속 DB 기록 (E6). 재고·1인1매 판정이 Redis에서 끝나므로
 * DB에는 coupon_issue INSERT만 남는다 — coupon 행(hot row)을 갱신하지 않는 것이 처리량 포인트.
 * 따라서 이 전략에서 DB의 remaining_quantity는 갱신되지 않으며, 정합성 검증은
 * count(coupon_issue) <= total_quantity 로 한다 (verify-coupon.sh).
 */
@Component
public class RedisIssueStrategy implements IssueStrategy {

    private static final Logger log = LoggerFactory.getLogger(RedisIssueStrategy.class);

    private final RedisCouponStockRepository stockRepository;
    private final CouponRepository couponRepository;
    private final CouponIssueRepository issueRepository;

    public RedisIssueStrategy(RedisCouponStockRepository stockRepository,
                              CouponRepository couponRepository,
                              CouponIssueRepository issueRepository) {
        this.stockRepository = stockRepository;
        this.couponRepository = couponRepository;
        this.issueRepository = issueRepository;
    }

    @Override
    public String name() {
        return "redis";
    }

    @Override
    public IssueResult issue(long couponId, long userId) {
        long result = stockRepository.tryIssue(couponId, userId);

        if (result == RedisCouponStockRepository.UNINITIALIZED) {
            // 재고 키 유실/미생성 → DB 진실(총량 - 발급 수)로 lazy 초기화 후 1회 재시도.
            // remaining_quantity가 아니라 count()를 쓰는 이유: redis 전략은 remaining을 갱신하지 않아 stale하다.
            Coupon coupon = couponRepository.findById(couponId).orElse(null);
            if (coupon == null) {
                return IssueResult.NOT_FOUND;
            }
            long issuedCount = issueRepository.countByCouponId(couponId);
            int stock = (int) Math.max(0, coupon.getTotalQuantity() - issuedCount);
            stockRepository.initStockIfAbsent(couponId, stock);
            log.warn("redis stock key missing — lazy init couponId={} stock={} (issued set은 재구축하지 않음, 중복은 DB unique가 방어)",
                    couponId, stock);
            result = stockRepository.tryIssue(couponId, userId);
        }

        if (result == RedisCouponStockRepository.ALREADY_ISSUED) {
            return IssueResult.ALREADY_ISSUED;
        }
        if (result == RedisCouponStockRepository.SOLD_OUT) {
            return IssueResult.SOLD_OUT;
        }

        // Redis 차감 성공 → DB 기록. 실패 시 보상(INCR)으로 재고를 되돌린다.
        try {
            issueRepository.save(new CouponIssue(couponId, userId));
        } catch (DataIntegrityViolationException e) {
            // DB에는 이미 발급됨(redis issued set 유실 케이스) → 재고만 복구, set은 유지해 자가 치유
            stockRepository.compensate(couponId, userId, true);
            return IssueResult.ALREADY_ISSUED;
        } catch (RuntimeException e) {
            stockRepository.compensate(couponId, userId, false);
            throw e;
        }
        return IssueResult.ISSUED;
    }
}
