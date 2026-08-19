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
- **단계**: 계획 수립 완료, 1주차(뼈대 생성) 시작 전
- **진행 중 journal**: 없음
- **열린 문제(open)**: 없음
- **다음 번호**: P-001 · D-004

## 마스터 문서
- [PLAN.md](../PLAN.md) — 실험 E1~E13, 뼈대 구조, 로컬↔클라우드 매핑, 로드맵

## Journal (작업 기록, 최신순)
- [2026-08-19 계획 수립 & 위키 체계 구축](journal/2026-08-19-plan-and-wiki-setup.md) — done

## Problems (문제 → 해결)
- (아직 없음)

## Decisions (ADR)
- [D-001 도메인: 선착순 쿠폰 발급 서비스](decisions/D-001-domain-flash-sale-coupon.md) — accepted
- [D-002 Spring MVC + Virtual Thread (WebFlux 대신)](decisions/D-002-spring-mvc-virtual-threads-over-webflux.md) — accepted
- [D-003 LLM-wiki 방식의 작업·기록 루프](decisions/D-003-llm-wiki-workflow.md) — accepted

## Experiments (실험)
- (계획은 PLAN.md §4.3, 수행 후 여기에 등록) — 예정: E1 계측 검증 → E2 I/O bound VT 비교 → …

## Concepts (학습 개념)
- (아직 없음) — 예정: virtual-thread-basics, pinning, hikari-pool-sizing, k6-open-vs-closed-model, coordinated-omission, circuit-breaker-bulkhead, idempotency, redis-atomic-stock

## Howto (런북)
- [wiki-workflow.md](howto/wiki-workflow.md) — 위키 사용법
- (예정) run-experiment.md, grafana.md, k6.md
