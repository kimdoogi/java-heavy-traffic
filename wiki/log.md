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
- 2026-08-20 | 수정 커밋 2차 리뷰 | 신규 결함 15건 확정(breakpoint VU 폭탄·delayAbortEval 전역 기준·status 검증 누락 등). k6 inspect가 OS env를 무시함을 발견(지난 검증 1건 무효) | [journal](journal/2026-08-20-code-review-round2.md)
- 2026-08-20 | 2차 리뷰 15건 수정 | E9 재설계(VU 예산·스텝 유예·dropped 가드·exit 매핑), 실효 검증 전수화(verify-effective.py), status/delay 상한, hang cap 문서화. 전부 실측 | [journal](journal/2026-08-20-review2-fixes.md)
- 2026-08-20 | E2 완료 | 플랫폼 천장 666rps 실측(S/M/L 동일, 이론 일치), VT는 1cpu에서 2,000rps p99 316ms. S(0.5cpu)에선 VT가 백프레셔 부재로 먼저 붕괴(3,000건 60s 타임아웃) | [E2](experiments/E2-io-bound-vt-vs-platform.md)
- 2026-08-24 | 2인 협업 트랙 확정 | A(kimdoogi)=런타임·자원(E3·E4·E5·E10~12), B(popogustn)=도메인·장애(구현+E6~E9·E13). 규칙은 CLAUDE.md, 번호 A=홀수/B=짝수 | [D-005](decisions/D-005-two-person-track-split.md)
