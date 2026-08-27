---
title: "E6 크로스오버 — 동시성 계단(VUS 50→1000) pessimistic vs redis"
date: 2026-08-27
status: done
tags: [experiment, E6, coupon, concurrency, pessimistic, redis]
related: [E6-flash-sale-consistency.md, ../concepts/redis-atomic-stock.md, ../journal/2026-08-27-e6-flash-sale.md]
---

## 가설
E6 본실험(VUS=50)에서 pessimistic과 redis가 처리량 동률(520 vs 549rps)이었다. "redis의 hot-row 제거 우위는 **동시성이 커질수록** 벌어질 것"이라는 후속 가설을 **동시성(VUS)을 계단식으로 올려** 검증한다.

## 설정
| 항목 | 값 |
|---|---|
| 변수 | **VUS = 50 / 200 / 500 / 1000** (동시성) |
| 고정 | `POOL_SIZE=80` (DB `max_connections=100` 근처 = 현실적 상한), stock=1000, ITERS=5000, 프로파일 M(1cpu), VT on |
| 전략 | db-pessimistic, redis (E6에서 none·optimistic은 이미 탈락) |
| 실행 | `VUS=<V> ITERS=5000 scripts/run-experiment.sh -n E6x-<s>-v<V> --strategy <s> -p M --pool 80 -s load/50-flash-sale.js` |

## 결과 (1회 측정)
| VUS | pess RPS | redis RPS | pess p99 | redis p99 | 500(errs) | 초과발급 |
|---|---|---|---|---|---|---|
| 50 | 586.9 | 626.6 | 410ms | 480ms | 0 / 0 | 0 / 0 |
| 200 | 806.3 | 1,059.9 | 899ms | 683ms | 0 / 0 | 0 / 0 |
| 500 | **1,182.4** | 1,103.4 | 1,090ms | 1,613ms | 0 / 0 | 0 / 0 |
| 1000 | 1,423.9 | **1,594.3** | 1,850ms | **984ms** | 0 / 0 | 0 / 0 |

- raw: `results/E6x-{pess,redis}-v{50,200,500,1000}/`

## 해석
1. **둘 다 VUS=1000까지 깔끔하게 스케일업** — 처리량이 계속 상승, **500(커넥션 타임아웃)=0, 초과발급=0**. 어느 전략도 붕괴하지 않았다. (초기 E6에서 본 커넥션 파일업은 pool=20이 VU에 비해 너무 작았던 것 — pool=80에선 재현 안 됨.)
2. **가설 (거의) 기각 — redis의 명확한 처리량 우위가 안 나옴.** RPS는 사실상 동률이고(redis가 3/4 지점 근소 우세, 단 **VUS=500에선 pessimistic이 앞섬** = 단일 런 노이즈 수준), 깔끔한 크로스오버 곡선이 아니다.
3. **왜?** 병목이 **DB hot-row/커넥션이 아니라 앱 CPU**다. E6에서 전 전략 CPU 92~103%(1cpu 포화)였고, 여기서도 두 전략 모두 **같은 CPU 벽(~1,400~1,600rps @ VUS=1000)에서 수렴**한다. redis가 없앤 것(DB hot-row UPDATE)은 CPU가 먼저 saturate되면 이득이 드러나지 않는다.
4. **유일한 redis 신호 = 꼬리 지연**: VUS=1000에서 p99 984ms(redis) vs 1,850ms(pessimistic). 극한 동시성에서 redis가 DB 경합이 덜해 tail이 낫다. 하지만 처리량은 tied.

## 수직 확장 (L=2cpu) — 병목을 CPU에서 빼니 갈라짐
같은 부하를 프로파일 **L(2cpu)**로 재실행. CPU 여유가 생기자 두 전략이 **분기**한다.
| 프로파일 | 전략 | VUS | RPS | p99 | 500 |
|---|---|---|---|---|---|
| M(1cpu) | pess | 1000 | 1,424 | 1,850ms | 0 |
| M(1cpu) | redis | 1000 | 1,594 | 984ms | 0 |
| L(2cpu) | pess | 1000 | 1,121 | 3,002ms | **80** |
| L(2cpu) | redis | 1000 | 1,749 | 2,920ms | 0 |
| L(2cpu) | pess | 2000 | 1,327 | 3,331ms | **614** |
| L(2cpu) | redis | 2000 | **3,007** | 2,057ms | 0 |

(초과발급 전부 0. raw: `results/E6L-*/`)

- **redis는 CPU를 주면 스케일**: VUS=2000에서 1,749→3,007rps로 2cpu를 실제 활용.
- **pessimistic은 정체·악화**: ~1,100~1,300에 갇히고 **커넥션 타임아웃(500) 80→614건** 발생. CPU가 늘자 더 많은 요청이 동시에 들어와 **DB 쿠폰 행(FOR UPDATE)에 직렬화**되며 커넥션(pool=80, DB `max_connections=100`이 상한)을 물고 대기 → 풀 고갈. **pessimistic의 진짜 천장 = DB hot-row 락 + 커넥션 수**, 둘 다 앱 CPU로 안 커진다.
- **VUS=2000/L: redis 3,007 vs pessimistic 1,327 = 2.3배 + pessimistic 614건 실패.**
- → M(1cpu)의 "동률"은 **CPU가 병목을 가렸던 것**. CPU 벽을 걷으니 redis의 hot-row 제거 이점이 드러남.

## 배운 점 / 결정 (스케일 의존)
- **전략 선택 = 병목 위치의 함수.** 병목이 이동함을 실측:
  - **1cpu(CPU-bound)**: pessimistic ≈ redis 동률 → **pessimistic**(단순).
  - **2cpu+ (수직) / scale-out (수평)**: CPU 벽이 걷히면 **DB hot-row 락 + 커넥션 풀**이 pessimistic 천장(정체 + 500) → **redis 우위**(2cpu서 이미 2.3배).
- **결론**: 단일 CPU-bound 소형 노드 → pessimistic로 충분(redis는 오버엔지니어링). **실제로 스케일하는(2cpu+ / 여러 대) 선착순 → redis** — DB의 한 행이 벽이고 앱 자원으로 못 넘는다.
- **카고컬트 회피**: "선착순=redis" 통념을 맹종하지 않고, **언제부터 참인지(≥2cpu)**를 측정으로 특정. 면접 어필 포인트.

## 다음
- **수평(replicas 2-3 + nginx, E12)**: 여러 인스턴스가 한 DB 행 공유 → pessimistic은 대수 늘려도 그 행에 직렬화(정체), redis만 스케일 예상. E12 인프라(nginx+scale 오버레이) 미구축 — A 트랙과 조율.
- 각 지점 **2~3회 반복**(현재 1회, VUS=500 등 노이즈 있음).
