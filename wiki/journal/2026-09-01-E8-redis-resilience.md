---
title: "2026-09-01 E8 — Redis 저항성 (timeout + 서킷브레이커)"
date: 2026-09-01
status: done
tags: [journal, e8, redis, resilience, circuit-breaker]
related: [../../PLAN.md, ../experiments/E8-redis-resilience.md, ../decisions/D-008-redis-resilience.md, ../concepts/redis-failure-resilience.md]
---

## 목표
Redis 장애/지연 시 coupon-api가 무너지지 않게 한다. 무엇을 timeout이 막고, 무엇이 남아 서킷브레이커가 필요한지 **실측으로** 정하고 구현한다. (PLAN E8 범위, 트랙 B)

## 범위
- **한다**: Redis command timeout, 장애 주입 실측(DEAD pause/stop, SLOW), 공용 서킷브레이커(idempotency+재고), 단위 테스트, before/after 실증.
- **안 한다(defer)**: replica/Sentinel(outage 길이 축 — 다음 슬라이스), 브레이커 메트릭·bulkhead·readiness gating.

## 설계 / 진행 (시간순)
- **Step 1 — timeout**: `spring.data.redis.timeout/connect-timeout = 1000ms`. Redis만 unbounded였음(Hikari 3s·external 1s는 이미 bounded). `docker pause redis` 중 단일 요청이 60s가 아닌 **1.03s에 503** — 실효 확인.
- **Step 2 — 베이스라인 실측**:
  - DEAD(pause): 400/s open-model 3-phase. 장애 중 전부 ~1s 503, dropped 16·vusMax 416 = **pileup 없음**, 복구 즉시. VT 가설 확인.
  - stop(refused)도 ~1s(Lettuce가 명령 큐잉+재연결 시도, timeout이 상한), 복구 자동(auto-reconnect).
  - 부수 발견: A(alive) p99 1.2s는 **JVM 콜드스타트**였음(warm은 23ms).
- **advisor 교정**: "SLOW를 안 재고 브레이커 marginal"이라 한 건 성급. closed-loop 50은 pileup 반증 못 함(open-model 필요), dead만 테스트한 건 timeout에 유리한 케이스. → SLOW를 제대로 재기로.
- **SLOW 주입 난항**: CPU throttle(컨테이너명 오타 + Redis가 너무 효율적)·`DEBUG SLEEP`(타이밍) 둘 다 무효. → toxiproxy latency toxic로 확정하되 `REDIS_HOST` 하드코딩이 막음 → `docker-compose.yml`을 `${REDIS_HOST:-redis}`로 수정(fork A).
- **SLOW 실측(before)**: +400ms/명령 → 매 요청 2.9~5.3s "느린 성공"(전부 200, 0 에러). **timeout이 못 잡는 구멍** 확인. graceful-but-slow(붕괴 아님).
- **Step 3 — 서킷브레이커**: Boot-4 호환 probe(resilience4j 의존성 resolve + 25 tests context-load OK) → 프로그래매틱 `resilience4j-circuitbreaker` 채택. `RedisCircuitBreaker`(infra) 공용 1개. idempotency는 계층 통일 위해 `IdempotencyRepository` 신설로 repo 경유로 리팩터(브레이커 infra로 이동, application→infra 순환 제거). 재고는 `RedisCouponStockRepository.tryIssue/getStock` 감쌈. OPEN→503 매핑.
- **검증(after)**: 같은 SLOW에 브레이커 → **OPEN 발동 → 전부 503, p50 0.955ms**(before 2910ms). posture 전환 실증.
- **단위 테스트**: `RedisCircuitBreakerTest`(실패 누적 OPEN / 느림 누적 OPEN / 정상 통과) 3건. 전체 **28 tests green**.
- **스택 복원**: coupon-api direct redis 재생성 + toxiproxy 프록시 삭제.

## 수동 실행 명령 (재현용)
```bash
# 이미지 재빌드 후 프록시 경유로 앱 띄우기
scripts/build.sh
REDIS_HOST=toxiproxy REDIS_PORT=6380 docker compose up -d coupon-api
# toxiproxy redis 프록시 + 400ms 지연 주입
curl -XPOST localhost:8474/proxies -d '{"name":"redis","listen":"0.0.0.0:6380","upstream":"redis:6379"}'
curl -XPOST localhost:8474/proxies/redis/toxics -d '{"name":"lat","type":"latency","attributes":{"latency":400}}'
# SLOW 부하
COUPON_ID=<id> RATE=400 DURATION=15s k6 run load/56-redis-fault-load.js
# 정리
curl -XDELETE localhost:8474/proxies/redis
docker compose up -d --force-recreate coupon-api   # direct redis 복원
# DEAD: docker compose pause redis / unpause / stop / start
```

## 결과
- 신규: `infra/RedisCircuitBreaker.java`, `infra/IdempotencyRepository.java`, `RedisCircuitBreakerTest.java`, `load/56-redis-fault-load.js`.
- 수정: `application.yml`(timeout·cb 설정), `docker-compose.yml`(REDIS_HOST override), `build.gradle`(resilience4j), `IdempotencyService`(repo 경유 재작성), `RedisCouponStockRepository`(감쌈), `CouponApiExceptionHandler`(CallNotPermitted→503).
- 28 tests green. 실측 리포트 [E8](../experiments/E8-redis-resilience.md), 결정 [D-008](../decisions/D-008-redis-resilience.md), 개념 [redis-failure-resilience](../concepts/redis-failure-resilience.md).

## 배운 것
- **장애는 한 종류가 아니다**: DEAD(hang/refused)는 timeout이, SLOW(sub-timeout)는 브레이커가 잡는다. 서로 다른 도구.
- VT+Lettuce async면 DEAD는 cascade 안 남 — 교과서보다 앱이 잘 버팀. 그래서 브레이커의 가치는 "붕괴 방지"가 아니라 SLOW의 "느린-성공→빠른-실패 posture 전환 + 부하 차단".
- Boot 4에선 스타터/AOP보다 프로그래매틱 라이브러리가 안전(P-002 재현 회피).

## 남은 일 / 다음 단계
- [x] replica/Sentinel 슬라이스 (2026-09-01 완료) — redis+replica+sentinel×3(quorum 2), B override `docker-compose.sentinel.yml`(shared 안 건드림)+`application-sentinel.yml`(프로파일 게이트, 단일모드 공존, **앱 코드 0**). advisor 조언대로 위험지점(Docker 주소 해석) 먼저 격리 검증 → 수동 failover → 앱-following failover. **앱이 primary stop 후 ~4s만에 승격된 replica로 자동 재연결(발급 복구)**. two-layer 완성(다운 동안=브레이커, 다운 길이=Sentinel). [E8](../experiments/E8-redis-resilience.md) replica/Sentinel 절.
- [ ] 브레이커 상태 Prometheus 메트릭 + Grafana(관측), bulkhead, readiness gating.
- [ ] build.gradle·docker-compose·RedisCouponStockRepository 공유/ A 경로 변경 → kimdoogi 합의(D-005).
- [ ] (E7 잔여) `wiki/experiments/E7-idempotency.md` — E7 부하 실측은 results/E7-*에 있으나 experiment md 미작성.
