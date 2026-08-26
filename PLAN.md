# 계획서 — Java 21 Virtual Thread 고트래픽 실험 프로젝트

> 목적: 제한된 리소스(CPU/메모리)에서 Java 21 버츄얼 쓰레드 기반 서비스가 트래픽을 얼마나 버티는지 **수치로** 측정하고,
> 그 과정에서 대용량 트래픽 백엔드의 핵심 개념(동시성 제어, 커넥션 풀, 백프레셔, 관측성)을 학습한다.
> 결과물은 이직용 포트폴리오(코드 + 실험 리포트)로 활용한다.

---

## 0. 목표와 성공 기준

| 구분 | 내용 |
|---|---|
| 핵심 질문 | "CPU N개 / 메모리 M에서 이 서비스는 p99 X ms를 유지하며 초당 몇 건까지 처리하는가? 버츄얼 쓰레드는 그 한계를 얼마나 올리는가?" |
| 정량 목표 | ① 플랫폼 vs 버츄얼 쓰레드 성능 비교표 (리소스 프로파일 S/M/L 각각) ② 선착순 발급 정합성 100% (초과 발급 0건, 중복 발급 0건) ③ 리소스별 한계 RPS(Breakpoint) 표 ④ 병목 전이 과정 기록 (쓰레드 → 커넥션풀 → DB → CPU) |
| 산출물 | 소스코드, `docker-compose` 한 방 실행 환경, k6 시나리오, Grafana 대시보드, `results/` 실험 리포트, README(실험 요약) |
| 범위 밖 | 실제 결제/인증, 멀티테넌시, 프론트엔드, 클라우드 실배포(선택 단계로만) |

**해석 원칙**: 로컬(맥 + Docker Desktop)의 절대 수치는 클라우드와 다르다. 모든 실험은 **같은 조건에서의 상대 비교**와 **병목이 어디서 생기는지**에 초점을 둔다.

---

## 1. 도메인: 선착순 쿠폰 발급 서비스

버츄얼 쓰레드는 **I/O 대기가 많은 워크로드**에서만 효과가 난다. 따라서 "DB 쓰기 경합 + 느린 외부 API 호출"이 함께 있는 선착순 쿠폰 발급을 선택한다. (국내 백엔드 면접 단골 주제이기도 함)

### 1.1 기능 범위 (MVP)
- 쿠폰 캠페인 생성: 총 수량 N개
- **선착순 발급**: 사용자 1명당 1회, 수량 초과 불가 (핵심 트래픽 지점)
- 발급 후 외부 알림/결제 호출 (느린 mock 서버, 200~500ms)
- 조회: 쿠폰 잔여 수량, 사용자 발급 내역

### 1.2 실험용 엔드포인트 설계
실험 목적별로 엔드포인트를 분리해 "무엇 때문에 빨라지고 느려지는지"를 고립시킨다.

| 엔드포인트 | 성격 | 목적 |
|---|---|---|
| `GET /api/ping` | 프레임워크 오버헤드만 | 베이스라인 (서버 자체 한계) |
| `GET /api/io/sleep?ms=300` | 순수 I/O 대기 (`Thread.sleep`) | VT 효과를 외부 의존 없이 가장 깨끗하게 확인 |
| `GET /api/io/external` | mock 외부 서버 HTTP 호출 | 실제 다운스트림 대기 + 타임아웃/장애 실험 |
| `GET /api/cpu/hash?n=` | CPU bound 연산 | "VT는 CPU 작업엔 도움 안 됨" 확인 |
| `GET /api/coupons/{id}` | DB 단건 조회 | 읽기 부하 + 커넥션 풀 실험 |
| `POST /api/coupons/{id}/issue` | DB 쓰기 경합 | 선착순 정합성/락 전략 실험 |
| `POST /api/coupons/{id}/issue-and-notify` | 발급 + 외부 호출 | 복합 시나리오 (실전형) |
| `GET /api/pin/sync?ms=` | `synchronized` 안에서 I/O | VT **pinning** 문제 재현 |
| `POST /api/coupons` | 캠페인 생성 | 실험 세팅용 (k6 setup, §1.1 MVP) |
| `GET /api/users/{userId}/coupon-issues` | 사용자 발급 내역 조회 | §1.1 MVP 조회 기능 |

#### 1.2.1 쿠폰 API 계약 (v1 — 2026-08-26, 도메인 구현과 함께 확정)
> 이 절이 쿠폰 API 스펙의 기준이다. 변경 시 이 절을 먼저 수정하고 커밋 제목에 `contract:` 접두 (CLAUDE.md).

| 메서드/경로 | 요청 본문 | 성공 | 에러 |
|---|---|---|---|
| `POST /api/coupons` | `{name(≤100자), totalQuantity(>0)}` | 201 `{id, name, totalQuantity, remainingQuantity, createdAt}` | 400(검증 실패) |
| `GET /api/coupons/{id}` | — | 200 (생성과 동일 본문) | 404 `{error:"coupon_not_found"}` |
| `POST /api/coupons/{id}/issue` | `{userId(>0)}` | 201 `{result:"issued", couponId, userId, strategy}` | 409 `{error:"sold_out"\|"already_issued"}` · 503 `{error:"retry_exhausted"\|"storage_unavailable"}` · 404 `{error:"coupon_not_found"}` · 500 `{error:"internal_error"}` |
| `POST /api/coupons/{id}/issue-and-notify` | `{userId(>0)}` | 201 (issue 본문 + `notify`) | issue와 동일 + 504/502(알림 실패 — **발급은 이미 커밋됨**, E7·E8-5의 동기) |
| `GET /api/users/{userId}/coupon-issues` | — | 200 `[{couponId, issuedAt}]` | — |

- 발급 계열 에러 본문에는 `strategy` 필드가 포함된다(실효 전략 확인용). `retry_exhausted`는 db-optimistic 전용, `storage_unavailable`은 포화/저장소 장애(503) 공통.
- redis 전략은 coupon 행(hot row)을 갱신하지 않으므로 `GET /api/coupons/{id}`의 `remainingQuantity`가 stale할 수 있다. 정합성 검증은 `count(coupon_issue) <= total_quantity` 기준(verify-coupon.sh).

### 1.3 발급 전략 (설정으로 스위칭)
`coupon.issue.strategy=` 값으로 교체 가능하게 전략 패턴으로 구현한다.

| 전략 | 방식 | 기대되는 결과 |
|---|---|---|
| `none` | 조회 후 감소 (락 없음) | 초과 발급 **재현** (왜 동시성 제어가 필요한지 체감) |
| `db-pessimistic` | `SELECT ... FOR UPDATE` | 정합성 OK, 처리량 낮음, DB 락 대기가 병목 |
| `db-optimistic` | `@Version` + 재시도 | 경합 심하면 재시도 폭증 → 실패율 상승 |
| `redis` | `DECR`/Lua 스크립트로 원자적 차감 + 후속 DB 기록 | 처리량 최고, "Redis 먼저 DB 나중" 정합성 처리 학습 |

---

## 2. 아키텍처 & 로컬 ↔ 클라우드 매핑

> "SaaS/클라우드"는 AWS 같은 관리형 서비스(Fargate, RDS, ElastiCache, Grafana Cloud, k6 Cloud 등)로 해석한다.
> **로컬 실험의 핵심은 "클라우드 인스턴스 크기를 Docker 리소스 제한으로 흉내 내는 것"** 이다. 그래야 로컬 수치가 클라우드 판단의 참고치가 된다.

```
[k6 (호스트)] ──HTTP──▶ [nginx (선택, 수평확장 실험시)] ──▶ [coupon-api ×N  (cpus/mem 제한)]
                                                                  │        │        │
                                                             [Postgres] [Redis] [toxiproxy] ─▶ [mock-external (지연/실패 주입)]
[Prometheus] ◀── /actuator/prometheus, k6 remote-write        [Grafana] ◀── Prometheus
```

| 구성요소 | 로컬 (Docker Compose) | 클라우드(실전) 대응 | 로컬에서 흉내 내는 법 |
|---|---|---|---|
| 애플리케이션 | `coupon-api` 컨테이너, `cpus`/`memory` 제한 | ECS Fargate task(0.5vCPU/1GB 등), EKS `requests/limits`, EC2 t3.small | Docker 리소스 제한 = 태스크 크기. `-Xmx`는 컨테이너 메모리의 50~70% |
| DB | Postgres 컨테이너, `max_connections` 제한 | RDS PostgreSQL (db.t4g.micro는 max_connections ≈ 수십~백 수준), RDS Proxy | `max_connections`를 작게 설정해 풀 병목 재현 |
| 캐시 | Redis 컨테이너 | ElastiCache / Upstash | 동일 |
| 외부 의존성 | `mock-external` (지연·실패율 파라미터로 주입) | 실제 PG사/알림 SaaS API | 지연 200~2000ms, 실패율 0~50% 주입 |
| 로드밸런서 | 없음 → 수평확장 실험 시 nginx | ALB/NLB | nginx upstream으로 replicas 분산 |
| 부하 생성 | k6 (호스트에서 실행) | Grafana Cloud k6 / 별도 EC2에서 k6 / k8s k6-operator | 호스트에서 실행해 앱 CPU와 분리 |
| 모니터링 | Prometheus + Grafana 컨테이너 | Grafana Cloud(remote_write), CloudWatch, Datadog | Micrometer → Prometheus, 동일 대시보드 JSON 사용 |
| 스케일링 | `docker compose up --scale coupon-api=3` | Auto Scaling (CPU 기준) | 수동 replicas 조정으로 scale-out 효과만 측정 |
| 배포 | 로컬 이미지 빌드 | ECR + ECS/EKS, Terraform | Dockerfile/Jib 동일 이미지 사용 |

### 2.1 리소스 프로파일 (실험의 고정 변수)
| 프로파일 | CPU | 메모리 | `-Xmx` | 대응 클라우드 크기 (참고) |
|---|---|---|---|---|
| **S** | 0.5 | 512MB | 256m | Fargate 0.25~0.5 vCPU, t4g.nano/micro |
| **M** | 1 | 1GB | 512m | Fargate 1 vCPU/2GB, t3.micro |
| **L** | 2 | 2GB | 1g | Fargate 2 vCPU, t3.small/medium |

- Docker Desktop VM 자체 리소스는 충분히(예: 6 CPU / 8GB) 잡아두고, 컨테이너 단위로 제한한다.
- 맥은 8코어이므로 앱 2 + DB 1 + Redis/mock 1 + k6 2~3 정도로 배분하면 서로 CPU를 뺏지 않는다.

### 2.2 클라우드에서 실제로 돌릴 때 (선택 단계, 비용 발생)
로컬 실험이 끝난 뒤 "진짜 환경에서도 맞는지" 1~2회 검증하는 용도. 필수는 아니다.

1. **이미지**: 멀티스테이지 Dockerfile(또는 Jib) → ECR push
2. **앱**: 가장 저렴한 구성은 EC2 t3.small 1대에 docker compose 그대로 올리기. 정석은 ECS Fargate + ALB.
3. **DB/Redis**: RDS db.t4g.micro + ElastiCache(또는 Upstash 무료 티어). RDS는 `max_connections`가 작으므로 HikariCP 풀 크기를 **그에 맞춰** 설정 → 로컬 E4 실험과 직접 비교 가능.
4. **부하**: 같은 VPC의 별도 EC2에서 k6 실행(인터넷 구간 병목 제거) 또는 Grafana Cloud k6 무료 티어.
5. **모니터링**: 로컬 Prometheus를 Grafana Cloud로 remote_write 하거나 CloudWatch Container Insights. 대시보드 JSON은 로컬 것 재사용.
6. **IaC/비용**: Terraform으로 올리고 실험 후 반드시 `destroy`. 몇 시간 단위 실험이면 비용은 소액이지만 RDS/NAT Gateway는 켜둔 채 잊으면 비싸다.
7. **로컬과 달라지는 점 (리포트에 명시)**: 앱↔DB 네트워크 RTT(로컬 ~0.1ms vs 클라우드 ~1ms), ALB idle timeout·오버헤드, 디스크 IOPS(gp3 한도), 오토스케일 반응 지연, 콜드스타트.

---

## 3. 기술 스택 & 프로젝트 뼈대

### 3.1 스택
| 영역 | 선택 | 이유 |
|---|---|---|
| 언어/런타임 | Java 21 (LTS) | 버츄얼 쓰레드 정식(JEP 444). `-Djdk.tracePinnedThreads`로 pinning 관찰 가능 |
| 프레임워크 | Spring Boot **4.0.x** (→ wiki D-004; 계획 당시 3.3.x는 EOL) + **Spring MVC** (WebFlux 아님) | "블로킹 코드를 그대로 두고 VT만 켜서" 효과를 보는 게 목적. `spring.threads.virtual.enabled=true` 한 줄로 토글 |
| 빌드 | Gradle (wrapper 포함) | 별도 설치 불필요 |
| DB 접근 | Spring Data JPA (+ 필요시 `JdbcClient`) | 비관적/낙관적 락 학습에 JPA가 직관적 |
| 커넥션 풀 | HikariCP | 풀 크기 실험의 핵심 변수 |
| Redis | Lettuce (Spring Data Redis) | Lua 스크립트/원자 연산 |
| HTTP 클라이언트 | `RestClient` (Spring 6.1+) | 동기 호출 → VT 효과 직접 확인, 타임아웃 설정 |
| 장애 대응 | Resilience4j (timeout, circuit breaker, bulkhead) | E8 실험 |
| 관측성 | Actuator + Micrometer + Prometheus + Grafana | JVM 쓰레드/힙/GC, Hikari, HTTP p99, k6 지표를 한 화면에 |
| 부하 | k6 (JS 시나리오, open-model 지원) | `arrival-rate` 시나리오로 coordinated omission 회피 |
| 테스트 | JUnit5 + Testcontainers | 정합성 검증 자동화 |
| 컨테이너 | Docker Compose (리소스 제한 포함) | 한 명령으로 전체 환경 |

### 3.2 디렉토리 구조
```
java-heavy-traffic/
├── PLAN.md                         # 이 문서
├── CLAUDE.md                       # 세션 규칙: 위키 우선 워크플로우 (작업 → 기록 → 반복)
├── README.md                       # 실행법 + 실험 결과 요약 (포트폴리오 얼굴)
├── wiki/                           # LLM-wiki: index, log, journal/, problems/(P-NNN), decisions/(D-NNN), experiments/, concepts/, howto/
├── docker-compose.yml              # postgres, redis, mock-external, toxiproxy, coupon-api, prometheus, grafana
├── docker-compose.scale.yml        # nginx + replicas (수평 확장 실험용 오버레이)
├── coupon-api/                     # 메인 Spring Boot 앱
│   ├── build.gradle, settings.gradle, gradlew*
│   ├── Dockerfile                  # 멀티스테이지 (JRE 21 slim)
│   └── src/main/java/com/example/coupon/
│       ├── CouponApiApplication.java
│       ├── config/                 # VirtualThreadConfig, HikariConfig, RedisConfig, RestClientConfig, MetricsConfig
│       ├── experiment/             # ping, io/sleep, io/external, cpu/hash, pin/sync (실험용 엔드포인트)
│       ├── coupon/
│       │   ├── domain/             # Coupon, CouponIssue (엔티티)
│       │   ├── api/                # CouponController, DTO
│       │   ├── application/        # CouponIssueService (전략 위임), IdempotencyService
│       │   ├── strategy/           # IssueStrategy 인터페이스 + None/DbPessimistic/DbOptimistic/Redis 구현
│       │   └── infra/              # JPA Repository, RedisCouponStockRepository (Lua)
│       ├── external/               # NotificationClient (RestClient + Resilience4j)
│       └── common/                 # 예외 처리, 응답 포맷, 요청 ID
│   └── src/main/resources/
│       ├── application.yml         # 공통
│       ├── application-platform.yml# VT off, tomcat max-threads=200
│       └── application-virtual.yml # VT on
│   └── src/test/java/...           # 정합성 테스트 (Testcontainers), 전략별 동시성 테스트
├── mock-external/                  # 경량 Spring Boot 앱 (VT on). GET /notify, POST /admin/fault (지연·실패·hang 런타임 주입)
├── load/                           # k6 시나리오
│   ├── lib/config.js               # BASE_URL, thresholds 공통
│   ├── 00-smoke.js
│   ├── 10-baseline-ping.js
│   ├── 20-io-sleep.js              # VT vs platform 핵심 비교
│   ├── 21-io-external.js
│   ├── 30-cpu-bound.js
│   ├── 40-db-read.js
│   ├── 50-flash-sale.js            # 선착순 (N명 동시 발급) + 사후 검증
│   ├── 60-breakpoint.js            # 스텝별 constant-arrival-rate + 스텝 스코프 threshold, 한계점 탐색
│   ├── 70-spike.js
│   └── 80-soak.js
├── monitoring/
│   ├── prometheus.yml
│   └── grafana/provisioning/...    # 데이터소스 + 대시보드 JSON (JVM, Hikari, HTTP, k6)
├── scripts/
│   ├── run-experiment.sh           # 프로파일(S/M/L), 쓰레드모드, 전략, 풀크기, 시나리오 지정 → 실행 → results/ 저장
│   ├── verify-coupon.sh            # 발급 수 == 수량, 중복 0건 SQL 검증
│   ├── fault-timeline.sh           # 부하 중 t=60s 장애 ON / t=180s OFF (admin API·Toxiproxy), Grafana annotation
│   └── reset-db.sh
└── results/                        # 실험별 k6 summary(json) + docker stats + 메모 (md)
    └── TEMPLATE.md                 # 실험 기록 템플릿
```

### 3.3 핵심 설정 포인트
- **쓰레드 모드**: `spring.threads.virtual.enabled=${VT:true}` — 프로파일로 토글, 나머지 코드는 동일.
- **커넥션 풀**: `spring.datasource.hikari.maximum-pool-size=${POOL_SIZE:20}` — 실험 변수.
- **전략**: `coupon.issue.strategy=${ISSUE_STRATEGY:redis}`.
- **JVM**: `JAVA_TOOL_OPTIONS="-Xmx${XMX} -XX:+UseZGC -XX:+ZGenerational"`(또는 G1) — compose env로 주입. 컨테이너 인식(`UseContainerSupport`)은 기본 on.
- **외부 호출 타임아웃**: connect 500ms / read 1s 기본, E8에서 제거·변경.
- **관측 지표 노출**: `management.endpoints.web.exposure.include=health,prometheus,metrics`, `http.server.requests` percentiles 히스토그램 on.

---

## 4. 테스트(실험) 계획

### 4.1 공통 측정 지표
| 출처 | 지표 |
|---|---|
| k6 | RPS(실제 처리량), p50/p95/p99 응답시간, `http_req_failed`, `dropped_iterations`(open-model에서 서버가 못 받은 양) |
| JVM (Micrometer) | `jvm_threads_live`, 힙 사용량, GC pause, CPU 사용률(`process_cpu_usage`) |
| Tomcat/HTTP | `tomcat_threads_busy`, `http_server_requests_seconds` |
| Hikari | `hikaricp_connections_active/pending/timeout_total` |
| DB/Redis | 커넥션 수, 락 대기, Redis latency |
| Docker | `docker stats` 피크 CPU%, 메모리 (제한 대비) |

### 4.2 k6 시나리오 설계 원칙
- **closed model(VU 고정)** 은 서버가 느려지면 요청도 같이 줄어 한계가 가려진다 → 처리 한계 측정엔 **open model(`constant-arrival-rate`/`ramping-arrival-rate`)** 사용.
- 선착순처럼 "동시에 N명"을 재현할 땐 `per-vu-iterations` 또는 `shared-iterations`로 VU를 한 번에 투입.
- 공통 threshold 초안: `http_req_failed < 1%`, `p(99) < 500ms` (실험별로 조정). 초과 시 자동 중단해 "무너진 지점"을 기록.
- 각 실험은 warm-up(30s) 후 측정, 최소 2~3회 반복해 중앙값 사용.

### 4.3 실험 매트릭스
각 실험은 **가설 → 설정 → 시나리오 → 지표 → 배울 점** 형식으로 기록한다. (기대 결과는 가설이며, 실제 수치로 검증)

| ID | 실험 | 변수 | 시나리오 | 가설 / 배울 점 |
|---|---|---|---|---|
| **E1** | 계측 검증 & 베이스라인 | 프로파일 M, `/ping` | smoke → baseline | 툴체인(k6·Prometheus·Grafana) 동작 확인, 프레임워크 자체 한계 RPS 파악 |
| **E2** | 플랫폼 vs 버츄얼 — I/O bound | VT on/off × S/M/L, `/io/sleep?ms=300` | ramping-arrival-rate | 플랫폼은 `200 threads / 0.3s ≈ 666 RPS` 천장에서 대기열 폭발, VT는 CPU/메모리 한계까지 선형 증가. **VT의 존재 이유** |
| **E3** | 플랫폼 vs 버츄얼 — CPU bound | VT on/off, `/cpu/hash` | ramping-arrival-rate | 차이 없음(오히려 VT 약간 손해 가능). "VT는 마법이 아니다" |
| **E4** | 커넥션 풀 병목 | VT on, `POOL_SIZE` 5/20/50/100, DB `max_connections` 100, `/coupons/{id}` | constant-arrival-rate 단계 증가 | VT 켜도 풀이 작으면 `hikaricp_pending` 폭증. 풀을 키우면 DB CPU가 병목으로 **전이**. "VT 시대의 풀 사이징" |
| **E5** | Pinning 재현 | VT on, `/pin/sync` vs `ReentrantLock` 버전, `-Djdk.tracePinnedThreads=full` | E2와 동일 부하 | `synchronized`+I/O 시 캐리어 쓰레드 고정 → 처리량 급락. 락 교체로 회복. (JDK 24 JEP 491로 해결됨을 기록) |
| **E6** | 선착순 정합성 | 전략 none/db-pessimistic/db-optimistic/redis, 쿠폰 1,000개 | 5,000 VU 동시 1회 발급 + 사후 DB 검증 | none은 초과 발급 발생, 나머지는 0건. 처리량·p99·DB CPU·재시도 횟수 비교 |
| **E7** | 멱등성 / 중복 요청 | 같은 userId 재시도 폭주, Idempotency-Key | per-vu-iterations 10회 | 중복 발급 0건, 응답 일관성 |
| **E8** | 느린/장애 외부 의존성 (상세: §4.6) | 3계층 장애 주입(앱 mock·Toxiproxy·프로세스) × 방어 단계(무방비→타임아웃→재시도→CB→벌크헤드→비동기), `/issue-and-notify` | constant-arrival-rate + 장애 타임라인 | 타임아웃 없으면 VT 요청 무한 누적·힙 증가·전체 지연 전파. 타임아웃+CB+벌크헤드로 빠른 실패 → 정상 경로 보호, 회복 시간 측정 |
| **E9** | 한계점(Breakpoint) 탐색 | S/M/L × VT on, `/issue-and-notify`(실전형) | **스텝별 constant-arrival-rate 시나리오**(STEPS×STEP_DUR_S, 스텝 스코프 threshold + dropped_iterations 가드, abort=한계 도달) | 프로파일별 "p99 500ms·에러 1% 유지 최대 RPS" 표. 이 표가 **최종 결과물** |
| **E10** | 메모리 / GC | `-Xmx` 256/512/1024, G1 vs Generational ZGC, VT 대량 생성 | E2 고부하 | OOM 재현 지점, GC pause가 p99에 미치는 영향 |
| **E11** | Soak (장시간) | M 프로파일, 한계의 70% 부하 | 30~60분 | 메모리·커넥션 누수, 시간 경과에 따른 p99 드리프트 |
| **E12** | 수평 확장 | `replicas` 1/2/3 + nginx, 총 리소스 동일 | E9 반복 | scale-up vs scale-out 효율, DB가 공통 병목이 되는 지점 |
| **E13** | 백프레셔 / 레이트 리밋 | 세마포어(동시 처리 N), Bucket4j, 큐 대기 vs 즉시 429 | spike 시나리오 | 과부하 시 "전부 느려짐" 대신 "일부 빠른 실패 + 나머지 정상" |

### 4.4 실험 실행 흐름 (자동화)
```
scripts/run-experiment.sh --profile M --vt on --strategy redis --pool 20 --scenario load/60-breakpoint.js --name E9-M-vt
  1) docker compose 환경변수 주입 후 up (리소스 제한 적용)
  2) /actuator/health 대기 (warm-up 포함)
  3) k6 실행 (--summary-export results/<name>.json, Prometheus remote-write로 Grafana 연동)
  4) docker stats 피크 기록, 필요 시 verify-coupon.sh 실행
  5) results/<name>.md 에 설정·결과·Grafana 스크린샷 경로 기록
```

### 4.5 실험 기록 템플릿 (`results/TEMPLATE.md`)
실험 ID · 날짜 · 가설 · 설정(프로파일, VT, 전략, 풀, JVM 옵션) · 시나리오 · 결과 표(RPS, p50/p95/p99, 에러율, CPU, 힙, Hikari pending) · 그래프 · 해석(병목은 어디였나) · 다음 액션

### 4.6 E8 상세 — 외부 API 지연/장애 테스트 계획

**원칙**: 장애를 3개 계층에서 주입할 수 있게 만들고, 방어 수단을 한 단계씩 켜면서 같은 부하로 비교한다. 각 실험은 "정상 → 장애 ON → 전파 → 장애 OFF → 회복" 타임라인을 Grafana에 남긴다.

#### (1) 장애 주입 3계층
| 계층 | 도구 | 재현 장애 | 비고 |
|---|---|---|---|
| 애플리케이션 | `mock-external`의 `POST /admin/fault` `{mode: normal\|slow\|error\|hang\|flapping, delayMs, jitterMs, failRate, status}` | 느린 응답(300→5000ms), 5xx 비율, 응답 없이 연결 유지(hang), 간헐적 장애 | **부하 도중 런타임 토글** 가능. 요청 단위 파라미터(`/notify?delayMs=`)도 병행 지원. **hang 동시 상한 `FAULT_MAX_HANGS`(기본 2000)** — 초과분은 즉시 503(hang-rejected). 동시 hang ≈ rate×hangSeconds 로 사이징할 것. delay 상한 60s |
| 네트워크 | Toxiproxy 컨테이너 (데이터 경로 app → toxiproxy:18081 → mock, 제어는 :8474 REST API. 전환: `--env EXTERNAL_BASE_URL=http://toxiproxy:18081`) | TCP latency, 바이트 미수신(read timeout), RST(reset_peer), 대역폭 제한, 전체 차단 | HTTP API로 부하 중 toxic 추가/삭제. 앱 mock으로 못 만드는 "진짜 네트워크 장애" |
| 프로세스 | `docker compose stop` / `docker pause mock-external` | connection refused / SYN 무응답(connect timeout) | 완전 다운과 재기동 후 회복 관찰 |

#### (2) 방어 수단 단계별 실험
| ID | 방어 | 설정 | 관찰 포인트 |
|---|---|---|---|
| E8-0 | 무방비 | 타임아웃 없음, VT on | VT는 쓰레드 풀 한도가 없어 대기 요청이 **무한 누적** → 힙 증가, p99 폭발, 회복 지연. (플랫폼 쓰레드 200개는 오히려 강제 백프레셔였음) |
| E8-1 | 타임아웃 | connect 500ms / read 1s (RestClient) | p99 ≈ 1s 캡, 에러율 = 타임아웃 비율, 힙 안정 |
| E8-2 | 재시도 | Retry 3회: 백오프·지터 없음 vs 지수 백오프+지터 vs 재시도 예산 | 무지성 재시도 → 외부 부하 3배 → mock 더 느려짐(**retry storm**) 재현 후 완화 |
| E8-3 | 서킷브레이커 | Resilience4j CB: 실패율 50%, open 10s, half-open 5건 | open 시 즉시 fallback으로 응답 빠름, half-open 회복 탐지까지 걸리는 시간 |
| E8-4 | 벌크헤드 | 외부 호출 동시 50개(세마포어) | 외부 지연과 무관하게 **다른 엔드포인트 p99 유지** (격리) |
| E8-5 | 비동기 분리 | 발급 커밋 후 알림은 outbox 테이블 + 워커(VT)로 비동기 전송 | 발급 API p99가 외부 지연과 **무관**해짐. 실패 알림은 재시도 큐로 |
| E8-6 | 회복 | 위 전부 적용, 장애 120s 후 정상화 | 회복 소요 시간, CB 상태 전이, 밀린 요청(outbox) 소화 속도 |

#### (3) 일부러 만드는 "함정" 비교
- **트랜잭션 안 외부 호출**: `issue-and-notify`를 나쁜 버전(`@Transactional` 내부에서 호출 → DB 커넥션 점유한 채 대기)과 좋은 버전(커밋 후 호출)으로 둘 다 구현. 나쁜 버전은 외부 지연 시 Hikari 풀 고갈 → **외부와 무관한 모든 DB 엔드포인트까지 마비** (장애 전파 사례).
- **VT 수는 `jvm_threads_live`에 안 잡힘**: 서블릿 필터로 in-flight 요청 게이지(`http_inflight_requests`)를 추가하고, JFR(`jdk.VirtualThreadStart/End`)·`jcmd <pid> Thread.dump_to_file -format=json`으로 VT 수를 확인.

#### (4) 실행 방식
- k6 `constant-arrival-rate`로 5분 고정 부하 (`/issue-and-notify` 70% + `/coupons/{id}` 30% 혼합, 요청 태그 `path=notify|read`로 경로별 분리 측정).
- `scripts/fault-timeline.sh`: t=60s 장애 ON(admin API 또는 Toxiproxy API), t=180s OFF. 시점은 Grafana annotation API로 기록.
- 측정: 경로별 p99·에러율, `http_inflight_requests`, 힙/GC, `hikaricp_connections_pending`, Resilience4j 지표(`resilience4j_circuitbreaker_state`, `resilience4j_bulkhead_available_concurrent_calls`, calls by kind), 회복 소요 시간.
- 기대 산출물: "방어 단계별 장애 중 p99 / 에러율 / 정상 경로 영향 / 회복 시간" 비교표 + 타임라인 그래프.

---

## 5. 로드맵

| 주차 | 할 일 | 완료 기준 |
|---|---|---|
| **1주** | 뼈대 생성(coupon-api, mock-external), docker-compose(리소스 제한), Prometheus/Grafana, k6 설치, 실험용 엔드포인트, `run-experiment.sh` | E1 통과, Grafana에서 JVM·HTTP 지표 보임 |
| **2주** | E2·E3·E5 (VT 핵심 실험), 결과 기록 | VT on/off 비교표 1차 완성, pinning 재현 성공 |
| **3주** | 쿠폰 도메인 + 4가지 전략, Testcontainers 정합성 테스트, E6·E7 | 초과 발급 재현 → 0건 달성, 전략별 비교표 |
| **4주** | E4·E8·E13 (병목 전이, 장애 대응, 백프레셔) | 병목이 이동하는 과정 기록, 타임아웃/CB 효과 수치화 |
| **5주** | E9·E10·E11·E12, README 최종 정리(결과 요약 + 배운 점) | 프로파일별 한계 RPS 표, 포트폴리오용 README |
| (선택) | 클라우드 1회 검증 (§2.2), 블로그 포스팅 | 로컬 vs 클라우드 수치 비교 |

---

## 6. 학습 체크리스트 (면접 대비용 — 실험하며 직접 답할 수 있어야 할 것)
- 버츄얼 쓰레드 동작: 캐리어 쓰레드, ForkJoinPool, mount/unmount, continuation, 왜 블로킹 I/O가 싸지는가
- Pinning이 무엇이고 언제 발생하며(`synchronized`, native) 어떻게 피하는가 — JDK 21 vs 24 차이
- VT에서 "풀 크기"의 의미 변화: 쓰레드 풀은 없어지고 **세마포어/커넥션 풀**이 동시성 제한 수단이 됨
- 왜 WebFlux 대신 MVC+VT를 선택했는가 (코드 단순성 vs 리액티브)
- 커넥션 풀 사이징 공식(`connections = (core_count * 2) + spindle`), DB `max_connections`와의 관계
- 동시성 제어 3종(비관적/낙관적/Redis 원자 연산)의 트레이드오프, Redis-first 설계의 정합성 보장 방법
- 멱등성 키 설계, 중복 요청 처리
- 타임아웃 전파, 서킷브레이커, 벌크헤드, 백프레셔 — 과부하 시 "빠른 실패"의 가치
- k6 open vs closed model, coordinated omission, p99를 믿기 위한 조건
- JVM 컨테이너 인식, `-Xmx` 비율, G1 vs ZGC 선택 기준
- 관측성: RED 지표(Rate, Errors, Duration), 대시보드에서 병목 읽는 법

---

## 7. 리스크 & 주의사항
- **같은 머신에서 k6 실행** → CPU 경합. 앱은 Docker로 제한하고 k6 CPU 사용률도 같이 기록. 의심되면 k6를 별도 컨테이너에 `cpus` 제한 걸어 분리.
- **Docker Desktop(macOS) 네트워크 오버헤드** → 절대값은 낮게 나올 수 있음. 상대 비교로 해석.
- **JDK 21.0.1**은 초기 패치 → 최신 21.0.x로 올리는 것 권장 (VT 관련 버그픽스 포함). pinning은 여전히 존재하므로 E5 실험은 유효.
- **측정 편향**: warm-up 없이 측정하면 JIT 때문에 초반 느림. 반복 측정 + 중앙값.
- **결과 과신 금지**: 로컬 수치는 "참고치". README에 환경(맥 사양, Docker 설정)을 반드시 명시.

---

## 8. 결정이 필요한 항목 (기본값으로 진행, 원하면 변경)
| 항목 | 기본값 | 대안 |
|---|---|---|
| 빌드 DSL | Gradle Groovy | Kotlin DSL |
| DB 접근 | Spring Data JPA | JdbcClient(더 가볍고 빠름, 락 직접 작성) |
| GC 기본 | G1 (E10에서 ZGC 비교) | 처음부터 Generational ZGC |
| mock-external | 자체 경량 Spring Boot 앱 | WireMock standalone(코드 0, 유연성↓) |
| 클라우드 검증 | 선택(로컬 완료 후) | 처음부터 병행 |
