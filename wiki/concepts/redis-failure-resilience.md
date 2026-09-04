---
title: "Redis 장애 저항성 — timeout · 서킷브레이커 · Lettuce · HA"
date: 2026-09-01
status: solid
tags: [concept, redis, resilience, circuit-breaker, lettuce, sentinel, e8]
related: [../experiments/E8-redis-resilience.md, ../decisions/D-008-redis-resilience.md, ./redis-atomic-stock.md]
---

핵심 질문: **"Redis가 죽으면 우리 서비스는 어떻게 되나요?"** — E8 실측으로 답할 수 있는 수준으로 정리.

## 1. 장애는 한 종류가 아니다 (제일 중요)
| 모드 | 증상 | 무엇이 잡나 |
|---|---|---|
| **DEAD — 정지(hang)** | 응답 없이 매달림 | command timeout |
| **DEAD — 죽음(refused)** | 연결 거부 | command timeout(Lettuce가 재연결 큐잉하므로 즉시 아님) |
| **SLOW — 느림** | 명령이 timeout 아래로 성공하지만 느림(예 400ms) | **서킷브레이커**(timeout은 장님) |

timeout·브레이커는 다운 **"동안"**의 거동을, replica/Sentinel은 다운 **"길이"**를 다룬다 — 다른 축.

## 2. command timeout — DEAD의 해결
Lettuce 기본 명령 timeout은 60s. 설정 안 하면 hung Redis에 매 요청이 60s 매달려 커넥션/스레드 적체. `spring.data.redis.timeout=1s`로 잘라 **1s fail-fast 503**(fail-closed).
- 실측: pause·stop 모두 ~1s, 400/s 스케일에서도 pileup 0, 복구 즉시.
- **왜 cascade 안 나나**: VT(가상 스레드)라 hung 요청이 싼 스레드를 묶고(platform 스레드였으면 고갈), Lettuce는 netty async라 명령들이 **병렬로** 1s에 만료(직렬 아님). VT+async 조합이 DEAD를 bound.

## 3. 서킷브레이커 — SLOW의 해결
SLOW는 명령당 지연이 timeout 아래(400ms<1s)라 매 요청이 "느린 성공"으로 저하(실측 2.9~5.3s, 전부 200). timeout은 못 본다.

서킷브레이커 = **두꺼비집**. 최근 N호출 중 느림/실패 비율이 임계 넘으면 회로를 끊는다.
- 상태: **CLOSED**(정상 통과) → 느림/실패 과다 → **OPEN**(즉시 차단, 실행 없이 예외) → 대기 후 **HALF_OPEN**(탐색 몇 개) → 회복 CLOSED / 여전히 나쁨 OPEN.
- 발동(OPEN) 효과: "3~5s 느린-성공"을 "~1ms 빠른-실패(503)"로 + 죽어가는 Redis에 부하 차단 → 회복 촉진.
- 우리 구현: 프로그래매틱 Resilience4j(`slowCallDuration 300ms`, slow/failure 50%, window 50, waitOpen 5s). Redis 하나=브레이커 하나(멱등성+재고 공유). Redis 연산만 감쌈(DB 제외 — DB 지연 오발 방지).

## 4. posture — "고칠" 게 아니라 "선택"할 것
장애 시 두 자세:
- **serve-slow**: 다 처리하되 3~5s 기다림(가용성↑, but 느림 + Redis 계속 과부하).
- **fail-fast**: 즉시 503 + 부하 차단(빠른 피드백 + Redis 회복, but 잠깐 불가).
브레이커는 posture를 serve-slow→fail-fast로 **바꾼다**. 선착순 정합성 서비스는 fail-fast가 낫다(느려도 파느니 잠깐 멈추고 빨리 회복). 연결 풀(throughput 축)과 상보적.

## 5. Lettuce — 클라이언트가 하는 일
Lettuce = Spring Boot 기본 Redis 클라이언트(앱 JVM 안, netty 기반).
- **fail-fast**: 우리 timeout 설정으로 명령을 1s에 자름.
- **auto-reconnect**: 연결 끊기면 백그라운드로 재연결 시도(기본 ON). Redis 복귀 시 자동 정상화.
- **단일 공유 연결**(pool 미설정): 명령을 하나의 netty 채널에 멀티플렉싱. 대기성 명령엔 병목 아님.
- **못 하는 것**: 다른 Redis로 **failover 안 함**. host 하나만 알면 그거 복구만 기다림 → HA는 Sentinel 몫.

## 6. HA — outage "길이"를 줄이는 축 (replica/Sentinel)
단일 Redis면 그 하나가 살아나야 복구(수초~수분, 사람 개입이면 더). **Sentinel/Cluster**는 자동 failover:
- Sentinel(감시자) N + primary + replica N. primary 죽으면 replica **자동 승격**(수 초).
- 앱은 단일 host 대신 sentinel 노드를 설정(`spring.data.redis.sentinel.*`) → Lettuce가 "현재 primary?"를 동적으로 물어 연결, failover 시 topology refresh로 새 primary 재연결(**런타임**, 재시작·env 변경 불가).
- 즉 timeout/브레이커(다운 동안 거동) + Sentinel(다운 길이) = 완전한 저항성.

## 7. 무손상 — 다운이어도 망가지진 않음
- **fail-closed**: Redis 장애 시 503 거부(조용히 발급 안 함). 초과발급 0.
- **DB unique 제약이 durable 진실**: Redis 키가 유실돼도 "누가 받았나"는 DB coupon_issue가 진실. Redis는 응답 재생 캐시(best-effort). 유실 시 재시도가 재생 대신 already_issued를 받을 뿐, 중복 발급은 없음.
- **복구 시 정합**: `CouponReconciliationService`(기동 시)가 redis-only 발급을 DB와 대조 복구.

## 면접 답변 스크립트 (~1분)
> "Redis 장애를 두 모드로 나눠 대응합니다. 완전 다운은 command timeout(1s)으로 fail-fast 503 — 가상 스레드와 Lettuce의 async I/O 덕에 스케일에서도 cascade 없이 bounded하게 견딥니다. 느린 Redis는 timeout이 못 잡아 요청이 3~5초로 저하되는데, 이건 Resilience4j 서킷브레이커가 느린 호출 비율로 감지해 회로를 열고 즉시 503으로 전환합니다 — 느린-성공을 빠른-실패로 바꾸는 posture 선택이자 Redis 부하 차단으로 회복을 돕습니다. 가용성 자체를 높이려면 Sentinel로 자동 failover를 두면 되고요. 어떤 경우든 fail-closed라 데이터는 무손상이고, DB unique 제약이 발급 사실의 진실이라 Redis가 흔들려도 중복 발급은 안 납니다."

## 꼬리질문
- **왜 브레이커가 timeout 위에 또?** timeout은 DEAD만. SLOW(sub-timeout)는 브레이커만.
- **왜 fail-open 안 함?** 멱등성/정합성 보장을 요청받고 조용히 안 지키면 신뢰가 깨짐. 차라리 거절.
- **왜 스타터 대신 프로그래매틱?** Boot 4/Spring 7 AOP 호환 리스크(P-002 재현) 회피.
- **DB 폴백은?** 격리 상실(풀 잠식·noisy neighbor). Redis가 별도 스토어라 격리 + TTL 자동정리(D-006).
