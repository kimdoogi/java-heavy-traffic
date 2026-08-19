package com.example.mockext;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.example.mockext.FaultConfig.Fault;
import com.example.mockext.FaultConfig.FaultProperties;
import com.example.mockext.FaultConfig.Mode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 장애 주입 런타임 제어 (E8 fault-timeline.sh 가 호출).
 *   GET  /admin/fault                     현재 설정
 *   POST /admin/fault  {"mode":"slow","delayMs":2000}   부분 갱신 (없는 필드는 유지)
 *   POST /admin/fault/reset               설정 파일 기본값으로 복귀
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
    public Fault set(@RequestBody FaultPatch p) {
        Fault patch = new Fault(p.mode(),
                p.delayMs() != null ? p.delayMs() : -1,
                p.jitterMs() != null ? p.jitterMs() : -1,
                p.failRate() != null ? p.failRate() : -1,
                p.status() != null ? p.status() : 0,
                p.hangSeconds() != null ? p.hangSeconds() : 0,
                p.flapPeriodSeconds() != null ? p.flapPeriodSeconds() : 0);
        return current.updateAndGet(cur -> cur.with(patch));
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        current.set(new Fault(defaults.mode(), defaults.delayMs(), defaults.jitterMs(), defaults.failRate(),
                defaults.status(), defaults.hangSeconds(), defaults.flapPeriodSeconds()));
        return Map.of("reset", true, "fault", current.get());
    }
}
