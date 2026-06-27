#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${THAAP_BASE_URL:-http://localhost:8080}"
ADMIN_BOOTSTRAP_TOKEN="${THAAP_ADMIN_BOOTSTRAP_TOKEN:-change-me-local-admin-bootstrap-token}"
RUN_ID="$(date +%Y%m%d%H%M%S)"

json_get() {
  local json="$1"
  local path="$2"
  JSON_INPUT="$json" JSON_PATH="$path" python3 - <<'PY'
import json
import os

value = json.loads(os.environ["JSON_INPUT"])
for part in os.environ["JSON_PATH"].split("."):
    value = value[part]
print(value)
PY
}

assert_equal() {
  local actual="$1"
  local expected="$2"
  local claim="$3"
  if [[ "$actual" != "$expected" ]]; then
    echo "Chapter 18 invariant failed: ${claim}; expected=${expected} actual=${actual}" >&2
    exit 1
  fi
}

post_json() {
  local path="$1"
  local body="$2"
  shift 2
  curl -fsS -X POST "${BASE_URL}${path}" \
    -H "Content-Type: application/json" \
    "$@" \
    -d "$body"
}

get_json() {
  local path="$1"
  shift
  curl -fsS "${BASE_URL}${path}" "$@"
}

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is required for JSON verification" >&2
  exit 1
fi

BASE_URL_TO_VALIDATE="$BASE_URL" python3 - <<'PY'
import os
from urllib.parse import urlsplit

value = os.environ["BASE_URL_TO_VALIDATE"]
parsed = urlsplit(value)
if (
    parsed.scheme not in {"http", "https"}
    or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}
    or parsed.username is not None
    or parsed.password is not None
    or parsed.path not in {"", "/"}
    or parsed.query
    or parsed.fragment
):
    raise SystemExit("Chapter 18 demo accepts only a loopback API origin")
PY

if ! curl -fsS "${BASE_URL}/actuator/health" >/dev/null; then
  echo "payments-api is not reachable at ${BASE_URL}" >&2
  echo "Start it with: scripts/payments-api-run.sh" >&2
  exit 1
fi

echo "THAAP Chapter 18 durable refund and ledger reversal demo"
echo "Base URL: ${BASE_URL}"

merchant_response="$(post_json "/admin/merchants" "{\"name\":\"Chapter 18 Demo ${RUN_ID}\"}" \
  -H "X-Admin-Bootstrap-Token: ${ADMIN_BOOTSTRAP_TOKEN}" \
  -H "X-Request-Id: chapter18-${RUN_ID}-merchant")"
merchant_id="$(json_get "$merchant_response" "id")"
api_key="$(json_get "$merchant_response" "apiKey")"

customer_response="$(post_json "/customers" "{\"email\":\"chapter18-${RUN_ID}@example.com\"}" \
  -H "X-Api-Key: ${api_key}" \
  -H "X-Request-Id: chapter18-${RUN_ID}-customer")"
customer_id="$(json_get "$customer_response" "id")"

payment_method_response="$(post_json "/payment_methods" \
  "{\"customerId\":\"${customer_id}\",\"cardNumber\":\"4999990000000001\"}" \
  -H "X-Api-Key: ${api_key}" \
  -H "Idempotency-Key: chapter18-${RUN_ID}-payment-method")"
payment_method_id="$(json_get "$payment_method_response" "id")"

post_json "/webhook_endpoints" \
  "{\"url\":\"https://chapter18-${RUN_ID}.example/webhooks\"}" \
  -H "X-Api-Key: ${api_key}" >/dev/null

payment_intent_response="$(post_json "/payment_intents" \
  "{\"customerId\":\"${customer_id}\",\"amount\":4200,\"currency\":\"USD\",\"captureMethod\":\"AUTOMATIC\"}" \
  -H "X-Api-Key: ${api_key}" \
  -H "Idempotency-Key: chapter18-${RUN_ID}-payment-intent")"
payment_intent_id="$(json_get "$payment_intent_response" "id")"

payment_attempt_response="$(post_json "/payment_intents/${payment_intent_id}/confirm" \
  "{\"paymentMethodId\":\"${payment_method_id}\"}" \
  -H "X-Api-Key: ${api_key}" \
  -H "Idempotency-Key: chapter18-${RUN_ID}-confirm")"
payment_attempt_id="$(json_get "$payment_attempt_response" "id")"
captured_intent="$(get_json "/payment_intents/${payment_intent_id}" -H "X-Api-Key: ${api_key}")"
assert_equal "$(json_get "$captured_intent" "status")" "CAPTURED" \
  "approved AUTOMATIC confirm must create capture truth before refund"

refund_response="$(post_json "/refunds" \
  "{\"paymentIntentId\":\"${payment_intent_id}\",\"amount\":4200}" \
  -H "X-Api-Key: ${api_key}" \
  -H "Idempotency-Key: chapter18-${RUN_ID}-refund" \
  -H "X-Request-Id: chapter18-${RUN_ID}-refund")"
refund_id="$(json_get "$refund_response" "id")"
authorization_id="$(json_get "$refund_response" "authorizationId")"
assert_equal "$(json_get "$refund_response" "status")" "RECORDED" \
  "refund must expose honest simulator-local status"
assert_equal "$(json_get "$refund_response" "paymentAttemptId")" "$payment_attempt_id" \
  "refund must preserve payment-attempt lineage"

refund_replay="$(post_json "/refunds" \
  "{\"paymentIntentId\":\"${payment_intent_id}\",\"amount\":4200}" \
  -H "X-Api-Key: ${api_key}" \
  -H "Idempotency-Key: chapter18-${RUN_ID}-refund")"
assert_equal "$(json_get "$refund_replay" "id")" "$refund_id" \
  "idempotent refund replay must return the original Refund"

stored_refund="$(get_json "/refunds/${refund_id}" -H "X-Api-Key: ${api_key}")"
assert_equal "$(json_get "$stored_refund" "id")" "$refund_id" "Refund must be durably retrievable"
refunded_intent="$(get_json "/payment_intents/${payment_intent_id}" -H "X-Api-Key: ${api_key}")"
assert_equal "$(json_get "$refunded_intent" "status")" "REFUNDED" \
  "full refund must move PaymentIntent to REFUNDED"

ledger_entries="$(get_json "/ledger/entries?limit=100" -H "X-Api-Key: ${api_key}")"
webhook_deliveries="$(get_json "/webhook_deliveries" -H "X-Api-Key: ${api_key}")"
JSON_LEDGER="$ledger_entries" \
JSON_WEBHOOKS="$webhook_deliveries" \
PAYMENT_INTENT_ID="$payment_intent_id" \
REFUND_ID="$refund_id" \
python3 - <<'PY'
import json
import os

entries = json.loads(os.environ["JSON_LEDGER"])
deliveries = json.loads(os.environ["JSON_WEBHOOKS"])
payment_intent_id = os.environ["PAYMENT_INTENT_ID"]
refund_id = os.environ["REFUND_ID"]


def fail(claim, actual):
    raise SystemExit(f"Chapter 18 invariant failed: {claim}; actual={actual}")


capture = [entry for entry in entries if entry["sourceType"] == "PAYMENT_INTENT_CAPTURE"]
refund = [entry for entry in entries if entry["sourceType"] == "REFUND"]
if len(entries) != 4 or len(capture) != 2 or len(refund) != 2:
    fail("demo must expose exactly one capture pair and one refund pair", entries)
if any(entry["paymentIntentId"] != payment_intent_id for entry in entries):
    fail("all ledger rows must preserve original PaymentIntent lineage", entries)
if any(entry["sourceId"] != payment_intent_id or entry["refundId"] is not None for entry in capture):
    fail("capture rows must remain sourced by PaymentIntent", capture)
if any(entry["sourceId"] != refund_id or entry["refundId"] != refund_id for entry in refund):
    fail("refund rows must be sourced by the durable Refund", refund)

expected_capture = {
    ("CUSTOMER_RECEIVABLE", "DEBIT"),
    ("MERCHANT_PAYABLE", "CREDIT"),
}
expected_refund = {
    ("MERCHANT_PAYABLE", "DEBIT"),
    ("CUSTOMER_RECEIVABLE", "CREDIT"),
}
if {(entry["account"], entry["direction"]) for entry in capture} != expected_capture:
    fail("capture account directions drifted", capture)
if {(entry["account"], entry["direction"]) for entry in refund} != expected_refund:
    fail("refund must exactly reverse capture account directions", refund)
for group_name, group in (("capture", capture), ("refund", refund)):
    if len({entry["transactionGroupId"] for entry in group}) != 1:
        fail(f"{group_name} rows must share one transaction group", group)
    if any(entry["amount"] != 4200 or entry["currency"] != "USD" for entry in group):
        fail(f"{group_name} amount/currency must match payment truth", group)
    debits = sum(entry["amount"] for entry in group if entry["direction"] == "DEBIT")
    credits = sum(entry["amount"] for entry in group if entry["direction"] == "CREDIT")
    if debits != credits:
        fail(f"{group_name} transaction group must balance", {"debits": debits, "credits": credits})

refund_deliveries = [
    delivery
    for delivery in deliveries
    if delivery["eventType"] == "refund.created" and delivery["resourceId"] == refund_id
]
if len(refund_deliveries) != 1:
    fail("exactly one refund.created delivery must identify the Refund", refund_deliveries)
if any(delivery["eventType"] == "payment_intent.refunded" for delivery in deliveries):
    fail("Chapter 18 must not emit deferred payment_intent.refunded deliveries", deliveries)
PY

echo "Merchant: ${merchant_id}"
echo "PaymentIntent: ${payment_intent_id} (REFUNDED)"
echo "PaymentAttempt: ${payment_attempt_id}"
echo "Authorization: ${authorization_id}"
echo "Refund: ${refund_id} (RECORDED)"
echo "Ledger entries: 4 (capture pair + exact refund reversal pair)"
echo "Chapter 18 demo passed."
