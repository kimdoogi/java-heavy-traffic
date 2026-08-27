package com.example.coupon.coupon.infra;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

/**
 * redis 전략(E6)의 재고·1인1매 원자 처리. 재고 확인 + 중복 확인 + 차감 + 발급자 기록을
 * Lua 스크립트 하나로 묶어 race window를 없앤다 (PLAN §1.3).
 */
@Repository
public class RedisCouponStockRepository {

    /**
     * 키 유실 복구(rebuild) 후 이 시간 동안의 발급은 ISSUED_RECOVERING으로 표시돼 DB 백스톱을 거친다.
     * 복구 시점의 재고 계산(total − 커밋된 발급 수)이 미커밋 in-flight 발급분을 놓칠 수 있기 때문 —
     * in-flight 트랜잭션이 정리되기에 충분한 여유로 잡는다.
     */
    private static final Duration RECOVERY_MARKER_TTL = Duration.ofSeconds(180);

    // 반환: 1=발급, 2=발급(복구 구간 — DB 백스톱 필요), 0=재고 소진, -1=이미 발급된 사용자, -3=재고 키 미초기화
    private static final DefaultRedisScript<Long> ISSUE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
              return -1
            end
            local stock = redis.call('GET', KEYS[1])
            if stock == false then
              return -3
            end
            if tonumber(stock) <= 0 then
              return 0
            end
            redis.call('DECR', KEYS[1])
            redis.call('SADD', KEYS[2], ARGV[1])
            if redis.call('EXISTS', KEYS[3]) == 1 then
              return 2
            end
            return 1
            """, Long.class);

    // 보상: 재고 키가 살아 있을 때만 INCR — 키 유실 후 INCR가 가짜 키(값 1)를 만들어 SETNX lazy 복구를
    // 영구 차단하는 것을 막는다. INCR와 SREM(ARGV[2]=='1'일 때)을 한 스크립트로 묶어 부분 실패를 없앤다.
    private static final DefaultRedisScript<Long> COMPENSATE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
              redis.call('INCR', KEYS[1])
            end
            if ARGV[2] == '1' then
              redis.call('SREM', KEYS[2], ARGV[1])
            end
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisCouponStockRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public RedisIssueOutcome tryIssue(long couponId, long userId) {
        Long result = redis.execute(ISSUE_SCRIPT,
                List.of(stockKey(couponId), issuedKey(couponId), recoveryMarkerKey(couponId)),
                String.valueOf(userId));
        if (result == null) {
            throw new IllegalStateException("redis issue script returned null (couponId=" + couponId + ")");
        }
        return switch (result.intValue()) {
            case 1 -> RedisIssueOutcome.ISSUED;
            case 2 -> RedisIssueOutcome.ISSUED_RECOVERING;
            case 0 -> RedisIssueOutcome.SOLD_OUT;
            case -1 -> RedisIssueOutcome.ALREADY_ISSUED;
            case -3 -> RedisIssueOutcome.UNINITIALIZED;
            default -> throw new IllegalStateException(
                    "unexpected issue script result " + result + " (couponId=" + couponId + ")");
        };
    }

    /** 캠페인 생성 시 재고 세팅. 발급자 set·복구 마커도 함께 초기화한다. */
    public void resetStock(long couponId, int stock) {
        redis.opsForValue().set(stockKey(couponId), String.valueOf(stock));
        redis.delete(List.of(issuedKey(couponId), recoveryMarkerKey(couponId)));
    }

    /**
     * 키 유실 시 DB 진실 기반 복구: 발급자 set을 재구축해 1인1매 판정을 복원하고, 재고는 SETNX로
     * 세팅(동시 복구 경합은 한 명만 이김), 복구 마커를 남겨 이후 발급을 DB 백스톱 경로로 보낸다.
     * 순서(마커 → set → 재고)가 중요: 재고가 보이기 전에 dedup과 마커가 준비돼 있어야 한다.
     */
    public void rebuild(long couponId, int stock, Collection<String> issuedUserIds) {
        redis.opsForValue().set(recoveryMarkerKey(couponId), "1", RECOVERY_MARKER_TTL);
        if (!issuedUserIds.isEmpty()) {
            redis.opsForSet().add(issuedKey(couponId), issuedUserIds.toArray(String[]::new));
        }
        redis.opsForValue().setIfAbsent(stockKey(couponId), String.valueOf(stock));
    }

    /** DB 기록 실패 시 보상: 차감분 복구. 중복 발급이면 set은 유지(keepIssuedMember=true)해 자가 치유. */
    public void compensate(long couponId, long userId, boolean keepIssuedMember) {
        redis.execute(COMPENSATE_SCRIPT,
                List.of(stockKey(couponId), issuedKey(couponId)),
                String.valueOf(userId), keepIssuedMember ? "0" : "1");
    }

    /** DB 백스톱이 발급을 거부한 경우: 소비한 재고는 애초에 과잉 계상된 가짜 단위이므로 INCR 없이 set만 정리. */
    public void removeIssuedMember(long couponId, long userId) {
        redis.opsForSet().remove(issuedKey(couponId), String.valueOf(userId));
    }

    /** 고아 키 정리 — DB에 없는 쿠폰의 잔재(생성 롤백, DB 단독 리셋 등). */
    public void deleteKeys(long couponId) {
        redis.delete(List.of(stockKey(couponId), issuedKey(couponId), recoveryMarkerKey(couponId)));
    }

    public Long getStock(long couponId) {
        String v = redis.opsForValue().get(stockKey(couponId));
        return v == null ? null : Long.valueOf(v);
    }

    /** 발급자 set 전체(SMEMBERS) — 조정(reconciliation)이 redis-only 발급을 DB로 복구할 때 쓴다. */
    public Set<String> issuedMembers(long couponId) {
        Set<String> members = redis.opsForSet().members(issuedKey(couponId));
        return members == null ? Set.of() : members;
    }

    private String stockKey(long couponId) {
        return "coupon:" + couponId + ":stock";
    }

    private String issuedKey(long couponId) {
        return "coupon:" + couponId + ":issued";
    }

    private String recoveryMarkerKey(long couponId) {
        return "coupon:" + couponId + ":recovering";
    }
}
