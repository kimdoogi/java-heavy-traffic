package com.example.coupon.coupon.application;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.example.coupon.coupon.strategy.IssueResult;
import com.example.coupon.coupon.strategy.IssueStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 발급 전략 위임 (PLAN §1.3). 전략 4종은 모두 빈으로 떠 있고,
 * `coupon.issue.strategy` 값과 name()이 일치하는 하나를 기동 시 선택한다.
 * 값이 잘못되면 조용히 기본값으로 동작하는 대신 기동을 실패시킨다 (실험 변수 오타 방지).
 */
@Service
public class CouponIssueService {

    private static final Logger log = LoggerFactory.getLogger(CouponIssueService.class);

    private final IssueStrategy strategy;

    public CouponIssueService(List<IssueStrategy> strategies,
                              @Value("${coupon.issue.strategy}") String strategyName) {
        Map<String, IssueStrategy> byName = strategies.stream()
                .collect(Collectors.toMap(IssueStrategy::name, Function.identity()));
        IssueStrategy selected = byName.get(strategyName);
        if (selected == null) {
            throw new IllegalStateException(
                    "unknown coupon.issue.strategy '" + strategyName + "' — available: " + byName.keySet());
        }
        this.strategy = selected;
        log.info("coupon issue strategy = {}", strategyName);
    }

    public String strategyName() {
        return strategy.name();
    }

    public IssueResult issue(long couponId, long userId) {
        return strategy.issue(couponId, userId);
    }
}
