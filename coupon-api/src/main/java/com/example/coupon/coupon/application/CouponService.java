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
        // 전략과 무관하게 redis 재고를 세팅해 둔다(멱등·저비용). redis가 없어도 생성은 성공해야 하므로
        // 실패는 경고만 남긴다 — redis 전략은 어차피 lazy init으로 복구된다.
        try {
            stockRepository.resetStock(coupon.getId(), totalQuantity);
        } catch (RuntimeException e) {
            log.warn("redis stock init failed (couponId={}) — redis 전략 사용 시 lazy init으로 복구됨: {}",
                    coupon.getId(), e.getMessage());
        }
        return coupon;
    }

    public Optional<Coupon> find(long couponId) {
        return couponRepository.findById(couponId);
    }

    public List<CouponIssue> userIssues(long userId) {
        return issueRepository.findByUserIdOrderByIssuedAtDesc(userId);
    }
}
