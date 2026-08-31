#!/usr/bin/env bash
# E6 실험 초기화: coupon/coupon_issue 를 비우고 id 시퀀스를 리셋 + redis 를 비운다.
# RESTART IDENTITY 로 다음 쿠폰 id 를 1 로 만들어 verify-coupon.sh 기본 id(=1)와 맞춘다.
#
#   scripts/reset-db.sh
#
# ⚠️ 공유 compose 스택의 쿠폰 데이터를 전부 지운다. 실험 전용으로 쓸 때만 실행할 것.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd); cd "$ROOT"

docker compose exec -T postgres psql -U coupon -d coupon -v ON_ERROR_STOP=1 \
  -c "TRUNCATE coupon_issue, coupon RESTART IDENTITY CASCADE;"
docker compose exec -T redis redis-cli FLUSHDB > /dev/null

echo "reset-db: coupon/coupon_issue truncated (id reset to 1), redis flushed"
