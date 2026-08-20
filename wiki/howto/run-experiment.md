---
title: "실험 실행 런북"
date: 2026-08-19
status: live
tags: [howto, k6, docker]
related: [../../scripts/run-experiment.sh, ../../docker-compose.yml]
---

## 사전 준비
- Docker Desktop 실행 (VM 리소스 6 CPU / 8GB 이상 권장), `brew install k6`
- 최초 1회: `scripts/build.sh && docker compose up -d`

## 실험 1회
```bash
scripts/run-experiment.sh -n <이름> -p S|M|L -v on|off -s load/<시나리오>.js [--pool N] [--strategy X] [--env K=V] [--no-build] [--skip-up]
```
- 프로파일 → `APP_CPUS/APP_MEM/-Xmx` 매핑 후 `docker compose up -d` (바뀐 서비스만 재생성)
- health 대기 → docker stats 샘플러 → k6(remote-write to Prometheus, `testid=<이름>` 태그) → `results/<이름>/summary.md`
- 시나리오 파라미터는 env로: `SLEEP_MS=300 MAX_RPS=2000 STEP_DUR=30s scripts/run-experiment.sh ...`
- **breakpoint(E9)만 지정법이 다름**: `STEPS=10 STEP_DUR_S=30`(숫자 초; `STEP_DUR=30s`도 허용). 한계에 닿으면 k6가 threshold abort로 끝나는 게 **정상**이고 스크립트가 exit 0으로 처리한다 — 한계 스텝은 `k6.log`의 THRESHOLDS 블록에서 마지막 통과 스텝을 읽는다.
- **hang 실험(E8)**: 동시 hang ≈ rate×hangSeconds. 기본 상한 `FAULT_MAX_HANGS=2000` 초과분은 즉시 503(hang-rejected 카운터)이므로, 순수 hang을 보려면 상한을 수요보다 크게 잡거나 rate를 낮출 것.

## 결과 보기
- 표: `results/<이름>/summary.md` (rps, p50/p95/p99, failed, dropped, maxVUs, app CPU 피크, 메모리)
- 그래프: Grafana `http://localhost:3000/d/heavy-traffic?var-testid=<이름>` — 서버 지표(Micrometer)와 k6 지표를 같은 시간축에서 비교
- raw: `summary.json`, `k6.log`, `docker-stats.csv`, `meta.env`(설정·git sha)

## 자주 쓰는 조작
```bash
docker compose ps                         # 상태
docker compose logs -f coupon-api         # 앱 로그
docker stats                              # 실시간 리소스
docker compose up -d --force-recreate coupon-api   # 앱만 재시작
docker compose down -v                    # 전부 내리고 볼륨 삭제(Prometheus/Grafana 데이터 포함)
curl -s localhost:8080/actuator/prometheus | grep http_inflight   # 지표 직접 확인
curl -s -G localhost:9090/api/v1/query --data-urlencode 'query=max_over_time(http_inflight_requests{group="io"}[5m])'   # PromQL ({}는 -G --data-urlencode 필수)
curl -s -X POST localhost:8081/admin/fault -H 'content-type: application/json' -d '{"mode":"slow","delayMs":2000}'  # 장애 주입
curl -s -X POST localhost:8081/admin/fault/reset
# Toxiproxy 경유(E8 네트워크 장애): 데이터 경로는 18081, 제어 API는 8474
#   scripts/run-experiment.sh ... --env EXTERNAL_BASE_URL=http://toxiproxy:18081
curl -s localhost:8474/proxies   # toxic 추가/삭제는 8474
```

## 주의
- k6는 호스트에서 돌아가므로 앱과 CPU를 나눠 쓴다. `docker-stats.csv`의 앱 CPU%가 제한(예: 1 CPU=100%)에 닿았는지 먼저 본다.
- 같은 이름으로 다시 실행하면 `results/<이름>/`이 덮어써진다. 반복 측정은 `-r2`, `-r3` 접미사.
