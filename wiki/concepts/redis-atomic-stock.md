---
title: "Redis 원자 재고 차감 — 선착순 발급의 hot-row 제거"
date: 2026-08-27
status: solid
tags: [concept, redis, concurrency, coupon, lua]
related: [../experiments/E6-flash-sale-consistency.md, ../journal/2026-08-26-coupon-domain.md]
---

## 문제
선착순 발급의 경합점은 **쿠폰 1행의 재고 컬럼**이다. DB 락(비관/낙관)은 이 한 행(hot row)에 몰려 직렬화·재시도를 유발한다. 처리량 상한 ≈ 1/(락 보유 시간) 또는 재시도 성공률에 묶인다.

## 아이디어
재고 판정을 **DB 밖(Redis)에서 원자적으로** 끝내고, DB는 발급 이력(`coupon_issue`) INSERT만 한다 → **coupon hot-row UPDATE가 사라진다**.

- Redis는 단일 스레드 이벤트 루프라 **명령 하나는 원자적**. 여러 명령을 원자 단위로 묶으려면 **Lua 스크립트**(실행 중 다른 명령 끼어들지 못함).
- 이 프로젝트 `tryIssue` Lua = `SISMEMBER 발급자셋`(1인1매) + `GET/DECR 재고`(품절) + `SADD 발급자`를 **한 번에** 판정.
  - 발급자셋에 이미 있음 → `ALREADY_ISSUED`
  - 재고 0 → `SOLD_OUT`
  - 아니면 `DECR` + `SADD` → `ISSUED`

## 왜 빠른가 (E6 실측: redis 549 RPS 최고, over 0)
- 재고 경합이 인메모리 Redis 원자 연산으로 끝나 **DB 행 락이 없다**.
- 정상 경로 DB 작업 = `coupon_issue` INSERT 1건뿐(서로 다른 행이라 경합 없음).

## 정합성: 진실은 어디에?
- 이 전략은 **DB `remaining_quantity`를 갱신하지 않는다** → DB remaining은 **stale**.
- 따라서 "몇 개 나갔나"의 진실 = **`count(coupon_issue)`** (또는 Redis 재고키). E6 `verify-coupon.sh`가 count 기준인 이유.
- 초과 발급 0 보장 = Redis `DECR`이 음수 못 가게 막고(Lua서 GET 후 판정), 최종 발급 수 = `total − 잔여재고` = `count(coupon_issue)`.

## 두 저장소의 진짜 난이도 = 복구 경로
행복 경로는 쉽다. Redis는 **휘발성**이라 키가 유실될 수 있고, 그때가 어렵다.
- **키 유실**: 재고키 없으면 lazy-init — DB 진실(`total − count(coupon_issue)`, **발급자 셋 포함**)로 재구축. 기준을 `remaining_quantity`로 잡으면 안 됨(stale).
- **복구 구간 이중 계상**: 재구축 시점의 in-flight(미커밋) 발급을 놓쳐 재고를 과대 계산 → 초과 발급 위험. 그래서 복구 직후 일정 시간은 **DB 백스톱**(`pg_advisory_xact_lock` + `count < total` 재확인)으로 성공 경로만 직렬화해 막는다.
- **보상**: DB INSERT 실패 시 Redis 재고를 되돌려야 하는데, 되돌리기(`INCR`)도 Lua로 원자화(키 있을 때만 — 없는 키에 `INCR`하면 가짜 키 생성). `unique`(중복)와 `FK`(쿠폰 없음) 위반은 같은 `DataIntegrityViolationException`으로 도착하므로 구분 필요.

## 알려진 한계 (E8-5에서 해소)
**Lua 성공 ~ DB INSERT 커밋 사이에 프로세스가 죽으면**(OOM-kill 등) 사용자가 Redis 발급자셋에만 남고 DB엔 없다 — catch 기반 보상으로는 못 잡는다. **outbox/조정 배치**가 필요(트랜잭셔널 아웃박스). "Redis 먼저, DB 나중" 설계의 본질적 창(window).

## 트레이드오프 요약
| | 처리량 | 정합성 난이도 |
|---|---|---|
| DB 락 | 중 (hot-row 직렬화) | 낮음 (단일 저장소, 트랜잭션이 다 해줌) |
| Redis 원자 | **최고** | **높음** (2 저장소 = 불일치 복구 경로가 난이도의 전부) |

## 면접 한 줄
"재고 판정을 Redis Lua로 원자화해 DB hot-row 락을 없애 처리량을 올렸고, 대신 DB remaining이 stale해지므로 정합성은 `count(coupon_issue)`로 본다. 진짜 어려움은 행복 경로가 아니라 키 유실·in-flight 이중계상·보상 실패 같은 불일치 복구 경로이고, Lua 성공~DB 커밋 사이 크래시 창은 outbox로 메운다."
