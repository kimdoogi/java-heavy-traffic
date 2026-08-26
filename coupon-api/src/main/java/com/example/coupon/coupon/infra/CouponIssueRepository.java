package com.example.coupon.coupon.infra;

import java.util.List;

import com.example.coupon.coupon.domain.CouponIssue;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponIssueRepository extends JpaRepository<CouponIssue, Long> {

    boolean existsByCouponIdAndUserId(long couponId, long userId);

    long countByCouponId(long couponId);

    /** redis 키 유실 복구 시 발급자 set 재구축용 (RedisIssueStrategy) */
    List<CouponIssue> findByCouponId(long couponId);

    List<CouponIssue> findByUserIdOrderByIssuedAtDesc(long userId);
}
