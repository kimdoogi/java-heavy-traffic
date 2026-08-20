package com.example.mockext;

import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.example.mockext.FaultConfig.Fault;
import com.example.mockext.FaultConfig.FaultProperties;
import com.example.mockext.FaultConfig.Mode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 느린 외부 서비스(알림/결제) 흉내. 우선순위: 요청 파라미터(delayMs/failRate) > 전역 fault 설정.
 * hang 동시 개수는 maxConcurrentHangs로 제한한다 — 무제한이면 rate×hangSeconds개의 커넥션이
 * 쌓여 max-connections를 고갈시키고 /admin/fault 제어 API까지 잠긴다.
 */
@RestController
@RequestMapping("/notify")
public class NotifyController {

    private static final Logger log = LoggerFactory.getLogger(NotifyController.class);
    /** slow/지연 경로도 커넥션을 점유하므로 delay 상한을 서버에서 강제한다 (admin 검증과 이중 방어) */
    private static final long MAX_DELAY_MS = 60_000;

    private final AtomicReference<Fault> current;
    private final Semaphore hangSlots;
    private final AtomicLong seq = new AtomicLong();
    private final Counter ok, failed, hung, hangRejected;

    public NotifyController(AtomicReference<Fault> current, FaultProperties props, MeterRegistry registry) {
        this.current = current;
        this.hangSlots = new Semaphore(Math.max(1, props.maxConcurrentHangs()));   // 0이면 hang이 전부 즉시 503이 되므로 최소 1
        this.ok = registry.counter("mock.notify", "outcome", "ok");
        this.failed = registry.counter("mock.notify", "outcome", "failed");
        this.hung = registry.counter("mock.notify", "outcome", "hung");
        this.hangRejected = registry.counter("mock.notify", "outcome", "hang_rejected");
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> get(@RequestParam(required = false) Long userId,
                                                   @RequestParam(required = false) Long delayMs,
                                                   @RequestParam(required = false) Double failRate) throws InterruptedException {
        return handle(userId, delayMs, failRate);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> post(@RequestParam(required = false) Long userId,
                                                    @RequestParam(required = false) Long delayMs,
                                                    @RequestParam(required = false) Double failRate) throws InterruptedException {
        return handle(userId, delayMs, failRate);
    }

    private ResponseEntity<Map<String, Object>> handle(Long userId, Long delayMs, Double failRate) throws InterruptedException {
        Fault f = current.get();
        long id = seq.incrementAndGet();
        Mode mode = effectiveMode(f);

        if (mode == Mode.hang) {
            if (!hangSlots.tryAcquire()) {
                if (hangRejected.count() == 0) {
                    log.warn("hang 슬롯({}) 소진 — 이후 초과분은 즉시 503. 동시 hang ≈ rate×hangSeconds 로 FAULT_MAX_HANGS 사이징 필요",
                            (int) Math.max(1, hangSlots.availablePermits()));
                }
                hangRejected.increment();
                return ResponseEntity.status(503).body(Map.of("id", id, "mode", "hang-rejected"));
            }
            try {
                hung.increment();
                Thread.sleep(f.hangSeconds() * 1000);   // 응답을 주지 않고 연결만 유지 (read timeout 유도)
            } finally {
                hangSlots.release();
            }
            return ResponseEntity.status(f.status()).body(Map.of("id", id, "mode", "hang-released"));
        }

        long delay = delayMs != null ? delayMs : f.delayMs();
        if (f.jitterMs() > 0) delay += ThreadLocalRandom.current().nextLong(f.jitterMs() + 1);
        delay = Math.max(0, Math.min(delay, MAX_DELAY_MS));   // 음수(파라미터 오입력) IAE 방지 + 커넥션 무한 점유 방지
        if (delay > 0) Thread.sleep(delay);

        // error 모드: fail-rate가 설정돼 있으면 그 확률로(부분 장애), 아니면 100% 실패
        double rate = failRate != null ? failRate
                : (mode == Mode.error ? (f.failRate() > 0 ? f.failRate() : 1.0) : f.failRate());
        if (rate > 0 && ThreadLocalRandom.current().nextDouble() < rate) {
            failed.increment();
            return ResponseEntity.status(f.status())
                    .body(Map.of("id", id, "status", "failed", "delayMs", delay, "mode", mode.name()));
        }
        ok.increment();
        return ResponseEntity.ok(Map.of("id", id, "status", "sent", "delayMs", delay, "mode", mode.name(),
                "userId", userId == null ? -1 : userId));
    }

    /** flapping: period 동안 normal, 다음 period 동안 error 를 반복 */
    private static Mode effectiveMode(Fault f) {
        if (f.mode() != Mode.flapping) return f.mode();
        long window = (System.currentTimeMillis() / 1000) / Math.max(1, f.flapPeriodSeconds());
        return window % 2 == 0 ? Mode.normal : Mode.error;
    }
}
