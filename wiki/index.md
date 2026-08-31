---
title: "위키 인덱스"
date: 2026-08-19
status: live
tags: [index]
---

# java-heavy-traffic 위키

> 세션 시작 시 이 페이지 → [log.md](log.md) 최근 항목 → 진행 중 journal 순으로 읽는다.
> 워크플로우 규칙: [../CLAUDE.md](../CLAUDE.md) · 사용법: [howto/wiki-workflow.md](howto/wiki-workflow.md)

## 현재 상태
- **단계**: 2주차 A 트랙 완료(E2·E3·E5, P-003) + 3주차 쿠폰 도메인 구현 완료(4전략, 동시성 테스트 통과) + E6(선착순 정합성) + E7(멱등성) 코드 레벨 구현 완료. 2인 협업([D-005](decisions/D-005-two-person-track-split.md), 2026-08-27 갱신): 쿠폰 도메인은 A(kimdoogi)가 선구현(PR #3), E6은 B(popogustn)가 이어받아 실행(PR #4, `scripts/verify-coupon.sh`·`reset-db.sh`도 B 작성) — **현행 조정안**: A=도메인 구현+런타임·자원(E3·E4·E5·E10~E12), B=E6·E7·E8·E9·E13. D-005 갱신은 popogustn 단독 초안이라 A 최종 합의 필요
- **다음 작업**: A → E4(이제 실행 가능), E10(GC), D-005 갱신 확인 · B → E7 k6 실측(50-flash-sale.js·verify-coupon.sh 이제 존재 — 가능해짐), E8(외부 장애 방어 단계별)·E13(백프레셔)
- **진행 중 journal**: 없음
- **열린 문제(open)**: 없음
- **다음 번호**: A(홀수) → P-007 · D-007 · B(짝수) → P-004 · D-008

## 마스터 문서
- [PLAN.md](../PLAN.md) — 실험 E1~E13, 뼈대 구조, 로컬↔클라우드 매핑, 로드맵

## Journal (작업 기록, 최신순)
- [2026-08-27 E7 — 멱등성(Idempotency-Key) 구현](journal/2026-08-27-E7-idempotency.md) — done
- [2026-08-27 E6 — 선착순 정합성 실험 (4전략 부하 비교)](journal/2026-08-27-e6-flash-sale.md) — done (PR #4 머지 확인 후 상태 정정, D-005 트랙 재조정 A 확정만 남음)
- [2026-08-26 쿠폰 도메인 — 선착순 발급 4전략 구현](journal/2026-08-26-coupon-domain.md) — done
- [2026-08-25 P-003 — pinned 천장 원인 탐색](journal/2026-08-25-p003-pinned-ceiling.md) — done
- [2026-08-24 E5 — Pinning 재현](journal/2026-08-24-E5-pinning.md) — done
- [2026-08-24 main 보호 — PR + 상대 리뷰 필수화](journal/2026-08-24-pr-branch-protection.md) — done
- [2026-08-24 E3 — CPU bound 플랫폼 vs 버츄얼](journal/2026-08-24-E3-cpu-bound.md) — done
- [2026-08-24 2인 협업 트랙 확정 및 규칙 문서화](journal/2026-08-24-track-split.md) — done
- [2026-08-20 E2 — I/O bound VT vs 플랫폼](journal/2026-08-20-E2-io-bound.md) — done
- [2026-08-20 2차 리뷰 결함 15건 수정](journal/2026-08-20-review2-fixes.md) — done
- [2026-08-20 수정 커밋 2차 코드 리뷰](journal/2026-08-20-code-review-round2.md) — done (수정 대기)
- [2026-08-20 리뷰 결함 15건 수정](journal/2026-08-20-review-fixes.md) — done
- [2026-08-20 1주차 뼈대 코드 리뷰](journal/2026-08-20-code-review-skeleton.md) — done (수정은 별도 작업)
- [2026-08-19 1주차 뼈대 생성](journal/2026-08-19-skeleton.md) — done
- [2026-08-19 계획 수립 & 위키 체계 구축](journal/2026-08-19-plan-and-wiki-setup.md) — done

## Problems (문제 → 해결)
- [P-002 Spring Boot 4/Spring 7에서 Jackson 2 ObjectMapper DI가 NoSuchBeanDefinitionException](problems/P-002-jackson2-objectmapper-no-bean.md) — solved (Jackson 3이 기본 빈, 직접 `new ObjectMapper()`로 회피)
- [P-001 로컬 JDK 21 소실로 빌드 실패](problems/P-001-jdk21-missing-build-fail.md) — solved (foojay resolver)
- [P-003 pinned 천장이 CPU 수와 무관하게 ~37.5rps](problems/P-003-pinned-ceiling-not-scaling.md) — solved (실효 캐리어 수≠CPU 수: M=1+보상1, L=2+0의 우연. parallelism·maxPoolSize 조작으로 검증)
- [P-005 lock 런 pinned-count가 sync 런 잔재로 오염](problems/P-005-pinned-count-carryover.md) — solved (0건 정정, 컨테이너 로그 timestamp 검증)

## Decisions (ADR)
- [D-001 도메인: 선착순 쿠폰 발급 서비스](decisions/D-001-domain-flash-sale-coupon.md) — accepted
- [D-002 Spring MVC + Virtual Thread (WebFlux 대신)](decisions/D-002-spring-mvc-virtual-threads-over-webflux.md) — accepted
- [D-003 LLM-wiki 방식의 작업·기록 루프](decisions/D-003-llm-wiki-workflow.md) — accepted
- [D-004 Spring Boot 4.0.x 채택](decisions/D-004-spring-boot-4.md) — accepted
- [D-005 2인 협업 — 실험 트랙 분할 (A=kimdoogi, B=popogustn)](decisions/D-005-two-person-track-split.md) — accepted
- [D-006 Idempotency-Key: Redis SET NX 클레임 + /issue 전용 스코프](decisions/D-006-idempotency-redis-claim.md) — accepted

## Experiments (실험)
- [E6 선착순 정합성 — 4전략 부하 비교](experiments/E6-flash-sale-consistency.md) — done (pool=50 격리: none 초과 +4,000 붕괴 / pessimistic·redis 정합성0, 520·549rps / optimistic 재시도폭증 503 72%)
- [E6 크로스오버 — 동시성·수직 pessimistic vs redis](experiments/E6-crossover-concurrency.md) — done (1cpu 동률=CPU-bound / 2cpu선 redis 2.3배·pessimistic 500. 결론=스케일 의존: 소형→pessimistic, 스케일→redis)
- [E5 Pinning 재현 — synchronized vs ReentrantLock](experiments/E5-pinning.md) — done (37.5 vs 1,541~1,994rps, 41~53배. P-003 파생)
- [E3 CPU bound — 플랫폼 vs 버츄얼 + 스레드 수 컨트롤](experiments/E3-cpu-bound-vt-vs-platform.md) — done (가설 기각: 변수는 스레드 수. M 피크 494/761/799)
- [E2 I/O bound — 플랫폼 vs 버츄얼 (S/M/L)](experiments/E2-io-bound-vt-vs-platform.md) — done (천장 666 실측, VT 2,000rps, S-on 붕괴)
- [E1 계측 검증 & 베이스라인](experiments/E1-baseline.md) — done (M/VT: ping 피크 6,000rps, CPU 병목)
- 예정: E2 I/O bound VT on/off → E3 CPU bound → E5 pinning → … (PLAN.md §4.3)

## Concepts (학습 개념)
- [쓰레드 풀 천장과 백프레셔](concepts/thread-ceiling-and-backpressure.md) — solid (E2 기반, 면접 답변 포함)
- [VT 캐리어 풀과 pinning 천장](concepts/vt-carrier-pool-and-pinning.md) — solid (E5·P-003 기반, 면접 답변 포함)
- [Redis 원자 재고 차감 — hot-row 제거](concepts/redis-atomic-stock.md) — solid (E6 기반, 면접 답변 포함)
- 예정: virtual-thread-basics, virtual-thread-observability, hikari-pool-sizing, k6-open-vs-closed-model, coordinated-omission, circuit-breaker-bulkhead, idempotency

## Howto (런북)
- [wiki-workflow.md](howto/wiki-workflow.md) — 위키 사용법
- [git.md](howto/git.md) — 원격/커밋 정책
- [run-experiment.md](howto/run-experiment.md) — 실험 실행/결과 보기/조작 명령
- (예정) grafana.md, k6.md
