package com.example.coupon.experiment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import com.example.coupon.external.NotificationClient;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 실험용 엔드포인트 (PLAN.md §1.2). 각 엔드포인트는 하나의 병목 유형만 고립시킨다.
 *  - /api/ping            프레임워크 오버헤드 베이스라인 (E1)
 *  - /api/io/sleep        순수 I/O 대기 → VT 효과 (E2)
 *  - /api/io/external     실제 다운스트림 HTTP 대기 (E2/E8)
 *  - /api/cpu/hash        CPU bound → VT 효과 없음 (E3)
 *  - /api/pin/sync        synchronized 안에서 블로킹 → VT pinning 재현 (E5)
 *  - /api/pin/lock        ReentrantLock 버전 → pinning 없음 (E5 대조군)
 *  - /api/db/ping         SELECT 1 → 커넥션 풀 경로 확인 (E1/E4)
 *  - /api/env             스케줄러/캐리어 실효값 관찰 (P-003)
 */
@RestController
@RequestMapping("/api")
public class ExperimentController {

    private static final int STRIPES = 4096;
    // 요청마다 다른 락을 쓰게 해서 "락 경합"이 아니라 "pinning" 자체만 보이게 한다 (JIT 락 제거도 피함).
    private final Object[] monitors = new Object[STRIPES];
    private final ReentrantLock[] locks = new ReentrantLock[STRIPES];
    private final AtomicLong seq = new AtomicLong();

    private final NotificationClient notificationClient;
    private final JdbcClient jdbcClient;

    public ExperimentController(NotificationClient notificationClient, JdbcClient jdbcClient) {
        this.notificationClient = notificationClient;
        this.jdbcClient = jdbcClient;
        for (int i = 0; i < STRIPES; i++) {
            monitors[i] = new Object();
            locks[i] = new ReentrantLock();
        }
    }

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Map.of("pong", true, "virtual", Thread.currentThread().isVirtual());
    }

    @GetMapping("/io/sleep")
    public Map<String, Object> sleep(@RequestParam(defaultValue = "300") long ms) throws InterruptedException {
        Thread.sleep(ms);
        return Map.of("sleptMs", ms, "virtual", Thread.currentThread().isVirtual());
    }

    @GetMapping("/io/external")
    public Map<String, Object> external(@RequestParam(required = false) Integer delayMs,
                                        @RequestParam(required = false) Double failRate) {
        Map<String, Object> res = notificationClient.notify(seq.incrementAndGet(), delayMs, failRate);
        return Map.of("external", res, "virtual", Thread.currentThread().isVirtual());
    }

    @GetMapping("/cpu/hash")
    public Map<String, Object> hash(@RequestParam(defaultValue = "20000") int n) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] data = "virtual-threads".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < n; i++) {
            data = md.digest(data);
        }
        return Map.of("n", n, "hash", HexFormat.of().formatHex(data, 0, 8), "virtual", Thread.currentThread().isVirtual());
    }

    @GetMapping("/pin/sync")
    public Map<String, Object> pinSync(@RequestParam(defaultValue = "50") long ms) throws InterruptedException {
        int i = (int) (seq.incrementAndGet() % STRIPES);
        synchronized (monitors[i]) {          // JDK 21: 여기서 블로킹되면 캐리어 쓰레드가 pinning 된다
            Thread.sleep(ms);
        }
        return Map.of("mode", "synchronized", "sleptMs", ms, "virtual", Thread.currentThread().isVirtual());
    }

    @GetMapping("/pin/lock")
    public Map<String, Object> pinLock(@RequestParam(defaultValue = "50") long ms) throws InterruptedException {
        int i = (int) (seq.incrementAndGet() % STRIPES);
        ReentrantLock lock = locks[i];
        lock.lock();
        try {
            Thread.sleep(ms);                  // j.u.c 락은 VT를 unmount 시킬 수 있다 → pinning 없음
        } finally {
            lock.unlock();
        }
        return Map.of("mode", "reentrantLock", "sleptMs", ms, "virtual", Thread.currentThread().isVirtual());
    }

    @GetMapping("/env")
    public Map<String, Object> env() {
        // VT 스케줄러의 캐리어는 "ForkJoinPool-1-worker-N" 플랫폼 스레드 — getAllStackTraces는 플랫폼 스레드만 반환하므로 그대로 셀 수 있다
        var carriers = Thread.getAllStackTraces().keySet().stream()
                .map(Thread::getName)
                .filter(n -> n.startsWith("ForkJoinPool-1-worker-"))
                .sorted()
                .toList();
        return Map.of(
                "availableProcessors", Runtime.getRuntime().availableProcessors(),
                "schedulerParallelismProp", String.valueOf(System.getProperty("jdk.virtualThreadScheduler.parallelism")),
                "schedulerMaxPoolSizeProp", String.valueOf(System.getProperty("jdk.virtualThreadScheduler.maxPoolSize")),
                "carrierCount", carriers.size(),
                "carriers", carriers,
                "virtual", Thread.currentThread().isVirtual());
    }

    @GetMapping("/db/ping")
    public Map<String, Object> dbPing() {
        Integer one = jdbcClient.sql("SELECT 1").query(Integer.class).single();
        return Map.of("db", one, "virtual", Thread.currentThread().isVirtual());
    }
}
