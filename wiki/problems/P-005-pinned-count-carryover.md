---
title: "P-005 lock 런 pinned-count가 직전 sync 런 잔재로 오염"
date: 2026-08-25
status: solved
tags: [problem, experiment-hygiene, pinning, docker]
related: [../experiments/E5-pinning.md, ../journal/2026-08-24-E5-pinning.md]
---

## 증상
- `results/E5-M-lock/pinned-count.txt`=1, `results/E5-L-lock/pinned-count.txt`=2 — ReentrantLock 런에서 pinning이 있었다는 기록이 커밋됨.
- 실험 결론("락 교체 → pinning 없음, 41~53배 회복")과 정면 모순. PR #1 셀프 리뷰에서 발견.

## 재현 / 확인 (2026-08-25)
- 런 순서(meta.env date): M-sync 11:29:21 → M-lock 11:32:36 → L-sync 11:35:31 → L-lock 11:38:42. 전부 `--no-build`.
- sync→lock 전환은 compose env 불변(PIN_MODE는 k6 쪽 변수) → `docker compose up -d`가 앱 컨테이너를 재생성하지 않음 → 로그에 직전 런 trace 잔존.
- 검증 명령·출력:
```
$ docker inspect -f '{{.Created}}' heavy-coupon-api-1
2026-08-24T02:35:31Z        # = L-sync 시작(11:35:31 KST). 이후 재생성 없음 → L-lock도 같은 컨테이너
$ docker logs -t heavy-coupon-api-1 2>&1 | grep -n 'reason:MONITOR'
54:2026-08-24T02:35:40.581457263Z VirtualThread[#53,tomcat-handler-6]... reason:MONITOR
107:2026-08-24T02:38:20.475130420Z VirtualThread[#5689,tomcat-handler-5642]... reason:MONITOR
```
- 두 trace 모두 L-sync 구간(11:35:40 = 런 초입, 11:38:20 = gracefulStop 드레인, L-lock 시작 11:38:42 이전).
- → **L-lock 런 구간 pinned 0건 확정.** 카운트 2 = L-sync trace 2건 전부.
- M-lock=1도 M-sync trace 수(pinned-traces.log 1건)와 일치 — 동일 방식 잔재로 추정(가설 아닌 정황: M 컨테이너는 L 프로파일 전환 때 재생성돼 로그 소실, 직접 검증 불가).

## 원인
- pinned 카운트를 timestamp 필터 없이 컨테이너 로그 전체에서 셌고, 컨테이너가 런 사이에 재생성되지 않아 이전 런 로그가 포함됨. 추출 명령을 journal에 기록하지 않아 발견·규명이 늦어짐.

## 해결
- `pinned-count.txt` 정정: L-lock 0(확정), M-lock 0(추정 표기). 파일에 정정 사유 주석.
- [E5](../experiments/E5-pinning.md) 증거 항목과 journal에 정정 기록.

## 재발 방지
- 수동 로그 추출 시 명령을 journal에 즉시, 그대로 기록(CLAUDE.md 규칙 재확인).
- 로그 카운트는 `docker logs -t` + 런 시작 timestamp 필터로만. 다음 pinning 실험 때 run-experiment.sh에 pinned 로그 자동 수집(k6 전후 로그 diff) 추가 검토.
