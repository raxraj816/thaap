#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
sdk_scratch="$(mktemp -d)"
generation_lock="build/python-sdk-generation.lock"
owns_generation_lock=false
cleanup() {
  rm -rf "$sdk_scratch"
  if [[ "$owns_generation_lock" == "true" ]]; then
    rmdir "$generation_lock"
  fi
}
trap cleanup EXIT
mkdir -p build
if ! mkdir "$generation_lock" 2>/dev/null; then
  echo "Python SDK generation is already running; refusing an interleaved output write." >&2
  exit 1
fi
owns_generation_lock=true

docker run --rm -v "$PWD:/local" -v "$sdk_scratch:/out" \
  openapitools/openapi-generator-cli:v7.22.0 generate \
  -i /local/openapi/thaap-public-api-2026-08-01.yaml \
  -g python -o /out --package-name thaap_sdk.generated \
  --additional-properties=projectName=thaap-risk-sdk,packageVersion=0.1.0,library=urllib3,hideGenerationTimestamp=true,generateSourceCodeOnly=true \
  >/dev/null
rm -rf "$sdk_scratch/thaap_sdk/generated/docs" "$sdk_scratch/thaap_sdk/generated/test"
python3 scripts/stamp-python-sdk.py "$sdk_scratch/thaap_sdk/generated"
# grep, not rg: a missing binary makes the `if` condition false and silently skips
# the guard, which would let an unresolved placeholder ship in a published SDK.
placeholder_scan_status=0
grep -rn 'ERROR_TO_EXAMPLE_VALUE' "$sdk_scratch/thaap_sdk/generated" || placeholder_scan_status=$?
if [[ "$placeholder_scan_status" -eq 0 ]]; then
  echo "Generated SDK contains an unresolved example-value placeholder." >&2
  exit 1
elif [[ "$placeholder_scan_status" -ne 1 ]]; then
  echo "Placeholder scan failed with status $placeholder_scan_status; refusing to publish." >&2
  exit 1
fi
rsync -a --delete "$sdk_scratch/thaap_sdk/generated/" \
  clients/python/thaap_sdk/thaap_sdk/generated/
echo "Python SDK generated from openapi/thaap-public-api-2026-08-01.yaml"
