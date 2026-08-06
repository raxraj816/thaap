#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
python3 scripts/openapi-contract-check.py "$@"
REDOCLY_TELEMETRY=off REDOCLY_SUPPRESS_UPDATE_NOTICE=true \
  npx --yes @redocly/cli@2.40.0 lint \
  openapi/thaap-public-api-2026-08-01.yaml --config redocly.yaml
