---
title: "2026-08-20 2차 리뷰 결함 15건 수정"
date: 2026-08-20
status: done
tags: [journal, fix, week1]
related: [2026-08-20-code-review-round2.md]
---

## 목표
- 2차 리뷰 15건 전부 수정. E9 breakpoint 관련(1·2·7·10·11·14)은 하나의 재설계로 묶는다.

## 진행 기록 (시간순)
- **E9 breakpoint 재설계** (#1·2·7·10·11·14): preAlloc을 rate×P99/4000(스텝당 ≤600, 기본값 합 ≈2,000)으로 축소 + gracefulStop 3s로 스텝 겹침 차단, delayAbortEval을 스텝 시작 오프셋+유예(min(15s, 스텝/2))로 개별 계산, dropped_iterations{scenario} threshold로 VU 부족 스텝 무효화, STEPS<2·MAX≤START throw, STEP_DUR('30s')와 STEP_DUR_S(숫자) 모두 허용, run-experiment.sh가 breakpoint 한정 k6 exit 99→0 처리.
- run-experiment.sh (#3·6·13): 새 curl 4곳 개별 if 검사(명확한 에러)·/api/ping은 python json 파싱, meta.env grep에 STEP_DUR_S|STEPS|BASE_URL|POOL_CONN_TIMEOUT_MS 추가·TOMCAT_/PG_ 와일드카드·RAMP 제거, `scripts/verify-effective.py` 신설 — 스냅샷 systemEnvironment와 요청 knob 전수 대조(불일치 exit 1).
- coupon-api: `management.endpoint.env.show-values: always`(#5, 로컬 랩 한정 주석) + `coupon.issue.strategy=${ISSUE_STRATEGY:redis}` property 배선(#15). `.gitignore`에 effective-env.json 제외.
- mock-external: admin 패치에 status 100~999·delay/jitter ≤60s·hang/flap ≤1h 검증(#4·8), handle()에 delay 클램프 0~60s(음수 IAE도 해결), Semaphore 최소 1 + hang 슬롯 소진 시 사이징 경고 로그 1회(#9), jackson enum 대소문자 무시(컷라인).
- build.sh: docker build 전 *-plain.jar 항상 삭제(#12, bash 글롭 의미론 확인).
- 문서: PLAN E9 행·트리 주석을 스텝 방식으로 정정, 장애표에 hang cap·delay 상한 명시, howto에 breakpoint 지정법·exit 해석·hang 사이징 추가(#9·컷라인 드리프트).

## 검증 (실측 명령·결과 — 전부 이 세션에서 실행)
```
k6 inspect -e STEPS=1 load/60-breakpoint.js                          # → throw "STEPS는 2 이상"
k6 inspect -e STEPS=5 -e MAX_RPS=333 -e STEP_DUR=20s load/60-breakpoint.js   # → OK (-e 사용: OS env는 inspect가 안 읽음)
curl -X POST :8081/admin/fault -d '{"status":0}'                     # → 400 (1000도 400, 404는 저장)
curl -X POST :8081/admin/fault -d '{"delayMs":600000}'               # → 400
curl -X POST :8081/admin/fault -d '{"mode":"SLOW"}'                  # → mode=slow 저장 (대소문자 흡수)
scripts/run-experiment.sh -n FIX2-mismatch -p M -v on --pool 5 --skip-up -s load/00-smoke.js
                                                                     # → exit 1 "POOL_SIZE: 요청 5 vs 실효 20"
STEPS=3 STEP_DUR_S=10 START_RPS=50 MAX_RPS=400 P99_MS=2 ENDPOINT='/api/io/sleep?ms=50' MAX_VUS=500 \
  scripts/run-experiment.sh -n FIX2-bp -v on --skip-up -s load/60-breakpoint.js   # → 한계 도달, exit 0 매핑
STEP_DUR=5s MAX_RPS=300 scripts/run-experiment.sh -n FIX2-ok ...     # → exit 0, meta.env에 mock_fault·effective_virtual, "effective-env 검증 OK"
STEPS=1 ... -s load/60-breakpoint.js                                 # → k6 즉시 실패 + "summary.json 없음" 안내, exit 107
FAULT_MAX_HANGS=1 compose up + hang 2건 동시                          # → 2번째 즉시 503(18ms) + "hang 슬롯(1) 소진" WARN 로그
```


## 결과
- 2차 리뷰 15건 전부 수정 + 실측 검증. k6 검증은 이번엔 전부 `-e` 플래그 사용 (inspect가 OS env를 안 읽는 함정 회피).
- 실효 설정 검증이 VT 1개 → 컨테이너 knob 전수 대조로 확장됨 (verify-effective.py).

## 남은 일 / 다음 단계
- [ ] 컷라인 아래 잔여: compose 기본값 이중화(list-form passthrough), toxiproxy depends_on, Grafana hang_rejected annotation, wait_healthy 함수화, healthcheck start_interval, 127.0.0.1 바인딩
- [ ] 2주차 E2/E3/E5 시작 (환경은 검증된 상태로 가동 중)
