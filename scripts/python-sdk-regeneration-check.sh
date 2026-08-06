#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
sdk_scratch="$(mktemp -d)"
trap 'rm -rf "$sdk_scratch"' EXIT

docker run --rm -v "$PWD:/local" -v "$sdk_scratch:/out" \
  openapitools/openapi-generator-cli:v7.22.0 generate \
  -i /local/openapi/thaap-public-api-2026-08-01.yaml \
  -g python -o /out --package-name thaap_sdk.generated \
  --additional-properties=projectName=thaap-risk-sdk,packageVersion=0.1.0,library=urllib3,hideGenerationTimestamp=true,generateSourceCodeOnly=true \
  >/dev/null
rm -rf "$sdk_scratch/thaap_sdk/generated/docs" "$sdk_scratch/thaap_sdk/generated/test"
python3 scripts/stamp-python-sdk.py "$sdk_scratch/thaap_sdk/generated"
diff -ru "$sdk_scratch/thaap_sdk/generated" \
  clients/python/thaap_sdk/thaap_sdk/generated
echo "Python SDK regeneration PASS"
