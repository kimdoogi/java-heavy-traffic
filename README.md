# java-heavy-traffic

Java 21 **Virtual Thread** 기반 선착순 쿠폰 서비스를 **리소스 제한(CPU/메모리)** 하에서 부하 테스트하며,
한계와 병목을 수치로 기록하는 학습/포트폴리오 프로젝트.

- 계획: [PLAN.md](PLAN.md) (실험 E1~E13, 뼈대, 로컬↔클라우드 매핑, 로드맵)
- 기록: [wiki/](wiki/index.md) (작업 journal, 문제→해결 카드, 결정 ADR, 실험 결과, 개념)
- 결과 요약: (실험 진행 후 이 섹션에 표로 정리)

## 구성
```
coupon-api/      Spring Boot 4 / Java 21 / MVC + VT 토글 (실험 대상)
mock-external/   느린 외부 API 흉내 (지연·실패·hang 런타임 주입)
load/            k6 시나리오
monitoring/      Prometheus, Grafana 대시보드, Toxiproxy 설정
scripts/         build.sh, run-experiment.sh, summarize.py
results/         실험별 raw 결과 (summary.json, docker-stats.csv, meta.env)
```

## 빠른 시작
```bash
# 1) 빌드 + 환경 기동 (postgres, redis, mock-external, toxiproxy, coupon-api, prometheus, grafana)
scripts/build.sh && docker compose up -d

# 2) 동작 확인
curl -s localhost:8080/api/ping
k6 run load/00-smoke.js

# 3) 실험 실행 (프로파일 M=1cpu/1g, 버츄얼 쓰레드 on, I/O bound 시나리오)
scripts/run-experiment.sh -n E2-M-vt -p M -v on -s load/20-io-sleep.js
scripts/run-experiment.sh -n E2-M-pt -p M -v off -s load/20-io-sleep.js
```
- Grafana: http://localhost:3000/d/heavy-traffic (익명 Admin) · Prometheus: http://localhost:9090
- 결과: `results/<name>/summary.md`

## 리소스 프로파일
| 프로파일 | CPU | 메모리 | -Xmx |
|---|---|---|---|
| S | 0.5 | 512m | 256m |
| M | 1 | 1g | 512m |
| L | 2 | 2g | 1g |

## 실험 엔드포인트
| 경로 | 목적 |
|---|---|
| `GET /api/ping` | 베이스라인 |
| `GET /api/io/sleep?ms=` | 순수 I/O 대기 → VT 효과 |
| `GET /api/io/external?delayMs=&failRate=` | 외부 HTTP 대기 (mock-external) |
| `GET /api/cpu/hash?n=` | CPU bound |
| `GET /api/pin/sync?ms=` / `GET /api/pin/lock?ms=` | synchronized pinning vs ReentrantLock |
| `GET /api/db/ping` | 커넥션 풀 경로 |
| `POST /api/coupons/{id}/issue` | 선착순 발급 (3주차) |
