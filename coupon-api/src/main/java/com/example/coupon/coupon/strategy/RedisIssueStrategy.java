package com.example.coupon.coupon.strategy;

import java.util.List;

import com.example.coupon.coupon.domain.Coupon;
import com.example.coupon.coupon.domain.CouponIssue;
import com.example.coupon.coupon.infra.CouponIssueRepository;
import com.example.coupon.coupon.infra.CouponRepository;
import com.example.coupon.coupon.infra.RedisCouponStockRepository;
import com.example.coupon.coupon.infra.RedisIssueOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Redis Lua 원자 차감 + 후속 DB 기록 (E6). 재고·1인1매 판정이 Redis에서 끝나므로
 * 정상 경로의 DB 작업은 coupon_issue INSERT 하나뿐 — coupon 행(hot row)을 갱신하지 않는 것이 처리량 포인트.
 * 따라서 이 전략에서 DB의 remaining_quantity는 갱신되지 않으며, 정합성 검증은
 * count(coupon_issue) <= total_quantity 로 한다 (verify-coupon.sh).
 *
 * 키 유실 복구: 재고 키가 없으면 DB 진실(total − count, 발급자 set 포함)로 재구축하고,
 * 복구 후 일정 시간(마커 TTL)의 발급은 DB 백스톱(advisory lock + count 확인)을 거쳐
 * in-flight 이중 계상으로 인한 초과 발급을 차단한다.
 *
 * 알려진 한계(E8-5에서 해소 예정): Lua 성공 ~ DB INSERT 커밋 사이에 프로세스가 죽으면(OOM-kill 등)
 * 사용자가 redis 발급자 set에만 남는다 — catch 기반 보상으로는 잡을 수 없고, outbox/조정 배치가 필요.
 */
@Component
public class RedisIssueStrategy implements IssueStrategy {

    private static final Logger log = LoggerFactory.getLogger(RedisIssueStrategy.class);

    private final RedisCouponStockRepository stockRepository;
    private final CouponRepository couponRepository;
    private final CouponIssueRepository issueRepository;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public RedisIssueStrategy(RedisCouponStockRepository stockRepository,
                              CouponRepository couponRepository,
                              CouponIssueRepository issueRepository,
                              JdbcClient jdbc,
                              PlatformTransactionManager transactionManager) {
        this.stockRepository = stockRepository;
        this.couponRepository = couponRepository;
        this.issueRepository = issueRepository;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public String name() {
        return "redis";
    }

    @Override
    public IssueResult issue(long couponId, long userId) {
        RedisIssueOutcome outcome = stockRepository.tryIssue(couponId, userId);

        if (outcome == RedisIssueOutcome.UNINITIALIZED) {
            IssueResult notFound = recoverKeys(couponId);
            if (notFound != null) {
                return notFound;
            }
            outcome = stockRepository.tryIssue(couponId, userId);
            if (outcome == RedisIssueOutcome.UNINITIALIZED) {
                // 복구 직후 또 유실 — 재고 게이트 없이 발급을 진행하면 안 되므로 실패시킨다 (재요청으로 해소)
                throw new IllegalStateException(
                        "redis stock key lost again right after recovery (couponId=" + couponId + ")");
            }
        }

        return switch (outcome) {
            case ALREADY_ISSUED -> IssueResult.ALREADY_ISSUED;
            case SOLD_OUT -> IssueResult.SOLD_OUT;
            case ISSUED -> recordIssue(couponId, userId);
            case ISSUED_RECOVERING -> recordIssueWithDbBackstop(couponId, userId);
            case UNINITIALIZED -> throw new IllegalStateException("unreachable: UNINITIALIZED after recovery");
        };
    }

    /** 키 유실 복구. 쿠폰이 DB에 없으면 고아 키를 지우고 NOT_FOUND, 정상 복구면 null. */
    private IssueResult recoverKeys(long couponId) {
        Coupon coupon = couponRepository.findById(couponId).orElse(null);
        if (coupon == null) {
            stockRepository.deleteKeys(couponId);
            return IssueResult.NOT_FOUND;
        }
        // remaining_quantity가 아니라 발급 이력을 쓰는 이유: redis 전략은 remaining을 갱신하지 않아 stale하다.
        List<CouponIssue> issued = issueRepository.findByCouponId(couponId);
        int stock = Math.max(0, coupon.getTotalQuantity() - issued.size());
        stockRepository.rebuild(couponId, stock,
                issued.stream().map(i -> String.valueOf(i.getUserId())).toList());
        log.warn("redis keys missing — rebuilt from DB: couponId={} stock={} issuedMembers={} (복구 구간 발급은 DB 백스톱 경유)",
                couponId, stock, issued.size());
        return null;
    }

    /** 정상 경로: DB에는 INSERT만. 실패 시 보상으로 재고를 되돌린다. */
    private IssueResult recordIssue(long couponId, long userId) {
        try {
            issueRepository.save(new CouponIssue(couponId, userId));
        } catch (DataIntegrityViolationException e) {
            return onIntegrityViolation(couponId, userId);
        } catch (RuntimeException e) {
            safeCompensate(couponId, userId, false);
            throw e;
        }
        return IssueResult.ISSUED;
    }

    /**
     * 복구 구간 발급: lazy init의 재고 계산은 미커밋 in-flight 발급분을 놓칠 수 있으므로,
     * advisory lock으로 발급 성공 경로만 직렬화해 count(coupon_issue) < total 을 DB에서 최종 확인한다.
     * 직렬화 비용은 '성공' 건수(≤ 총수량)에만 들고, sold_out 대다수 경로는 여전히 Redis에서 끝난다.
     */
    private IssueResult recordIssueWithDbBackstop(long couponId, long userId) {
        Coupon coupon = couponRepository.findById(couponId).orElse(null);
        if (coupon == null) {
            stockRepository.deleteKeys(couponId);
            return IssueResult.NOT_FOUND;
        }
        try {
            IssueResult result = tx.execute(status -> {
                jdbc.sql("SELECT pg_advisory_xact_lock(:id)").param("id", couponId).query().singleRow();
                long committed = issueRepository.countByCouponId(couponId);
                if (committed >= coupon.getTotalQuantity()) {
                    return IssueResult.SOLD_OUT;
                }
                issueRepository.save(new CouponIssue(couponId, userId));
                return IssueResult.ISSUED;
            });
            if (result == IssueResult.SOLD_OUT) {
                // 이 요청이 소비한 redis 재고는 복구 시 과잉 계상된 가짜 단위 — INCR 없이 발급자 set만 정리
                safeRemoveIssuedMember(couponId, userId);
            }
            return result;
        } catch (DataIntegrityViolationException e) {
            return onIntegrityViolation(couponId, userId);
        } catch (RuntimeException e) {
            safeCompensate(couponId, userId, false);
            throw e;
        }
    }

    /** 같은 DataIntegrityViolationException으로 도착하는 unique 위반(중복)과 FK 위반(쿠폰 없음)을 구분한다. */
    private IssueResult onIntegrityViolation(long couponId, long userId) {
        if (!couponRepository.existsById(couponId)) {
            stockRepository.deleteKeys(couponId);   // 고아 키 정리 — 이후 요청은 lazy init 경로에서 404
            return IssueResult.NOT_FOUND;
        }
        safeCompensate(couponId, userId, true);     // 중복: set은 유지해 자가 치유, 재고만 복구
        return IssueResult.ALREADY_ISSUED;
    }

    /** 보상 실패가 원래 결과/예외를 덮어쓰지 않게 격리. 실패하면 재고 1단위가 유실될 수 있어 로그로 남긴다. */
    private void safeCompensate(long couponId, long userId, boolean keepIssuedMember) {
        try {
            stockRepository.compensate(couponId, userId, keepIssuedMember);
        } catch (RuntimeException e) {
            log.error("redis compensate failed — stock unit may be lost (couponId={}, userId={}, keepIssuedMember={})",
                    couponId, userId, keepIssuedMember, e);
        }
    }

    private void safeRemoveIssuedMember(long couponId, long userId) {
        try {
            stockRepository.removeIssuedMember(couponId, userId);
        } catch (RuntimeException e) {
            log.error("redis issued-set cleanup failed (couponId={}, userId={})", couponId, userId, e);
        }
    }
}
