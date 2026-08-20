package com.example.coupon.common;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
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

    // 게이지 태그는 허용목록으로 제한한다: 임의 404 경로가 시리즈를 무한 생성하면 안 됨 (카디널리티 누수)
    private static final Set<String> KNOWN_GROUPS = Set.of("ping", "io", "cpu", "pin", "db", "coupons", "actuator");

    /** /api/io/sleep → "io", /api/coupons/1/issue → "coupons", /actuator/... → "actuator", 그 외 전부 → "other" */
    static String groupOf(String uri) {
        String seg;
        if (uri.startsWith("/api/")) {
            int end = uri.indexOf('/', 5);
            seg = end < 0 ? uri.substring(5) : uri.substring(5, end);
        } else if (uri.startsWith("/actuator")) {
            seg = "actuator";
        } else {
            return "other";
        }
        return KNOWN_GROUPS.contains(seg) ? seg : "other";
    }
}
