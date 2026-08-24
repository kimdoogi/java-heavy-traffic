---
title: "P-003 pinned 천장이 캐리어 수(CPU)와 무관하게 ~37.5rps"
date: 2026-08-24
status: open
tags: [problem, virtual-thread, pinning, scheduler]
related: [../experiments/E5-pinning.md]
---

## 증상
- E5에서 `synchronized`+sleep(50ms) pinned 천장이 M(1cpu) 37.5rps, L(2cpu) 37.6rps로 **동일**.
- 예측은 "캐리어 수/0.05s" = 20/40rps — 스케일 방향 자체가 안 맞음.

## 재현
- `scripts/run-experiment.sh -n E5-<P>-sync -p M|L -v on --java-opts "-Djdk.tracePinnedThreads=full" --env PIN_MODE=sync -s load/22-pin.js`
- 실측: `results/E5-M-sync/`, `results/E5-L-sync/` (피크30s 37.5/37.6)

## 관찰 / 가설 (미검증)
- 37.5rps × 0.05s ≈ 동시 sleep 1.875개 — CPU 수와 무관한 상수처럼 보임.
- 가설 1: FJ 스케줄러가 pinned park 시 보상 스레드(spare)를 만들며, 유효 동시성이 "parallelism이 아니라 보상 정책"에 의해 결정된다.
- 가설 2: 컨테이너에서 `availableProcessors`가 기대와 다르다(cgroup 인식 문제) → parallelism이 M/L 동일할 수 있음.
- 가설 3: `tracePinnedThreads` 자체의 직렬화 영향(전역 락) — 단, 출력은 런당 1~2회뿐이라 약한 가설.

## 다음 시도
- [ ] 앱에서 `Runtime.availableProcessors()`·`jdk.virtualThreadScheduler.parallelism` 실효값 로그로 확인 (가설 2 먼저 — 제일 싸다)
- [ ] `--java-opts "-Djdk.virtualThreadScheduler.parallelism=4"`로 천장이 움직이는지
- [ ] tracePinnedThreads 빼고 재실행 (가설 3)
- [ ] 부하 중 `jcmd <pid> Thread.dump_to_file`로 캐리어/보상 스레드 수 직접 관찰
