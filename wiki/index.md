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
- **단계**: 2주차 A 트랙 목표 완료(E2·E3·E5). 2인 협업([D-005](decisions/D-005-two-person-track-split.md)): A(kimdoogi)=런타임·자원, B(popogustn)=도메인·장애
- **다음 작업**: A → P-003 탐색 또는 E10(GC) · B → 쿠폰 도메인 구현 (E4는 B 완성 후)
- **진행 중 journal**: 없음
- **열린 문제(open)**: [P-003](problems/P-003-pinned-ceiling-not-scaling.md)
- **다음 번호**: A(홀수) → P-007 · D-007 · B(짝수) → P-002 · D-006

## 마스터 문서
- [PLAN.md](../PLAN.md) — 실험 E1~E13, 뼈대 구조, 로컬↔클라우드 매핑, 로드맵

## Journal (작업 기록, 최신순)
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
- [P-001 로컬 JDK 21 소실로 빌드 실패](problems/P-001-jdk21-missing-build-fail.md) — solved (foojay resolver)
- [P-003 pinned 천장이 CPU 수와 무관하게 ~37.5rps](problems/P-003-pinned-ceiling-not-scaling.md) — **open**
- [P-005 lock 런 pinned-count가 sync 런 잔재로 오염](problems/P-005-pinned-count-carryover.md) — solved (0건 정정, 컨테이너 로그 timestamp 검증)

## Decisions (ADR)
- [D-001 도메인: 선착순 쿠폰 발급 서비스](decisions/D-001-domain-flash-sale-coupon.md) — accepted
- [D-002 Spring MVC + Virtual Thread (WebFlux 대신)](decisions/D-002-spring-mvc-virtual-threads-over-webflux.md) — accepted
- [D-003 LLM-wiki 방식의 작업·기록 루프](decisions/D-003-llm-wiki-workflow.md) — accepted
- [D-004 Spring Boot 4.0.x 채택](decisions/D-004-spring-boot-4.md) — accepted
- [D-005 2인 협업 — 실험 트랙 분할 (A=kimdoogi, B=popogustn)](decisions/D-005-two-person-track-split.md) — accepted

## Experiments (실험)
- [E5 Pinning 재현 — synchronized vs ReentrantLock](experiments/E5-pinning.md) — done (37.5 vs 1,541~1,994rps, 41~53배. P-003 파생)
- [E3 CPU bound — 플랫폼 vs 버츄얼 + 스레드 수 컨트롤](experiments/E3-cpu-bound-vt-vs-platform.md) — done (가설 기각: 변수는 스레드 수. M 피크 494/761/799)
- [E2 I/O bound — 플랫폼 vs 버츄얼 (S/M/L)](experiments/E2-io-bound-vt-vs-platform.md) — done (천장 666 실측, VT 2,000rps, S-on 붕괴)
- [E1 계측 검증 & 베이스라인](experiments/E1-baseline.md) — done (M/VT: ping 피크 6,000rps, CPU 병목)
- 예정: E2 I/O bound VT on/off → E3 CPU bound → E5 pinning → … (PLAN.md §4.3)

## Concepts (학습 개념)
- [쓰레드 풀 천장과 백프레셔](concepts/thread-ceiling-and-backpressure.md) — solid (E2 기반, 면접 답변 포함)
- 예정: virtual-thread-basics, virtual-thread-observability, pinning, hikari-pool-sizing, k6-open-vs-closed-model, coordinated-omission, circuit-breaker-bulkhead, idempotency, redis-atomic-stock

## Howto (런북)
- [wiki-workflow.md](howto/wiki-workflow.md) — 위키 사용법
- [git.md](howto/git.md) — 원격/커밋 정책
- [run-experiment.md](howto/run-experiment.md) — 실험 실행/결과 보기/조작 명령
- (예정) grafana.md, k6.md
