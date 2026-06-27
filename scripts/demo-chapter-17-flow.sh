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
    echo "Chapter 17 invariant failed: ${claim}; expected=${expected} actual=${actual}" >&2
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
    raise SystemExit("Chapter 17 demo accepts only a loopback API origin")
PY

if ! curl -fsS "${BASE_URL}/actuator/health" >/dev/null; then
  echo "payments-api is not reachable at ${BASE_URL}" >&2
  echo "Start it with: scripts/payments-api-run.sh" >&2
  exit 1
fi

echo "THAAP Chapter 17 automatic capture and authorization void demo"
echo "Base URL: ${BASE_URL}"

merchant_response="$(post_json "/admin/merchants" "{\"name\":\"Chapter 17 Demo ${RUN_ID}\"}" \
  -H "X-Admin-Bootstrap-Token: ${ADMIN_BOOTSTRAP_TOKEN}" \
  -H "X-Request-Id: chapter17-${RUN_ID}-merchant")"
merchant_id="$(json_get "$merchant_response" "id")"
api_key="$(json_get "$merchant_response" "apiKey")"

customer_response="$(post_json "/customers" "{\"email\":\"chapter17-${RUN_ID}@example.com\"}" \
  -H "X-Api-Key: ${api_key}" \
  -H "X-Request-Id: chapter17-${RUN_ID}-customer")"
customer_id="$(json_get "$customer_response" "id")"

payment_method_response="$(post_json "/payment_methods" \
  "{\"customerId\":\"${customer_id}\",\"cardNumber\":\"4999990000000001\"}" \
  -H "X-Api-Key: ${api_key}" \
  -H "Idempotency-Key: chapter17-${RUN_ID}-payment-method" \
  -H "X-Request-Id: chapter17-${RUN_ID}-payment-method")"
payment_method_id="$(json_get "$payment_method_response" "id")"

automatic_intent_response="$(post_json "/payment_intents" \
  "{\"customerId\":\"${customer_id}\",\"amount\":4200,\"currency\":\"USD\",\"captureMethod\":\"AUTOMATIC\"}" \
  -H "X-Api-Key: ${api_key}" \
  -H "Idempotency-Key: chapter17-${RUN_ID}-automatic-intent")"
automatic_intent_id="$(json_get "$automatic_intent_response" "id")"
automatic_attempt_response="$(post_json "/payment_intents/${automatic_intent_id}/confirm" \
  "{\"paymentMethodId\":\"${payment_method_id}\"}" \
  -H "X-Api-Key: ${api_key}" \
  -H "Idempotency-Key: chapter17-${RUN_ID}-automatic-confirm" \
  -H "X-Request-Id: chapter17-${RUN_ID}-automatic-confirm")"
automatic_attempt_id="$(json_get "$automatic_attempt_response" "id")"
automatic_intent="$(get_json "/payment_intents/${automatic_intent_id}" -H "X-Api-Key: ${api_key}")"
automatic_status="$(json_get "$automatic_intent" "status")"
assert_equal "$automatic_status" "CAPTURED" "approved AUTOMATIC confirm must capture"

manual_intent_response="$(post_json "/payment_intents" \
  "{\"customerId\":\"${customer_id}\",\"amount\":4300,\"currency\":\"USD\",\"captureMethod\":\"MANUAL\"}" \
  -H "X-Api-Key: ${api_key}" \
  -H "Idempotency-Key: chapter17-${RUN_ID}-manual-intent")"
manual_intent_id="$(json_get "$manual_intent_response" "id")"
manual_attempt_response="$(post_json "/payment_intents/${manual_intent_id}/confirm" \
  "{\"paymentMethodId\":\"${payment_method_id}\"}" \
  -H "X-Api-Key: ${api_key}" \
  -H "Idempotency-Key: chapter17-${RUN_ID}-manual-confirm" \
  -H "X-Request-Id: chapter17-${RUN_ID}-manual-confirm")"
manual_attempt_id="$(json_get "$manual_attempt_response" "id")"
manual_authorized_intent="$(get_json "/payment_intents/${manual_intent_id}" -H "X-Api-Key: ${api_key}")"
manual_authorized_status="$(json_get "$manual_authorized_intent" "status")"
assert_equal "$manual_authorized_status" "REQUIRES_CAPTURE" \
  "approved MANUAL confirm must wait for capture"

canceled_intent="$(post_json "/payment_intents/${manual_intent_id}/cancel" "{}" \
  -H "X-Api-Key: ${api_key}" \
  -H "Idempotency-Key: chapter17-${RUN_ID}-manual-cancel" \
  -H "X-Request-Id: chapter17-${RUN_ID}-manual-cancel")"
canceled_status="$(json_get "$canceled_intent" "status")"
assert_equal "$canceled_status" "CANCELED" "approved uncaptured MANUAL intent must cancel"

voided_authorization="$(get_json "/payment_attempts/${manual_attempt_id}/authorization" \
  -H "X-Api-Key: ${api_key}")"
voided="$(json_get "$voided_authorization" "voided")"
void_status="$(json_get "$voided_authorization" "authorizationVoidStatus")"
void_reason="$(json_get "$voided_authorization" "authorizationVoidReason")"
assert_equal "$voided" "True" "post-authorization cancel must expose void evidence"
assert_equal "$void_status" "RECORDED" "void status must be honest simulator-local truth"
assert_equal "$void_reason" "MERCHANT_CANCELED" "void reason must preserve merchant intent"

ledger_entries="$(get_json "/ledger/entries?limit=100" -H "X-Api-Key: ${api_key}")"
ledger_count="$(
  JSON_INPUT="$ledger_entries" \
  AUTOMATIC_INTENT_ID="$automatic_intent_id" \
  MANUAL_INTENT_ID="$manual_intent_id" \
  python3 - <<'PY'
import json
import os

entries = json.loads(os.environ["JSON_INPUT"])
automatic_intent_id = os.environ["AUTOMATIC_INTENT_ID"]
manual_intent_id = os.environ["MANUAL_INTENT_ID"]
automatic_entries = [entry for entry in entries if entry["sourceId"] == automatic_intent_id]
manual_entries = [entry for entry in entries if entry["sourceId"] == manual_intent_id]


def fail(claim, actual):
    raise SystemExit(f"Chapter 17 invariant failed: {claim}; actual={actual}")


if len(entries) != 2:
    fail("the fresh demo merchant must have exactly two ledger rows", len(entries))
if len(automatic_entries) != 2:
    fail("both ledger rows must belong to the automatic PaymentIntent", automatic_entries)
if manual_entries:
    fail("the canceled manual PaymentIntent must have no ledger rows", manual_entries)

expected_postings = {
    ("CUSTOMER_RECEIVABLE", "DEBIT"),
    ("MERCHANT_PAYABLE", "CREDIT"),
}
actual_postings = {(entry["account"], entry["direction"]) for entry in automatic_entries}
if actual_postings != expected_postings:
    fail("automatic capture must use the reviewed account/direction pair", actual_postings)
if any(entry["sourceType"] != "PAYMENT_INTENT_CAPTURE" for entry in automatic_entries):
    fail("automatic ledger rows must use PAYMENT_INTENT_CAPTURE", automatic_entries)
if any(entry["amount"] != 4200 or entry["currency"] != "USD" for entry in automatic_entries):
    fail("automatic ledger rows must preserve the captured amount and currency", automatic_entries)
if len({entry["transactionGroupId"] for entry in automatic_entries}) != 1:
    fail("automatic ledger rows must share one transaction group", automatic_entries)

debits = sum(entry["amount"] for entry in automatic_entries if entry["direction"] == "DEBIT")
credits = sum(entry["amount"] for entry in automatic_entries if entry["direction"] == "CREDIT")
if debits != credits:
    fail("automatic capture ledger rows must balance", {"debits": debits, "credits": credits})

print(len(entries))
PY
)"

echo "Merchant: ${merchant_id}"
echo "Automatic PaymentIntent: ${automatic_intent_id} (${automatic_status})"
echo "Automatic PaymentAttempt: ${automatic_attempt_id}"
echo "Manual PaymentIntent: ${manual_intent_id} (${canceled_status})"
echo "Manual PaymentAttempt: ${manual_attempt_id}"
echo "Authorization void: ${void_status}/${void_reason}"
echo "Ledger entries: ${ledger_count}"
echo "Chapter 17 demo passed."
