---
title: "2026-08-27 E6 — 선착순 정합성 실험 (4전략 부하 비교)"
date: 2026-08-27
status: done
tags: [journal, e6, coupon, load-test, k6]
related: [../../PLAN.md, ../experiments/E6-flash-sale-consistency.md, ./2026-08-26-coupon-domain.md, ../decisions/D-005-two-person-track-split.md]
---

## 목표
쿠폰 도메인(A가 PR#3로 구현)의 발급 전략 4종을 실제 부하로 돌려 **선착순 정합성 + 성능을 수치로 비교**(PLAN §4.3 E6).
- 정합성: none=초과발급 발생 / db-pessimistic·db-optimistic·redis = `count(coupon_issue) ≤ total`
- 성능: RPS·p50/95/99·응답분포(issued/sold_out/retry_exhausted)·재시도(`coupon_issue_retry_total`)·DB/앱 CPU

## 범위
- **한다**: k6 부하 시나리오(`load/50-flash-sale.js`) · 검증/리셋 스크립트(`scripts/verify-coupon.sh`·`reset-db.sh`) · 전략 4종 실행 · 결과 표·해석.
- **안 한다**: 전략 코드(이미 A가 구현·테스트 완료) · 멱등성(E7) · 외부장애(E8) · 한계점(E9).

## 협업 메모 (D-005)
- 쿠폰 도메인은 원래 B(popogustn) 소유였으나 A(kimdoogi)가 PR#3로 구현. E6 실험은 B가 이어받음.
- `scripts/`는 A 소유 경로 — verify/reset 스크립트 신설은 A journal "남은 일"에 이미 예상된 항목. PR에서 A 리뷰로 합의.
- D-005 갱신(트랙 재조정: A=도메인·B=실험) 필요 — PR 때 반영.

## 설계 요점
- **전략 전환 = 앱 재기동**: `run-experiment.sh --strategy <s>`가 `ISSUE_STRATEGY` env 주입 + compose 재적용 + 실효검증.
- **409 sold_out이 정상**(5,000중 4,000): `http.setResponseCallback(expectedStatuses(201,409,503))`로 내장 실패지표를 정직화하고, 결과별 커스텀 Counter로 분리 집계.
- **정합성 검증은 count 기준**: redis는 DB `remaining`이 stale → `count(coupon_issue)`가 진실(A journal). 4전략 공통으로 count로 본다.
- **무효 런 방지**: `iterations count>=ITERS` threshold — maxDuration에 잘려 덜 돌면 실패 처리(특히 db-pessimistic은 전 요청이 행 락에 직렬화).
- **결정적 id**: `reset-db.sh`가 RESTART IDENTITY → 첫 쿠폰 id=1 → `verify-coupon.sh` 기본 id=1.

## 진행 (시간순)
- Step 1: `load/50-flash-sale.js`(shared-iterations 버스트) + `scripts/verify-coupon.sh`·`reset-db.sh` 작성. 스모크 통과. (k6 `check`는 `'k6'`에서 import — 초기 `'k6/check'` 오류 수정.)
- 스테일 컨테이너 발견: 실행 중 이미지가 08-24 빌드(쿠폰 도메인 머지 08-27 이전) → `POST /api/coupons` 404. `run-experiment.sh`가 현재 main 코드로 재빌드해 해결.
- Step 2~3: 4전략 실행. **초기 pool=20/VUS=1,000은 HikariCP 커넥션 타임아웃 1,126건**(풀 병목=E4 성격)으로 비교 오염 → advisor 조언대로 **`POOL_SIZE=50=VUS` 고정**(풀 비병목, 전 전략 `unexpected(500)=0`)으로 4전략 재실행.
- Step 4: 실험 리포트 [E6](../experiments/E6-flash-sale-consistency.md) + concept [redis-atomic-stock](../concepts/redis-atomic-stock.md) 작성.
- 크로스오버 + 수직 L(추가): 1cpu선 pessimistic≈redis 동률(CPU-bound). **2cpu(L)선 갈라짐 — redis 스케일(VUS2000 3,007rps) vs pessimistic 정체(~1,300)+커넥션타임아웃 614건**(DB hot-row 락+풀 천장). **결론 = 스케일 의존**(단일 소형→pessimistic / 2cpu+·scale-out→redis). → [E6 크로스오버](../experiments/E6-crossover-concurrency.md).

## 결과 요약 (pool=50/VUS=50/M, 5,000 발급 / 재고 1,000)
| 전략 | 초과발급 | RPS | p99 | 특이 |
|---|---|---|---|---|
| none | **+4,000** | 122 | 2,109ms | 무락 붕괴 — 전원 발급 |
| db-pessimistic | 0 | 520 | 483ms | 정합성 + 의외로 높은 처리량 |
| db-optimistic | 0 | 182 | 1,318ms | 503(retry 소진) 3,617 = 72% (재시도 폭증) |
| redis | 0 | 549 | 488ms | 최고 처리량 |

## redis 제품화 (E6 결론 반영, 별도 작업)
E6로 "스케일=redis" 확정 → 선택한 redis 경로를 제품 수준으로 완성(전략 코드는 A가 구현한 것 유지, 스위치도 유지 — 데모/비교용).
- **잔여수량 정확화**: `GET /coupons/{id}` = `total − count(coupon_issue)`. redis는 DB remaining stale이므로 발급 원장 기준(`CouponService.issuedCount` + `CouponController.withIssued`). 4전략 공통 진실.
- **크래시 갭 조정(reconciliation)**: Lua성공~DB커밋 사이 크래시로 redis-only 발급이 남는 창 → 조정이 redis 발급자 set(`SMEMBERS`)과 DB `coupon_issue`를 대조해 누락분을 DB로 전진 복구(멱등). `CouponReconciliationService`, **기동 시 1회(`ApplicationReadyEvent`)** 실행.
  - 처음엔 `@Scheduled`(30s)로 짰으나 **리뷰에서 초과발급 버그 지적**: 주기 조정이 **살아있는 in-flight 발급**(redis성공~DB커밋 사이)과 겹치면 그 발급을 orphan으로 오인해 DB에 먼저 INSERT → 원 요청은 unique 위반 → 보상이 재고 INCR → **유령 재고 → 초과 발급**(크래시 무관, 타이밍만 겹치면 발생). 기동 직후엔 in-flight이 없어 경합 원천 차단 → `ApplicationReadyEvent` 1회로 변경, `CouponSchedulingConfig` 제거. 멀티노드는 유예 시간 기반 주기 조정 필요(E12).
  - 실검증: orphan 주입(redis만 발급) → 앱 재시작 → 기동 조정이 자동 복구(로그 "기동 조정 완료 — 1건 복구"), 13 tests 통과.
- **README** 데이터 기반 갱신(전략 비교·스케일 결론·제품화). 데모 사이트는 만들었다 제거(불필요).
- 코드 전부 uncommitted — 사용자 리뷰 후 커밋 예정.

## 남은 일
- [x] Step 5: 커밋·PR (A 리뷰) — PR #4 `kimdoogi/e6-firstcome-consistency` 머지 완료(`09c2bf9`).
- [ ] D-005 트랙 재조정 최종 확정(A와) — 본 journal에서 갱신 초안, PR에서 확정 아직 안 됨.
- [x] (후속) E7 멱등성 — [2026-08-27 E7 journal](2026-08-27-E7-idempotency.md), D-006.
- [ ] E8 `issue-and-notify` 장애 전파.

> 2026-08-31 갱신(popogustn, E7 작업 중 main 머지하며 발견): PR 머지·main 반영 확인. 상태 in-progress → done으로 정정(파일 자체는 병합 전 uncommitted 상태로 남아 있었음).
