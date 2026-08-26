package com.example.coupon.coupon.domain;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 발급 이력. (coupon_id, user_id) unique 제약이 1인 1매의 최종 방어선 (V1__init.sql). */
@Entity
@Table(name = "coupon_issue")
public class CouponIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private long couponId;

    private long userId;

    private Instant issuedAt;

    protected CouponIssue() {
    }

    public CouponIssue(long couponId, long userId) {
        this.couponId = couponId;
        this.userId = userId;
        this.issuedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public long getCouponId() {
        return couponId;
    }

    public long getUserId() {
        return userId;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }
}
