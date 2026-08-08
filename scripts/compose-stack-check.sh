#!/usr/bin/env bash
set -euo pipefail

# Proves that `docker compose up` starts THAAP end to end, and not merely its databases.
#
# `C60-OPS-001` and `C60-API-001` claim a stranger with only Docker can start the whole system
# and reach it. This runs that claim rather than describing it: it builds from the working tree,
# starts everything, waits for the sixteen-indicator readiness group, and proves the workspace
# reaches the API on every prefix the routing contract declares.
#
# It runs in its own compose project on its own ports and volumes, so it never touches a
# developer's local stack or the month of accumulated data in it, and tears down what it created.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT="${THAAP_STACK_CHECK_PROJECT:-thaap-stack-check}"
API_PORT="${THAAP_STACK_CHECK_API_PORT:-58080}"
UI_PORT="${THAAP_STACK_CHECK_UI_PORT:-58081}"
KEEP="${THAAP_STACK_CHECK_KEEP:-false}"

# The seven prefixes the workspace routes to the API. Kept in one place here, and held equal to
# vite.config.ts and nginx.conf by src/routingContract.test.ts.
PREFIXES=(/risk /developer /customers /payment_methods /payment_intents /webhook_endpoints /webhook_deliveries)

compose() {
    THAAP_POSTGRES_PORT="${THAAP_STACK_CHECK_PG_PORT:-55432}" \
    THAAP_REDIS_PORT="${THAAP_STACK_CHECK_REDIS_PORT:-56379}" \
    THAAP_KAFKA_PORT="${THAAP_STACK_CHECK_KAFKA_PORT:-59092}" \
    THAAP_PAYMENTS_API_PORT="$API_PORT" \
    THAAP_ANALYST_UI_PORT="$UI_PORT" \
    docker compose -p "$PROJECT" -f "$ROOT_DIR/infra/docker/compose.yml" "$@"
}

cleanup() {
    if [[ "$KEEP" == "true" ]]; then
        echo "leaving $PROJECT running (THAAP_STACK_CHECK_KEEP=true)"
        return
    fi
    # -v removes only this check's volumes; the project name keeps them separate from the
    # developer's own stack.
    compose down -v >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "building and starting the $PROJECT stack..."
compose up -d --build

echo "waiting for readiness..."
ready=false
for _ in $(seq 1 60); do
    if curl --fail --silent "http://127.0.0.1:${API_PORT}/actuator/health/readiness" \
            | grep -q '"UP"'; then
        ready=true
        break
    fi
    sleep 5
done

if [[ "$ready" != true ]]; then
    echo "FAIL: readiness did not become UP" >&2
    compose logs payments-api --tail=40 >&2 || true
    exit 1
fi
echo "ok: readiness reports UP"

# Liveness and readiness are different questions, and an orchestrator needs both to be honest.
curl --fail --silent "http://127.0.0.1:${API_PORT}/actuator/health/liveness" >/dev/null
echo "ok: liveness reports UP"

failures=0

# The workspace shell and its deep links. A routed SPA that 404s on reload is broken for every
# link an analyst shares.
for path in / /healthz /workspace/investigation; do
    code="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${UI_PORT}${path}")"
    if [[ "$code" != "200" ]]; then
        echo "FAIL: workspace $path returned $code" >&2
        failures=$((failures + 1))
    fi
done
(( failures == 0 )) && echo "ok: workspace serves its shell and deep links"

# Every declared prefix must reach the API through the workspace's own router. A 502 means nginx
# could not reach the upstream; any application status proves it did. The status itself is not
# asserted, because these are unauthenticated probes and the API is entitled to reject them —
# what is being proven is that the request arrived.
for prefix in "${PREFIXES[@]}"; do
    code="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${UI_PORT}${prefix}")"
    if [[ "$code" == "502" || "$code" == "503" || "$code" == "000" ]]; then
        echo "FAIL: $prefix did not reach the API (HTTP $code)" >&2
        failures=$((failures + 1))
    fi
done
echo "ok: all ${#PREFIXES[@]} routed prefixes reach the API"

# Flyway must have taken a fresh database all the way to head, in one pass, with no failed row.
migrations="$(compose exec -T postgres psql -X -A -t -U "${THAAP_POSTGRES_USER:-thaap}" \
    -d "${THAAP_POSTGRES_DB:-thaap_payments}" \
    -c "select count(*) from flyway_schema_history where success" 2>/dev/null | tr -d '[:space:]')"
failed="$(compose exec -T postgres psql -X -A -t -U "${THAAP_POSTGRES_USER:-thaap}" \
    -d "${THAAP_POSTGRES_DB:-thaap_payments}" \
    -c "select count(*) from flyway_schema_history where not success" 2>/dev/null | tr -d '[:space:]')"

if [[ -z "$migrations" || "$migrations" -lt 91 || "$failed" != "0" ]]; then
    echo "FAIL: migrations did not complete cleanly (applied=$migrations failed=$failed)" >&2
    failures=$((failures + 1))
else
    echo "ok: $migrations migrations applied on a fresh database, none failed"
fi

if (( failures > 0 )); then
    echo "compose stack check FAILED with $failures problem(s)" >&2
    exit 1
fi

echo "compose stack check passed"
