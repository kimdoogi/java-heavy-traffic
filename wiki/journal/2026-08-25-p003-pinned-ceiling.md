---
title: "2026-08-25 P-003 — pinned 천장 원인 탐색"
date: 2026-08-25
status: done
tags: [journal, P-003, virtual-thread, pinning, scheduler]
related: [../problems/P-003-pinned-ceiling-not-scaling.md, ../experiments/E5-pinning.md]
---

## 목표
- [P-003](../problems/P-003-pinned-ceiling-not-scaling.md): sync pinned 천장이 M(1cpu)/L(2cpu) 동일 ~37.5rps인 원인 규명.
- 가설 2(availableProcessors/parallelism 오인식)를 실효값으로 직접 확인, 부하 중 캐리어 스레드 수 직접 관찰, parallelism 조작으로 천장이 움직이는지 실측.

## 범위 / 하지 않는 것
- M 프로파일 중심 3런: base 재현+관찰 → `-Djdk.virtualThreadScheduler.parallelism=4` → tracePinnedThreads 제거(가설 3). 필요 시 L 추가.
- JDK 24(JEP 491) 비교는 안 함. 브랜치 `a/p003-pinned-ceiling`(E5 코드 위에서 시작 — PR #1 merge 대기와 무관하게 진행).

## 진행 기록 (시간순)
- 사전 확인: `pinSync`의 스트라이프 키는 `seq.incrementAndGet() % 4096` 순차 증가 — 락 경합 가설은 코드상 배제.
- `/api/env` 관찰 엔드포인트 추가: availableProcessors, `jdk.virtualThreadScheduler.parallelism/maxPoolSize` 프로퍼티, 플랫폼 스레드 중 `ForkJoinPool-1-worker-*`(캐리어) 목록. (jcmd는 `eclipse-temurin:21-jre` 이미지에 없음 — /api/env로 대체)
- R1 시작: `scripts/run-experiment.sh -n P003-M-sync-base -p M -v on --no-build --java-opts "-Djdk.tracePinnedThreads=full" --env PIN_MODE=sync -s load/22-pin.js` (직전 `scripts/build.sh` 완료 후라 --no-build)
- **부하 전 idle 관찰 (M, 1cpu)**: `curl -s http://localhost:8080/api/env` →
  `{"carrierCount":2,"carriers":["ForkJoinPool-1-worker-1","ForkJoinPool-1-worker-2"],"availableProcessors":1,...}`
  - **availableProcessors=1 — cgroup 정상 인식, 가설 2 기각.**
  - **parallelism 기본(=1)인데 idle 캐리어가 이미 2개** — 천장 37.5rps ≈ 2캐리어/0.053s와 부합. 이 +1이 어디서 왔는지가 다음 질문(가설: FJP 보상 스레드가 부팅 중 생성 후 잔존).
- 부하 중 샘플링(수동 명령 기록): `( while true; do out=$(curl -s --max-time 90 http://localhost:8080/api/env); echo "$(date +%H:%M:%S) $out"; sleep 10; done ) > results/P003-M-sync-base/env-samples.log`

- **R1 완료**: 35.5rps·p50 26.6s — E5-M-sync(35.3) 재현. `env-samples.log`: 부하 전~부하 내내 **carrierCount=2 고정**(worker-1, worker-2). 증가도 감소도 없음.
  - 천장 산식 성립: 2캐리어 × 1/0.053s ≈ 37.7rps ≈ 실측 37.5. **천장의 직접 원인 = 캐리어 2개 확정.**
  - pinned park가 보상 스레드를 추가로 만들지 않는 것도 확인(부하 중 3개 이상으로 안 늘어남 — maxPoolSize 256인데도).
  - 남은 질문: parallelism=1(availableProcessors=1)인데 왜 캐리어가 2개인가? L(parallelism=2)도 2개라 "parallelism+1"인지 "최소 2"인지 R2로 구분.

- **R2 완료** (`-Djdk.virtualThreadScheduler.parallelism=4`): 평균 67.0rps(base 35.5의 1.89배), p50 10.6s, http_reqs 11,829. **carrierCount=4** (worker-1~4, 5개 아님).
  - **천장이 parallelism을 따라 이동 — 병목은 스케줄러 캐리어 수로 확정.** 포화 이론치 4/0.053≈75.5rps, 평균비 1.89는 램프 초반(양쪽 다 소화) 희석 감안 시 2.0×와 부합.
  - "parallelism+1" 패턴 기각(p=4에서 5개 안 됨). 현재 패턴: p=1→2, p=2→2, p=4→4 = **max(2, parallelism) 꼴**.
  - 샘플링 주의: R2 샘플은 wait 루프가 컨테이너 재생성과 레이스해 k6 종료 후(idle)부터만 기록 — 부하 중 수치는 처리량 비로 보강. R3에선 wait 없이 연속 샘플링.
- R3 시작(tracePinnedThreads 제거, 가설 3): `scripts/run-experiment.sh -n P003-M-sync-notrace -p M -v on --no-build --env PIN_MODE=sync -s load/22-pin.js` + 동일 샘플링(부팅 직후~부하~종료 후 idle 3분까지 — p=1의 2번째 캐리어가 부팅 시 생겼다 idle에 죽는지 관찰).

- **R3 완료** (tracePinnedThreads 제거): 평균 35.5rps·p50 25.6s — base(35.5)와 동일. **가설 3(trace 플래그 직렬화) 기각.**
- R3 연속 샘플(부팅→부하→종료 후 idle): 부팅 직후부터 캐리어 **2** 고정(부하 전 idle 포함 — 부하가 만든 게 아니라 부팅 시점부터 존재), 부하 내내 2, **k6 종료 ~10s 뒤 3으로 증가 후 idle에서도 유지**(17:59:56~18:02:46).
  - 해석(가설): FJP는 managed blocking 보상으로 parallelism 초과 성장 가능(maxPoolSize 기본 256). k6 gracefulStop에서 클라이언트가 끊은 요청 874건의 서버측 잔여 처리(닫힌 소켓 write 등 비-pinned 블로킹) 중 보상 +1 발생. 부팅 중 +1(worker-2)도 같은 메커니즘 추정.
- R4 시작(확정 실험): `scripts/run-experiment.sh -n P003-M-sync-maxpool1 -p M -v on --no-build --java-opts "-Djdk.virtualThreadScheduler.maxPoolSize=1" --env PIN_MODE=sync -s load/22-pin.js` — 예측: 부팅 캐리어 1, 천장 ~20rps(=1/0.053). 맞으면 "초과 성장분이 M의 2번째 캐리어" 확정.

- **R4 완료** (`maxPoolSize=1`): 부팅 캐리어 **1개**, 평균 23.1rps에 **http_req_failed 48%**(k6 60s 타임아웃), p50 56.8s — 천장 붕괴(캐리어 1개 이론치 ~19rps 수준). k6 exit 99(threshold 실패)로 종료 — 의도된 결과.
  - **확정: M 기본 런의 2번째 캐리어 = parallelism 초과 성장(보상)분.** maxPoolSize로 막으니 1개만 남음.

## 결과
- **P-003 원인 규명 완료 → solved.** pinned 천장 = 실효 캐리어 수 × 1/(50ms+~3ms). M/L 동일 37.5rps는 M=1(parallelism)+1(부팅 중 보상), L=2+0으로 **둘 다 실효 캐리어 2개였던 우연**. 상세: [P-003](../problems/P-003-pinned-ceiling-not-scaling.md)
- 가설 판정: 가설1(보상 정책) 부분 채택 · 가설2(cgroup 오인식) 기각(availableProcessors=1 정상) · 가설3(trace 직렬화) 기각(notrace 동일 35.5rps).
- 산출물: `/api/env` 관찰 엔드포인트(ExperimentController), 검증 4런 `results/P003-M-sync-{base,par4,notrace,maxpool1}/`(env-samples.log 포함), [concepts/vt-carrier-pool-and-pinning](../concepts/vt-carrier-pool-and-pinning.md)

## 배운 것 / 결정
- FJP 캐리어 풀은 parallelism을 초과 성장한다(managed blocking 보상, maxPoolSize 256) — 단 pinned park는 보상을 안 만든다. "이론 천장 = CPU 수 기반" 예측은 실효 캐리어 관찰 없이는 틀릴 수 있다.
- 관찰 도구가 결론을 만든다: /api/env 30분 작업이 4런 만에 원인 확정. jcmd 없는 JRE 이미지에서 `Thread.getAllStackTraces()` 기반 엔드포인트가 유효한 대체.
- 미해명 잔여(가설로 기록): 부팅 중 +1 보상의 정확한 트리거, L에서 +1이 안 생기는 이유.

## 남은 일 / 다음 단계
- [x] /api/env 추가 + 빌드
- [x] R1~R4 + P-003 갱신(solved) + concept 페이지
- [ ] 커밋 + push (PR은 PR #1 merge 후 생성 — 브랜치가 a/e5-pinning 위에 쌓임)
- [ ] 다음: E10(GC) 또는 B의 도메인 완성 대기 후 E4
