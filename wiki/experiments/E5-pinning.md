---
title: "E5 Pinning 재현 — synchronized vs ReentrantLock (VT on, M/L)"
date: 2026-08-24
status: done
tags: [experiment, E5, virtual-thread, pinning]
related: [../journal/2026-08-24-E5-pinning.md, E2-io-bound-vt-vs-platform.md, ../problems/P-003-pinned-ceiling-not-scaling.md]
---

## 가설
- `synchronized` 블록 안 I/O(sleep 50ms)는 VT를 캐리어에 pinning → 천장 ≈ 캐리어 수/0.05s (M=1cpu → 20rps, L=2cpu → 40rps).
- `ReentrantLock`으로 바꾸면 VT가 unmount → E2 수준(수천 rps) 회복.

## 설정
| 항목 | 값 |
|---|---|
| 엔드포인트 | `GET /api/pin/{sync|lock}?ms=50` — 스트라이프 락 4096개(경합 없음, 순수 pinning만) |
| 부하 | `load/22-pin.js` — sync: MAX 200rps / lock: MAX 2,000rps, 스텝 30s, open-model |
| JVM | VT on + `-Djdk.tracePinnedThreads=full` |
| 실행 | `[MAX_RPS=2000] scripts/run-experiment.sh -n E5-<P>-<mode> -p M|L -v on --no-build --java-opts "-Djdk.tracePinnedThreads=full" --env PIN_MODE=sync|lock -s load/22-pin.js` |

## 결과
| 런 | 프로파일 | 모드 | **피크 rps(30s창)** | 평균 rps | p50 | p99 | dropped | maxVUs | CPU 피크 |
|---|---|---|---|---|---|---|---|---|---|
| E5-M-sync | 1cpu | synchronized | **37.5** | 35.3 | **25,833ms** | 54,320ms | 10,917 | 2,000(cap) | 38.7% |
| E5-M-lock | 1cpu | ReentrantLock | **1,541.2** | 1,048.4 | **51.9ms** | 2,749ms | 22,637 | 2,000(cap) | 142.8% |
| E5-L-sync | 2cpu | synchronized | **37.6** | 35.4 | 25,434ms | 54,298ms | 10,898 | 2,000(cap) | 44.7% |
| E5-L-lock | 2cpu | ReentrantLock | **1,993.9** | 1,155.6 | 51.4ms | 1,589ms | 6,750 | 2,000(cap) | 278.9% |

- pinned 스택 증거: `results/E5-M-sync/pinned-traces.log` — `VirtualThread[#50,tomcat-handler-6]/runnable@ForkJoinPool-1-worker-1 reason:MONITOR` … `pinSync(ExperimentController.java:82) <== monitors:1`
- raw: `results/E5-*/` · Grafana `testid=E5-*`

## 해석
1. **pinning 재현 성공**: 같은 50ms sleep인데 `synchronized`는 37.5rps, `ReentrantLock`은 1,541~1,994rps — **41~53배 차이**. p50도 26초 vs 52ms. 락 한 줄 차이로 시스템이 죽고 산다.
2. lock 버전의 51~52ms p50 = sleep 50ms + 오버헤드 1~2ms — VT unmount가 실제로 일어남(E2와 동일 패턴). M-lock 피크 1,541은 CPU 천장, L-lock은 부하 목표 2,000 소화.
3. **가설 절반 기각**: pinned 천장이 캐리어 수에 비례하지 않았다 — M(1cpu)과 L(2cpu)이 37.5/37.6rps로 사실상 동일. 예측(20/40) 빗나감 → [P-003](../problems/P-003-pinned-ceiling-not-scaling.md) (open).
4. `tracePinnedThreads` 출력이 런당 1~2회뿐(요청 ~5,300건 전부 pinned인데) — 동일 스택은 1회만 출력되는 것으로 관찰(가설: dedup). 증거 수집엔 충분하나 "pinning 빈도 측정" 용도로는 부적합.
5. JDK 24(JEP 491)는 synchronized pinning을 제거 — 이 실험은 JDK 21의 현상 기록. 그래도 Object.wait() 등 pinning 경로는 남으므로 교훈(락 안에서 블로킹 금지) 유효.

## 문제 / 배운 것
- [P-003](../problems/P-003-pinned-ceiling-not-scaling.md): pinned 천장 37.5rps가 CPU 수와 무관 — 미해명, open.
- docker stats CPU%가 제한(100%/200%)을 초과 표기(142.8%, 278.9%) — 샘플링 구간 버스트로 보임. 절대값보단 추세 지표로 쓸 것.

## 다음 액션
- P-003 원인 탐색(스케줄러 parallelism 명시 설정, jcmd Thread.dump로 캐리어 관찰)
- 2주차 A 트랙 목표(E2·E3·E5) 완료 → 다음: E10(GC) 또는 B의 도메인 대기 후 E4
