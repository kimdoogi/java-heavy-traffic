package com.example.coupon.coupon.infra;

/**
 * ISSUE_SCRIPT(Lua)의 반환 계약. 숫자 코드를 enum으로 강제해 새 코드가 생겨도
 * 소비자(RedisIssueStrategy)의 switch가 컴파일 단계에서 누락을 잡게 한다.
 * ISSUED_RECOVERING = 키 유실 복구 구간의 발급 — 재고 수치가 부정확할 수 있어 DB 백스톱 검증을 거쳐야 한다.
 */
public enum RedisIssueOutcome {
    ISSUED,
    ISSUED_RECOVERING,
    SOLD_OUT,
    ALREADY_ISSUED,
    UNINITIALIZED
}
