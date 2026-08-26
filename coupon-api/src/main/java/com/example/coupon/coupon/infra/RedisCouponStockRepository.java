package com.example.coupon.coupon.infra;

import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

/**
 * redis 전략(E6)의 재고·1인1매 원자 처리. 재고 확인 + 중복 확인 + 차감 + 발급자 기록을
 * Lua 스크립트 하나로 묶어 race window를 없앤다 (PLAN §1.3).
 */
@Repository
public class RedisCouponStockRepository {

    /** 반환: 1=발급, 0=재고 소진, -1=이미 발급된 사용자, -3=재고 키 미초기화 */
    public static final long ISSUED = 1;
    public static final long SOLD_OUT = 0;
    public static final long ALREADY_ISSUED = -1;
    public static final long UNINITIALIZED = -3;

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
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisCouponStockRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public long tryIssue(long couponId, long userId) {
        Long result = redis.execute(ISSUE_SCRIPT,
                List.of(stockKey(couponId), issuedKey(couponId)),
                String.valueOf(userId));
        if (result == null) {
            throw new IllegalStateException("redis issue script returned null (couponId=" + couponId + ")");
        }
        return result;
    }

    /** 캠페인 생성 시 재고 세팅. 기존 발급자 set도 함께 초기화한다. */
    public void resetStock(long couponId, int stock) {
        redis.opsForValue().set(stockKey(couponId), String.valueOf(stock));
        redis.delete(issuedKey(couponId));
    }

    /** 키 유실 시 lazy 초기화. 동시 초기화 경합은 SETNX로 한 명만 이기게 한다. */
    public void initStockIfAbsent(long couponId, int stock) {
        redis.opsForValue().setIfAbsent(stockKey(couponId), String.valueOf(stock));
    }

    /**
     * DB INSERT 실패 시 보상: 차감분 복구. 중복 발급(이미 DB에 존재)이면 발급자 set은 유지해
     * 다음 요청부터 Lua 단계에서 걸러지게 하고, 그 외 실패는 set에서도 제거한다.
     */
    public void compensate(long couponId, long userId, boolean keepIssuedMember) {
        redis.opsForValue().increment(stockKey(couponId));
        if (!keepIssuedMember) {
            redis.opsForSet().remove(issuedKey(couponId), String.valueOf(userId));
        }
    }

    public Long getStock(long couponId) {
        String v = redis.opsForValue().get(stockKey(couponId));
        return v == null ? null : Long.valueOf(v);
    }

    private String stockKey(long couponId) {
        return "coupon:" + couponId + ":stock";
    }

    private String issuedKey(long couponId) {
        return "coupon:" + couponId + ":issued";
    }
}
