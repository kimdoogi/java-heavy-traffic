---
title: "2026-08-26 쿠폰 도메인 — 선착순 발급 4전략 구현"
date: 2026-08-26
status: done
tags: [journal, coupon, e6-prep]
related: [../../PLAN.md, ../decisions/D-001-domain-flash-sale-coupon.md, ../decisions/D-005-two-person-track-split.md]
---

## 목표
- 선착순 쿠폰 발급 도메인 구현: 엔티티(Coupon, CouponIssue) + API + 발급 전략 4종(none / db-pessimistic / db-optimistic / redis, PLAN §1.3)
- Testcontainers 동시성 테스트로 "none은 초과 발급, 나머지는 0건" 을 코드 레벨에서 먼저 확인 (E6 실험의 사전 조건)

## 범위 / 하지 않는 것
- 하지 않음: IdempotencyService(E7), Resilience4j 방어(E8), outbox(E8-5), k6 시나리오 50-flash-sale.js·verify-coupon.sh (다음 작업)
- **D-005 주의**: `coupon-api/…/coupon` 패키지는 원래 B(popogustn) 소유 경로. kimdoogi가 직접 구현하기로 함(본인 결정) — popogustn에게 공유 필요. 트랙 재조정이 확정되면 D-005 갱신할 것.

## API 계약 (경로는 PLAN §1.2 그대로, 본문·상태코드는 이번에 확정)
- `POST /api/coupons` `{name, totalQuantity}` → 201 쿠폰 생성 (실험 세팅용)
- `GET /api/coupons/{id}` → 200 `{id, name, totalQuantity, remainingQuantity, createdAt}` / 404
  - 주의: redis 전략에서는 hot-row UPDATE를 하지 않으므로 DB의 `remainingQuantity`는 갱신되지 않음(아래 설계 참고). 정합성 검증은 `count(coupon_issue)` 기준.
- `POST /api/coupons/{id}/issue` `{userId}` →
  - 201 `{result: "issued", couponId, userId, strategy}`
  - 409 `{error: "sold_out" | "already_issued", strategy}`
  - 503 `{error: "retry_exhausted", strategy}` (db-optimistic 재시도 소진)
  - 404 `{error: "coupon_not_found"}`
- `POST /api/coupons/{id}/issue-and-notify` `{userId}` → 발급 성공 시 mock-external `/notify` 동기 호출, 201 본문에 `notify` 포함. 알림 실패는 기존 GlobalExceptionHandler대로 504/502 — **발급은 이미 커밋된 상태**(트랜잭션 밖 호출, PLAN §4.6(3)의 "좋은 버전"). 이 불일치가 E7 멱등성·E8-5 outbox의 동기.
- `GET /api/users/{userId}/coupon-issues` → 200 발급 내역 목록

## 전략 설계 요점
- 전략 빈 4개 상시 등록, `coupon.issue.strategy` 값으로 기동 시 1개 선택(없는 값이면 기동 실패). 응답에 `strategy`를 실어 실효 설정을 k6에서 확인 가능하게.
- `none`: JdbcClient로 read→check→insert→**계산값 UPDATE** (JPA @Version을 우회해야 진짜 무방비가 됨). 초과 발급 재현용.
- `db-pessimistic`: `SELECT … FOR UPDATE` 후 중복검사·차감. 행 락으로 직렬화.
- `db-optimistic`: @Version + TransactionTemplate 재시도(기본 3회, `OPTIMISTIC_MAX_RETRIES`). 재시도 횟수는 Micrometer `coupon_issue_retry_total`로 계측(E6 비교 지표).
- `redis`: Lua 하나로 SISMEMBER(1인1매)+GET/DECR(재고)+SADD 원자 처리 → DB는 `coupon_issue` INSERT만(hot row 제거가 처리량 포인트). 키 없으면 `total - count(coupon_issue)`로 lazy 초기화(SETNX). DB INSERT 실패 시 보상(INCR, 중복이면 set 유지·그 외 SREM).
  - 알려진 한계(학습 포인트): Redis 유실 시 issued set 재구축 없이 unique 제약+보상으로만 방어. 실험 간에는 reset-db로 리셋 전제.

## 진행 기록 (시간순)
- 시작 — wiki/PLAN/기존 코드 파악. 50-flash-sale.js·verify-coupon.sh는 아직 없음 → 계약을 이 journal에 확정하고 구현 진행.
- 구현 — domain(Coupon·CouponIssue) / infra(JPA 리포지토리 2, RedisCouponStockRepository Lua) / strategy(4종) / application(CouponIssueService·CouponService) / api(CouponController). 스키마는 V1__init.sql 그대로(ddl-auto=validate 통과). 공유 경로(common/·config/·build.gradle)는 건드리지 않음.
- 테스트 — `TestcontainersConfiguration`을 public으로 변경(하위 패키지 테스트에서 @Import 하기 위해).
- 예상 밖 동작: 계약 테스트의 "알림 실패 504" 케이스가 **201로 성공**. 원인: 호스트에 compose 스택이 떠 있어 mock-external이 localhost:8081을 리슨 중 → 테스트가 진짜 알림을 보내버림. 조치: 테스트 클래스에서 `external.base-url=http://localhost:1` 고정(항상 connection refused). 교훈: 테스트의 localhost 기본값은 떠 있는 compose 스택과 간섭할 수 있다 — DB/Redis는 @ServiceConnection이 격리해 주지만 그 외 의존성은 직접 고정해야 함.
- `./gradlew :coupon-api:test` — **10 tests, all passed** (Testcontainers postgres+redis).
- PR 생성 중 `gh` CLI 소실 발견(JDK·k6에 이은 세 번째) → brew 재설치 + keychain git 자격증명으로 인증해 PR #3 생성 → [P-001](../problems/P-001-jdk21-missing-build-fail.md)에 추가 기록.

## 결과
- 구현 파일 14개 (coupon-api `coupon/` 패키지) + 테스트 2클래스 10케이스.
- 동시성 실측 (VT executor, latch 동시 출발):

| 전략 | 조건 | 결과 |
|---|---|---|
| none | 재고 50, 200명 동시 | **200명 전원 발급 — 초과 발급 150건(4배) 재현** |
| db-pessimistic | 재고 100, 300명 동시 | 정확히 100 발급, remaining 0, SOLD_OUT 200 |
| db-optimistic | 재고 100, 300명 동시, 재시도 3회 | **94 발급 / RETRY_EXHAUSTED 206** — 초과 발급 0, 불변식(발급수=100−remaining) 유지. 재시도 소진으로 재고 6개를 못 팖 |
| redis | 재고 100, 300명 동시 | 정확히 100 발급, redis stock 0, SOLD_OUT 200, DB remaining은 100 그대로(문서화된 특성) |

- db-optimistic의 "경합 심하면 재시도 폭증 → 실패율 상승"(PLAN E6 가설)이 코드 레벨에서 이미 관찰됨. k6 부하에서 처리량·p99·재시도 횟수(`coupon_issue_retry_total`) 비교는 E6에서.

## 배운 것 / 결정
- none 전략은 JPA로 구현하면 @Version 때문에 "무방비"가 안 됨 → JdbcClient로 우회 (재현용 코드는 의도적으로 프레임워크 보호를 벗겨야 한다).
- PG는 unique 위반 후 트랜잭션이 aborted → 같은 tx에서 후속 SQL 불가. none의 중복 처리(catch 후 즉시 반환)는 이 제약과 맞물려 있음.
- redis 전략의 처리량 포인트는 "DB hot-row UPDATE 제거"(INSERT만). 대신 DB remaining이 stale해짐 → 정합성 검증은 count(coupon_issue) 기준(verify-coupon.sh 설계에 반영할 것).
- redis lazy init의 기준값은 remaining_quantity가 아니라 `total − count(coupon_issue)` (redis 전략에선 remaining이 진실이 아니므로).

## 남은 일 / 다음 단계
- [ ] popogustn에게 트랙 조정 공유, 필요 시 D-005 갱신
- [ ] load/50-flash-sale.js + scripts/verify-coupon.sh, reset-db.sh (verify는 count 기준 — 위 "배운 것" 참고)
- [ ] E6 실행 (전략 4종 비교) — 이제 E4(`/coupons/{id}`)도 실행 가능해짐
- [ ] concepts/redis-atomic-stock.md — E6 수치 나온 뒤 작성
