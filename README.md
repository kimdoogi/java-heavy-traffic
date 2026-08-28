# java-heavy-traffic

Java 21 **Virtual Thread** 기반 **선착순 쿠폰 발급** 서비스를 **리소스 제한(CPU/메모리)** 하에서 부하 테스트하며,
발급 전략별 정합성·처리량 한계와 병목을 **수치로** 기록하는 학습/포트폴리오 프로젝트.

- 계획: [PLAN.md](PLAN.md) · 기록: [wiki/](wiki/index.md) (journal · 문제→해결 · ADR · 실험 · 개념)

## 결과 하이라이트 — E6 선착순 정합성 (발급 전략 4종)

쿠폰 1,000개에 동시 발급 부하. 전략은 `coupon.issue.strategy`로 교체하고, 정합성은 `count(coupon_issue) <= total`로 검증.
(상세·raw: [E6](wiki/experiments/E6-flash-sale-consistency.md) · [크로스오버/수직](wiki/experiments/E6-crossover-concurrency.md))

| 전략 | 초과발급 | 특징 (실측) |
|---|---|---|
| `none` (락 없음) | **발생** (동시성↑ 시 전원 발급) | 무방비 — "왜 동시성 제어가 필요한가"의 재현 |
| `db-pessimistic` | 0 | `SELECT FOR UPDATE`. 단순·정합성 OK |
| `db-optimistic` | 0 | `@Version`+재시도. 고경합 시 **재시도 폭증 → 72% 503** |
| `redis` | 0 | Lua 원자 차감. **DB hot-row 제거** |

**전략 선택은 배포 규모의 함수 (측정으로 특정):**
- **1cpu(단일 노드, CPU-bound)**: `pessimistic ≈ redis` 동률 → 단순한 **pessimistic**로 충분.
- **2cpu+(수직) / scale-out(수평)**: CPU 벽이 걷히면 `pessimistic`이 **DB hot-row 락 + 커넥션 풀**에 막혀 정체·타임아웃(500). `redis`는 계속 스케일 → **VUS=2000·2cpu에서 redis 3,007 vs pessimistic 1,327 rps (2.3배)**.

→ 실제 선착순(고경합·스케일)에는 **redis**. 단일 소형 노드면 pessimistic도 정답. "선착순=redis" 통념을 **언제부터 참인지(≥2cpu)** 데이터로 확정.

### Redis 전략의 제품화
- **잔여 수량** = `total − count(coupon_issue)` — redis는 DB `remaining`을 갱신하지 않아(hot-row 제거) stale하므로 발급 원장으로 계산.
- **크래시 갭 보정**: Lua 성공(재고 DECR + 발급자 SADD) ~ DB INSERT 커밋 사이 크래시로 "redis엔 발급, DB엔 없음"이 생기면, **기동 시 1회 조정**(reconciliation, `ApplicationReadyEvent`)이 redis 발급자 set과 DB `coupon_issue`를 대조해 누락분을 DB로 전진 복구(멱등). (주기 실행은 살아있는 in-flight 발급과 경합해 초과 발급을 유발 → 기동 직후 in-flight 없는 시점 1회로. 멀티노드는 유예-주기 E12.)
- 복구 경로·알려진 한계: [redis-atomic-stock](wiki/concepts/redis-atomic-stock.md).

## 구성
```
coupon-api/      Spring Boot 4 / Java 21 / MVC + VT 토글 — 쿠폰 도메인 + 발급 전략 4종 + 조정
mock-external/   느린 외부 API 흉내 (지연·실패·hang 런타임 주입)
load/            k6 시나리오 (50-flash-sale.js = 선착순 발급 버스트)
monitoring/      Prometheus · Grafana 대시보드 · Toxiproxy
scripts/         build.sh · run-experiment.sh · verify-coupon.sh · reset-db.sh
results/         실험별 raw (summary.json · docker-stats.csv · meta.env)
```

## 빠른 시작
```bash
# 빌드 + 전체 기동 (postgres · redis · mock-external · toxiproxy · coupon-api · prometheus · grafana)
scripts/build.sh && docker compose up -d

# 쿠폰 생성 → 발급 → 조회
curl -s -XPOST localhost:8080/api/coupons -H 'content-type: application/json' -d '{"name":"쿠폰","totalQuantity":100}'
curl -s -XPOST localhost:8080/api/coupons/1/issue -H 'content-type: application/json' -d '{"userId":1}'
curl -s localhost:8080/api/coupons/1        # 남은 수량 = total − 발급수

# E6 선착순 실험 (전략 교체 + 정합성 검증)
scripts/reset-db.sh
VUS=50 ITERS=5000 scripts/run-experiment.sh -n E6-redis --strategy redis -p M -s load/50-flash-sale.js
scripts/verify-coupon.sh 1                   # count(coupon_issue) <= total 확인
```
- Grafana: http://localhost:3000/d/heavy-traffic (익명 Admin) · Prometheus: http://localhost:9090
- 결과: `results/<name>/summary.md`

## 리소스 프로파일
| 프로파일 | CPU | 메모리 | -Xmx |
|---|---|---|---|
| S | 0.5 | 512m | 256m |
| M | 1 | 1g | 512m |
| L | 2 | 2g | 1g |

## 주요 엔드포인트
| 경로 | 목적 |
|---|---|
| `POST /api/coupons` | 캠페인 생성 (`{name, totalQuantity}`) |
| `GET /api/coupons/{id}` | 조회 (남은 수량 = total − 발급수) |
| `POST /api/coupons/{id}/issue` | **선착순 발급** (`{userId}`) — 201 / 409 sold_out·already / 503 retry_exhausted / 404 |
| `POST /api/coupons/{id}/issue-and-notify` | 발급 + 외부 알림 (E8) |
| `GET /api/users/{userId}/coupon-issues` | 사용자 발급 내역 |
| `GET /api/ping` · `/io/sleep` · `/cpu/hash` · `/pin/{sync,lock}` | VT 실험용 (E1~E5) |

## 실험 (전체)
E1 베이스라인 · E2 I/O(VT vs 플랫폼, 천장 666→VT 2,000rps) · E3 CPU · E5 pinning(41~53배) · **E6 선착순 정합성** —
매트릭스·로드맵은 [PLAN §4.3](PLAN.md), 실행 기록은 [wiki](wiki/index.md).
