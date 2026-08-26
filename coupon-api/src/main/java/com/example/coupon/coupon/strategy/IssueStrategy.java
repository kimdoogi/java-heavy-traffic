package com.example.coupon.coupon.strategy;

/**
 * 선착순 발급 전략 (PLAN §1.3). 구현 4종은 모두 빈으로 등록되고,
 * `coupon.issue.strategy` 값이 name()과 일치하는 하나가 기동 시 선택된다.
 */
public interface IssueStrategy {

    /** `coupon.issue.strategy` property 값과 일치해야 한다. */
    String name();

    IssueResult issue(long couponId, long userId);
}
