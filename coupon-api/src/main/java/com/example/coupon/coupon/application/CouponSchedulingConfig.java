package com.example.coupon.coupon.application;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** @Scheduled 조정 배치(CouponReconciliationService) 활성화. */
@Configuration
@EnableScheduling
class CouponSchedulingConfig {
}
