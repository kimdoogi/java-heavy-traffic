package com.example.coupon.coupon.strategy;

import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 락 없는 read-modify-write — 초과 발급 "재현"용 (E6).
 * 두 요청이 같은 remaining을 읽으면 둘 다 통과해 count(coupon_issue) > total_quantity 가 된다.
 * 일부러 JdbcClient를 쓴다: JPA 엔티티로 갱신하면 @Version이 낙관적 락을 걸어버려 무방비가 아니게 된다.
 */
@Component
public class NoneIssueStrategy implements IssueStrategy {

    private final JdbcClient jdbc;

    public NoneIssueStrategy(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "none";
    }

    @Override
    @Transactional
    public IssueResult issue(long couponId, long userId) {
        Optional<Integer> remaining = jdbc.sql("SELECT remaining_quantity FROM coupon WHERE id = :id")
                .param("id", couponId)
                .query(Integer.class)
                .optional();
        if (remaining.isEmpty()) {
            return IssueResult.NOT_FOUND;
        }
        if (remaining.get() <= 0) {
            return IssueResult.SOLD_OUT;
        }
        try {
            jdbc.sql("INSERT INTO coupon_issue (coupon_id, user_id) VALUES (:couponId, :userId)")
                    .param("couponId", couponId)
                    .param("userId", userId)
                    .update();
        } catch (DuplicateKeyException e) {
            // unique 위반으로 PG 트랜잭션은 aborted 상태 — 더 실행할 문장이 없으므로 그대로 반환 (커밋은 롤백으로 처리됨)
            return IssueResult.ALREADY_ISSUED;
        }
        // 읽어둔 값 기준으로 덮어쓰기(lost update 유발 지점). 원자적 감소(SET r = r - 1)를 쓰지 않는 게 의도.
        jdbc.sql("UPDATE coupon SET remaining_quantity = :newRemaining WHERE id = :id")
                .param("newRemaining", remaining.get() - 1)
                .param("id", couponId)
                .update();
        return IssueResult.ISSUED;
    }
}
