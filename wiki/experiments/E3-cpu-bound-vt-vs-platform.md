---
title: "E3 CPU bound — 플랫폼 vs 버츄얼 (M/L + 스레드 수 컨트롤)"
date: 2026-08-24
status: done
tags: [experiment, E3, virtual-thread, cpu]
related: [../journal/2026-08-24-E3-cpu-bound.md, E2-io-bound-vt-vs-platform.md]
---

## 가설 (PLAN 원안)
- CPU bound에선 VT on/off 차이 없음(오히려 VT 약간 손해 가능). "VT는 마법이 아니다."
- → **실측으로 뒤집힘.** 아래 참조.

## 설정
| 항목 | 값 |
|---|---|
| 엔드포인트 | `GET /api/cpu/hash?n=20000` (SHA-256 체인 20,000회, 순수 CPU) |
| 요청 비용 | 워밍업 후 ≈ 1.25ms/req (t2 런 역산: 800rps ≈ 1 CPU) |
| 부하 | `load/30-cpu-bound.js` — MAX의 25/50/75/100/100% 스텝 30s, open-model |
| 실행 | `[MAX_RPS=N] scripts/run-experiment.sh -n <이름> -p M|L -v on|off --no-build [--env TOMCAT_MAX_THREADS=2] -s load/30-cpu-bound.js` |
| 커밋 | 37f56ff (+ settings.gradle foojay, P-001) |

## 결과
1차(MAX 400): M/L × on/off 전부 피크 ~400rps 소화 — 천장 미도달. 단 M 포화 구간에서 지연 차이 관찰(off p50 239.5ms·CPU 99.5% vs on p50 11.3ms·CPU 71.3%).

2차(천장 탐색) + 컨트롤:
| 런 | 프로파일 | 스레드 | **피크 rps(30s창)** | 전구간 평균 | p50 | p99 | dropped | maxVUs | CPU 피크 |
|---|---|---|---|---|---|---|---|---|---|
| E3-M-off-800 | 1cpu | 플랫폼 200 | 493.6 | 367.2 | 896.3ms | 6,471ms | 15,701 | **2,000(cap)** | 110.5% |
| E3-M-on-800 | 1cpu | **VT** | 760.6 | 460.9 | **2.7ms** | 2,441ms | 1,997 | 1,442 | 102.5% |
| E3-M-off-800-t2 | 1cpu | **플랫폼 2** | **799.4** | 480.5 | **1.4ms** | 291ms | **203** | 193 | 114.8% |
| E3-L-off-1200 | 2cpu | 플랫폼 200 | 1,058.1 | 685.6 | 2,043ms | 2,676ms | 4,414 | 2,000(cap) | 230.3% |
| E3-L-on-1200 | 2cpu | VT | **1,194.9** | 697.2 | **1.4ms** | 475ms | 946 | 456 | 183.8% |

- 피크 rps: `max_over_time((sum(rate(k6_http_reqs_total{testid=...}[30s])))[2h:15s])`
- raw: `results/E3-*/` · Grafana `testid=E3-*`

## 해석
1. **원안 가설 기각**: CPU bound에서 VT가 플랫폼(기본 200 스레드)보다 처리량 +54%(M 피크 761 vs 494), p50은 수백 배 낮았다.
2. **진짜 변수는 VT가 아니라 활성 스레드 수** — 컨트롤 런이 증명: 플랫폼을 `TOMCAT_MAX_THREADS=2`로 줄이자 피크 799.4rps·p50 1.4ms로 **VT보다도 좋아짐**. 1 CPU를 200개 스레드가 타임슬라이싱하면 컨텍스트 스위치·캐시 오염으로 유효 처리량이 ~38% 증발한다(494/799).
3. VT가 이긴 이유: 캐리어 스레드 수 = CPU 수(cgroup 인지). CPU bound에서 오버서브스크립션이 원천적으로 없음. 즉 **"VT의 이득"이 아니라 "기본값이 맞게 잡힌 것"**. 튜닝된 플랫폼(스레드≈CPU)이 VT보다 소폭 우위(799 vs 761, VT 스케줄러 오버헤드 ~5%).
4. CPU 스케일링 확인: L(2cpu) VT 피크 1,195 ≈ M(1cpu) 761의 1.57배(k6 부하 상한 1,200에 걸림 — 실제 천장은 그 이상, ~1,600 추정). I/O bound(E2)와 달리 CPU를 주면 천장이 오른다.
5. E2와 묶은 한 줄: **"I/O bound에선 스레드가 모자라서 지고(E2), CPU bound에선 스레드가 남아서 진다(E3). VT는 두 경우 모두 기본값으로 맞는 수를 잡아준다."**
6. 주의(정직 노트): 플랫폼 200은 Boot 기본값 그대로의 비교다. 실무에선 CPU bound 서비스에 200 스레드를 두지 않는다 — 이 실험의 교훈은 "VT 채택"이 아니라 "워크로드에 맞는 동시 실행 수"이며, VT는 그걸 자동으로 얻는 수단이다.

## 문제 / 배운 것
- 사전 단발 측정(6.5~8ms/req)으로 천장 140rps 추정 → 실측 800rps. **단발 curl은 JIT 워밍업 안 된 값** — 부하 후 역산이 정답. 1차 4런이 전부 천장 아래였던 이유.
- [P-001](../problems/P-001-jdk21-missing-build-fail.md): 로컬 JDK 21 소실 → foojay resolver로 해결. k6도 같이 소실(재설치 v2.2.0, E2 당시와 버전 다름 — 결과엔 영향 없음, k6는 부하 생성기).
- run-experiment.sh가 k6 exit 127(미설치)에도 exit 0으로 계속 진행 — 개선 후보로 기록만.

## 다음 액션
- E5: pinning 재현 (`/pin/sync` vs `/pin/lock`) — E2 부하 재사용, pin용 시나리오 필요 여부 확인
- (선택) E9에서 M 프로파일 진짜 천장(≈800) breakpoint로 정밀 측정
