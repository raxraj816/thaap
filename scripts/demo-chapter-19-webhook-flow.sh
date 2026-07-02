#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${THAAP_BASE_URL:-http://localhost:8080}"
ADMIN_BOOTSTRAP_TOKEN="${THAAP_ADMIN_BOOTSTRAP_TOKEN:-change-me-local-admin-bootstrap-token}"
CALLBACK_PORT="${THAAP_WEBHOOK_DEMO_PORT:-19019}"
RUN_ID="$(date +%Y%m%d%H%M%S)"
REPORT_DIR="build/reports/webhook-delivery"
SECRET_FILE="$(mktemp "${TMPDIR:-/tmp}/thaap-webhook-secret.XXXXXX")"
EVIDENCE_FILE="${REPORT_DIR}/chapter19-${RUN_ID}.jsonl"
FIXTURE_PID=""

cleanup() {
  rm -f "$SECRET_FILE"
  if [[ -n "$FIXTURE_PID" ]]; then
    kill "$FIXTURE_PID" >/dev/null 2>&1 || true
    wait "$FIXTURE_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT
umask 077
mkdir -p "$REPORT_DIR"

json_get() {
  JSON_INPUT="$1" JSON_PATH="$2" python3 - <<'PY'
import json
import os
value = json.loads(os.environ["JSON_INPUT"])
for part in os.environ["JSON_PATH"].split("."):
    value = value[int(part)] if isinstance(value, list) else value[part]
print(value)
PY
}

post_json() {
  local path="$1"
  local body="$2"
  shift 2
  curl -fsS -X POST "${BASE_URL}${path}" -H "Content-Type: application/json" "$@" -d "$body"
}

BASE_URL_TO_VALIDATE="$BASE_URL" python3 - <<'PY'
import os
from urllib.parse import urlsplit
parsed = urlsplit(os.environ["BASE_URL_TO_VALIDATE"])
if parsed.scheme not in {"http", "https"} or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
    raise SystemExit("Chapter 19 demo accepts only a loopback API origin")
if parsed.username or parsed.password or parsed.query or parsed.fragment:
    raise SystemExit("Chapter 19 demo API origin contains prohibited URL components")
PY

scripts/webhook-callback-fixture.py \
  --port "$CALLBACK_PORT" \
  --secret-file "$SECRET_FILE" \
  --output "$EVIDENCE_FILE" \
  --fail-first 1 &
FIXTURE_PID=$!

for _ in {1..20}; do
  if curl -fsS "http://127.0.0.1:${CALLBACK_PORT}/health" >/dev/null 2>&1; then
    break
  fi
  sleep 0.1
done
curl -fsS "http://127.0.0.1:${CALLBACK_PORT}/health" >/dev/null
curl -fsS "${BASE_URL}/actuator/health" >/dev/null

merchant_response="$(post_json "/admin/merchants" "{\"name\":\"Chapter 19 Demo ${RUN_ID}\"}" \
  -H "X-Admin-Bootstrap-Token: ${ADMIN_BOOTSTRAP_TOKEN}")"
api_key="$(json_get "$merchant_response" "apiKey")"

endpoint_response="$(post_json "/webhook_endpoints" \
  "{\"url\":\"http://127.0.0.1:${CALLBACK_PORT}/thaap\",\"enabledEvents\":[\"payment_intent.created\"]}" \
  -H "X-Api-Key: ${api_key}")"
json_get "$endpoint_response" "signingSecret" > "$SECRET_FILE"

customer_response="$(post_json "/customers" "{\"email\":\"chapter19-${RUN_ID}@example.com\"}" \
  -H "X-Api-Key: ${api_key}")"
customer_id="$(json_get "$customer_response" "id")"
payment_response="$(post_json "/payment_intents" \
  "{\"customerId\":\"${customer_id}\",\"amount\":1900,\"currency\":\"USD\"}" \
  -H "X-Api-Key: ${api_key}" \
  -H "Idempotency-Key: chapter19-${RUN_ID}-payment")"
payment_intent_id="$(json_get "$payment_response" "id")"
initial_payment_status="$(json_get "$payment_response" "status")"
initial_ledger="$(curl -fsS "${BASE_URL}/ledger/entries" -H "X-Api-Key: ${api_key}")"

deliveries="$(curl -fsS "${BASE_URL}/webhook_deliveries?limit=10" -H "X-Api-Key: ${api_key}")"
delivery_id="$(json_get "$deliveries" "0.id")"

for _ in {1..50}; do
  delivery="$(curl -fsS "${BASE_URL}/webhook_deliveries/${delivery_id}" -H "X-Api-Key: ${api_key}")"
  status="$(json_get "$delivery" "delivery.status")"
  if [[ "$status" == "SUCCEEDED" ]]; then
    break
  fi
  sleep 2
done

if [[ "${status:-}" != "SUCCEEDED" ]]; then
  echo "Webhook did not succeed. Start payments-api with THAAP_WEBHOOK_EXECUTION_ENABLED=true" >&2
  exit 1
fi

attempts="$(curl -fsS "${BASE_URL}/webhook_deliveries/${delivery_id}/attempts?limit=10" \
  -H "X-Api-Key: ${api_key}")"
final_payment="$(curl -fsS "${BASE_URL}/payment_intents/${payment_intent_id}" \
  -H "X-Api-Key: ${api_key}")"
final_payment_status="$(json_get "$final_payment" "status")"
final_ledger="$(curl -fsS "${BASE_URL}/ledger/entries" -H "X-Api-Key: ${api_key}")"

EVIDENCE_FILE="$EVIDENCE_FILE" ATTEMPTS="$attempts" PAYMENT_INTENT_ID="$payment_intent_id" \
  INITIAL_PAYMENT_STATUS="$initial_payment_status" FINAL_PAYMENT_STATUS="$final_payment_status" \
  INITIAL_LEDGER="$initial_ledger" FINAL_LEDGER="$final_ledger" python3 - <<'PY'
import json
import os
from pathlib import Path
evidence = [json.loads(line) for line in Path(os.environ["EVIDENCE_FILE"]).read_text().splitlines()]
attempts = json.loads(os.environ["ATTEMPTS"])
if len(evidence) != 2 or [item["responseStatus"] for item in evidence] != [503, 204]:
    raise SystemExit("Chapter 19 demo did not observe deterministic failure then success")
if not all(item["signatureVerified"] for item in evidence):
    raise SystemExit("Chapter 19 callback signature verification failed")
if [item["businessEffectApplied"] for item in evidence] != [False, True]:
    raise SystemExit("Chapter 19 callback did not apply exactly one durable business effect")
if any(item["duplicate"] for item in evidence):
    raise SystemExit("Chapter 19 failure-then-success flow was incorrectly classified as a duplicate")
if len({item["eventId"] for item in evidence}) != 1:
    raise SystemExit("Chapter 19 retry changed immutable event identity")
if len({item["payloadSha256"] for item in evidence}) != 1:
    raise SystemExit("Chapter 19 retry changed immutable payload bytes")
if len(attempts) != 2 or {item["outcome"] for item in attempts} != {"RETRYABLE_FAILURE", "SUCCEEDED"}:
    raise SystemExit("Chapter 19 attempt evidence is incomplete")
if os.environ["INITIAL_PAYMENT_STATUS"] != os.environ["FINAL_PAYMENT_STATUS"]:
    raise SystemExit("Webhook execution changed originating PaymentIntent truth")
if json.loads(os.environ["INITIAL_LEDGER"]) != json.loads(os.environ["FINAL_LEDGER"]):
    raise SystemExit("Webhook execution changed ledger truth")
print(json.dumps({
    "deliveryId": evidence[0]["deliveryId"],
    "eventId": evidence[0]["eventId"],
    "paymentIntentId": os.environ["PAYMENT_INTENT_ID"],
    "receiverStatuses": [item["responseStatus"] for item in evidence],
    "signatureVerified": True,
    "businessEffectCount": sum(item["businessEffectApplied"] for item in evidence),
    "attemptCount": len(attempts),
    "paymentStatusUnchanged": os.environ["FINAL_PAYMENT_STATUS"],
    "ledgerUnchanged": True,
}, indent=2, sort_keys=True))
PY

echo "Sanitized receiver evidence: ${EVIDENCE_FILE}"
