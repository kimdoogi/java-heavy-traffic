---
title: "2026-08-20 수정 커밋 2차 코드 리뷰"
date: 2026-08-20
status: done
tags: [journal, review, week1]
related: [2026-08-20-review-fixes.md, 2026-08-20-code-review-skeleton.md]
---

## 목표
- 결함 수정 커밋 a33b481 자체를 같은 강도(11개 병렬 앵글 + 갭 스윕)로 재리뷰 — "수정이 새 결함을 만들지 않았나".

## 확정 결함 (15건, 심각도순 — 수정은 다음 작업)
| # | 위치 | 내용 |
|---|---|---|
| 1 | 60-breakpoint.js | **스텝별 preAllocatedVUs 합계 ≈32,000** (k6는 시나리오 간 VU 미공유, `k6 inspect --execution-requirements` 실측 maxVUs 16,000) → 첫 E9 실행이 로드 호스트 과부하/OOM |
| 2 | 60-breakpoint.js | delayAbortEval '15s'가 **테스트 시작 기준**(라이브 프로브 실측) → step01만 유예, 후속 스텝은 첫 샘플부터 abort 평가 → 과소평가. 미실행 스텝은 0샘플 ✓ |
| 3 | run-experiment.sh 새 curl 블록 | EFF_VT=$() 실패 시 set -e로 **무메시지 즉사**(bash 3.2 실측), reset curl 무메시지 abort, mock_fault= 빈 값 조용히 기록, 0바이트 effective-env.json 잔류 |
| 4 | AdminController | 패치 검증에서 **status만 누락** — {"status":0} 저장 후 응답 작성 시점 IAE 500 폭탄 (spring-web 100..999 assert 바이트코드 확인). 구 sentinel엔 있던 가드의 회귀 |
| 5 | run-experiment.sh:91 | /actuator/env은 Boot 기본 show-values=NEVER라 **effective-env.json이 전부 ******** — 스냅샷 무용지물 + gitignore 안 걸려 매 실험 커밋됨 |
| 6 | run-experiment.sh:58 | meta.env grep이 이번 커밋의 새 knob(STEPS/STEP_DUR_S/TOMCAT·POOL) 미기록, 죽은 RAMP 잔재, 무효 STEP_DUR은 기록 — 3곳 동시 편집 샷건이 수정 커밋 안에서 재발 |
| 7 | 60-breakpoint.js:7 | 헤더 예시 STEP_DUR=30s vs 코드 STEP_DUR_S(숫자) — 예시대로면 no-op, STEP_DUR_S=30s면 NaN 파싱 중단 |
| 8 | NotifyController slow 경로 | 세마포어가 hang만 보호 — slow+큰 delayMs는 여전히 무제한 커넥션 점유 → admin 잠금 재현 가능 (delayMs 상한 검증도 없음) |
| 9 | NotifyController hang cap | rate×hangSeconds ≫ 2000이라 몇 초 만에 소진 → E8이 "즉시 503" 실험으로 변질. PLAN/howto 미문서화, Semaphore(0) 허용, 거부 503 vs 해제 f.status() 불일치 |
| 10 | 60-breakpoint.js | dropped_iterations 무감시 → VU 부족 시 명목 rate로 한계 과대평가 |
| 11 | 60-breakpoint.js | STEPS=1/0 퇴화값이 무의미한 런을 exit 0으로 기록 (k6 실측) |
| 12 | build.gradle | jar disabled는 forward-only — stale plain jar 미제거 → e34054f를 빌드했던 협업자/CI에서 빌드 파탄 |
| 13 | run-experiment.sh | 실효 검증이 VT 하나뿐 — POOL_SIZE 등은 --skip-up 무방비 (스냅샷 저장만 하고 비교 없음) |
| 14 | run-experiment.sh:121 | breakpoint는 abort=정상 종료인데 exit 99 전파 → 배치가 성공을 실패로 처리 |
| 15 | docker-compose:79 | ISSUE_STRATEGY 전달+기록이 오히려 no-op 가짜 기록을 강화 (소비자 없음) |

## 컷라인 아래 (수정 시 함께 검토)
- compose 기본값 이중화 → **list-form passthrough**(`- FAULT_MODE`)가 올바른 해법 (compose config 실증; `${VAR}` map형은 빈 문자열 함정)
- /admin/fault 패치의 Jackson enum 대소문자 민감("SLOW"→400) — 기동 relaxed binding과 비대칭
- PLAN.md:162/226 E9 설명이 여전히 ramping 방식 — 방법론 드리프트
- toxiproxy 경로(E8) 시작 순서/health 무가드, Grafana fault annotation이 hang_rejected 미포함
- wait_healthy 함수 중복, step/rate 중복 태그, healthcheck 3s 상시, FaultProperties @DefaultValue 부재, journal 검증 명령 원문 미기록(CLAUDE.md 규칙), DEFAULT_THRESHOLDS 여전히 죽은 export

## 배운 것 (뼈아픈 것 포함)
- **지난 수정의 검증 중 하나가 무효였다**: `k6 inspect`는 OS 환경변수를 읽지 않는다(`-e KEY=VAL`만 반영, `k6 run`은 OS env 반영 — 실측). 따라서 "MAX_RPS=250으로 inspect 4파일 통과"는 사실상 기본값 검증이었다. 코드 수정(Math.round) 자체는 유효하나 검증 방법이 틀렸음. → 이후 inspect 검증은 반드시 `-e`로.
- 수정 커밋도 같은 강도로 재리뷰할 가치가 있다: 15건 중 다수가 "수정이 만든 새 결함"(VU 폭탄, status 검증 누락, STEP_DUR_S 개명 여파, stale jar)이었다.
- k6 threshold의 delayAbortEval·서브메트릭 평가는 모두 테스트 전역 기준 — 시나리오 단위 시간 개념이 없다.

## 남은 일
- [x] 15건 수정 완료 → [2026-08-20-review2-fixes](2026-08-20-review2-fixes.md) (E9는 묶어서 재설계)
- [x] 지난 journal에 무효 검증 주석 완료 (2차 리뷰 커밋에 포함)
