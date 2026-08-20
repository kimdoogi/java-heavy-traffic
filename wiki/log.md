---
title: "작업 로그 (append-only)"
date: 2026-08-19
status: live
tags: [log]
---

# 작업 로그
형식: `- YYYY-MM-DD | 작업 | 결과 한 줄 | 링크`

- 2026-08-19 | 프로젝트 주제 선정 | 선착순 쿠폰 발급 서비스로 결정 (I/O bound + 쓰기 경합) | [D-001](decisions/D-001-domain-flash-sale-coupon.md)
- 2026-08-19 | 계획서 작성 | PLAN.md v1 (E1~E13, 뼈대, 로컬↔클라우드 매핑, 5주 로드맵) | [PLAN.md](../PLAN.md)
- 2026-08-19 | 외부 API 장애 테스트 상세화 | 3계층 장애 주입 × 방어 단계별 비교 설계 → PLAN.md §4.6 | [journal](journal/2026-08-19-plan-and-wiki-setup.md)
- 2026-08-19 | 위키 체계 구축 | wiki/ 구조·템플릿·CLAUDE.md 워크플로우 규칙 생성 | [D-003](decisions/D-003-llm-wiki-workflow.md)
- 2026-08-19 | GitHub 원격 연결 & 첫 푸시 | origin=kimdoogi/java-heavy-traffic, main 푸시(95dc114). 커밋 정책 확정 | [howto/git.md](howto/git.md)
- 2026-08-19 | 1주차 뼈대 생성 | coupon-api/mock-external(Boot 4.0.7), compose(리소스 제한), Prometheus/Grafana, k6 6종, run-experiment.sh. smoke 통과 | [journal](journal/2026-08-19-skeleton.md)
- 2026-08-19 | E1 계측 검증 & 베이스라인 | M/VT on ping: 피크 6,000rps, p99 88ms, CPU 97% (CPU 병목) | [E1](experiments/E1-baseline.md)
- 2026-08-20 | 1주차 뼈대 코드 리뷰 | 11개 병렬 앵글로 결함 15건 확정(가짜 실험기록 3건, 기동 크래시 2건, k6 중단, 카디널리티 누수 등) + 개선 후보 다수 | [journal](journal/2026-08-20-code-review-skeleton.md)
- 2026-08-20 | 리뷰 결함 15건 수정 | 전부 수정+실측 검증(부분 장애·hang 상한·실효 설정 기록·스텝별 breakpoint 등). smoke/파이프라인 회귀 통과 | [journal](journal/2026-08-20-review-fixes.md)
