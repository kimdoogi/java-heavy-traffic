---
title: "2026-08-27 E7 — 멱등성(Idempotency-Key) 구현"
date: 2026-08-27
status: done
tags: [journal, coupon, e7]
related: [../../PLAN.md, ../decisions/D-006-idempotency-redis-claim.md, ../problems/P-002-jackson2-objectmapper-no-bean.md]
---

## 목표
- PLAN E7: 같은 userId 재시도 폭주(네트워크 재시도)에서 중복 발급 0건 + 응답 일관성 보장.
- `coupon/application/IdempotencyService` 구현 (PLAN §3.2 뼈대에 이름만 있던 것, 2026-08-26 journal에서 "하지 않음"으로 명시했던 부분).

## 범위 / 하지 않는 것
- `POST /api/coupons/{id}/issue`만 지원. `issue-and-notify`는 의도적으로 제외 — 이유는 [D-006](../decisions/D-006-idempotency-redis-claim.md).
- k6 시나리오(`load/55-idempotency-retry.js`)·`wiki/experiments/E7-idempotency.md` 실측 기록은 안 함 — `verify-coupon.sh`/`50-flash-sale.js`(E6 선행 인프라, A 담당)가 아직 없어 실행 인프라 자체가 안 갖춰짐. 이번 작업은 코드 레벨 구현+테스트까지.
- 실제 `docker compose up` 기반 E2E 확인은 안 함 — Testcontainers 기반 `CouponApiContractTest`가 같은 코드 경로(실제 Postgres+Redis, 임베디드 Tomcat, HTTP 레벨)를 이미 검증하므로 커버리지상 중복이라 판단. `docker-compose.yml`의 `IDEMPOTENCY_*_SECONDS` env passthrough 자체는 미검증(기존 `OPTIMISTIC_MAX_RETRIES`와 동일 패턴이라 위험 낮음으로 판단).

## 설계
- Redis `SET idempotency:{key} PROCESSING NX EX <lockTtl>`로 클레임. 성공한 요청만 실행하고 결과(`{status,body}` JSON)를 같은 키에 `EX <resultTtl>`로 캐시. 이후 같은 키는 캐시를 그대로 재생(비즈니스 로직 재실행 없음). 아직 처리 중인 동시 재시도는 409 `request_in_progress`.
- 액션이 예외를 던지면 클레임을 지우고 재시도를 허용 — 이 규칙이 안전한 건 `/issue`뿐이라 스코프를 거기로 한정. 상세 이유는 [D-006](../decisions/D-006-idempotency-redis-claim.md).
- Redis 장애는 예외를 삼키지 않고 그대로 전파 — `RedisConnectionFailureException`(`DataAccessException` 서브타입)이 기존 `CouponApiExceptionHandler.onDataAccess`로 503 `storage_unavailable`이 됨. 새 코드 없이 fail-closed.
- 헤더(`Idempotency-Key`)는 opt-in. 미전송 시 기존 동작과 100% 동일.

## 진행 기록 (시간순)
- PLAN.md·wiki·기존 코드(CouponController, RedisCouponStockRepository, CouponApiExceptionHandler, 테스트 3클래스) 파악 → EnterPlanMode로 플랜 작성.
- advisor 리뷰에서 `issue-and-notify`도 감싸면 "커밋 후 notify 실패 → 클레임 삭제 → 재시도가 재생 아닌 already_issued(409)"가 재발한다는 걸 지적받음 → 스코프를 `/issue`로 좁힘 (D-006에 기록). `CouponApiContractTest.알림_실패는_504지만_발급은_이미_커밋되어_있다`가 이 비대칭을 이미 실증하고 있었다.
- `contract:` 커밋으로 PLAN §1.2.1 먼저 갱신(코드보다 먼저, CLAUDE.md 규칙) → 커밋 `8cf6cc5`.
- `IdempotencyService`(+ `IdempotencyInProgressException`), `CouponApiExceptionHandler` 매핑, `CouponController.issue()` 배선, `application.yml`/`docker-compose.yml` TTL 설정 구현.
- `IdempotencyServiceTest`(순차 재생·독립 키·TTL 만료 후 재실행·동시 클레임) + `CouponApiContractTest`에 3케이스(10회 재시도 바이트 동일성·독립 키·헤더 미전송 회귀) 추가.
- → [P-002](../problems/P-002-jackson2-objectmapper-no-bean.md): `IdempotencyService`에 `com.fasterxml.jackson.databind.ObjectMapper`(Jackson 2)를 생성자 주입했더니 전체 컨텍스트 로딩 실패로 20개 테스트 전부 연쇄 실패. 원인은 Spring Boot 4/Spring 7이 Jackson 3을 기본 빈으로 등록해서였음(`./gradlew :coupon-api:dependencies`로 확인). `new ObjectMapper()`로 직접 소유하는 걸로 해결.
- `./gradlew :coupon-api:test` — **20 tests, 0 failures** (기존 13 + 신규 IdempotencyServiceTest 4 + CouponApiContractTest 신규 3).

## 결과
- 신규: `coupon/application/IdempotencyService.java`, `IdempotencyInProgressException.java`, `IdempotencyServiceTest.java`.
- 수정: `CouponController.java`(issue()에 `Idempotency-Key` 헤더), `CouponApiExceptionHandler.java`(409 request_in_progress), `application.yml`/`docker-compose.yml`(TTL 설정), `PLAN.md`(§1.2.1 계약), `CouponApiContractTest.java`(케이스 3개 추가).
- 신규 wiki: [P-002](../problems/P-002-jackson2-objectmapper-no-bean.md), [D-006](../decisions/D-006-idempotency-redis-claim.md).
- 테스트: 20/20 통과. 핵심 시나리오 — 동시 20개 같은 키 요청 중 액션 실행은 정확히 1회, 나머지는 재생 또는 request_in_progress로만 분기(중간 상태 없음).

## 배운 것 / 결정
- Idempotency-Key와 비즈니스 레벨 중복 방지(unique 제약 + ALREADY_ISSUED)는 서로 다른 문제를 푼다 — 전자는 "같은 시도의 재시도 응답 일관성", 후자는 "다른 시도인데 이미 발급받음". 둘 다 필요하고 서로 대체 불가.
- "액션 실패 시 클레임 삭제 후 재시도 허용"은 액션이 원자적일 때만 안전한 규칙 — 커밋 후 부수효과가 있는 액션(issue-and-notify)에 그대로 적용하면 오히려 응답 일관성을 깬다. → D-006.
- Spring Boot 4/Spring 7 + Jackson 2/3 공존 함정 → P-002. A 트랙 코드에도 영향 가능성 있어 공유 필요.

## 남은 일 / 다음 단계
- [ ] k6 `load/55-idempotency-retry.js` + `wiki/experiments/E7-idempotency.md` — 2026-08-31 origin/main 병합으로 `verify-coupon.sh`·`50-flash-sale.js`·`reset-db.sh`가 이제 존재(E6, B 본인 작업). 더 이상 blocked 아님 — 다음 세션에서 착수 가능.
- [ ] kimdoogi에게 P-002(Jackson 2/3 공존 함정) 공유.
- [ ] E8-5(outbox) 구현 시 `issue-and-notify`에도 Idempotency-Key 적용 재검토 (D-006 재검토 조건).
- [ ] D-005 트랙 재조정 최종 확정(A와) — E6 journal에서도 동일하게 open.
