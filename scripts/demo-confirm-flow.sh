#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${THAAP_BASE_URL:-http://localhost:8080}"
ADMIN_BOOTSTRAP_TOKEN="${THAAP_ADMIN_BOOTSTRAP_TOKEN:-change-me-local-admin-bootstrap-token}"
CARD_NUMBER="${THAAP_DEMO_CARD:-4999990000000001}"
RUN_ID="$(date +%Y%m%d%H%M%S)"

require_python() {
  if ! command -v python3 >/dev/null 2>&1; then
    echo "python3 is required for JSON extraction" >&2
    exit 1
  fi
}

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

require_python

if ! curl -fsS "${BASE_URL}/actuator/health" >/dev/null; then
  echo "payments-api is not reachable at ${BASE_URL}" >&2
  echo "Start it with: scripts/payments-api-run.sh" >&2
  exit 1
fi

echo "THAAP Payment confirmation confirm demo"
echo "Base URL: ${BASE_URL}"
echo "Card: ${CARD_NUMBER}"
echo

merchant_response="$(post_json "/admin/merchants" "{\"name\":\"Demo Merchant ${RUN_ID}\"}" \
  -H "X-Admin-Bootstrap-Token: ${ADMIN_BOOTSTRAP_TOKEN}" \
  -H "X-Request-Id: demo-${RUN_ID}-merchant")"
merchant_id="$(json_get "$merchant_response" "id")"
api_key="$(json_get "$merchant_response" "apiKey")"
echo "Merchant: ${merchant_id}"

customer_response="$(post_json "/customers" "{\"email\":\"buyer-${RUN_ID}@example.com\",\"phone\":\"+155501${RUN_ID: -4}\"}" \
  -H "X-Api-Key: ${api_key}" \
  -H "X-Request-Id: demo-${RUN_ID}-customer")"
customer_id="$(json_get "$customer_response" "id")"
echo "Customer: ${customer_id}"

payment_method_response="$(post_json "/payment_methods" "{\"customerId\":\"${customer_id}\",\"cardNumber\":\"${CARD_NUMBER}\"}" \
  -H "X-Api-Key: ${api_key}" \
  -H "Idempotency-Key: demo-${RUN_ID}-payment-method" \
  -H "X-Request-Id: demo-${RUN_ID}-payment-method")"
payment_method_id="$(json_get "$payment_method_response" "id")"
echo "PaymentMethod: ${payment_method_id}"

payment_intent_response="$(post_json "/payment_intents" "{\"customerId\":\"${customer_id}\",\"amount\":4200,\"currency\":\"USD\",\"captureMethod\":\"MANUAL\",\"description\":\"Payment confirmation demo payment\",\"metadata\":{\"demoRun\":\"${RUN_ID}\"}}" \
  -H "X-Api-Key: ${api_key}" \
  -H "Idempotency-Key: demo-${RUN_ID}-payment-intent" \
  -H "X-Request-Id: demo-${RUN_ID}-payment-intent")"
payment_intent_id="$(json_get "$payment_intent_response" "id")"
echo "PaymentIntent: ${payment_intent_id}"

payment_attempt_response="$(post_json "/payment_intents/${payment_intent_id}/confirm" "{\"paymentMethodId\":\"${payment_method_id}\"}" \
  -H "X-Api-Key: ${api_key}" \
  -H "Idempotency-Key: demo-${RUN_ID}-confirm" \
  -H "X-Request-Id: demo-${RUN_ID}-confirm")"
payment_attempt_id="$(json_get "$payment_attempt_response" "id")"
fraud_decision_id="$(json_get "$payment_attempt_response" "fraudDecisionId")"
attempt_status="$(json_get "$payment_attempt_response" "status")"
echo "PaymentAttempt: ${payment_attempt_id} (${attempt_status})"
echo "FraudDecision: ${fraud_decision_id}"
echo

echo "Fetched PaymentIntent"
get_json "/payment_intents/${payment_intent_id}" -H "X-Api-Key: ${api_key}"
echo
echo

echo "Fetched PaymentAttempt"
get_json "/payment_attempts/${payment_attempt_id}" -H "X-Api-Key: ${api_key}"
echo
echo

echo "Fetched FraudDecision detail"
get_json "/fraud/decisions/${fraud_decision_id}" -H "X-Api-Key: ${api_key}"
echo
echo

echo "Demo complete. Use THAAP_DEMO_CARD=4999990000000002 for issuer decline or 4999990000000008 for 3DS-required."
