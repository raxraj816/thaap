#!/usr/bin/env bash
set -euo pipefail

# Proves that running more than one API instance against one database is safe.
#
# `C60-CON-001` claims Flyway migrates exactly once and the eight lease-fenced `@Scheduled`
# workers do not double-process when replicas start together. The deployed service runs more than
# one task, so this is not a hypothetical: it is the deployment's normal state.
#
# The replicas are started from a single `docker run` batch against an EMPTY database, because a
# migration race only exists on the first boot. Starting them one after another proves nothing —
# the second would find the schema already at head and never contend for the lock.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT="${THAAP_REPLICA_CHECK_PROJECT:-thaap-replica-check}"
NETWORK="${PROJECT}_default"
REPLICAS="${THAAP_REPLICA_CHECK_REPLICAS:-2}"
IMAGE="${THAAP_REPLICA_CHECK_IMAGE:-thaap/payments-api:local}"
EXPECTED_MIGRATIONS="${THAAP_REPLICA_CHECK_MIGRATIONS:-91}"

compose() {
    THAAP_POSTGRES_PORT="${THAAP_REPLICA_CHECK_PG_PORT:-55433}" \
    THAAP_REDIS_PORT="${THAAP_REPLICA_CHECK_REDIS_PORT:-56380}" \
    THAAP_KAFKA_PORT="${THAAP_REPLICA_CHECK_KAFKA_PORT:-59093}" \
    docker compose -p "$PROJECT" -f "$ROOT_DIR/infra/docker/compose.yml" "$@"
}

psql_scalar() {
    compose exec -T postgres psql -X -A -t -U "${THAAP_POSTGRES_USER:-thaap}" \
        -d "${THAAP_POSTGRES_DB:-thaap_payments}" -c "$1" 2>/dev/null | tr -d '[:space:]'
}

cleanup() {
    for i in $(seq 1 "$REPLICAS"); do
        docker rm -f "${PROJECT}-api-${i}" >/dev/null 2>&1 || true
    done
    compose down -v >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "starting infrastructure on an empty database..."
# Only the dependencies. The API replicas are launched by hand so they start together rather
# than in whatever order compose happens to choose.
compose up -d --wait postgres redis kafka

if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
    echo "Image not found: $IMAGE — build it first" >&2
    exit 2
fi

applied_before="$(psql_scalar "select count(*) from flyway_schema_history" || echo 0)"
if [[ -n "$applied_before" && "$applied_before" != "0" ]]; then
    echo "FAIL: database is not empty ($applied_before migrations already applied)" >&2
    exit 1
fi
echo "ok: database is empty, so the migration lock is genuinely contended"

echo "launching $REPLICAS replicas simultaneously..."
for i in $(seq 1 "$REPLICAS"); do
    docker run -d --rm --name "${PROJECT}-api-${i}" --network "$NETWORK" \
        -e THAAP_POSTGRES_HOST=postgres -e THAAP_REDIS_HOST=redis \
        -e THAAP_KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
        -e THAAP_ADMIN_BOOTSTRAP_TOKEN=local-admin-bootstrap-token \
        -e THAAP_PLATFORM_BOOTSTRAP_TOKEN=local-platform-bootstrap-token \
        -e THAAP_SCENARIO_RUNNER_TOKEN=local-scenario-runner-token \
        -e THAAP_HASH_PEPPER=local-hash-pepper-not-for-production \
        -e THAAP_CORRELATION_PEPPER=local-correlation-pepper-not-for-production \
        -e THAAP_WEBHOOK_SECRET_ENCRYPTION_KEY='AXqcQyEyp+PaWc4kNkglchMezuXhDCBnqoQT/BRKEo0=' \
        -e THAAP_REVIEW_NOTE_ENCRYPTION_KEY='qLPaIJTvXW+fHN7xUJTuVzw+BQuCa3zXBsFonNM56Z0=' \
        -e THAAP_AUTHENTICATION_REPLAY_ENCRYPTION_KEY='s083f4o4N0LsdH3WLDjeJa/S4Ow+StpqsYeoMs0eCb0=' \
        "$IMAGE" >/dev/null &
done
wait
echo "ok: $REPLICAS replicas launched in one batch"

echo "waiting for every replica to report healthy..."
for i in $(seq 1 "$REPLICAS"); do
    healthy=false
    for _ in $(seq 1 60); do
        state="$(docker inspect --format '{{.State.Health.Status}}' \
            "${PROJECT}-api-${i}" 2>/dev/null || echo gone)"
        [[ "$state" == "healthy" ]] && { healthy=true; break; }
        [[ "$state" == "gone" ]] && break
        sleep 5
    done
    if [[ "$healthy" != true ]]; then
        echo "FAIL: replica $i never became healthy" >&2
        docker logs "${PROJECT}-api-${i}" --tail=40 2>&1 | tail -40 >&2 || true
        exit 1
    fi
done
echo "ok: all $REPLICAS replicas report readiness UP against one database"

failures=0

# Flyway's lock is the thing under test. Two replicas contending must leave one clean history:
# no duplicated version, no failed attempt, and the full set applied.
total="$(psql_scalar "select count(*) from flyway_schema_history")"
succeeded="$(psql_scalar "select count(*) from flyway_schema_history where success")"
duplicates="$(psql_scalar \
    "select count(*) from (select version from flyway_schema_history where version is not null \
     group by version having count(*) > 1) duplicated")"

if [[ "$total" != "$succeeded" ]]; then
    echo "FAIL: $((total - succeeded)) migration row(s) recorded a failure" >&2
    failures=$((failures + 1))
fi
if [[ "$duplicates" != "0" ]]; then
    echo "FAIL: $duplicates migration version(s) were applied more than once" >&2
    failures=$((failures + 1))
fi
if [[ "$succeeded" -lt "$EXPECTED_MIGRATIONS" ]]; then
    echo "FAIL: only $succeeded migrations applied, expected $EXPECTED_MIGRATIONS" >&2
    failures=$((failures + 1))
fi
(( failures == 0 )) && echo "ok: $succeeded migrations applied exactly once, none failed"

# Every worker claims through a database lease with a fencing epoch. Two replicas polling the
# same tables must never hold the same lease, which is what makes horizontal scaling safe without
# leader election.
overlapping="$(psql_scalar \
    "select count(*) from (select lease_owner, lease_token from event_publications \
     where lease_owner is not null and lease_expires_at > now() \
     group by lease_owner, lease_token having count(distinct lease_owner) > 1) contended")"
if [[ -n "$overlapping" && "$overlapping" != "0" ]]; then
    echo "FAIL: $overlapping publication lease(s) held by more than one owner" >&2
    failures=$((failures + 1))
else
    echo "ok: no publication lease is held by more than one replica"
fi

if (( failures > 0 )); then
    echo "multi-replica check FAILED with $failures problem(s)" >&2
    exit 1
fi

echo "multi-replica check passed"
