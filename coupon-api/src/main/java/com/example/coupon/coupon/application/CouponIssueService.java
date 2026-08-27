package com.example.coupon.coupon.application;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.example.coupon.coupon.strategy.IssueResult;
import com.example.coupon.coupon.strategy.IssueStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 발급 전략 위임. 제품 발급은 <b>redis로 고정</b>한다 — E6에서 스케일 시 redis 우위를 확인했고,
 * 발급 동작이 설정값에 따라 바뀌지 않게 못박는다.
 *
 * 전략 클래스 4종(none/db-pessimistic/db-optimistic/redis)과 IssueStrategyConcurrencyTest(전략 빈을
 * 직접 주입해 비교)는 <b>실험/테스트용으로 그대로 유지</b>한다. 이 서비스는 그중 redis만 골라 쓴다.
 * (설정 coupon.issue.strategy / ISSUE_STRATEGY 는 이 서비스에선 무시됨.)
 */
@Service
public class CouponIssueService {

    private static final Logger log = LoggerFactory.getLogger(CouponIssueService.class);

    /** 제품 발급 전략 (고정). 추후 테스트를 위해 기존 전략선택 구조 남겨놓음. 나머지 전략은 코드·테스트로만 존재. */
    private static final String FIXED_STRATEGY = "redis";

    private final IssueStrategy strategy;

    public CouponIssueService(List<IssueStrategy> strategies) {
        Map<String, IssueStrategy> byName = strategies.stream()
                .collect(Collectors.toMap(IssueStrategy::name, Function.identity()));
        IssueStrategy selected = byName.get(FIXED_STRATEGY);
        if (selected == null) {
            throw new IllegalStateException(
                    "발급 전략 '" + FIXED_STRATEGY + "' 빈 없음 — available: " + byName.keySet());
        }
        this.strategy = selected;
        log.info("coupon issue strategy = {} (고정)", FIXED_STRATEGY);
    }

    public String strategyName() {
        return strategy.name();
    }

    public IssueResult issue(long couponId, long userId) {
        return strategy.issue(couponId, userId);
    }
}
