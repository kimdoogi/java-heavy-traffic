package com.example.mockext;

import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 장애 주입 설정. 기본값은 application.yml(mock.fault.*, env 오버라이드 가능) 한 곳에만 두고
 * Boot relaxed binding으로 바인딩한다 (FAULT_MODE=SLOW 같은 대소문자 차이도 흡수).
 * 런타임에는 /admin/fault 로 교체 가능 (E8 타임라인 실험).
 */
@Configuration
@EnableConfigurationProperties(FaultConfig.FaultProperties.class)
public class FaultConfig {

    public enum Mode { normal, slow, error, hang, flapping }

    public record Fault(Mode mode, long delayMs, long jitterMs, double failRate, int status,
                        long hangSeconds, long flapPeriodSeconds) {
    }

    /** maxConcurrentHangs는 기동 시 고정(커넥터 보호용) — /admin/fault 로 패치되지 않는다. */
    @ConfigurationProperties(prefix = "mock.fault")
    public record FaultProperties(Mode mode, long delayMs, long jitterMs, double failRate, int status,
                                  long hangSeconds, long flapPeriodSeconds, int maxConcurrentHangs) {
        Fault toFault() {
            return new Fault(mode, delayMs, jitterMs, failRate, status, hangSeconds, flapPeriodSeconds);
        }
    }

    /** 현재 장애 설정 (원자적 교체) */
    @Bean
    public AtomicReference<Fault> currentFault(FaultProperties props) {
        return new AtomicReference<>(props.toFault());
    }
}
