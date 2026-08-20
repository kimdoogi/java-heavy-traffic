---
title: "E2 I/O bound — 플랫폼 vs 버츄얼 쓰레드 (S/M/L)"
date: 2026-08-20
status: done
tags: [experiment, E2, virtual-thread]
related: [../journal/2026-08-20-E2-io-bound.md, E1-baseline.md, ../concepts/thread-ceiling-and-backpressure.md]
---

## 가설
- 플랫폼(Tomcat 200 threads): `200 / 0.3s ≈ 666 rps` 천장. CPU/메모리와 무관.
- VT: 천장 없음 — 리소스(CPU/메모리) 한계까지 선형.

## 설정
| 항목 | 값 |
|---|---|
| 엔드포인트 | `GET /api/io/sleep?ms=300` (순수 I/O 대기, DB/외부 없음) |
| 부하 | `load/20-io-sleep.js` — 400→800→1200→1600→2000→2000 rps, 스텝 30s (총 3분), open-model |
| MAX_VUS | 3000 (k6 요청 타임아웃 기본 60s) |
| 실행 | `SLEEP_MS=300 MAX_RPS=2000 STEP_DUR=30s MAX_VUS=3000 scripts/run-experiment.sh -n E2-<P>-<V> -p <P> -v <V> --no-build -s load/20-io-sleep.js` (P∈S,M,L / V∈on,off — 6회 순차) |
| 커밋 | 6953f61 |

## 결과
| 런 | 프로파일 | VT | **피크 rps(30s창)** | 전구간 평균 rps | p50 | p99 | 실패율 | dropped | k6 exit |
|---|---|---|---|---|---|---|---|---|---|
| E2-S-on | 0.5cpu/512m | on | **1,450.7** | 576.8 | 301ms | **60,000ms** | **2.76%** | 102,693 | 99 (실패) |
| E2-S-off | 0.5cpu/512m | off | 661.5 | 567.6 | 4,515ms | 5,676ms | 0% | 106,754 | 0 |
| E2-M-on | 1cpu/1g | on | **1,997.8** | 1,169.5 | **301ms** | **316ms** | 0% | 634 | 0 |
| E2-M-off | 1cpu/1g | off | 663.5 | 578.8 | 4,487ms | 4,596ms | 0% | 104,700 | 0 |
| E2-L-on | 2cpu/2g | on | **1,990.1** | 1,169.9 | **301ms** | **307ms** | 0% | 554 | 0 |
| E2-L-off | 2cpu/2g | off | 666.7 | 579.7 | 4,476ms | 4,561ms | 0% | 104,549 | 0 |

- 피크 rps: Prometheus `max_over_time((sum(rate(k6_http_reqs_total{testid=...}[30s])))[2h:15s])`
- raw: `results/E2-*/` (summary.json, k6.log, docker-stats.csv, meta.env) · Grafana `testid=E2-*`

## 해석
1. **플랫폼 천장은 정확히 이론값**: S/M/L 모두 661~667 rps — `200 threads / 0.3s = 666.7`. CPU를 4배(0.5→2) 줘도 1 rps도 안 늘었다. **병목이 CPU가 아니라 쓰레드 수라는 직접 증거.**
2. **플랫폼의 4.5초 지연도 계산과 일치**: 3,000 VU가 밀어넣고 200개만 서비스 → 대기열 ≈ 2,800 → 대기시간 ≈ 2800/666 ≈ 4.2s + 서비스 0.3s ≈ **4.5s** (실측 p50 4,476~4,515ms).
3. **VT는 M(1cpu)부터 목표 2,000 rps를 소화** — p99 306~316ms(순수 sleep 300ms + 오버헤드 6~16ms), 동시 in-flight ≈ 600(=rate×0.3s)을 쓰레드 걱정 없이 유지. CPU 피크 58%(M)로 여유. L도 동일(이 부하에선 CPU 1개면 충분).
4. **S(0.5cpu)에서 VT가 먼저 무너졌다**: ~1,450 rps까지는 처리했지만 CPU 포화(피크 50.7% = 0.5cpu 한계) 후 도착>처리가 계속되자 **수용 무제한**인 VT는 백로그를 계속 받았고, k6 VU 3,000개 전부가 응답을 못 받아 **60s 타임아웃(정확히 3,000건 실패 = MAX_VUS)**. 반면 같은 S의 플랫폼은 지연 5.7s로 느리지만 실패 0% — **쓰레드 200개 한도가 사실상 강제 백프레셔로 작동**했다.
5. 한 줄 요약: **"VT는 천장을 없애준다. 그리고 천장이 없다는 것은, 과부하 때 시스템을 지켜주던 안전장치도 없다는 뜻이다."** → E8(타임아웃/벌크헤드), E13(백프레셔)이 VT에서 필수가 되는 이유를 S-on이 실증.

## 문제 / 배운 것
- 서버 in-flight 게이지 최대 603인데 클라이언트는 3,000개가 걸려 있었음 — 초과분은 서블릿에 도달하기 전 TCP/accept 단계에서 대기. "서버 지표만 보면 과부하가 안 보일 수 있다"는 관측 포인트 (5s 스크레이프 한계 포함).
- S-on 힙 피크 206MB/256m — OOM은 아님. 붕괴의 1차 원인은 CPU 포화.
- k6 exit 99(S-on)는 threshold(실패율<1%) 위반의 정상 동작 — 파이프라인이 실패 런을 정확히 구분.

## 다음 액션
- E3: `/api/cpu/hash`로 "CPU bound에선 VT 무차이" 확인 → E5: pinning
- E9에서 S 프로파일 VT의 정확한 한계(1,400~1,500 추정) breakpoint 측정
