package com.example.coupon.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 외부(mock) 서버 호출 설정. connectTimeoutMs/readTimeoutMs 모두 0이면 타임아웃 없음 (E8-0 무방비 실험용). */
@ConfigurationProperties(prefix = "external")
public record ExternalProperties(String baseUrl, int connectTimeoutMs, int readTimeoutMs) {
}
