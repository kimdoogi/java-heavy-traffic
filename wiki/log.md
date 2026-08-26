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
- 2026-08-24 | 빌드 환경 복구 | 로컬 JDK 21·k6 소실 → foojay resolver 추가, k6 v2.2.0 재설치 | [P-001](problems/P-001-jdk21-missing-build-fail.md)
- 2026-08-24 | E3 완료 | 가설 기각 — CPU bound에서도 VT 우위(M 피크 761 vs 494). 컨트롤(플랫폼 스레드2)=799로 원인은 오버서브스크립션 확정. 튜닝된 플랫폼 ≥ VT | [E3](experiments/E3-cpu-bound-vt-vs-platform.md)
- 2026-08-24 | main 보호 활성 | PR 필수+승인 1명+관리자 포함. 이후 브랜치 a/*·b/* → PR → 상대 승인 → merge | [journal](journal/2026-08-24-pr-branch-protection.md)
- 2026-08-24 | E5 완료 | pinning 재현: sync 37.5rps·p50 26s vs lock 1,541~1,994rps·p50 52ms (41~53배). pinned 스택 확보. 천장이 CPU 수 무관 → P-003 open | [E5](experiments/E5-pinning.md)
- 2026-08-25 | E5 리뷰 반영 | PR #1 셀프 리뷰 11건 수정. lock 런 pinned-count는 sync 잔재 → 0건 정정(P-005 solved), 위키 수치·표 정정, run-experiment.sh --dirty·PIN_* 캡처 보강 | [P-005](problems/P-005-pinned-count-carryover.md)
- 2026-08-25 | P-003 규명 | pinned 천장=실효 캐리어 수×1/53ms. M=1+보상1, L=2+0 — 동일 37.5는 우연. parallelism=4→67rps·maxPoolSize=1→붕괴로 검증. /api/env 신설 | [P-003](problems/P-003-pinned-ceiling-not-scaling.md)
- 2026-08-25 | PR 승인 요구 해제 | main branch protection에서 required_approving_review_count 1→0. PR 필수는 유지, 리뷰는 권장으로 | [git.md](howto/git.md)
- 2026-08-26 | 쿠폰 도메인 구현 | 선착순 발급 4전략(none/db-pessimistic/db-optimistic/redis) + API + 동시성 테스트 10건 통과. none 초과발급 4배 재현, optimistic 재시도소진 206/300 (원 소유 B — 트랙 조정 공유 필요) | [journal](journal/2026-08-26-coupon-domain.md)
- 2026-08-26 | PR #3 코드리뷰 + 15/16건 수정 | redis 복구 경로 재설계(마커+DB 백스톱, 보상 Lua 원자화, FK/unique 구분), knob 파이프라인·@Size·에러계약·테스트 하네스 수정, PLAN §1.2.1 계약 확정(contract:). 보류 1건=E8-5 outbox. 13 tests passed | [journal](journal/2026-08-26-coupon-domain.md)
