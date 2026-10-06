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
PROJECT="${THAAP_STACK_CHECK_PROJECT:-thaap-stack-check-$$}"
API_PORT="${THAAP_STACK_CHECK_API_PORT:-58080}"
UI_PORT="${THAAP_STACK_CHECK_UI_PORT:-58081}"
KEEP="${THAAP_STACK_CHECK_KEEP:-false}"

# The sixteen public and four operational prefixes routed to the API. Held equal to
# vite.config.ts and nginx.conf by src/routingContract.test.ts.
PREFIXES=(/risk /developer /customers /payment_methods /payment_intents /webhook_endpoints /webhook_deliveries /chargebacks /fraud /ledger /payment_attempts /post_authorization_signals /refunds /reviews /rules /simulator /actuator /admin /internal /v3)

# The identity this stack is built with. `release-identity-check.sh` proves the image *contains*
# the right version and commit; this proves a running instance *serves* them over HTTP, which is
# the form of the promise an operator actually uses. Building with the real values here rather
# than the compose defaults is what makes that assertion mean something.
BUILD_COMMIT="$(git -C "$ROOT_DIR" rev-parse HEAD 2>/dev/null || echo unknown)"
if [[ -n "$(git -C "$ROOT_DIR" status --porcelain 2>/dev/null)" ]]; then
    BUILD_COMMIT="${BUILD_COMMIT}-dirty"
fi
BUILD_VERSION="$(bash "$ROOT_DIR/scripts/project-version.sh" 2>/dev/null || echo unknown)"

compose() {
    THAAP_BUILD_COMMIT="$BUILD_COMMIT" \
    THAAP_BUILD_VERSION="$BUILD_VERSION" \
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
# Refuse an occupied namespace before registering cleanup. A project name is not evidence
# of ownership: a prior run may intentionally have retained its database.
if [[ ! "$PROJECT" =~ ^[a-z0-9][a-z0-9_-]+$ ]]; then
    echo "FAIL: invalid proof project name" >&2
    exit 1
fi
for resource in container volume network; do
    case "$resource" in
        container) occupied="$(docker ps -aq --filter "label=com.docker.compose.project=$PROJECT")" ;;
        volume) occupied="$(docker volume ls -q --filter "label=com.docker.compose.project=$PROJECT")" ;;
        network) occupied="$(docker network ls -q --filter "label=com.docker.compose.project=$PROJECT")" ;;
    esac
    if [[ -n "$occupied" ]]; then
        echo "FAIL: $PROJECT already owns $resource resources; refusing reuse or cleanup" >&2
        exit 1
    fi
done
trap cleanup EXIT

echo "building and starting the $PROJECT stack..."
compose up -d --build

echo "waiting for readiness..."
ready=false
for _ in $(seq 1 60); do
    if curl --max-time 10 --fail --silent "http://127.0.0.1:${API_PORT}/actuator/health/readiness" \
            | grep -Fxq '{"status":"UP"}'; then
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
curl --max-time 10 --fail --silent "http://127.0.0.1:${API_PORT}/actuator/health/liveness" | grep -Fxq '{"status":"UP"}'
echo "ok: liveness reports UP"

failures=0

# Release identity, over HTTP, from a running instance. This is the exact question asked during an
# incident by someone who has a URL and no shell, and the only honest answer comes from the process
# itself rather than from what a deployment pipeline believes it shipped.
info="$(curl --max-time 10 --fail --silent "http://127.0.0.1:${API_PORT}/actuator/info" || true)"
if ! grep -q "\"version\":\"${BUILD_VERSION}\"" <<<"$info"; then
    echo "FAIL: /actuator/info did not report version $BUILD_VERSION" >&2
    echo "$info" >&2
    failures=$((failures + 1))
elif ! grep -q "\"commit\":\"${BUILD_COMMIT}\"" <<<"$info"; then
    echo "FAIL: /actuator/info did not report commit $BUILD_COMMIT" >&2
    echo "$info" >&2
    failures=$((failures + 1))
else
    echo "ok: the running instance reports $BUILD_VERSION at $BUILD_COMMIT"
fi

# The workspace shell and its deep links. A routed SPA that 404s on reload is broken for every
# link an analyst shares.
for path in / /healthz /workspace/investigation; do
    code="$(curl --max-time 10 -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${UI_PORT}${path}")"
    if [[ "$code" != "200" ]]; then
        echo "FAIL: workspace $path returned $code" >&2
        failures=$((failures + 1))
    fi
done
(( failures == 0 )) && echo "ok: workspace serves its shell and deep links"

# Probe a missing child under every root. Real application errors are JSON and carry requestId;
# a SPA 200, nginx 404 or unavailable upstream does not satisfy that contract.
for prefix in "${PREFIXES[@]}"; do
    response="$(curl --max-time 10 -s -i "http://127.0.0.1:${UI_PORT}${prefix}/__routing_probe_missing__" || true)"
    if ! grep -qi '^Content-Type: application/json' <<<"$response" \
            || ! grep -q '"requestId"' <<<"$response" \
            || ! grep -qE '^HTTP/[0-9.]+ (400|401|403|404|405) ' <<<"$response"; then
        echo "FAIL: $prefix did not return a real API rejection" >&2
        failures=$((failures + 1))
    fi
done
(( failures == 0 )) && echo "ok: all ${#PREFIXES[@]} routed prefixes return API errors, not SPA HTML"

# Flyway must have taken a fresh database all the way to head, in one pass, with no failed row.
migrations="$(compose exec -T postgres psql -X -A -t -U "${THAAP_POSTGRES_USER:-thaap}" \
    -d "${THAAP_POSTGRES_DB:-thaap_payments}" \
    -c "select count(*) from flyway_schema_history where success and type in ('SQL','JDBC')" 2>/dev/null | tr -d '[:space:]')"
failed="$(compose exec -T postgres psql -X -A -t -U "${THAAP_POSTGRES_USER:-thaap}" \
    -d "${THAAP_POSTGRES_DB:-thaap_payments}" \
    -c "select count(*) from flyway_schema_history where not success" 2>/dev/null | tr -d '[:space:]')"

versions="$(compose exec -T postgres psql -X -A -t -U "${THAAP_POSTGRES_USER:-thaap}" \
    -d "${THAAP_POSTGRES_DB:-thaap_payments}" \
    -c "select count(distinct version) from flyway_schema_history where success and type in ('SQL','JDBC') and version::integer between 1 and 91" | tr -d '[:space:]')"
if [[ "$migrations" != "91" || "$versions" != "91" || "$failed" != "0" ]]; then
    echo "FAIL: migrations did not complete cleanly (applied=$migrations failed=$failed)" >&2
    failures=$((failures + 1))
else
    echo "ok: $migrations migrations applied on a fresh database, none failed"
fi

if (( failures > 0 )); then
    echo "compose stack check FAILED with $failures problem(s)" >&2
    exit 1
fi

if [[ "${THAAP_STACK_CHECK_JOURNEYS:-false}" == "true" ]]; then
    export THAAP_BASE_URL="http://127.0.0.1:${UI_PORT}"
    export THAAP_POSTGRES_CONTAINER="${PROJECT}-postgres-1"
    export THAAP_ADMIN_BOOTSTRAP_TOKEN="${THAAP_ADMIN_BOOTSTRAP_TOKEN:-local-admin-bootstrap-token}"
    wait_journey_health() {
        local healthy=false
        for _ in $(seq 1 60); do
            if curl --max-time 5 --fail --silent "$THAAP_BASE_URL/actuator/health" \
                | python3 -c 'import json,sys; sys.exit(0 if json.load(sys.stdin).get("status") == "UP" else 1)' 2>/dev/null; then
                healthy=true
                break
            fi
            sleep 1
        done
        [[ "$healthy" == "true" ]] || { echo "FAIL: health did not recover between journeys" >&2; return 1; }
    }
    for journey in feature-lineage fraud-decision-loop governed-controls \
        payment-investigation saved-views-and-bulk-review workspace-insights; do
        wait_journey_health
        python3 "$ROOT_DIR/scripts/demo-${journey}.py"
    done
    wait_journey_health
    python3 "$ROOT_DIR/scripts/demo-processor-outcome-catalog.py" --base-url "$THAAP_BASE_URL"
fi
echo "compose stack check passed"
