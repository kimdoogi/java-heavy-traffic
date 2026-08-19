package com.example.mockext;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.example.mockext.FaultConfig.Fault;
import com.example.mockext.FaultConfig.Mode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 느린 외부 서비스(알림/결제) 흉내. 우선순위: 요청 파라미터(delayMs/failRate) > 전역 fault 설정.
 */
@RestController
@RequestMapping("/notify")
public class NotifyController {

    private final AtomicReference<Fault> current;
    private final AtomicLong seq = new AtomicLong();
    private final Counter ok, failed, hung;

    public NotifyController(AtomicReference<Fault> current, MeterRegistry registry) {
        this.current = current;
        this.ok = registry.counter("mock.notify", "outcome", "ok");
        this.failed = registry.counter("mock.notify", "outcome", "failed");
        this.hung = registry.counter("mock.notify", "outcome", "hung");
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
            hung.increment();
            Thread.sleep(f.hangSeconds() * 1000);           // 응답을 주지 않고 연결만 유지 (read timeout 유도)
            return ResponseEntity.status(f.status()).body(Map.of("id", id, "mode", "hang-released"));
        }

        long delay = delayMs != null ? delayMs : f.delayMs();
        if (f.jitterMs() > 0) delay += ThreadLocalRandom.current().nextLong(f.jitterMs() + 1);
        if (delay > 0) Thread.sleep(delay);

        double rate = failRate != null ? failRate : (mode == Mode.error ? Math.max(f.failRate(), 1.0) : f.failRate());
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
