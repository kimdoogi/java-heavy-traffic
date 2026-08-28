#!/usr/bin/env bash
# E6 정합성 검증: count(coupon_issue) 가 total_quantity 를 넘지 않는지(초과 발급 0) 확인한다.
# redis 전략은 DB의 remaining_quantity 가 stale 하므로 4전략 공통으로 반드시 count 기준으로 본다.
#
#   scripts/verify-coupon.sh [couponId=1]
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd); cd "$ROOT"

ID="${1:-1}"

# "total issued" 두 값을 공백 구분 한 줄로 뽑는다 (-tA: tuples-only unaligned, -F' ': 필드 구분 공백).
ROW=$(docker compose exec -T postgres psql -U coupon -d coupon -tA -F' ' -v ON_ERROR_STOP=1 -c \
  "SELECT c.total_quantity, (SELECT count(*) FROM coupon_issue i WHERE i.coupon_id = c.id) \
   FROM coupon c WHERE c.id = ${ID};")
ROW=$(printf '%s' "$ROW" | tr -d '\r')

if [[ -z "$ROW" ]]; then
  echo "verify: coupon id=${ID} 없음 (reset 후 쿠폰을 생성했는지 확인)" >&2
  exit 1
fi

read -r TOTAL ISSUED <<< "$ROW"
OVER=$(( ISSUED - TOTAL ))

echo "coupon=${ID} total=${TOTAL} issued(count)=${ISSUED} over=${OVER}"
if (( ISSUED > TOTAL )); then
  echo "RESULT: OVER-ISSUE ${OVER}건 — 정합성 깨짐 (none 전략에서 기대되는 결과, 락 전략이면 결함)"
else
  echo "RESULT: OK — 초과 발급 0건"
fi
