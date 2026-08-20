package com.example.mockext;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.example.mockext.FaultConfig.Fault;
import com.example.mockext.FaultConfig.FaultProperties;
import com.example.mockext.FaultConfig.Mode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 장애 주입 런타임 제어 (E8 fault-timeline.sh 가 호출).
 *   GET  /admin/fault                                     현재 설정
 *   POST /admin/fault  {"mode":"slow","delayMs":2000}     부분 갱신 (넣은 필드만 교체, 0도 유효값)
 *   POST /admin/fault/reset                               설정 파일 기본값으로 복귀
 */
@RestController
@RequestMapping("/admin/fault")
public class AdminController {

    public record FaultPatch(Mode mode, Long delayMs, Long jitterMs, Double failRate, Integer status,
                             Long hangSeconds, Long flapPeriodSeconds) {}

    private final AtomicReference<Fault> current;
    private final FaultProperties defaults;

    public AdminController(AtomicReference<Fault> current, FaultProperties defaults) {
        this.current = current;
        this.defaults = defaults;
    }

    @GetMapping
    public Fault get() {
        return current.get();
    }

    @PostMapping
    public ResponseEntity<?> set(@RequestBody FaultPatch p) {
        if ((p.delayMs() != null && p.delayMs() < 0) || (p.jitterMs() != null && p.jitterMs() < 0)
                || (p.failRate() != null && (p.failRate() < 0 || p.failRate() > 1))
                || (p.hangSeconds() != null && p.hangSeconds() < 0)
                || (p.flapPeriodSeconds() != null && p.flapPeriodSeconds() < 1)) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_patch", "patch", p));
        }
        Fault updated = current.updateAndGet(cur -> new Fault(
                p.mode() != null ? p.mode() : cur.mode(),
                p.delayMs() != null ? p.delayMs() : cur.delayMs(),
                p.jitterMs() != null ? p.jitterMs() : cur.jitterMs(),
                p.failRate() != null ? p.failRate() : cur.failRate(),
                p.status() != null ? p.status() : cur.status(),
                p.hangSeconds() != null ? p.hangSeconds() : cur.hangSeconds(),
                p.flapPeriodSeconds() != null ? p.flapPeriodSeconds() : cur.flapPeriodSeconds()));
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        current.set(defaults.toFault());
        return Map.of("reset", true, "fault", current.get());
    }
}
