---
title: "E8 Redis 저항성 — command timeout + 서킷브레이커 (장애 주입 실측)"
date: 2026-09-01
status: done
tags: [experiment, e8, redis, resilience, circuit-breaker, timeout]
related: [../../PLAN.md, ../journal/2026-09-01-E8-redis-resilience.md, ../decisions/D-008-redis-resilience.md, ../concepts/redis-failure-resilience.md]
---

## 가설
Redis 의존(멱등성 claim/cache + redis 전략 재고 Lua)이 장애나 지연에 놓이면 coupon-api가 어떻게 되는가. timeout·서킷브레이커가 실제로 무엇을 막고 무엇을 못 막는가를 수치로 확인한다.

## 설정
- 프로파일 M(app 1 CPU), redis 전략, VT on. Redis는 compose에서 0.5 CPU/256m.
- 장애 주입:
  - **DEAD**: `docker compose pause redis`(hang) / `docker compose stop redis`(refused).
  - **SLOW**: toxiproxy latency toxic(+400ms/명령). compose `REDIS_HOST` override로 앱을 `toxiproxy:6380` 경유로 띄움(`docker-compose.yml`을 `${REDIS_HOST:-redis}`로 수정). CPU throttle·`DEBUG SLEEP`은 각각 Redis 효율·타이밍 문제로 무효 → toxiproxy로 확정.
- 부하: `load/56-redis-fault-load.js`(constant-arrival-rate, open-model). 단일/버스트는 curl.
- 앱 설정: `spring.data.redis.timeout=1000ms`(Step 1), 서킷브레이커 `slowCallDuration 300ms·window 50·minCalls 20·slow/failure rate 50%·waitOpen 5s`.

## 결과

### 1. timeout 실효 (단일 요청, pause)
```
정상 발급   : 201  0.11s
Redis pause : 503  1.03s   ← timeout 없으면 Lettuce 기본 60s, 지금 1s
health      : 503  1.05s   (actuator도 ~1s 내 DOWN, hang 안 함)
복구        : 201  0.03s
```

### 2. DEAD는 스케일에서도 pileup 없음 (400/s open-model, pause 3-phase)
| 국면 | iters | 2xx | 503 | dropped | vusMax | p99 |
|---|---|---|---|---|---|---|
| A 정상 | 4740 | 4740 | 0 | 60 | 460 | 1218ms* |
| B Redis pause | 4784 | 0 | 4784 | 16 | 416 | 1026ms |
| C 복구 | 4800 | 4756 | 0 | 0 | 400 | 3.6ms |

\* A의 1.2s는 JVM 콜드스타트 아티팩트(warm은 p99 23ms). B의 1.0s는 timeout 천장.
- 장애 중에도 지연 ~1s 고정, dropped 낮음, vusMax 안 폭증 = **pileup 없음**. 복구 즉시.

### 3. stop(refused)도 동일 (~1s, pileup 0)
```
stop 단일   : 503 1.01s     (즉시 아님 — Lettuce가 명령 큐잉+재연결 시도, timeout이 상한)
stop 버스트 : max 1.02s, pileup 0
복구        : 201 0.008s    (Lettuce auto-reconnect)
```
→ pause·stop 둘 다 timeout+VT+Lettuce async가 bound. Redis 완전 다운은 timeout으로 해결됨.

### 4. SLOW(+400ms/명령)는 timeout이 못 잡음 — 브레이커 before/after (400/s, 15s)
| 지표 | BEFORE (브레이커 X) | AFTER (브레이커 O) |
|---|---|---|
| 2xx (느린 성공) | 3849 | 0 |
| 503 (빠른 실패) | 0 | 6001 (전부) |
| p50 지연 | 2910ms | **0.955ms** |
| p99 | 4566ms | 1071ms* |
| vusMax | 1321 | 400 |
| dropped | 950 | 0 |

\* AFTER p99 꼬리 = 5초마다 half-open 탐색 5회(toxic 켜져 있어 여전히 느림 → 재OPEN).

## 해석
1. **timeout(Step 1)이 DEAD의 실질 해결.** pause·stop 모두 ~1s fail-fast, storm 스케일에서도 pileup 0, 즉시 복구. VT(싼 블로킹)+Lettuce netty async라 명령들이 병렬로 1s에 만료 → cascade 없음.
2. **DEAD는 붕괴가 아님** — timeout 있으면 bounded degradation(다 503, 앱 멀쩡). 없으면 60s 매달려 적체.
3. **SLOW는 timeout이 장님** — 명령당 400ms < 1s라 안 걸리고 매 요청이 "느린 성공"으로 2.9~5.3s 저하. 전부 성공(0 에러) = **graceful-but-slow, 붕괴 아님**(dropped 950은 k6 VU 램프 아티팩트지 앱 pileup 아님 — advisor 정정).
4. **서킷브레이커가 SLOW를 fast-fail로 전환.** 느린 호출 누적 → OPEN → 즉시 503(p50 0.955ms) + Redis 부하 차단. "3~5s 느린-성공"을 "~1ms 빠른-실패"로. **posture 선택**(serve-slow vs fail-fast)이지 throughput 개선이 아님 — before도 다 성공했고 after는 다 실패. 선착순 correctness-first엔 fast-fail이 나음(빠른 피드백 + Redis 회복).
5. 연결 풀(`lettuce.pool`)은 throughput 축, 브레이커는 posture 축 — 상보적. 브레이커를 택한 건 "느린-성공을 빠른-실패로 + 부하 차단"이 목적이라서(D-008).

## replica/Sentinel — outage "길이" 축 (2026-09-01 추가)
timeout+브레이커가 "다운 **동안**" 거동이라면, Sentinel은 "다운이 **얼마나 오래**"를 자동 failover로 줄인다.

### 설정
- 토폴로지: redis(primary) + redis-replica(1) + sentinel×3(quorum 2). B 소유 override `docker-compose.sentinel.yml`(shared compose 안 건드림) + `application-sentinel.yml`(프로파일 게이트 → 단일모드 공존). **앱 코드 변경 0**(Spring Data Redis가 sentinel 모드 처리).
- Docker 주소 함정 회피: sentinel `resolve/announce-hostnames yes` → get-master-addr가 도달 가능 호스트명(`redis`) 반환(127.0.0.1 함정 X). Step 0에서 격리 검증.

### 결과 (앱 sentinel 모드, `docker compose stop redis`)
```
baseline: 201
t=0  primary stop
t=1~3s: 503   (failover 창 — 앱은 timeout/브레이커로 fast-fail)
t=4s:  201    ← 앱이 승격된 redis-replica로 자동 재연결(코드·재시작 없이)
master = redis-replica (sentinel 승격 확인)
```
- **failover+재연결 창 ~4s.** 창 상한은 `down-after-milliseconds`(5s)+`failover-timeout`(10s) 설정이 정함 — clean stop은 연결 RESET 즉시 감지라 down-after보다 빠름. 단일 Redis의 "사람이 고칠 때까지(분+)"를 "~4s"로.

### 해석
- **two-layer 완성**: 다운 **동안** = timeout+브레이커 fast-fail(안 쌓임), 다운 **길이** = Sentinel auto-failover(~4s). "Redis 죽으면?" → 앱은 버티고 Sentinel이 초 단위로 새 primary 전환.
- failover는 무손실 아님 — async 복제라 승격 순간 마지막 write 몇 개 유실 가능. 근데 Redis=재생 캐시 / DB=진실이라 **중복 발급은 없음**(fail-closed + unique 제약).

## 남은 것
- 브레이커 상태 메트릭(Prometheus)·bulkhead·readiness gating = 후속.
- k6-under-load failover 창(throughput 관점) — 1/s poll이 ~4s를 줬으므로 정밀 측정은 선택.
- 실 프로덕션 replica 2+ (failover 후 재이중화), 멀티노드 조정(E12).
