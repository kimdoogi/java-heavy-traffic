---
title: "E6 선착순 정합성 — 발급 전략 4종 부하 비교 (none/pessimistic/optimistic/redis)"
date: 2026-08-27
status: done
tags: [experiment, E6, coupon, concurrency, consistency]
related: [../journal/2026-08-27-e6-flash-sale.md, ../concepts/redis-atomic-stock.md, ../decisions/D-005-two-person-track-split.md, ../../PLAN.md]
---

## 가설 (PLAN §4.3)
- 쿠폰 1,000개에 5,000명이 몰릴 때 `none`(락 없음)은 **초과 발급 발생**, `db-pessimistic`·`db-optimistic`·`redis`는 **초과 0건**.
- 처리량은 `redis` > DB 락 계열, `db-optimistic`은 경합 심하면 **재시도 폭증 → 실패율 상승**.

## 설정
| 항목 | 값 |
|---|---|
| 엔드포인트 | `POST /api/coupons/{id}/issue` (전략은 기동 시 `coupon.issue.strategy`로 선택, 응답 `strategy`로 실효 확인) |
| 쿠폰 | 총 1,000개 (`reset-db.sh`로 매 런 truncate + `RESTART IDENTITY` → id=1, redis flush) |
| 부하 | `load/50-flash-sale.js` — `shared-iterations` VUS=50 / ITERS=5,000, userId 전역 유니크(1인1매) |
| 프로파일 | M (1 cpu / 1 GB / Xmx 512m), VT on |
| **풀** | `POOL_SIZE=50` (= VUS, DB `max_connections=100` 이하). **E6의 고정 상수** — 풀을 병목에서 제외해 전략만 변수로 격리 |
| 검증 | `verify-coupon.sh` = `count(coupon_issue) <= total_quantity` (redis는 DB remaining이 stale하므로 count가 진실) |
| 실행 | `VUS=50 ITERS=5000 DURATION=5m scripts/run-experiment.sh -n E6-<s> --strategy <s> -p M -v on --pool 50 -s load/50-flash-sale.js` |

> **풀 격리 설계 (중요)**: 최초 pool=20 / VUS=1,000으로 돌리자 1,126건이 HikariCP 커넥션 타임아웃(500)으로 실패했다 — 이건 전략이 아니라 **풀 병목(E4 영역)**이라 4전략이 전부 풀-바운드로 수렴해 비교가 무의미해진다. `VUS <= POOL_SIZE`로 고정하니 4전략 모두 `unexpected(500)=0` → 풀이 비병목임이 실측으로 증명되고 전략이 유일 변수가 됨. (stampede 케이스는 아래 별도 기록.)

## 결과 (매 런 5,000 발급 / 재고 1,000, 중앙값 아님 1회 측정)
| 전략 | issued | sold_out | 503(retry 소진) | **초과발급** | RPS | p50 | p95 | p99 | app CPU 피크 | 정합성 |
|---|---|---|---|---|---|---|---|---|---|---|
| **none** | 5,000 | 0 | 0 | **+4,000** | 121.9 | 282ms | 1,300ms | 2,109ms | 92% | ❌ 붕괴 |
| **db-pessimistic** | 1,000 | 4,000 | 0 | 0 | 519.7 | 74ms | 313ms | 483ms | 100% | ✅ |
| **db-optimistic** | 1,000 | 383 | **3,617** | 0 | 182.1 | 206ms | 732ms | 1,318ms | 103% | ✅ (가용성↓) |
| **redis** | 1,000 | 4,000 | 0 | 0 | **548.6** | 75ms | 301ms | 488ms | 100% | ✅ |

- 정합성 검증: `verify-coupon.sh 1` — none `count=5000 over=4000`, 나머지 `count=1000 over=0`. redis 재고키 `coupon:1:stock=0` 확인.
- raw: `results/E6-{none,db-pessimistic,db-optimistic,redis}/` (summary.json · summary.md · meta.env · docker-stats.csv) · Grafana `testid=E6-*`

## 해석
1. **none은 "조금 초과"가 아니라 완전 붕괴**: 5,000명 **전원** 발급(초과 +4,000, 400%). 무락 + 절대값 `UPDATE remaining = 읽은값-1`이라 50-way lost update로 remaining 갱신이 대량 유실 → 재고 게이트(`remaining<=0`)가 사실상 무력화되어 SOLD_OUT이 한 번도 안 뜸. (SQL 상대감소 `r=r-1`였다면 `CHECK(remaining>=0)`가 음수를 막아 다른 방식으로 실패했을 것 — 무방비를 만들려 일부러 절대값을 씀.)
2. **처리량 redis(549) ≈ pessimistic(520) > optimistic(182) > none(122)**.
   - none 최저: sold_out 없이 5,000건 **전부 INSERT** + hot-row `UPDATE` 경합.
   - pessimistic이 redis에 근접: 이 규모(50 VU·1 cpu)선 행 락 **보유 시간이 짧아** 병목이 아니고, 4,000 sold_out은 락→체크→해제로 빠르다. redis의 hot-row 제거 우위는 **경합·재고 규모가 커질수록** 벌어질 것(후속 가설 — E9/스케일).
   - **전 전략 CPU 92~103%** → 이 규모의 병목은 DB 락이 아니라 **앱 CPU(1 cpu 포화)**.
3. **optimistic = 정합성은 지키나 가용성이 무너짐**: sold_out은 383뿐인데 `retry_exhausted(503)`가 **3,617(72%)**. 50-way 경합에서 `@Version` 충돌이 반복돼 대부분의 "졌어야 할" 요청이 깨끗한 409 sold_out 대신 3회 재시도를 소진하고 503으로 끝났다. 재시도 자체가 DB 부하를 가중(p99 1.3s). **낙관락은 경합이 낮을 때만 유리**하다는 교과서 명제의 실측.
4. p99: none 2.1s(자기 경합) > optimistic 1.3s(재시도) > redis 0.49s ≈ pessimistic 0.48s.

## Stampede 케이스 (별도 — 전략 아닌 풀/백프레셔 성격, E4 연결)
`pool=20 / VUS=1,000 / none`: issued 3,874, **초과 +2,874**, `unexpected(500)=1,126`(HikariCP `Connection is not available ... timeout 3000ms`), p99 4.4s, RPS 316. → 커넥션 풀이 바인딩 제약이 되면 모든 전략이 풀-바운드로 수렴. E6 비교의 baseline에서 제외하고, "비관락 하 커넥션 파일업" 관찰은 E4/백프레셔(E13)에서 다룬다. raw: `results/E6-none/`은 clean(pool=50) 런으로 덮어씀 — 이 수치는 본 문단이 유일 기록.

## 배운 점 (면접 대비)
- **무락의 실제 위험은 규모에 비례해 폭증**: 동시성이 높을수록 lost update가 심해져, 상황에 따라 재고 카운터가 통째로 무의미해진다. "가끔 몇 개 더 나가는" 문제가 아니다.
- **비관락**: 정합성 + 의외로 높은 처리량(짧은 락 홀드). 락 대기가 병목이 되려면 더 높은 경합/더 긴 트랜잭션이 필요.
- **낙관락**: 고경합 = 재시도 폭증 = 가용성 붕괴(72% 503). 재시도 예산·백오프 없이는 스스로 부하를 키운다.
- **redis 원자 차감**: hot-row 제거로 최고 처리량, 정합성은 `count(coupon_issue)` 기준(DB remaining은 stale) — [redis-atomic-stock](../concepts/redis-atomic-stock.md).
- **실험 설계**: 병목 변수(풀)를 상수로 격리하지 않으면 전략이 아니라 풀을 측정하게 된다. `unexpected(500)=0`이 "풀이 비병목"임을 보증하는 증거였다.

## 다음
- 더 높은 경합·재고에서 redis vs pessimistic 격차 확대 확인(E9 한계점과 연계).
- E7 멱등성(중복 userId·Idempotency-Key), E8 `issue-and-notify` 장애 전파.
