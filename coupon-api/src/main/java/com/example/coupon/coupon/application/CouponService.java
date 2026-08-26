package com.example.coupon.coupon.application;

import java.util.List;
import java.util.Optional;

import com.example.coupon.coupon.domain.Coupon;
import com.example.coupon.coupon.domain.CouponIssue;
import com.example.coupon.coupon.infra.CouponIssueRepository;
import com.example.coupon.coupon.infra.CouponRepository;
import com.example.coupon.coupon.infra.RedisCouponStockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 캠페인 생성/조회 (실험 세팅·검증용 — 부하 대상은 CouponIssueService 쪽). */
@Service
public class CouponService {

    private static final Logger log = LoggerFactory.getLogger(CouponService.class);

    private final CouponRepository couponRepository;
    private final CouponIssueRepository issueRepository;
    private final RedisCouponStockRepository stockRepository;

    public CouponService(CouponRepository couponRepository,
                         CouponIssueRepository issueRepository,
                         RedisCouponStockRepository stockRepository) {
        this.couponRepository = couponRepository;
        this.issueRepository = issueRepository;
        this.stockRepository = stockRepository;
    }

    @Transactional
    public Coupon create(String name, int totalQuantity) {
        Coupon coupon = couponRepository.save(new Coupon(name, totalQuantity));
        // redis 재고 세팅은 커밋 이후로 미룬다: 트랜잭션 안에서 쓰면 (1) DB 커넥션을 redis 왕복 동안 점유하고
        // (2) 롤백 시 존재하지 않는 쿠폰 id의 고아 키가 영구히 남는다(BIGSERIAL은 id를 재사용하지 않음).
        // redis가 없어도 생성은 성공해야 하므로 실패는 경고만 — redis 전략은 lazy init으로 복구된다.
        long couponId = coupon.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    stockRepository.resetStock(couponId, totalQuantity);
                } catch (RuntimeException e) {
                    log.warn("redis stock init failed (couponId={}) — redis 전략 사용 시 lazy init으로 복구됨: {}",
                            couponId, e.getMessage());
                }
            }
        });
        return coupon;
    }

    public Optional<Coupon> find(long couponId) {
        return couponRepository.findById(couponId);
    }

    public List<CouponIssue> userIssues(long userId) {
        return issueRepository.findByUserIdOrderByIssuedAtDesc(userId);
    }
}
