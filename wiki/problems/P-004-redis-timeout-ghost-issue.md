---
title: "P-004 Redis 응답 지연 시 ghost 발급 — tryIssue timeout 미보상"
date: 2026-09-01
status: solved
tags: [problem, e8, redis, ghost, timeout, correctness]
related: [../decisions/D-008-redis-resilience.md, ../experiments/E8-redis-resilience.md, ../journal/2026-09-01-E8-redis-resilience.md]
---

## 증상
redis 전략에서 발급 Lua(재고 DECR + 발급자 SADD)가 서버에서 **실행됐는데** 응답이 command timeout을 넘겨 도착하면, 앱은 `QueryTimeoutException`을 받고 DB INSERT도 보상도 하지 않는다. 결과: **재고는 깎이고 명단(issued set)엔 있는데 DB `coupon_issue`엔 없는 "ghost 발급"**. 같은 userId 재시도는 Lua 첫 줄 `SISMEMBER`가 -1(ALREADY_ISSUED)을 반환해 **409로 영구 잠긴다**.

리뷰(외부)에서 지적받아 재현·검증.

## 재현 (확정)
`docker-compose.sentinel.yml` 아님 — toxiproxy로 Redis **응답만** 지연:
```bash
# 앱을 toxiproxy 경유로 + downstream(서버→클라) latency > command timeout 주입
curl -XPOST localhost:8474/proxies -d '{"name":"redis","listen":"0.0.0.0:6380","upstream":"redis:6379"}'
REDIS_HOST=toxiproxy REDIS_PORT=6380 docker compose up -d --force-recreate coupon-api
curl -XPOST localhost:8474/proxies/redis/toxics -d '{"type":"latency","stream":"downstream","attributes":{"latency":3500}}'
# 헤더 없이 발급 (timeout 3s)
curl -XPOST .../coupons/{id}/issue -d '{"userId":8888}'   # → 503, 3.05s
# 결과: SISMEMBER issued 8888 = 1, stock = 9(초기10), DB row = 0  → GHOST 확정
```
`coupon_issue_ambiguous_total` 카운터도 1 증가.

## 원인
`RedisIssueStrategy.issue()`가 `tryIssue`(Lua) 예외를 잡지 않는다. 보상(compensate)은 **DB INSERT 실패**에만 걸리고, tryIssue가 던지면 그 전에 예외가 나가 보상 경로에 못 들어간다. 클래스 javadoc이 이 갭을 "프로세스가 죽으면"으로만 문서화했는데, **timeout도 같은 갭을 만든다**.

## 중요 뉘앙스 (재현으로 밝혀진 것)
- **트리거는 "응답 지연"이지 "정지(pause)"가 아니다.** `docker compose pause`/`CLIENT PAUSE`(실행 지연)는 명령이 큐 대기 상태에서 timeout에 **취소**돼 서버가 실행 안 함 → ghost 없음(실측 2회 ghost=0). 서버가 **실행을 마친 뒤 응답만 늦은** 경우에만 ghost. (실행 중간에 pause가 맞으면 이론상 가능하나 마이크로초 창이라 드묾.)
- **멱등성 헤더가 부분 방어.** 헤더 있으면 claim(SET NX)이 첫 Redis 명령이라 그게 먼저 timeout → tryIssue Lua가 아예 안 돎 → 재고 ghost 없음(단 claim ghost는 lockTtl 30s로 자가치유). 그래서 헤더를 보내는 `55/56` 부하는 재고 ghost가 안 보인다. **재고 ghost는 헤더 없는 /issue + 느린 응답**에서 남.
- pre-existing 갭이지만 **command timeout을 60s→1s로 줄이며 빈도↑**(응답>timeout이 더 자주). → 3s로 완화(D-008).

## timeout 값 (측정으로 근거 확보 → 1s 유지)
리뷰 지적("1s 근거 없음")에 잠정 3s로 올렸다가, **실측 후 1s로 복귀**:
- 1차(앱 부하 55 storm)는 Redis를 안 밀어(CPU 0.71%) 1~5ms만 — **혼잡 아니었음**. `redis-benchmark`로 **0.5 CPU 포화**(50k+ ops/s) 재측정: p50 0.2ms·p95 0.7ms·**p99 ~73ms·max ~85ms**. p99 꼬리 = 0.5 CPU cgroup throttle(주기 quota 소진 시 ~50ms freeze) 특성.
- → **1s = 혼잡 max(85ms)의 ~12배 헤드룸** = "1초 넘으면 throttle 포함 정상 지연 아니라 확실히 멈춘 것" → 1s 정당. ghost는 값과 무관하게 heal이 처리하므로 늘릴 이유 없음(fast-fail 유리).
- `coupon_issue_ambiguous` **카운터 + warn 로그** — QueryTimeoutException 관측(발동 검증됨).

## 근본 수정 — retry self-heal (이 PR, sync)
`RedisIssueStrategy`가 Lua -1(명단에 있음)을 받으면 `healGhostOrReject`: **DB에 발급 이력 없으면 = ghost → 그 자리에서 DB 기록해 치유(201)**. 재고는 ghost 시점에 이미 소비돼 다시 안 깎음(1 소비 = 1 발급 정합). 동시 치유 경합은 unique 제약이 잡음.
- **왜 sync heal인가**: timeout 시 Lua 실행 여부를 앱이 알 수 없어 sync 보상(INCR)은 위험(안 됐는데 늘리면 유령 재고). 반면 heal은 **재시도 시점의 실제 상태(명단 있음 + DB 없음 = 확정 ghost)**를 보고 forward-complete하므로 안전.
- **실증**: toxiproxy 응답지연으로 ghost 유발 → 재시도 → 예전 409(영구잠김) 대신 **201(치유)**, DB row 생성, 재고 정합. 회귀 테스트 `CouponApiContractTest.재고는_깎였는데_DB없는_ghost는_재시도에_치유된다`.
- **커버 범위**: 재시도하는 사용자(503 받고 재시도 = 대부분)의 잠김·유실을 즉시 해소.

## 잔여 + async 후속 (별도 PR)
- **never-retry 런타임 ghost**: 503 받고 재시도 안 하는 사용자 → 명단·재고만 소비되고 방치. 기동 조정(PR#4, ApplicationReadyEvent)이 forward-recover하나 재시작 전까지 잔존.
- **async 조정 부활**(리뷰어안): 발급자 set을 **ZSET(시각)**으로 → "유예 10s 초과분만" 주기 조정 → PR#4가 막은 in-flight 경합 없이 런타임에 잔여 ghost 정리. 다음 세션.
- 대안: outbox(E8-5).
