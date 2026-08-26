package com.example.coupon.coupon.strategy;

import com.example.coupon.coupon.domain.Coupon;
import com.example.coupon.coupon.domain.CouponIssue;
import com.example.coupon.coupon.infra.CouponIssueRepository;
import com.example.coupon.coupon.infra.CouponRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * @Version 낙관적 락 + 재시도 (E6). 커밋 시점 version 충돌이 나면 새 트랜잭션으로 재시도한다.
 * 경합이 심하면 재시도가 폭증해 RETRY_EXHAUSTED(503) 비율이 올라가는 것이 관찰 포인트 —
 * 재시도 횟수는 `coupon_issue_retry_total`(Micrometer)로 계측한다.
 * 재시도는 새 트랜잭션에서 엔티티를 다시 읽어야 하므로 @Transactional 대신 TransactionTemplate으로 감싼다.
 */
@Component
public class DbOptimisticIssueStrategy implements IssueStrategy {

    private final CouponRepository couponRepository;
    private final CouponIssueRepository issueRepository;
    private final TransactionTemplate tx;
    private final Counter retryCounter;
    private final int maxRetries;

    public DbOptimisticIssueStrategy(CouponRepository couponRepository,
                                     CouponIssueRepository issueRepository,
                                     PlatformTransactionManager transactionManager,
                                     MeterRegistry meterRegistry,
                                     @Value("${coupon.issue.optimistic-max-retries}") int maxRetries) {
        this.couponRepository = couponRepository;
        this.issueRepository = issueRepository;
        this.tx = new TransactionTemplate(transactionManager);
        this.retryCounter = meterRegistry.counter("coupon_issue_retry_total", "strategy", "db-optimistic");
        this.maxRetries = maxRetries;
    }

    @Override
    public String name() {
        return "db-optimistic";
    }

    @Override
    public IssueResult issue(long couponId, long userId) {
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                retryCounter.increment();
            }
            try {
                return tx.execute(status -> doIssue(couponId, userId));
            } catch (OptimisticLockingFailureException | jakarta.persistence.OptimisticLockException e) {
                // version 충돌 → 루프 계속
            } catch (DataIntegrityViolationException e) {
                // 같은 userId 동시 요청이 중복검사를 동시에 통과한 경우 — unique 제약이 잡아줌
                return IssueResult.ALREADY_ISSUED;
            }
        }
        return IssueResult.RETRY_EXHAUSTED;
    }

    private IssueResult doIssue(long couponId, long userId) {
        Coupon coupon = couponRepository.findById(couponId).orElse(null);
        if (coupon == null) {
            return IssueResult.NOT_FOUND;
        }
        // 중복검사는 재시도마다 반복되지만(시도당 SELECT 1회) 유지한다: 중복 요청이 version 충돌과 섞였을 때
        // 재시도 낭비 없이 already_issued로 조기 반환하기 위함이고, 4전략 공통 순서(중복 → 품절)를 지킨다.
        if (issueRepository.existsByCouponIdAndUserId(couponId, userId)) {
            return IssueResult.ALREADY_ISSUED;
        }
        if (!coupon.decrease()) {
            return IssueResult.SOLD_OUT;
        }
        issueRepository.save(new CouponIssue(couponId, userId));
        return IssueResult.ISSUED;
    }
}
