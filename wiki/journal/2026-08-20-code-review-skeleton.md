---
title: "2026-08-20 1주차 뼈대 코드 리뷰"
date: 2026-08-20
status: done
tags: [journal, review, week1]
related: [2026-08-19-skeleton.md]
---

## 목표
- 커밋 e34054f(1주차 뼈대) 전체를 다각도 코드 리뷰하고, 수정 전에 결함 목록을 확정한다.

## 방법
- 병렬 리뷰 에이전트 11개: 정확성 5앵글(라인 스캔·기본값 대체 감사·크로스파일 추적·언어 함정·래퍼/실험 유효성) + 정리 3앵글(재사용·단순화·효율) + 깊이·컨벤션 + 최종 갭 스윕.
- 주요 주장 2건은 로컬에서 직접 실증: `./gradlew assemble` → `-plain.jar` 생성 확인, JDK 21 `HttpClient.Builder.connectTimeout(Duration.ZERO)` → `IllegalArgumentException: Invalid duration: PT0S`.
- 반박된 후보 예: "k6 remote-write duration이 ms 단위" 주장 → E1 실측(게이지 0.0069 ≈ 6.9ms=초 단위)으로 기각. 대시보드 단위 `s`가 맞음.

## 확정 결함 (15건, 심각도순 — 수정은 다음 작업)
| # | 위치 | 내용 |
|---|---|---|
| 1 | docker-compose.yml (coupon-api/mock env 블록) | env 허용목록이라 `--env`로 준 실험 변수(POOL_CONN_TIMEOUT_MS, TOMCAT_MAX_CONNECTIONS/ACCEPT_COUNT, FAULT_FAIL_RATE 등)가 컨테이너에 전달 안 되는데 meta.env에는 기록됨 → **가짜 실험 기록** |
| 2 | run-experiment.sh `--skip-up` | 미적용 설정(VT 등)이 meta.env에 기록됨 → VT 비교 데이터 오염 위험. 실효 설정 검증 필요 |
| 3 | run-experiment.sh 종료부 | k6 threshold 실패해도 스크립트 exit 0 → 배치 자동화에서 실패 감지 불가 |
| 4 | RestClientConfig | `EXTERNAL_CONNECT_TIMEOUT_MS=0`이면 기동 크래시 (read만 0=무제한 가드) — E8-0 차단 |
| 5 | load/*.js stage target | `MAX*0.25` 비정수면 k6 파싱 중단 (MAX_RPS=250 등) — 실측 확인 |
| 6 | NotifyController error 모드 | `Math.max(failRate, 1.0)` = 항상 1.0 → fail-rate 무시, 부분 장애 실험 불가 |
| 7 | NotifyController hang 모드 | 커넥션을 300초 점유 → 슬롯 고갈로 admin API까지 잠김 |
| 8 | InFlightRequestsFilter | 임의 URI 세그먼트마다 게이지 영구 등록 → 카디널리티/힙 누수 |
| 9 | summarize.py | `--` CPU 값·summary.json 부재에 크래시 → 완주한 실험 결과 유실 |
| 10 | Dockerfile jar 글롭 | `-plain.jar` 공존 시 빌드 실패 (assemble로 실증) |
| 11 | FaultConfig | `Mode.valueOf` 대소문자 민감 → `FAULT_MODE=SLOW` 기동 크래시. @ConfigurationProperties는 미등록 죽은 애노테이션 |
| 12 | 60-breakpoint.js | k6 누적 threshold라 breakpoint 과대평가 — E9 방법론 수정 필요 |
| 13 | run-experiment.sh | `/admin/fault` 상태를 리셋 안 함 → 주입 장애가 다음 실험에 지속 |
| 14 | PLAN.md §4.6 | Toxiproxy 경유를 8474(admin 포트)로 잘못 안내 (실제 데이터 포트 18081) |
| 15 | docker-compose | mock-external 준비 완료를 아무도 확인 안 함 (healthcheck 없음 + service_started) |

## 확정 외 개선 후보 (리포트 컷라인 아래, 수정 시 함께 처리 검토)
- FaultConfig 수동 바인딩 → `@EnableConfigurationProperties` 전환(기본값 3중 복사 제거), AdminController sentinel 패치(0으로 설정 불가) → nullable 직접 비교, `reset()` → `defaults.toFault()` 재사용
- k6: `DEFAULT_THRESHOLDS`/`constantRate` 죽은 export, stages 배열 4중 복사 → `stepStages()` 헬퍼, `preAllocatedVUs` 부족(측정 중 VU 할당 노이즈)
- docker stats 샘플러 실제 주기 4~5초(주석 2초와 다름, 피크 놓침 — 커밋된 CSV로 확인) → Prometheus `process_cpu_usage`를 피크 소스로
- 보안: 0.0.0.0 바인딩 + Grafana 익명 Admin + actuator threaddump/env 노출 (공용 Wi-Fi 주의) → 127.0.0.1 바인딩 검토
- E4 대비: Postgres max_connections=100 vs pool 100+·minimum-idle=POOL_SIZE 충돌 가능
- ISSUE_STRATEGY 플래그가 현재 no-op인데 meta.env에 기록됨 (3주차 배선 전까지 주의)
- 기타: Testcontainers `:latest` 핀 안 됨, `.gitignore` `*.log`가 results/k6.log까지 삼켜 E1 페이지 링크가 끊김, GET/POST 중복 핸들러, GlobalExceptionHandler 스코프(3주차 쿠폰 API에 오적용 예정), layered jar 미사용, pin 엔드포인트 공유 seq AtomicLong

## 배운 것
- "실험 기록의 정합성"이 이 프로젝트의 아킬레스건: 변수 전달 경로(셸→compose→컨테이너→Spring)가 4홉 전부 조용한 기본값 폴백이라, **의도한 설정이 아니라 실효 설정을 기록**해야 함 (/actuator/env, /api/ping의 virtual 필드 활용).
- k6 threshold는 누적 통계 기준 — breakpoint 측정 설계에 직접 영향.

## 남은 일 / 다음 단계
- [ ] 15건 수정 (사용자 승인 후) — 수정 커밋에 이 journal 참조
- [ ] 수정 후 smoke + E1 재실행으로 회귀 확인
