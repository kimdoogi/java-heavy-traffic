#!/usr/bin/env bash
# 실험 1회 실행: 리소스 프로파일/쓰레드 모드/전략/풀 크기 지정 → compose 반영 → health 대기 → k6 → results/<name>/ 저장
#
#   scripts/run-experiment.sh -n E2-M-vt -p M -v on -s load/20-io-sleep.js
#   scripts/run-experiment.sh -n E4-M-pool5 -p M -v on --pool 5 -s load/40-db-read.js
#   scripts/run-experiment.sh -n E8-0 -p M -v on --env EXTERNAL_READ_TIMEOUT_MS=0 -s load/21-io-external.js
#   시나리오 파라미터는 환경변수로: SLEEP_MS=300 MAX_RPS=2000 scripts/run-experiment.sh ...
#
# 옵션: -n 이름(필수) -p S|M|L(기본 M) -v on|off(기본 on) -s 시나리오(필수)
#       --pool N  --strategy X  --java-opts "..."  --env KEY=VAL(반복 가능)
#       --no-build (jar/이미지 빌드 생략)  --skip-up (compose up 생략: 이미 떠 있는 환경 그대로 사용)
#       -- 이후는 k6 추가 인자
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd); cd "$ROOT"

NAME=""; PROFILE="M"; VT="on"; SCRIPT=""; POOL=""; STRATEGY=""; JAVA_OPTS=""; EXTRA_ENV=(); NO_BUILD=0; SKIP_UP=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    -n|--name) NAME=$2; shift 2;;
    -p|--profile) PROFILE=$2; shift 2;;
    -v|--vt) VT=$2; shift 2;;
    -s|--scenario) SCRIPT=$2; shift 2;;
    --pool) POOL=$2; shift 2;;
    --strategy) STRATEGY=$2; shift 2;;
    --java-opts) JAVA_OPTS=$2; shift 2;;
    --env) EXTRA_ENV+=("$2"); shift 2;;
    --no-build) NO_BUILD=1; shift;;
    --skip-up) SKIP_UP=1; shift;;
    --) shift; break;;
    *) echo "unknown option: $1" >&2; exit 1;;
  esac
done
K6_EXTRA=("$@")
if [[ -z "$NAME" || -z "$SCRIPT" ]]; then
  sed -n '2,14p' "$0"; exit 1
fi

case "$PROFILE" in
  S) APP_CPUS=0.5; APP_MEM=512m; XMX=256m;;
  M) APP_CPUS=1;   APP_MEM=1g;   XMX=512m;;
  L) APP_CPUS=2;   APP_MEM=2g;   XMX=1g;;
  *) echo "profile must be S|M|L" >&2; exit 1;;
esac
case "$VT" in on|true) VT_VAL=true;; off|false) VT_VAL=false;; *) echo "vt must be on|off" >&2; exit 1;; esac

export APP_CPUS APP_MEM VT="$VT_VAL"
export APP_JAVA_OPTS="-Xmx$XMX -XX:+UseG1GC${JAVA_OPTS:+ $JAVA_OPTS}"
[[ -n "$POOL" ]] && export POOL_SIZE="$POOL"
[[ -n "$STRATEGY" ]] && export ISSUE_STRATEGY="$STRATEGY"
for kv in "${EXTRA_ENV[@]+"${EXTRA_ENV[@]}"}"; do export "$kv"; done

OUT="results/$NAME"; mkdir -p "$OUT"
{
  echo "name=$NAME"; echo "date=$(date +%Y-%m-%dT%H:%M:%S%z)"; echo "git=$(git rev-parse --short HEAD 2>/dev/null || echo none)"
  echo "profile=$PROFILE cpus=$APP_CPUS mem=$APP_MEM xmx=$XMX"; echo "VT=$VT_VAL"; echo "APP_JAVA_OPTS=$APP_JAVA_OPTS"
  echo "POOL_SIZE=${POOL_SIZE:-20(default)}"; echo "ISSUE_STRATEGY=${ISSUE_STRATEGY:-default}"
  echo "extra_env=${EXTRA_ENV[*]+"${EXTRA_ENV[*]}"}"; echo "scenario=$SCRIPT"; echo "k6_extra=${K6_EXTRA[*]+"${K6_EXTRA[*]}"}"
  env | grep -E '^(SLEEP_MS|DELAY_MS|MAX_RPS|START_RPS|STEP_DUR|HASH_N|ENDPOINT|RAMP|P99_MS|ERR_RATE|MAX_VUS|DURATION|EXTERNAL_[A-Z_]+|FAULT_[A-Z_]+|PG_MAX_CONNECTIONS|TOMCAT_MAX_THREADS)=' || true
} > "$OUT/meta.env"
echo "== experiment $NAME =="; cat "$OUT/meta.env"

if [[ $SKIP_UP -eq 0 ]]; then
  [[ $NO_BUILD -eq 0 ]] && scripts/build.sh
  docker compose up -d --remove-orphans
fi

echo "-- waiting for coupon-api health..."
for i in $(seq 1 60); do
  if curl -sf http://localhost:8080/actuator/health | grep -q '"status":"UP"'; then echo "   UP after ${i}x2s"; break; fi
  if [[ $i -eq 60 ]]; then echo "coupon-api not healthy" >&2; docker compose logs --tail=50 coupon-api; exit 1; fi
  sleep 2
done

# docker stats 샘플러 (2초 간격) → CPU/메모리 피크 기록
( while true; do
    ts=$(date +%s)
    docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' 2>/dev/null | sed "s/^/${ts},/"
    sleep 2
  done ) > "$OUT/docker-stats.csv" &
STATS_PID=$!
stop_stats() { { kill "$STATS_PID" && wait "$STATS_PID"; } 2>/dev/null || true; }
trap stop_stats EXIT

echo "-- k6 run $SCRIPT (testid=$NAME)"
set +e
K6_PROMETHEUS_RW_SERVER_URL=http://localhost:9090/api/v1/write \
K6_PROMETHEUS_RW_TREND_STATS="p(95),p(99),avg,max" \
k6 run --tag "testid=$NAME" -o experimental-prometheus-rw \
  --summary-trend-stats "avg,min,med,max,p(90),p(95),p(99)" \
  --summary-export "$OUT/summary.json" \
  ${K6_EXTRA[@]+"${K6_EXTRA[@]}"} "$SCRIPT" 2>&1 | tee "$OUT/k6.log"
K6_EXIT=${PIPESTATUS[0]}
set -e
stop_stats

python3 scripts/summarize.py "$OUT"
echo "-- k6 exit=$K6_EXIT (threshold 실패 시 99). 결과: $OUT/  Grafana: http://localhost:3000/d/heavy-traffic?var-testid=$NAME"
