package com.example.coupon.coupon.infra;

import java.util.List;

import com.example.coupon.coupon.domain.CouponIssue;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponIssueRepository extends JpaRepository<CouponIssue, Long> {

    boolean existsByCouponIdAndUserId(long couponId, long userId);

    long countByCouponId(long couponId);

    List<CouponIssue> findByUserIdOrderByIssuedAtDesc(long userId);
}
