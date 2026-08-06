#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
candidate="openapi/thaap-public-api-2026-08-01.yaml"
negative_fixture="$(mktemp "$PWD/.openapi-breaking-removal.XXXXXX.yaml")"
negative_output="$(mktemp "$PWD/.openapi-breaking-output.XXXXXX.txt")"
trap 'rm -f "$negative_fixture" "$negative_output"' EXIT

python3 - "$candidate" "$negative_fixture" <<'PY'
import json
import pathlib
import sys

source = pathlib.Path(sys.argv[1])
target = pathlib.Path(sys.argv[2])
contract = json.loads(source.read_text(encoding="utf-8"))
del contract["paths"]["/customers"]
target.write_text(json.dumps(contract, sort_keys=True, indent=2) + "\n", encoding="utf-8")
PY

if scripts/openapi-breaking-change-check.sh "$candidate" \
    "$(basename "$negative_fixture")" >"$negative_output" 2>&1; then
  echo "Breaking-change gate accepted a removed public operation." >&2
  exit 1
fi

echo "OpenAPI breaking-change negative fixture PASS (removed /customers was rejected)"
