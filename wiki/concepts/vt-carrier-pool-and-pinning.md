---
title: "VT 캐리어 풀과 pinning 천장"
date: 2026-08-25
status: solid
tags: [concept, virtual-thread, pinning, scheduler, forkjoinpool]
related: [../experiments/E5-pinning.md, ../problems/P-003-pinned-ceiling-not-scaling.md]
---

## 한 줄
Virtual Thread는 ForkJoinPool 캐리어(플랫폼 스레드) 위에서 실행되며, pinning되면 그 캐리어를 통째로 점유한다 — pinned 워크로드의 처리량 천장은 "실효 캐리어 수 × 1/블로킹시간"이고, 실효 캐리어 수는 CPU 수와 다를 수 있다.

## 구조 (JDK 21)
- **캐리어 풀** = 전용 ForkJoinPool. 워커 이름 `ForkJoinPool-1-worker-N`(플랫폼 스레드라 `Thread.getAllStackTraces()`로 셀 수 있다 — `/api/env` 구현 방식).
- **parallelism** 기본 = `Runtime.availableProcessors()` (cgroup 인식: --cpus=1 컨테이너에서 1 실측). `-Djdk.virtualThreadScheduler.parallelism`으로 변경 가능.
- **maxPoolSize** 기본 256. FJP는 워커가 managed blocking으로 막히면 **보상(compensation) 워커를 만들어 parallelism을 초과 성장**할 수 있다. `-Djdk.virtualThreadScheduler.maxPoolSize`로 상한 제어.
- **pinning**: `synchronized` 블록/네이티브 프레임 안에서 블로킹하면 VT가 unmount 못 하고 캐리어에 고정(`reason:MONITOR`). **pinned park는 보상을 만들지 않는다** — 부하 내내 캐리어 수가 안 늘어나는 것을 실측(P-003 base 런). 반면 pinned가 아닌 블로킹(소켓 write 등)은 보상을 만든다(k6 종료 직후 2→3 증가 실측).

## 천장 계산 (실측 검증)
```
pinned 천장 rps ≈ 실효 캐리어 수 × 1/(블로킹시간 + 오버헤드)
```
- E5/P-003 실측(50ms sleep, 오버헤드 ~3ms → 캐리어당 ~18.9rps):
  - 캐리어 2(M 기본: parallelism 1 + 부팅 중 보상 1) → 37.5rps
  - 캐리어 2(L 기본: parallelism 2 + 0) → 37.6rps ← M/L 동일은 우연
  - 캐리어 4(parallelism=4 강제) → 평균 67rps
  - 캐리어 1(maxPoolSize=1 강제) → p50 56.8s, 48% 타임아웃 — 붕괴
- 함정: "캐리어 수 = CPU 수"로 예측하면 틀린다. 보상 워커가 비결정적으로 +α — 예측하려면 부하 직전 실효값을 직접 관찰.

## 면접 답변용
- **Q. VT에서 synchronized가 왜 위험한가?** 블록 안에서 블로킹하면 캐리어가 pinning돼 풀 전체가 그 수만큼 줄어든 것처럼 동작한다. 50ms 블로킹 × 캐리어 2개면 시스템 전체가 ~37rps로 붕괴(같은 코드를 ReentrantLock으로 바꾸면 VT가 unmount돼 ~2,000rps). 발견은 `-Djdk.tracePinnedThreads=full`(단 출력이 드물어 빈도 측정엔 부적합), 근본 해결은 락 안에서 블로킹 제거 또는 j.u.c 락 사용. JDK 24(JEP 491)는 synchronized pinning 자체를 제거.
- **Q. pinned 천장이 왜 CPU 수와 안 맞았나?** 실효 캐리어 수가 parallelism과 달랐기 때문(FJP 보상 성장). parallelism 조작·maxPoolSize=1 실험으로 천장이 캐리어 수를 정확히 따라가는 것을 검증했다.
