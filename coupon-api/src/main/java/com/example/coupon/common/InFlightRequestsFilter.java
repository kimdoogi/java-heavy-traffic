package com.example.coupon.common;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 처리 중(in-flight) 요청 수 게이지: http_inflight_requests{group="io"|"cpu"|"coupons"|...}
 * 버츄얼 쓰레드는 jvm_threads_live 에 잡히지 않으므로, 서버에 "쌓여 있는" 요청 양을 보려면 이 지표가 필요하다 (E8).
 */
@Component
public class InFlightRequestsFilter extends OncePerRequestFilter {

    private final MeterRegistry registry;
    private final AtomicInteger total = new AtomicInteger();
    private final Map<String, AtomicInteger> byGroup = new ConcurrentHashMap<>();

    public InFlightRequestsFilter(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("http.inflight.requests", total, AtomicInteger::get)
                .tag("group", "all").register(registry);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        AtomicInteger g = byGroup.computeIfAbsent(groupOf(req.getRequestURI()), this::newGroupGauge);
        total.incrementAndGet();
        g.incrementAndGet();
        try {
            chain.doFilter(req, res);
        } finally {
            g.decrementAndGet();
            total.decrementAndGet();
        }
    }

    private AtomicInteger newGroupGauge(String group) {
        AtomicInteger counter = new AtomicInteger();
        Gauge.builder("http.inflight.requests", counter, AtomicInteger::get)
                .tag("group", group).register(registry);
        return counter;
    }

    /** /api/io/sleep → "io", /api/coupons/1/issue → "coupons", /actuator/... → "actuator" */
    static String groupOf(String uri) {
        String[] parts = uri.split("/");
        if (parts.length >= 3 && "api".equals(parts[1])) return parts[2];
        if (parts.length >= 2 && !parts[1].isEmpty()) return parts[1];
        return "root";
    }
}
