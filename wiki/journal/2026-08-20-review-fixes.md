---
title: "2026-08-20 코드 리뷰 결함 15건 수정"
date: 2026-08-20
status: done
tags: [journal, fix, week1]
related: [2026-08-20-code-review-skeleton.md]
---

## 목표
- 리뷰에서 확정한 결함 15건 전부 수정 + 빌드·smoke·짧은 파이프라인 실행으로 회귀 확인.

## 범위 / 하지 않는 것
- 컷라인 아래 개선 후보는 수정과 자연스럽게 겹치는 것만 함께 처리(FaultConfig 바인딩 전환, sentinel 제거, stages 헬퍼). 나머지(보안 바인딩, layered jar 등)는 별도 작업.

## 진행 기록 (시간순)
- coupon-api: RestClientConfig connectTimeout >0 가드(0=무제한, read와 대칭), InFlightRequestsFilter 그룹 허용목록+무할당 파싱(카디널리티 바운드), 양쪽 build.gradle `jar { enabled = false }`(plain jar 제거).
- mock-external: FaultConfig를 `@EnableConfigurationProperties`로 전환(수동 Environment 바인딩·기본값 3중 복사·sentinel `with()` 삭제, relaxed binding으로 FAULT_MODE 대소문자 흡수), AdminController는 null 기반 부분 패치(0 유효값, 음수/범위 밖 400), reset은 `defaults.toFault()` 재사용. NotifyController error 모드 `failRate>0 ? failRate : 1.0`(부분 장애 가능), hang은 Semaphore 상한(FAULT_MAX_HANGS=2000, 초과 즉시 503)으로 커넥터·admin API 보호.
- compose: coupon-api에 TOMCAT_MAX_CONNECTIONS/ACCEPT_COUNT·POOL_CONN_TIMEOUT_MS·ISSUE_STRATEGY, mock에 FAULT_* 7종 전달. mock TCP healthcheck + coupon-api `service_healthy` 게이트.
- run-experiment.sh: mock health 대기 → 매 실험 `POST /admin/fault/reset` + `mock_fault=` 기록 → `/api/ping`의 virtual로 실효 VT 검증(불일치 시 exit 1) + `effective_virtual=` 기록 + `/actuator/env` 스냅샷 → 마지막에 `exit $K6_EXIT` 전파, summary.json 있을 때만 summarize.
- summarize.py: summary.json 부재 시 한줄 에러 종료, docker stats `--` 샘플 skip.
- k6: `stepStages()` 헬퍼(Math.round)로 4개 시나리오의 중복 stages 교체. 60-breakpoint.js를 스텝별 constant-arrival-rate 시나리오 + `{scenario:stepNN}` 스코프 threshold(abortOnFail)로 재설계 — 누적 threshold 희석 문제 해소.
- 문서: PLAN.md Toxiproxy 8474→18081 정정, howto에 전환 명령 추가.
- 검증(전부 실측): error failRate=0.3 → 60회 중 16회 500(≈27%) / hangSeconds:0 패치 허용·음수 400 / 미지 경로 3종 → `group="other"` 단일 시리즈 / `FAULT_MODE=SLOW`(대문자) 기동 성공 mode=slow / connect·read 타임아웃 0으로 기동+700ms 호출 성공 / MAX_RPS=250으로 k6 inspect 4파일 통과 *(주의: 사후 확인 결과 k6 inspect는 OS env를 읽지 않아 이 검증은 기본값 검증이었음 — [2차 리뷰](2026-08-20-code-review-round2.md) 참고. 코드 수정 자체는 유효)* / 파이프라인 exit 0 + meta.env에 mock_fault·effective_virtual 기록 / `--skip-up -v off` 불일치 → exit 1 / 컨테이너 env 전달 스팟체크(TOMCAT_MAX_CONNECTIONS 등) 통과.

## 결과
- 리뷰 15건 전부 수정 + 실측 검증 완료. 컷라인 아래 후보 중 수정과 겹친 것(FaultConfig 바인딩·sentinel·reset 재사용·stages 중복·GET/POST는 유지)도 함께 정리.
- 부수 효과: 실험 기록이 "의도"가 아니라 "실효"를 담게 됨 — meta.env에 effective_virtual·mock_fault, effective-env.json 스냅샷.

## 남은 일 / 다음 단계
- [ ] 컷라인 아래 잔여 후보: 127.0.0.1 바인딩(보안), layered jar, docker stats → Prometheus 피크 대체, Testcontainers 이미지 핀, `.gitignore` k6.log 예외
- [ ] 2주차 E2/E3/E5 시작
