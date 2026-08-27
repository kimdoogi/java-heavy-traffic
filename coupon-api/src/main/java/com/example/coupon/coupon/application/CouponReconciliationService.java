package com.example.coupon.coupon.application;

import java.util.Set;

import com.example.coupon.coupon.domain.Coupon;
import com.example.coupon.coupon.domain.CouponIssue;
import com.example.coupon.coupon.infra.CouponIssueRepository;
import com.example.coupon.coupon.infra.CouponRepository;
import com.example.coupon.coupon.infra.RedisCouponStockRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * redis 전략의 유일한 정확성 구멍 보정 (redis-atomic-stock 참고).
 * Lua 성공(재고 DECR + 발급자 SADD) ~ DB coupon_issue INSERT 커밋 사이에 프로세스가 죽으면
 * 사용자가 redis 발급자 set에만 남고 DB엔 없다 — catch 기반 보상은 크래시엔 못 돈다.
 * 그래서 주기적으로 "redis 발급자 set에 있는데 DB엔 없는" 사용자를 찾아 DB로 전진 복구한다(멱등).
 *
 * 전진 복구가 옳은 이유: redis가 이미 재고를 차감·판정했으므로 그 사용자는 '당첨'이다.
 * redis 발급자 수 <= total(재고 게이트가 보장) 이므로 DB로 복구해도 count(coupon_issue) <= total 이 유지된다.
 */
@Service
public class CouponReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(CouponReconciliationService.class);

    private final CouponRepository couponRepository;
    private final CouponIssueRepository issueRepository;
    private final RedisCouponStockRepository stockRepository;
    private final Counter repairedCounter;

    public CouponReconciliationService(CouponRepository couponRepository,
                                       CouponIssueRepository issueRepository,
                                       RedisCouponStockRepository stockRepository,
                                       MeterRegistry meterRegistry) {
        this.couponRepository = couponRepository;
        this.issueRepository = issueRepository;
        this.stockRepository = stockRepository;
        this.repairedCounter = meterRegistry.counter("coupon_reconcile_repaired_total");
    }

    /** 한 쿠폰의 redis-only 발급을 DB로 복구. 복구한 건수 반환. */
    public int reconcile(long couponId) {
        Set<String> members = stockRepository.issuedMembers(couponId);
        if (members.isEmpty()) {
            return 0;
        }
        int repaired = 0;
        for (String member : members) {
            long userId;
            try {
                userId = Long.parseLong(member);
            } catch (NumberFormatException e) {
                continue;   // 예상 밖 멤버는 건너뜀
            }
            if (issueRepository.existsByCouponIdAndUserId(couponId, userId)) {
                continue;   // 이미 DB에 있음 (정상 경로가 기록함)
            }
            try {
                // save()는 각각 자기 트랜잭션 — 하나가 unique 위반(라이브 경로가 동시에 넣음)이어도 나머지에 영향 없음
                issueRepository.save(new CouponIssue(couponId, userId));
                repaired++;
            } catch (DataIntegrityViolationException e) {
                // 라이브 발급이 동시에 같은 행을 넣음 — 멱등, 정상
            }
        }
        if (repaired > 0) {
            repairedCounter.increment(repaired);
            log.warn("reconcile: couponId={} — redis-only 발급 {}건을 DB로 복구", couponId, repaired);
        }
        return repaired;
    }

    /**
     * 주기 조정. 데모 규모라 전체 쿠폰 스캔 — 대규모라면 '최근 활성 쿠폰'만 타겟해야 한다.
     * fixedDelay: 이전 실행이 끝난 뒤부터 간격을 재므로 실행이 겹치지 않는다.
     */
    @Scheduled(fixedDelayString = "${coupon.reconcile.interval-ms:30000}")
    public void reconcileAll() {
        for (Coupon coupon : couponRepository.findAll()) {
            try {
                reconcile(coupon.getId());
            } catch (RuntimeException e) {
                log.error("reconcile 실패 couponId={}", coupon.getId(), e);
            }
        }
    }
}
