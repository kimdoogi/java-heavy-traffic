package com.example.coupon.coupon.strategy;

public enum IssueResult {
    ISSUED,
    SOLD_OUT,
    ALREADY_ISSUED,
    /** db-optimistic 재시도 소진 (E6: 경합 심하면 실패율 상승) */
    RETRY_EXHAUSTED,
    NOT_FOUND
}
