#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
baseline="${1:-openapi/baselines/thaap-public-api-2026-08-01.yaml}"
candidate="${2:-openapi/thaap-public-api-2026-08-01.yaml}"

if command -v oasdiff >/dev/null 2>&1; then
  oasdiff breaking "$baseline" "$candidate" --fail-on ERR
elif command -v docker >/dev/null 2>&1; then
  docker run --rm -v "$PWD:/spec" tufin/oasdiff:v1.17.0 breaking \
    "/spec/$baseline" "/spec/$candidate" --fail-on ERR
else
  echo "OpenAPI breaking-change check requires oasdiff or Docker." >&2
  exit 1
fi
