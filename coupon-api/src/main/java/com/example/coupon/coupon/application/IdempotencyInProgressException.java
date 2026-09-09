package com.example.coupon.coupon.application;

/** 같은 Idempotency-Key로 아직 처리 중인 요청이 있을 때(동시 재시도) — CouponApiExceptionHandler가 409로 매핑. */
public class IdempotencyInProgressException extends RuntimeException {

    public IdempotencyInProgressException(String idempotencyKey) {
        super("idempotency key already in progress: " + idempotencyKey);
    }
}
