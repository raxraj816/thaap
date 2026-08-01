# Event Pipeline, Projection, And Replay Operations Runbook

## Purpose and safety model

This runbook operates THAAP's Phase 5 fact pipeline:

```text
committed payment/risk mutation
  -> immutable PostgreSQL domain event and publication row
  -> thaap.domain-events.v1
  -> inbox-idempotent payment-risk projection
  -> generation-qualified analytics
  -> shadow rebuild and point-in-time replay dataset
```

The PostgreSQL source-domain rows, immutable domain events, and immutable
bootstrap facts are authoritative. Kafka is a delivery log, projections are
rebuildable read models, and replay datasets are immutable derived evidence.
Never repair delivery by calling a payment, processor, ledger, review, label,
control, dispute, or webhook command again.

The non-negotiable recovery invariants are:

- do not edit or delete `domain_events`, `event_publications`, bootstrap facts,
  inbox evidence, cutoff manifests, rebuild evidence, or dataset evidence;
- do not change the partition count of `thaap.domain-events.v1`; use a new topic
  version and an explicit migration if capacity requires repartitioning;
- do not edit Kafka offsets manually; consumer recovery is inbox-idempotent;
- do not activate a shadow generation until catch-up and all reconciliation
  families pass at one captured source-head watermark;
- do not interpret `EMPTY`, `PARTIAL`, `STALE`, or `UNAVAILABLE` as numeric
  zero;
- use only a live loopback `DATA_OPERATOR` for publication, quarantine, and
  projection operations, and a tenant `RISK_MANAGER` for replay datasets;
- use a new stable `Idempotency-Key` for each human intent and reuse that exact
  key only to retry the same intent.

## Required local configuration

The examples assume the repository root and the default loopback API origin.
Keep credentials in shell environment variables; never put them in command
history, URLs, files, screenshots, logs, or support tickets.

Required values:

- `THAAP_BASE_URL` (default `http://localhost:8080`, loopback only)
- `THAAP_PLATFORM_BOOTSTRAP_TOKEN` for the one-time local platform bootstrap
- `THAAP_PLATFORM_OPERATOR_KEY` for a live `DATA_OPERATOR`
- `THAAP_RISK_OPERATOR_KEY` for the tenant-scoped replay-dataset API
- PostgreSQL, Redis, and Kafka configuration documented in
  `apps/payments-api/src/main/resources/application.yml`

Start durable local dependencies with:

```bash
docker compose -f infra/docker/compose.yml up -d postgres redis kafka
```

The application declares `thaap.domain-events.v1` with exactly six partitions
and `retention.ms=604800000`. Readiness rejects a missing topic or partition /
retention drift. Confirm the broker contract without changing it:

```bash
docker exec thaap-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --describe --topic thaap.domain-events.v1

docker exec thaap-kafka /opt/kafka/bin/kafka-configs.sh \
  --bootstrap-server localhost:9092 \
  --entity-type topics --entity-name thaap.domain-events.v1 --describe
```

The description must report six partitions and seven-day retention before the
service is considered ready.

## Triage order

1. Check `/actuator/health/readiness`. A red component is a safety signal, not
   permission to bypass it.
2. Determine which boundary is unhealthy: publication, broker, consumer,
   quarantine, active generation, rebuild, or replay dataset.
3. Record the request ID, safe closed outcome/reason code, component status,
   topic/partition where exposed, and timestamps. Do not record payloads,
   merchant/event/resource identifiers, credentials, or exception messages.
4. Stabilize the dependency before requesting retry. Retrying while Kafka or
   PostgreSQL is still unhealthy only consumes the bounded retry budget.
5. Recover through the bounded API or normal worker restart. Never mutate the
   evidence tables directly.
6. Verify readiness, backlog age, consumer progress, projection freshness, and
   unchanged source-domain counts/digests.

## Publisher pause and resume

Set `THAAP_EVENT_PUBLICATION_ENABLED=false` before starting a deliberately
publisher-disabled application instance. This preserves pending publication
rows but readiness remains explicit about the disabled component. Resume by
restarting with `THAAP_EVENT_PUBLICATION_ENABLED=true`; the leased worker
continues from durable pending/retryable rows.

Use the bounded health endpoint after authenticating a loopback data operator:

```bash
curl --fail-with-body --silent --show-error \
  -H "X-Platform-Operator-Key: ${THAAP_PLATFORM_OPERATOR_KEY}" \
  "${THAAP_BASE_URL:-http://localhost:8080}/internal/data/publications/health"
```

Do not pause the publisher by deleting rows, changing due times, or disabling
Kafka acknowledgements.

## Broker outage and recovery

During a Kafka outage, a successfully committed merchant API result remains
successful because the source mutation and event are already durable in
PostgreSQL. Publication attempts move through bounded safe outcomes and retry
with capped backoff; no payment command is repeated.

Recovery procedure:

1. Confirm PostgreSQL remains healthy and publication rows are retained.
2. Restore the broker and verify the exact topic contract above.
3. Wait for the normal publisher to reclaim due work.
4. Verify pending/oldest age fall, acknowledgements advance, and blocked count
   does not grow.
5. If a record reached `BLOCKED`, repair the underlying broker/configuration or
   record-integrity cause before using audited manual recovery.

An acknowledgement whose database finalization was ambiguous may publish a
duplicate. That is expected at-least-once behavior; the consumer inbox must
deduplicate it. Never relabel an unknown acknowledgement as a definite failure
or success by hand.

## Blocked publication recovery

Only a `BLOCKED` publication is eligible for human retry. Use a stable key for
one recovery intent:

```bash
curl --fail-with-body --silent --show-error -X POST \
  -H "X-Platform-Operator-Key: ${THAAP_PLATFORM_OPERATOR_KEY}" \
  -H "Idempotency-Key: ${RECOVERY_IDEMPOTENCY_KEY}" \
  "${THAAP_BASE_URL:-http://localhost:8080}/internal/data/publications/${PUBLICATION_ID}/retry"
```

Expected result is bounded lifecycle metadata only; raw event bytes are never
returned. Confirm the predecessor stream resumes in order and unrelated
streams continued while it was blocked.

## Consumer pause, restart, and rollback

Set `THAAP_PROJECTION_CONSUMER_ENABLED=false` only for a controlled deployment
or diagnosis. New events remain in Kafka and immutable PostgreSQL events remain
the rebuild authority. Resume by restarting with the consumer enabled.

Manual acknowledgement occurs only after the inbox and projection transaction
commits. Therefore:

- crash before commit: Kafka redelivers and no projection effect exists;
- crash after database commit but before acknowledgement: Kafka redelivers and
  inbox identity `(consumer_name, event_id)` makes it a no-op;
- same event ID with different complete bytes: integrity failure and
  quarantine, never a duplicate;
- dependency failure: retry without poison classification;
- protocol/schema/invariant failure: durable quarantine while safe unrelated
  streams continue.

Do not reset offsets. If an application release is incompatible with the
active handler/projection generation, roll the application configuration back
to the compatible version or activate only a fully reconciled compatible
generation.

## Poison event and quarantine recovery

List bounded quarantine evidence:

```bash
curl --fail-with-body --silent --show-error \
  -H "X-Platform-Operator-Key: ${THAAP_PLATFORM_OPERATOR_KEY}" \
  "${THAAP_BASE_URL:-http://localhost:8080}/internal/data/quarantines?status=OPEN&limit=100"
```

The response exposes closed reason/state metadata, never payload or exception
text. Before retry, deploy a compatible handler or repair the dependency; do
not edit the quarantined record. Then request an audited idempotent retry:

```bash
curl --fail-with-body --silent --show-error -X POST \
  -H "X-Platform-Operator-Key: ${THAAP_PLATFORM_OPERATOR_KEY}" \
  -H "Idempotency-Key: ${RECOVERY_IDEMPOTENCY_KEY}" \
  "${THAAP_BASE_URL:-http://localhost:8080}/internal/data/quarantines/${QUARANTINE_ID}/retry"
```

Verify the held stream resumes from its predecessor, the inbox digest matches
the immutable event, and unrelated partitions/streams never stopped.

## Projection drift and shadow rebuild

Never repair a live projection row directly. Start a shadow rebuild for the
capability name (`payment-risk-v1` or `merchant-risk-v1`) through the local
platform boundary:

```bash
curl --fail-with-body --silent --show-error -X POST \
  -H "Content-Type: application/json" \
  -H "X-Platform-Operator-Key: ${THAAP_PLATFORM_OPERATOR_KEY}" \
  -H "Idempotency-Key: ${REBUILD_IDEMPOTENCY_KEY}" \
  -d '{}' \
  "${THAAP_BASE_URL:-http://localhost:8080}/internal/data/projections/${PROJECTION_NAME}/rebuilds"
```

Poll `GET /internal/data/projections/{name}/rebuilds/{rebuildId}`. The rebuild
uses immutable per-stream/bootstrap cutoff membership, 100-input Spring Batch
chunks, a 60-second fenced lease, and generation-local stream positions. A
worker crash is resumed from durable chunk/checkpoint evidence; a stale worker
must not finalize after lease takeover.

After base replay and live catch-up, request reconciliation:

```bash
curl --fail-with-body --silent --show-error -X POST \
  -H "X-Platform-Operator-Key: ${THAAP_PLATFORM_OPERATOR_KEY}" \
  -H "Idempotency-Key: ${RECONCILE_IDEMPOTENCY_KEY}" \
  "${THAAP_BASE_URL:-http://localhost:8080}/internal/data/projections/${PROJECTION_NAME}/rebuilds/${REBUILD_ID}/reconcile"
```

All count, stream-position, content-digest, money/balance, and business
invariant families must pass against one captured current source-head
watermark. A mismatch is a failed release signal; investigate the handler or
source coverage and build a new shadow generation. Do not waive or edit it.

Activate only the current successful reconciliation attempt:

```bash
curl --fail-with-body --silent --show-error -X POST \
  -H "X-Platform-Operator-Key: ${THAAP_PLATFORM_OPERATOR_KEY}" \
  -H "Idempotency-Key: ${ACTIVATE_IDEMPOTENCY_KEY}" \
  "${THAAP_BASE_URL:-http://localhost:8080}/internal/data/projections/${PROJECTION_NAME}/rebuilds/${REBUILD_ID}/activate"
```

The pointer switch is atomic; partial shadow data is never merchant-visible.
Recheck both analytics freshness and source non-interference after activation.

## Cancel, resume, and generation rollback

Cancel a running rebuild with the documented cancel endpoint. Cancellation is
cooperative, durable, and fenced; it does not delete the shadow evidence or
change the active generation. Resume means start a new authorized rebuild or
allow an eligible leased worker to resume a nonterminal job according to the
returned lifecycle state—never rewrite a terminal state.

Rollback switches the active pointer to a retained, reconciled, handler-
compatible generation:

```bash
curl --fail-with-body --silent --show-error -X POST \
  -H "Content-Type: application/json" \
  -H "X-Platform-Operator-Key: ${THAAP_PLATFORM_OPERATOR_KEY}" \
  -H "Idempotency-Key: ${ROLLBACK_IDEMPOTENCY_KEY}" \
  -d "{\"generationId\":\"${RETAINED_GENERATION_ID}\"}" \
  "${THAAP_BASE_URL:-http://localhost:8080}/internal/data/projections/${PROJECTION_NAME}/rollback"
```

Rollback must reject an incompatible, partial, unreconciled, or non-retained
generation. It changes no source event or payment/fraud/ledger state.

## Offline replay dataset restart and compatibility

A tenant `RISK_MANAGER` creates an immutable dataset from an existing event
cutoff manifest and projection generation. The request must include explicit
cohort bounds, label-availability cutoff, and traffic-origin policy. A later
label is never allowed to leak backward into the cohort.

The worker resumes by fenced keyset checkpoint and immutable chunk evidence.
Do not submit a replacement request merely because an in-progress worker
restarted; poll `GET /risk/replay-datasets/{id}` with the same tenant operator.
Terminal manifests, item digests, inclusion/exclusion counts, and compatibility
evidence are append-only. A dataset may feed the governed simulation boundary
only when the exact approved overlap—population, eligibility, action, label,
money, origin, and feature lineage—passes.

## Schema and deployment compatibility

Apply Flyway migrations forward only. A retained deployment must migrate from
the actual pre-Phase-5 schema without inventing retroactive domain events.
Deploy in this order:

1. schema and code capable of storing/reading the new evidence;
2. topic contract validation;
3. payment/risk producers and publisher enablement;
4. compatible consumer and active generation;
5. analytics, rebuild, and dataset workers.

Before rolling back application code, confirm it can read the installed schema
and the active projection handler version. Never roll Flyway history or applied
migrations backward. Create a new forward capability-named migration for a
repair.

## Safe local reset

A reset is allowed only for disposable local development after explicitly
confirming no required evidence exists. It is not incident recovery and must
never be used against a shared, staging, or production volume.

1. Export any evidence needed for a review.
2. Stop the API.
3. Run `docker compose -f infra/docker/compose.yml down --volumes` only after
   independently confirming the Compose project and all three local volumes.
4. Start PostgreSQL, Redis, and Kafka again, then start the API and let Flyway
   create a fresh schema and Spring Kafka create the exact V1 topic.
5. Bootstrap new local merchant/platform credentials; old credentials and IDs
   are intentionally invalid.
6. Run fresh migration, topic-contract, and public-demo proof before declaring
   the environment healthy.

Deleting only event, inbox, projection, batch, or Kafka data creates false
lineage and is prohibited even locally.

## Recovery completion checklist

- readiness is truthful for every required component;
- publication backlog and oldest age return within policy;
- no blocked publication or open quarantine is hidden;
- consumer checkpoint/stream positions converge without amount/count changes;
- analytics disclose generation, cutoff, origin, coverage, and freshness;
- any rebuilt generation passed every reconciliation family at its captured
  watermark before activation;
- replay dataset digests/counts and optional simulation compatibility pass;
- source payment, processor, fraud, review, label, control, webhook, and
  balanced-ledger identities/counts/digests are unchanged by recovery;
- audit contains only the human privileged action, while automatic retries
  remain operational evidence;
- logs, metrics, API output, and tickets contain no prohibited values or
  high-cardinality tenant/event/resource identifiers.
