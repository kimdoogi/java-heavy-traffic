package com.example.mockext;

import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 장애 주입 설정. 런타임에 /admin/fault 로 교체 가능 (E8 타임라인 실험). */
@Configuration
public class FaultConfig {

    public enum Mode { normal, slow, error, hang, flapping }

    public record Fault(Mode mode, long delayMs, long jitterMs, double failRate, int status,
                        long hangSeconds, long flapPeriodSeconds) {
        public Fault with(Fault patch) {
            return new Fault(
                    patch.mode != null ? patch.mode : mode,
                    patch.delayMs >= 0 ? patch.delayMs : delayMs,
                    patch.jitterMs >= 0 ? patch.jitterMs : jitterMs,
                    patch.failRate >= 0 ? patch.failRate : failRate,
                    patch.status > 0 ? patch.status : status,
                    patch.hangSeconds > 0 ? patch.hangSeconds : hangSeconds,
                    patch.flapPeriodSeconds > 0 ? patch.flapPeriodSeconds : flapPeriodSeconds);
        }
    }

    @ConfigurationProperties(prefix = "mock.fault")
    public record FaultProperties(Mode mode, long delayMs, long jitterMs, double failRate, int status,
                                  long hangSeconds, long flapPeriodSeconds) {
        Fault toFault() {
            return new Fault(mode, delayMs, jitterMs, failRate, status, hangSeconds, flapPeriodSeconds);
        }
    }

    /** 현재 장애 설정 (원자적 교체) */
    @Bean
    public AtomicReference<Fault> currentFault(FaultProperties props) {
        return new AtomicReference<>(props.toFault());
    }

    @Bean
    public FaultProperties faultProperties(org.springframework.core.env.Environment env) {
        return new FaultProperties(
                Mode.valueOf(env.getProperty("mock.fault.mode", "normal")),
                env.getProperty("mock.fault.delay-ms", Long.class, 300L),
                env.getProperty("mock.fault.jitter-ms", Long.class, 0L),
                env.getProperty("mock.fault.fail-rate", Double.class, 0.0),
                env.getProperty("mock.fault.status", Integer.class, 500),
                env.getProperty("mock.fault.hang-seconds", Long.class, 300L),
                env.getProperty("mock.fault.flap-period-seconds", Long.class, 10L));
    }
}
