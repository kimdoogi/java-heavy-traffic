#!/usr/bin/env bash
# jar 빌드(호스트) + 이미지 빌드. 코드 변경 후 실험 전에 실행.
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew bootJar -x test -q
docker compose build -q coupon-api mock-external
echo "built: $(ls coupon-api/build/libs/*.jar) $(ls mock-external/build/libs/*.jar)"
