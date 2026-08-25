---
title: "P-003 pinned 천장이 캐리어 수(CPU)와 무관하게 ~37.5rps"
date: 2026-08-24
status: solved
tags: [problem, virtual-thread, pinning, scheduler]
related: [../experiments/E5-pinning.md, ../journal/2026-08-25-p003-pinned-ceiling.md, ../concepts/vt-carrier-pool-and-pinning.md]
---

## 증상
- E5에서 `synchronized`+sleep(50ms) pinned 천장이 M(1cpu) 37.5rps, L(2cpu) 37.6rps로 **동일**.
- 예측은 "캐리어 수/0.05s" = 20/40rps — 스케일 방향 자체가 안 맞음.

## 재현
- `scripts/run-experiment.sh -n E5-<P>-sync -p M|L -v on --java-opts "-Djdk.tracePinnedThreads=full" --env PIN_MODE=sync -s load/22-pin.js`
- 실측: `results/E5-M-sync/`, `results/E5-L-sync/` (피크30s 37.5/37.6)

## 원인 (2026-08-25 규명)
**천장 공식은 맞았고, 캐리어 수 가정이 틀렸다.** 실효 캐리어 수 ≠ CPU 수(parallelism).

1. pinned 천장 = 실효 캐리어 수 × 1/(sleep 50ms + 오버헤드 ~3ms) ≈ 캐리어당 18.9rps.
2. VT 스케줄러(FJP)는 parallelism(기본 availableProcessors)을 **초과해 워커를 만들 수 있다**(managed blocking 보상, maxPoolSize 기본 256). pinned park 자체는 보상을 만들지 않지만, 그 외 블로킹(소켓 write 등)은 만든다.
3. **M(1cpu): 부팅 직후부터 캐리어 2개**(parallelism 1 + 보상 1, 부하 전 idle에서 관찰) → 2×18.9 ≈ 37.7rps. **L(2cpu): 캐리어 2개**(parallelism 2 + 0) → 동일 37.6rps.
4. 즉 M/L 동일은 스케일 법칙이 아니라 **1+1 = 2+0의 우연**. cgroup 오인식 아님(`availableProcessors=1` 정상 인식).

## 검증 (M 프로파일 4런 + 실효값 직접 관찰, `/api/env` 엔드포인트 신설)
| 런 | 설정 | 캐리어(관찰) | 평균 rps | p50 | 판정 |
|---|---|---|---|---|---|
| `P003-M-sync-base` | 기본 | **2** (부팅~부하 내내) | 35.5 | 26.6s | E5-M-sync 재현 |
| `P003-M-sync-par4` | `parallelism=4` | **4** | 67.0 | 10.6s | 천장이 parallelism 따라 이동(~2×) |
| `P003-M-sync-notrace` | trace 플래그 제거 | 2 | 35.5 | 25.6s | 가설 3(trace 직렬화) 기각 |
| `P003-M-sync-maxpool1` | `maxPoolSize=1` | **1** | 23.1 (48% 타임아웃) | 56.8s | 초과 성장 차단 → 천장 붕괴. 보상분이 M의 2번째 캐리어임을 확정 |

- 보상 성장 실관찰: notrace 런에서 k6 종료 ~10s 뒤 캐리어 2→**3** 증가 후 idle 유지 — gracefulStop이 끊은 874건의 서버측 잔여 처리 중 비-pinned 블로킹이 보상 +1을 만든 정황.
- 부팅 시 +1의 정확한 트리거(어떤 블로킹인지)는 미확인 — 가설: Spring 부팅 중 managed blocking. L에서 +1이 없는 것은 캐리어 2개라 보상 조건(전원 busy)이 덜 성립하기 때문으로 추정.
- 상세 타임라인·명령: [journal](../journal/2026-08-25-p003-pinned-ceiling.md) · raw: `results/P003-*/`(env-samples.log 포함)

## 해석 / 배운 것
- 가설 1(보상 정책이 유효 동시성 결정) **부분 채택**: 기본은 parallelism, 보상으로 +α(비결정적). 가설 2(cgroup) **기각**. 가설 3(trace 부하) **기각**.
- pinned 상황의 실질 천장은 "CPU 수"가 아니라 "그 순간의 실효 캐리어 수" — 보상 스레드 유무로 런마다 다를 수 있는 값. 예측 공식에 쓰려면 부하 직전 실효값을 관찰해야 한다.
- 개념 정리: [VT 캐리어 풀과 pinning 천장](../concepts/vt-carrier-pool-and-pinning.md)

## 재발 방지
- `/api/env`(availableProcessors·scheduler 프로퍼티·캐리어 목록)를 상시 관찰 수단으로 유지 — 이후 실험(E10~E12)에서도 실효값 확인 후 해석.
- "이론 천장" 계산은 반드시 실효 캐리어 수 관찰과 함께 기록.
