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
  echo "name=$NAME"; echo "date=$(date +%Y-%m-%dT%H:%M:%S%z)"; echo "git=$(git describe --always --dirty 2>/dev/null || echo none)"
  echo "profile=$PROFILE cpus=$APP_CPUS mem=$APP_MEM xmx=$XMX"; echo "VT=$VT_VAL"; echo "APP_JAVA_OPTS=$APP_JAVA_OPTS"
  echo "POOL_SIZE=${POOL_SIZE:-20(default)}"; echo "ISSUE_STRATEGY=${ISSUE_STRATEGY:-default}"; echo "# ISSUE_STRATEGY는 3주차 전략 구현 전까지 동작에 영향 없음 (property로만 소비)"
  echo "extra_env=${EXTRA_ENV[*]+"${EXTRA_ENV[*]}"}"; echo "scenario=$SCRIPT"; echo "k6_extra=${K6_EXTRA[*]+"${K6_EXTRA[*]}"}"
  env | grep -E '^(SLEEP_MS|DELAY_MS|MAX_RPS|START_RPS|STEP_DUR|STEP_DUR_S|STEPS|HASH_N|PIN_MODE|PIN_MS|ENDPOINT|P99_MS|ERR_RATE|MAX_VUS|DURATION|BASE_URL|EXTERNAL_[A-Z_]+|FAULT_[A-Z_]+|PG_[A-Z_]+|POOL_CONN_TIMEOUT_MS|TOMCAT_[A-Z_]+)=' || true
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
echo "-- waiting for mock-external health..."
for i in $(seq 1 60); do
  if curl -sf http://localhost:8081/actuator/health | grep -q '"status":"UP"'; then echo "   UP after ${i}x2s"; break; fi
  if [[ $i -eq 60 ]]; then echo "mock-external not healthy" >&2; docker compose logs --tail=50 mock-external; exit 1; fi
  sleep 2
done

# 이전 실험에서 /admin/fault 로 주입한 런타임 장애가 남아있지 않도록 항상 초기화하고, 현재 fault를 기록
# (각 curl 실패는 명확한 메시지로 즉시 실패한다 — $()는 set -e에 안 잡히거나 무메시지로 잡히므로 개별 검사)
if ! curl -sf -X POST http://localhost:8081/admin/fault/reset > /dev/null; then
  echo "ERROR: mock fault reset 실패 — mock-external 상태 확인 (docker compose logs mock-external)" >&2; exit 1
fi
if ! MOCK_FAULT=$(curl -sf http://localhost:8081/admin/fault); then
  echo "ERROR: mock fault 조회 실패" >&2; exit 1
fi
echo "mock_fault=$MOCK_FAULT" >> "$OUT/meta.env"

# 실효 설정 검증 1: 동작 레벨 — /api/ping 의 virtual 필드로 실제 쓰레드 모드 확인
if ! PING_BODY=$(curl -sf http://localhost:8080/api/ping); then
  echo "ERROR: /api/ping 호출 실패 — 앱이 health 통과 후 비정상" >&2; exit 1
fi
if ! EFF_VT=$(printf '%s' "$PING_BODY" | python3 -c 'import json,sys; print(str(json.load(sys.stdin)["virtual"]).lower())' 2>/dev/null); then
  echo "ERROR: /api/ping 응답 파싱 실패: $PING_BODY" >&2; exit 1
fi
echo "effective_virtual=$EFF_VT" >> "$OUT/meta.env"
if [[ "$EFF_VT" != "$VT_VAL" ]]; then
  echo "ERROR: VT 불일치 — 요청 $VT_VAL vs 실제 $EFF_VT (--skip-up 으로 재적용이 생략됐거나 컨테이너가 갱신 안 됨)" >&2
  exit 1
fi

# 실효 설정 검증 2: env 레벨 — 스냅샷(show-values=always)과 요청 knob 전수 대조
if ! curl -sf http://localhost:8080/actuator/env -o "$OUT/effective-env.json"; then
  rm -f "$OUT/effective-env.json"
  echo "ERROR: /actuator/env 스냅샷 실패" >&2; exit 1
fi
CONTAINER_VARS=" VT POOL_SIZE TOMCAT_MAX_THREADS TOMCAT_MAX_CONNECTIONS TOMCAT_ACCEPT_COUNT POOL_CONN_TIMEOUT_MS EXTERNAL_BASE_URL EXTERNAL_CONNECT_TIMEOUT_MS EXTERNAL_READ_TIMEOUT_MS ISSUE_STRATEGY "
CHECKS=("VT=$VT_VAL")
[[ -n "${POOL_SIZE:-}" ]] && CHECKS+=("POOL_SIZE=$POOL_SIZE")
for kv in "${EXTRA_ENV[@]+"${EXTRA_ENV[@]}"}"; do
  k=${kv%%=*}
  [[ "$CONTAINER_VARS" == *" $k "* ]] && CHECKS+=("$kv")
done
python3 scripts/verify-effective.py "$OUT/effective-env.json" "${CHECKS[@]}"

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

# breakpoint 시나리오는 threshold abort(=한계 도달)가 정상 종료다 → 배치/자동화가 실패로 읽지 않게 성공 처리
if [[ $K6_EXIT -eq 99 && "$SCRIPT" == *breakpoint* ]]; then
  echo "-- breakpoint: threshold abort = 한계 도달 (k6 exit 99 → 0 처리). 한계 스텝은 k6.log의 THRESHOLDS 블록 참고"
  K6_EXIT=0
fi

if [[ -f "$OUT/summary.json" ]]; then
  python3 scripts/summarize.py "$OUT"
else
  echo "!! summary.json 없음 — k6가 실행에 실패한 것으로 보임 (exit=$K6_EXIT). $OUT/k6.log 확인" >&2
fi
echo "-- k6 exit=$K6_EXIT (threshold 실패 시 99). 결과: $OUT/  Grafana: http://localhost:3000/d/heavy-traffic?var-testid=$NAME"
exit $K6_EXIT
