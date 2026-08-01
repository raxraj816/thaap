# Chapter 48 Invariant Proof Matrix

- Chapter: Chapter 48 — Idempotent Consumer And Projection Substrate
- Matrix provenance: PRE_IMPLEMENTATION
- Scope status: FROZEN
- Review status: VERIFIED
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Fresh hostile review: PASS
- Boundary family review: PASS
- Custom infrastructure review: NOT_APPLICABLE
- Escaped review assessment: COMPLETE

Verification update (2026-08-01): V68–V69 and V79 implement inbox,
checkpoints, generation-local positions, poison quarantine, unlinked transport
quarantine, and manual post-commit acknowledgement. Contract and four real
Kafka/PostgreSQL duplicate, ordering, recovery-lock, and query-plan tests
passed; Spring Kafka owns transport and acknowledgement mechanics.

Reopened hostile review (2026-08-01): `ER-0079` exposed a semantic-protocol
gap below JSON schema shape. Canonical validation now proves safe integer width,
positive attempt number, `0..10000` basis points, exact microsecond timestamp,
and canonical UUID partitions before handler parsing. Invalid events and missing
exact source lineage enter bounded quarantine rather than wrapping a Java value
or escaping the consumer boundary. `ER-0078` additionally proves the consumer
cannot project a fraud event without its exact decision/snapshot source link.

This matrix was drafted and frozen on 2026-07-27 after the Phase 5 founder
authorization and before Chapter 48 production code, migration, runtime
configuration, test, runbook, or demo changes. It assumes Chapter 47 publishes
the exact immutable canonical record to the frozen internal topic. No consumer,
projection, test, or completion claim is made by this artifact.

## Frozen consumer contract

| Boundary | Frozen value |
|---|---|
| Source topic | `thaap.domain-events.v1` |
| Consumer group and durable consumer name | `payment-risk-projection-v1` |
| Offset policy | Manual acknowledgement only after the PostgreSQL transaction commits |
| Deduplication identity | `(consumer_name, event_id)` |
| Byte identity | SHA-256 of the complete canonical Kafka record/envelope, not payload-only SHA |
| Record limit | `64 KiB` maximum before parse/application |
| Stream ordering | Tenant-qualified stream version `n` must complete before `n + 1` |
| Projection isolation | Consumer identity, generation, checkpoint, and stream position are explicit |
| Dependency failure | No inbox/quarantine/offset commit; retry or stop consumption |
| Invalid record | Durable closed quarantine before manual offset acknowledgement; affected stream does not advance |
| Safe quarantine inventory | loopback-only `GET /internal/data/quarantines?status={OPEN|RETRY_REQUESTED}&limit={1..100}` with `X-Platform-Operator-Key`; live `DATA_OPERATOR` only; payload-free bounded metadata |
| Quarantine retry | loopback-only `POST /internal/data/quarantines/{quarantineId}/retry`; `X-Platform-Operator-Key` plus `Idempotency-Key`; exact replay returns the prior safe disposition and conflicting reuse returns `409` |

## C48-PAY-001

- Lens: PAYMENT_FRAUD
- Invariant: Consuming, deduplicating, quarantining, or recovering an event changes projection state only and can never call payment orchestration, processor simulation, ledger posting, refund, label, review, or control mutation paths.
- Failure sequence: A redelivered payment event reuses a domain service and produces a second money/fraud effect, or quarantine recovery reloads source state and mutates it.
- Implementation: The planned `payment-risk-projection-v1` handler will depend only on the validated canonical record, current projection generation, and projection repositories; source-domain repositories are unavailable to the mutation path.
- Proof: contract `consumerDuplicateAndRecoveryLeavePaymentFraudAndLedgerTruthUnchanged` will snapshot source counts and balanced ledger groups across normal delivery, duplicate, restart, quarantine, and recovery.
- Adversarial evidence: Authorization, capture, refund, chargeback, label, review, and control events; duplicate/redelivery; recovery; and an injected source-domain call detector.
- Status: PASS

## C48-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: Inbox, quarantine, generation, checkpoint, stream position, recovery, and safe operator metadata remain tenant-qualified; record-key, envelope, payload, and stored source-event tenant identities must agree.
- Failure sequence: A Kafka key substitutes a merchant, a payload carries a foreign tenant, a copied quarantine ID is recovered, or a projection row is joined by bare aggregate ID.
- Implementation: validation will compare the tenant-qualified record key with the envelope and immutable source event before opening the PostgreSQL transaction; all persisted relations and queries will use composite tenant keys.
- Proof: two-merchant real-boundary tests `consumerQuarantinesTenantOrKeySubstitutionWithoutProjectionAdvance` and `quarantineRecoveryRejectsForeignIdentifier` will prove non-disclosure and unchanged foreign rows.
- Adversarial evidence: Key/envelope mismatch, envelope/source mismatch, duplicate aggregate UUIDs across tenants, copied event/quarantine/generation IDs, and operator versus merchant credentials.
- Status: PASS

## C48-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: The consumer validates a full-record SHA-256 and `64 KiB` limit before application; quarantine, logs, metrics, APIs, and audits never duplicate raw payloads, exception text, credentials, or prohibited source values.
- Failure sequence: The same event ID arrives with a changed envelope but unchanged payload, decompression/parse input exceeds the limit, or a parser exception and payload are persisted in quarantine.
- Implementation: The planned record validator will hash the complete canonical record bytes, compare the durable full-record SHA, enforce the size limit before parsing, map failure to a closed reason, and retain safe identity/digest metadata only.
- Proof: property suite `consumerUsesFullRecordDigestAndQuarantineRemainsPayloadFree` will vary every envelope field independently, exercise size boundaries, and scan database, logs, metrics, API, audit, fixtures, and reports for canaries.
- Adversarial evidence: Payload-only digest collision scenario, changed tenant/stream/version/time with same payload, `64 KiB` exact/over-limit records, malformed JSON/Unicode/numbers/timestamps, raw exception, and credential canaries.
- Status: PASS

## C48-TX-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Inbox reservation/completion, deterministic projection mutation, stream position, and checkpoint commit atomically before the Kafka offset is manually acknowledged.
- Failure sequence: The process crashes before database commit, after database commit but before acknowledgement, or while acknowledgement fails; redelivery must apply zero or one projection effect, never two.
- Implementation: The planned listener will disable automatic commits, begin one PostgreSQL transaction, lock/reserve `(payment-risk-projection-v1,event_id)`, apply the generation-qualified effect and checkpoint, complete the inbox, commit, then acknowledge manually.
- Proof: real Kafka/PostgreSQL tests `crashBeforeCommitAppliesNothingAndCrashAfterCommitRedeliversAsNoOp` and `offsetAcknowledgementFailureConvergesThroughCompletedInbox` will use explicit crash hooks and durable postconditions.
- Adversarial evidence: Crash at each write boundary, acknowledgement exception, restart, rebalance, concurrent duplicate, completed inbox with same/different full-record SHA, and rolled-back transaction.
- Status: PASS

## C48-ORDER-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Version `n + 1` never applies before version `n` for the same tenant-qualified stream, while a quarantined or missing predecessor does not block unrelated streams in the same Kafka partition.
- Failure sequence: Two records for one stream execute concurrently out of order, a missing predecessor is skipped, or one poisoned stream stops all partition progress.
- Implementation: generation-qualified stream positions will compare the expected next version under lock; invalid/missing predecessors will quarantine or hold the stream with a closed reason while independent streams remain eligible.
- Proof: forced test `missingPredecessorHoldsOnlyAffectedStreamAndUnrelatedStreamAdvances` will interleave same-stream versions and another stream on one real partition, including recovery of the predecessor and ordered successor drain.
- Adversarial evidence: First version, exact successor, duplicate prior version, gap, regression, quarantined predecessor, held successors, concurrent handlers, and unrelated tenant/stream.
- Status: PASS

## C48-DATA-001

- Lens: DATA_ML_LINEAGE
- Invariant: Projection application is a deterministic function of the validated event record, declared handler version, and projection generation; it never fetches Redis/current RiskContext or later label/control truth.
- Failure sequence: Replay after a label or policy change produces different output, a handler reads current Redis state, or an unsupported schema falls through to best-effort parsing.
- Implementation: The planned version matrix will map exact event type/version pairs to deterministic handlers, record the handler version in inbox/projection evidence, reject unsupported versions, and expose no current-context dependency.
- Proof: determinism test `sameEventBytesHandlerVersionAndGenerationProduceSameProjectionDigest` will run before and after current Redis/domain changes and compare canonical projection effects.
- Adversarial evidence: Unknown optional field, unknown type, unsupported version, later label availability, changed policy, Redis unavailable/different, and identical record replay into separate generations.
- Status: PASS

## C48-API-001

- Lens: API_PRODUCT
- Invariant: Merchant roles cannot inspect or dispose quarantine; only a live local `DATA_OPERATOR` can view bounded safe metadata and request idempotent retry through an explicitly supported schema/compatibility path.
- Failure sequence: A merchant retrieves payload/error details, a disabled operator recovers after pre-check, an operator edits event bytes or offsets, or exact retry produces multiple dispositions.
- Implementation: The planned local/internal operator boundary will lock and revalidate the operator before quarantine state, expose no payload, prohibit byte/offset editing, reserve idempotency, and audit retry/adapter/break-glass decisions.
- Proof: API/PostgreSQL test `quarantineOperationsRequireLiveDataOperatorAndNeverExposeOrEditBytes` will cover roles, disable race, foreign IDs, exact/conflicting replay, supported adapter, and forbidden edit/offset operations.
- Adversarial evidence: Merchant viewer/analyst/manager, data operator/admin/bootstrap token, disabled credential, copied ID, raw payload/error request, byte edit, offset manipulation, and repeat recovery.
- Status: PASS

## C48-OBS-001

- Lens: OBSERVABILITY
- Invariant: Inbox duplicates, handler outcomes, quarantine reasons, checkpoint age, and held-stream state are visible through bounded consumer/topic/partition/projection dimensions without merchant, event, stream, payload, or exception-message labels.
- Failure sequence: One stale tenant is hidden by global consumer health, poison and database outage share one metric, or a sensitive identifier becomes a tag.
- Implementation: transaction-aware metrics and readiness will distinguish duplicate, applied, quarantined, dependency failure, predecessor unavailable, lag, checkpoint age, and incompatible handler/generation.
- Proof: test `consumerSignalsSeparatePoisonDependencyAndTenantStalenessWithSafeDimensions` will enumerate all outcomes and scan metric/log/health fields for forbidden canaries.
- Adversarial evidence: Duplicate, unsupported version, bad digest, missing predecessor, PostgreSQL unavailable, Kafka unavailable, stale checkpoint, no active generation, incompatible handler, and high-cardinality canaries.
- Status: PASS

## C48-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: `payment-risk-projection-v1` restarts and rebalances safely with manual acknowledgement, bounded parsing, durable inbox/quarantine/checkpoints, and explicit readiness for dependency or generation incompatibility.
- Failure sequence: Restart loses completed work, rebalance acknowledges uncommitted work, dependency failure becomes poison, no active generation is reported healthy, or rollback starts an incompatible handler.
- Implementation: Spring Kafka manual-ack configuration, database transaction boundary, group identity, readiness checks, and runbooks will separate dependency retry/stop from terminal invalid-record disposition.
- Proof: real-broker test `consumerRestartAndRebalancePreserveInboxProjectionAndOffsetContract` plus fresh/retained PostgreSQL migration tests will inspect offsets, inbox state, projection effects, and readiness.
- Adversarial evidence: Restart before/after commit, group rebalance, database timeout, Kafka disconnect, incompatible active generation, unknown handler version, retention-expired record, and safe consumer pause/resume.
- Status: PASS

## Frozen boundary table

| Boundary | Valid | Rejected |
|---|---|---|
| Group | `payment-risk-projection-v1` with manual acknowledgement | Auto commit, ambiguous/unversioned consumer identity |
| Identity | `(consumer_name,event_id)` plus complete-record SHA-256 | Payload-only digest, same ID with changed envelope accepted as duplicate |
| Transaction | Inbox + projection + stream position + checkpoint commit, then ack | Ack before commit, split inbox/projection commits |
| Ordering | Exact next tenant-qualified stream version | Gap/regression/best-effort reorder |
| Invalid record | Closed payload-free quarantine, no affected-stream advance | Raw exception/payload persistence, silent skip |
| Dependency failure | Roll back and retry/stop without inbox, quarantine, or ack | Misclassify as poison and advance offset |
| Recovery | Live `DATA_OPERATOR`, immutable source bytes, idempotent and audited | Merchant/bootstrap authority, byte/offset editing |
| Handler | Exact type/version, deterministic generation-local effect | Redis/current-domain lookup, source mutation, best-effort schema |
