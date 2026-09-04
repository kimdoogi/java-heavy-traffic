---
title: "D-008 Redis 저항성 — command timeout + 프로그래매틱 Resilience4j 서킷브레이커"
date: 2026-09-01
status: accepted
tags: [decision, adr, e8, redis, resilience, circuit-breaker]
related: [../experiments/E8-redis-resilience.md, ../concepts/redis-failure-resilience.md, ./D-006-idempotency-redis-claim.md]
---

## 맥락
멱등성(E7)과 redis 전략 재고(E6)가 Redis에 의존한다. Redis 장애/지연 시 앱 거동을 실측(E8)한 결과, 두 장애 모드가 갈렸다:
- **DEAD**(정지/단절): 명령이 hang하거나 refused → timeout이 없으면 Lettuce 기본 60s 매달림.
- **SLOW**(느림, 명령당 400ms): 명령이 timeout(1s) 아래라 성공하지만 요청이 2.9~5.3s로 저하 — timeout이 못 잡음.

## 선택지
1. **아무것도 안 함(fail-open 기본)** — Redis hang 시 60s 매달려 커넥션/스레드 적체.
2. **command timeout만** — DEAD를 1s로 bound. SLOW는 못 잡음.
3. **timeout + 서킷브레이커** — DEAD·SLOW 둘 다. 브레이커는 slow/실패 누적 시 OPEN.
4. **DB 멱등 테이블로 폴백** — 격리 상실(풀 잠식·noisy neighbor), D-006에서 기각한 것과 동종.

브레이커 구현도 갈림:
- **A) `resilience4j-spring-boot3`(@CircuitBreaker 애노테이션)** — Spring AOP 의존, Boot 4/Spring 7 호환 리스크(P-002 재현 우려).
- **B) 프로그래매틱 `resilience4j-circuitbreaker`(core)** — 순수 라이브러리, `CircuitBreaker.decorateSupplier`. AOP·스타터 없음.

## 결정
**3 + B.** command timeout(1s) + **프로그래매틱 Resilience4j** 서킷브레이커.
- `spring.data.redis.timeout/connect-timeout = 1000ms` (Hikari 3s·external 1s와 정렬).
- `RedisCircuitBreaker`(infra) 공용 1개 — idempotency(`IdempotencyRepository`)·재고(`RedisCouponStockRepository`)가 공유(Redis 하나=브레이커 하나).
- **Redis 연산만 감쌈**, DB·직렬화 제외(DB 지연을 Redis 실패로 오발 방지).
- OPEN → `CallNotPermittedException` → 기존 timeout·단절과 **같은 503 storage_unavailable**(fail-closed 일관).
- 임계값: slowCallDuration 300ms(정상<50ms, 저하~400ms 구분), window 50, minCalls 20, slow/failure 50%, waitOpen 5s.

## 이유
- **timeout이 DEAD의 실질 해결**(실측: pause·stop 모두 ~1s, storm 스케일 pileup 0, 즉시 복구). VT+Lettuce async라 cascade 없음.
- **SLOW는 브레이커만 잡음**(실측: before 2.9~5.3s 느린-성공 → after ~1ms fast-fail 503). timeout·VT 둘 다 SLOW엔 장님.
- **프로그래매틱 선택**은 Boot 4 AOP 호환 리스크 회피(의존성 resolve + 25 tests context-load 확인). P-002 교훈.
- **연결 풀 대신 브레이커**: 풀은 throughput 축, 브레이커는 posture 축(느린-성공→빠른-실패 + 부하 차단). 목적이 후자라 브레이커. 둘은 상보적이라 풀은 후속 여지.
- **DB 폴백 기각**: 격리 상실(D-006 논리 동일).

## posture 결정 (명시)
브레이커 OPEN 시 SLOW 구간엔 **전부 503**(잠깐 발급 불가) — before의 "느려도 다 성공"과의 트레이드오프. 선착순은 **정합성·회복 우선**이라 fast-fail 채택: 사용자에 즉시 "재시도" 신호 + 죽어가는 Redis 부하 차단으로 회복 촉진. 데이터 손상 없음(fail-closed) + DB unique 제약이 durable 진실(Redis=재생 캐시).

## 결과
- DEAD·SLOW 모두 앱이 graceful(빠른 503, 자동 복구). 28 tests green(`RedisCircuitBreakerTest` 포함).
- 공유 경로 변경: `build.gradle`(resilience4j 의존성)·`docker-compose.yml`(REDIS_HOST override)·`RedisCouponStockRepository`(A 코드 감쌈) — D-005상 A 합의 대상(플래그).
- **replica/Sentinel 슬라이스 완료(2026-09-01)**: redis+replica+sentinel×3(quorum 2), B override(`docker-compose.sentinel.yml`)+프로파일 게이트(`application-sentinel.yml`, 단일모드 공존, **앱 코드 0**). 앱-following failover **~4s** 실측 — timeout/브레이커(다운 "동안")와 다른 축(다운 "길이")을 채움. [E8 실험](../experiments/E8-redis-resilience.md) replica/Sentinel 절.
- **defer(후속)**: 브레이커 메트릭(Prometheus), bulkhead, readiness gating, replica 2+·멀티노드 조정(E12).
