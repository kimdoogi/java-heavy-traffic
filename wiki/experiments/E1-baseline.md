---
title: "E1 계측 검증 & 베이스라인 (/api/ping)"
date: 2026-08-19
status: done
tags: [experiment, baseline, E1]
related: [../journal/2026-08-19-skeleton.md, ../howto/run-experiment.md, ../../results/E1-M-vt-ping/summary.md]
---

## 가설
- 툴체인(compose 리소스 제한 → 앱 → Prometheus/Grafana ← k6 remote-write)이 끝까지 동작한다.
- `/api/ping`은 I/O가 없으므로 VT 여부와 무관하게 **CPU가 병목**이며, 1 CPU에서 수천 rps 수준의 천장을 가진다.

## 설정
| 항목 | 값 |
|---|---|
| 리소스 프로파일 | M (1 cpu, 1g, -Xmx512m, G1) |
| 쓰레드 모드 | virtual |
| k6 시나리오 | `load/10-baseline-ping.js`, `START_RPS=500 MAX_RPS=6000 STEP_DUR=20s` (ramping-arrival-rate 1500→3000→4500→6000→6000) |
| 실행 명령 | `STEP_DUR=20s MAX_RPS=6000 START_RPS=500 scripts/run-experiment.sh -n E1-M-vt-ping -p M -v on --no-build --skip-up -s load/10-baseline-ping.js` |
| 앱 커밋 | 1주차 뼈대 (이 journal 커밋) |

## 결과
| 조건 | 평균 rps(전 구간) | 서버 피크 rps(20s) | p50 | p95 | p99 | 에러율 | dropped iter | app CPU 피크(docker) | process_cpu_usage max | 힙/메모리 |
|---|---|---|---|---|---|---|---|---|---|---|
| M / VT on / ping | 3,611 | **6,000** | 0.3ms | 22.9ms | 87.5ms | 0% | 3,782 | 97.2% | 0.906 | 416MiB/1GiB(컨테이너) |

- 사전 파이프라인 점검(`E1-pipeline-check`, 최대 2,000 rps): p99 3.6ms, CPU 피크 41% — 여유 구간에서는 지연이 거의 없음.
- raw: `results/E1-M-vt-ping/` (summary.json, k6.log, docker-stats.csv, meta.env), Grafana `testid=E1-M-vt-ping`

## 해석
- 1 CPU 제한에서 `/api/ping`은 6,000 rps 도착률도 순간적으로는 받아냈지만 CPU가 90~97%로 포화되며 **p95 23ms / p99 88ms로 지연이 튐**, k6가 3,782 iteration을 drop(=VU가 응답 대기에 묶여 도착률을 못 맞춤). 즉 **천장은 대략 4,000~6,000 rps 사이**이고 병목은 CPU. 정확한 "p99 500ms 이내 최대 rps"는 E9 breakpoint에서 측정한다.
- 같은 머신에서 k6가 돌고 있으므로 6,000 rps 구간에서는 k6도 CPU를 꽤 쓴다 → 절대값은 참고치. 상대 비교(E2 VT on/off)에는 문제 없음.
- 툴체인 검증: k6 → Prometheus remote-write(`k6_http_reqs_total` 60,997 = k6 summary와 일치), 서버 지표(`http_server_requests_seconds_*`, `http_inflight_requests{group}`, Hikari, JVM), Grafana 대시보드(13패널) 모두 동작.

## 문제 / 배운 것
- **VT 모드에서 `tomcat_threads_busy_threads = -1`**: Tomcat이 VT executor를 쓰면 플랫폼 쓰레드 풀 지표가 의미 없어진다. "서버에 쌓인 요청"은 `http_inflight_requests`(직접 만든 필터 게이지)로 봐야 한다. → concepts 예정: virtual-thread-observability
- k6 prometheus-rw의 duration 지표는 **초 단위**(`k6_http_req_duration_p99`=0.0069). 대시보드 단위를 `s`로 수정.
- curl로 PromQL을 보낼 때 `{}`가 curl 글로빙에 걸림 → `-G --data-urlencode` 사용 (howto에 반영).
- `http_inflight_requests` 게이지는 5초 스크레이프라 sub-ms 요청(ping)에서는 거의 0으로 보임 — 느린 엔드포인트(E2/E8)에서 의미가 생긴다.

## 다음 액션
- E2: `/api/io/sleep?ms=300` 에서 VT on/off 비교 (플랫폼 200쓰레드 이론 천장 666 rps 확인)
- E3: `/api/cpu/hash` VT on/off 차이 없음 확인
