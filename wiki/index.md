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
- **단계**: 2차 리뷰 완료(신규 15건 확정, 수정 대기). 다음: 2차 결함 수정 → 2주차 E2/E3/E5
- **진행 중 journal**: 없음
- **열린 문제(open)**: 없음
- **다음 번호**: P-001 · D-005

## 마스터 문서
- [PLAN.md](../PLAN.md) — 실험 E1~E13, 뼈대 구조, 로컬↔클라우드 매핑, 로드맵

## Journal (작업 기록, 최신순)
- [2026-08-20 수정 커밋 2차 코드 리뷰](journal/2026-08-20-code-review-round2.md) — done (수정 대기)
- [2026-08-20 리뷰 결함 15건 수정](journal/2026-08-20-review-fixes.md) — done
- [2026-08-20 1주차 뼈대 코드 리뷰](journal/2026-08-20-code-review-skeleton.md) — done (수정은 별도 작업)
- [2026-08-19 1주차 뼈대 생성](journal/2026-08-19-skeleton.md) — done
- [2026-08-19 계획 수립 & 위키 체계 구축](journal/2026-08-19-plan-and-wiki-setup.md) — done

## Problems (문제 → 해결)
- (아직 없음)

## Decisions (ADR)
- [D-001 도메인: 선착순 쿠폰 발급 서비스](decisions/D-001-domain-flash-sale-coupon.md) — accepted
- [D-002 Spring MVC + Virtual Thread (WebFlux 대신)](decisions/D-002-spring-mvc-virtual-threads-over-webflux.md) — accepted
- [D-003 LLM-wiki 방식의 작업·기록 루프](decisions/D-003-llm-wiki-workflow.md) — accepted
- [D-004 Spring Boot 4.0.x 채택](decisions/D-004-spring-boot-4.md) — accepted

## Experiments (실험)
- [E1 계측 검증 & 베이스라인](experiments/E1-baseline.md) — done (M/VT: ping 피크 6,000rps, CPU 병목)
- 예정: E2 I/O bound VT on/off → E3 CPU bound → E5 pinning → … (PLAN.md §4.3)

## Concepts (학습 개념)
- (아직 없음) — 예정: virtual-thread-basics, virtual-thread-observability, pinning, hikari-pool-sizing, k6-open-vs-closed-model, coordinated-omission, circuit-breaker-bulkhead, idempotency, redis-atomic-stock

## Howto (런북)
- [wiki-workflow.md](howto/wiki-workflow.md) — 위키 사용법
- [git.md](howto/git.md) — 원격/커밋 정책
- [run-experiment.md](howto/run-experiment.md) — 실험 실행/결과 보기/조작 명령
- (예정) grafana.md, k6.md
