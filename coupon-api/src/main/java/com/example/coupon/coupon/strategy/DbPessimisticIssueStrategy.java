package com.example.coupon.coupon.strategy;

import com.example.coupon.coupon.domain.Coupon;
import com.example.coupon.coupon.domain.CouponIssue;
import com.example.coupon.coupon.infra.CouponIssueRepository;
import com.example.coupon.coupon.infra.CouponRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * SELECT ... FOR UPDATE (E6). 쿠폰 행 락으로 발급이 직렬화되므로
 * 락 획득 이후의 중복검사·차감·INSERT는 경합이 없다. 정합성 OK, 락 대기가 병목.
 */
@Component
public class DbPessimisticIssueStrategy implements IssueStrategy {

    private final CouponRepository couponRepository;
    private final CouponIssueRepository issueRepository;

    public DbPessimisticIssueStrategy(CouponRepository couponRepository, CouponIssueRepository issueRepository) {
        this.couponRepository = couponRepository;
        this.issueRepository = issueRepository;
    }

    @Override
    public String name() {
        return "db-pessimistic";
    }

    @Override
    @Transactional
    public IssueResult issue(long couponId, long userId) {
        Coupon coupon = couponRepository.findByIdForUpdate(couponId).orElse(null);
        if (coupon == null) {
            return IssueResult.NOT_FOUND;
        }
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
