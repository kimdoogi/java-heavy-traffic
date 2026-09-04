---
title: "D-006 Idempotency-Key: Redis SET NX 클레임 + /issue 전용 스코프"
date: 2026-08-27
status: accepted
tags: [decision, coupon, idempotency, e7]
related: [../journal/2026-08-27-E7-idempotency.md, ../experiments/E7-idempotency.md]
---

## 맥락
E7(PLAN §4.3): 같은 userId의 재시도 폭주(네트워크 재시도로 같은 요청이 여러 번 옴)에서 "중복 발급 0건, 응답 일관성"을 보장해야 한다. 기존 `ALREADY_ISSUED`(409)는 진짜 두 번째 요청은 막지만, **같은 논리적 시도의 재시도**엔 부적합하다 — 클라이언트가 최초 201을 못 받고 재시도하면 409를 받아 "내가 쿠폰을 받았는지" 알 수 없게 된다.

## 선택지
1. **Redis SET NX 클레임 + 응답 캐시** — `SET idempotency:{key} PROCESSING NX EX <lockTtl>`로 선점, 성공한 요청만 실행하고 결과를 같은 키에 캐시해 재생. 장점: 이미 같은 redis 컨테이너로 원자 연산 인프라가 있음(RedisCouponStockRepository), TTL 자동 정리, 스키마 변경 불필요. 단점: Redis 장애 시 멱등성 보장이 같이 죽음(→ fail-closed로 503 처리, 아래 결정).
2. **DB 테이블(`idempotency_key` unique 컬럼) + 응답 저장** — 장점: 저장소 신뢰성이 DB와 동일. 단점: Flyway 마이그레이션 필요, TTL 자동 정리가 없어 별도 배치/스케줄러가 필요(만료 정책을 직접 구현), 쓰기 경합이 hot row(쿠폰 재고)와 별개로 하나 더 생김.

## 결정
Redis SET NX 클레임(선택지 1)을 채택하고, **스코프를 `POST /issue`로만 한정**한다(`/issue-and-notify`는 지원하지 않음).

## 이유
- 인프라 재사용: redis 컨테이너·`StringRedisTemplate` 사용 패턴이 이미 확립돼 있어(RedisCouponStockRepository) 새 저장소를 안 늘려도 됨. TTL이 곧 만료 정책이라 별도 청소 로직 불필요.
- Redis 장애 시 `RedisConnectionFailureException`(`DataAccessException` 서브타입)이 기존 `CouponApiExceptionHandler.onDataAccess`로 자연스럽게 503 `storage_unavailable`이 됨 — 새 예외 처리 코드 없이 fail-closed 확보(멱등성 체크를 조용히 건너뛰고 중복 실행을 허용하는 fail-open보다 안전).
- **`/issue-and-notify` 제외**: "액션이 예외를 던지면 클레임을 지우고 재시도를 허용한다" 규칙은 액션이 원자적(전부 실행되거나 전부 안 되거나)일 때만 안전하다. `/issue`는 전략 내부에서 이미 보상/원자성을 보장하므로 안전하지만, `issue-and-notify`는 **DB 발급 커밋 후** 별도 비트랜잭션 호출(`notify`)이 실패할 수 있어 "커밋 완료 상태에서 예외"가 발생한다. 이때 클레임을 지우면 재시도가 재생이 아니라 `issue()`를 다시 태워 `ALREADY_ISSUED`(409)라는 **새 응답**을 만들어버려, 막으려던 "응답 일관성 붕괴"가 재발한다(`CouponApiContractTest.알림_실패는_504지만_발급은_이미_커밋되어_있다`가 이 비대칭을 이미 실증). 이 갭은 커밋-후-부수효과 구조 자체의 문제라 E8-5(outbox 비동기 분리)에서 해소하기로 하고, 이번 스코프에선 손대지 않는다.

## 결과 / 영향
- `/issue`: `Idempotency-Key` 헤더(선택) 지원, 재시도는 원본 응답 그대로 재생, 동시 재시도는 409 `request_in_progress`.
- `/issue-and-notify`: 변경 없음 — PLAN §1.2.1에 이미 기록된 504/502·already_issued 불일치가 그대로 남아 있고, 재검토 조건은 E8-5 구현 시점.
- 재검토 조건: E8-5(outbox)로 발급-알림을 비동기 분리하면 `issue-and-notify`도 "커밋 후 예외" 문제가 사라져 같은 IdempotencyService로 감쌀 수 있게 될 것 — 그때 이 결정을 다시 본다.
