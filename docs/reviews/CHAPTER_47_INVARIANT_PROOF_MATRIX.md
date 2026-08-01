# Chapter 47 Invariant Proof Matrix

- Chapter: Chapter 47 — Kafka Publication And Recovery
- Matrix provenance: PRE_IMPLEMENTATION
- Scope status: FROZEN
- Review status: VERIFIED
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Fresh hostile review: PASS
- Boundary family review: PASS
- Custom infrastructure review: PASS
- Escaped review assessment: COMPLETE

Verification update (2026-08-01): V67, the leased/fenced outbox publisher,
attempt evidence, blocked recovery API, readiness, and runbook are implemented.
Five real Kafka/PostgreSQL tests passed six-partition configuration, retained
migration, exact-byte publication, broker recovery, drift rejection, and
locked-claim schedules. ADR-0011 remains the accepted custom outbox decision.

This matrix was drafted and frozen on 2026-07-27 after the Phase 5 founder
authorization and before Chapter 47 production code, migration, runtime
configuration, test, runbook, or demo changes. It inherits the canonical event
and atomic outbox boundary from Chapters 44–46 and freezes the transport and
recovery promises that Chapter 47 must prove. No implementation or completion
claim is made by this artifact.

## Frozen publication contract

| Boundary | Frozen value |
|---|---|
| Topic | `thaap.domain-events.v1` |
| Partition count | `6`; immutable for topic V1 |
| Retention | `7 days` |
| Maximum canonical event record | `64 KiB` before Kafka send |
| Producer acknowledgement | `acks=all` |
| Producer duplicate reduction | Kafka idempotence enabled; not an exactly-once claim |
| Request timeout | `10 seconds` |
| Delivery timeout | `30 seconds` |
| Publisher poll batch | At most `100` publications |
| Per-tenant poll budget | At most `20` publications |
| Per-stream poll budget | At most `10` publications |
| Publication lease | `30 seconds` with a fencing token |
| Retry ceiling | At most `10 retries` after an unsuccessful or unknown send, with capped exponential backoff; after retry 10, `BLOCKED` |
| Record key | Canonical tenant-qualified stream identity |
| Send bytes | Exact immutable full-record canonical bytes stored with the domain event |

## C47-PAY-001

- Lens: PAYMENT_FRAUD
- Invariant: Kafka publication communicates an already committed payment or risk fact and can never repeat processor, payment, refund, ledger, label, review, or control mutation logic.
- Failure sequence: A publish retry, lease takeover, or operator recovery calls a source-domain service and creates a second authorization, refund, ledger group, label, or audit fiction.
- Implementation: The planned publisher will read immutable event/publication rows only, send the stored record after source commit, and finalize publication state without invoking source-domain orchestration.
- Proof: real PostgreSQL/Kafka contract `publisherRetryAndRecoveryNeverMutatePaymentFraudOrLedgerTruth` will snapshot source counts and balanced ledger groups before outage, retry, duplicate acknowledgement, and operator recovery, then prove exact equality.
- Adversarial evidence: Broker outage after source commit, acknowledgement followed by process failure, expired lease takeover, stale finalizer, and manual retry of a blocked event.
- Status: PASS

## C47-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: Publication identity, ordering, recovery, and operator summaries remain tenant-qualified; a copied merchant, event, publication, or stream identifier cannot disclose or mutate another merchant's work.
- Failure sequence: A caller substitutes a foreign publication ID, a record key omits or changes the tenant, or a publisher joins an event by bare UUID and sends it under another merchant's stream.
- Implementation: queries and foreign keys will bind merchant, event, stream type, stream ID, and publication; the record key will be derived from the stored tenant-qualified stream rather than caller data.
- Proof: two-merchant PostgreSQL/Kafka tests `publisherUsesStoredTenantQualifiedKeyAndRejectsForeignRecovery` and `operatorRecoveryDoesNotAcceptMerchantSubstitution` will compare stored event identity, Kafka key, attempt evidence, and bounded responses.
- Adversarial evidence: Foreign event/publication UUIDs, same-shaped stream IDs, altered record key, disabled operator credential, and global-health versus tenant-detail response partitions.
- Status: PASS

## C47-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: Kafka records contain only the frozen allowlisted canonical event, and logs, metrics, attempts, health, and recovery responses never expose payloads, credentials, tenant secrets, raw identifiers, or exception text.
- Failure sequence: An oversized or prohibited event is sent, broker authentication failure logs a secret, an exception message is persisted, or event/merchant IDs become unbounded metric dimensions.
- Implementation: The planned send boundary will reject records above `64 KiB`, use exact stored bytes, map outcomes to closed safe codes, and expose only bounded component/topic/outcome dimensions.
- Proof: canary suite `publisherRejectsOversizedOrProhibitedRecordBeforeNetwork` and `publicationTelemetryContainsOnlyClosedSafeDimensions` will scan database attempts, Kafka records, logs, metrics, health, API responses, fixtures, and reports.
- Adversarial evidence: `64 KiB` boundary values, payload and credential canaries, raw exception messages, hostile broker errors, high-cardinality IDs, and malformed canonical bytes.
- Status: PASS

## C47-CLAIM-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: One worker owns a publication through a `30-second` lease and fencing token; sending occurs outside the claim transaction, and only the current token can append the terminal attempt and finalize state.
- Failure sequence: A lease expires during a send, a new worker takes ownership, and the stale worker later records success or failure over the new owner's result.
- Implementation: bounded `FOR UPDATE SKIP LOCKED` claiming will commit the claim before network I/O; finalization will lock the publication, compare the token and lease generation, append one attempt, and reject stale ownership.
- Proof: forced PostgreSQL tests `leaseTakeoverRejectsStalePublisherFinalizer` and `claimTransactionCommitsBeforeKafkaSend` will use barriers and lock probes to force both finalizer/takeover orderings.
- Adversarial evidence: Live lease, exact-expiry boundary, takeover before and after acknowledgement, stale success, stale failure, process crash before send, and process crash after acknowledgement.
- Status: PASS

## C47-ORDER-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: The publisher never sends stream version `n + 1` before an unpublished version `n`, while the `100`-item batch, `20`-per-tenant, and `10`-per-stream budgets allow unrelated tenants and streams to progress.
- Failure sequence: A blocked or leased earlier version is skipped, or one noisy tenant/stream consumes the poll and starves an unrelated ready event.
- Implementation: The planned eligibility query will reject a publication with an earlier non-published stream predecessor and enforce all three frozen poll budgets before claiming.
- Proof: forced-ordering test `blockedPredecessorHoldsStreamWhileTenantAndStreamBudgetsPreserveFairness` will seed six partitions, multiple tenants, and a noisy stream, then verify exact send order and bounded unrelated progress.
- Adversarial evidence: Earlier pending, leased, retryable, blocked, and published states; later due work; more than 100 candidates; more than 20 tenant candidates; and more than 10 stream candidates.
- Status: PASS

## C47-DATA-001

- Lens: DATA_ML_LINEAGE
- Invariant: Event ID, full-record SHA-256, canonical bytes, event/schema version, traffic origin, occurrence time, and stream position remain byte- and meaning-stable across every publication attempt.
- Failure sequence: Retry reconstructs an event from current domain state, changes timestamp precision or JSON representation, or an event ID is reused with different bytes.
- Implementation: The planned producer will serialize no mutable domain object; it will send the immutable stored full-record bytes and retain their full-record SHA-256 on every attempt.
- Proof: golden-byte and real-Kafka test `retryPublishesIdenticalStoredRecordBytesAndDigest` will compare database bytes, producer record, broker-consumed bytes, and attempt evidence before and after restart.
- Adversarial evidence: Object key order, Unicode, null versus absent, decimal/integer form, PostgreSQL microseconds, later labels, current policy changes, and same ID with altered envelope bytes.
- Status: PASS

## C47-API-001

- Lens: API_PRODUCT
- Invariant: Only a live local `DATA_OPERATOR` can inspect bounded publication health or request idempotent retry of a `BLOCKED` publication; merchant roles and bootstrap tokens cannot operate the publisher.
- Failure sequence: A merchant risk manager retries publication, a disabled operator passes a stale pre-check, a bootstrap token impersonates an actor, or exact replay creates another recovery action.
- Implementation: The planned local/internal recovery boundary will authenticate and lock the operator before the publication, revalidate role and credential, reserve idempotency, return safe metadata only, and write a human audit record in the same transaction as the requested transition.
- Proof: API/PostgreSQL test `blockedPublicationRecoveryRequiresLiveDataOperatorAndIsIdempotent` will cover authorized recovery, replay, changed request, disable/role races, merchant credentials, bootstrap credentials, and foreign IDs.
- Adversarial evidence: `VIEWER`, `ANALYST`, `RISK_MANAGER`, `PLATFORM_ADMIN`, `DATA_OPERATOR`, disabled credential, copied ID, retryable versus blocked state, and exact/conflicting idempotency replay.
- Status: PASS

## C47-OBS-001

- Lens: OBSERVABILITY
- Invariant: Operators can distinguish pending, claimed, acknowledged, failed, unknown, retrying, blocked, and lease-recovered publication state without high-cardinality or sensitive dimensions.
- Failure sequence: An unknown acknowledgement is reported as a known failure, blocked backlog remains readiness-green, or payload/tenant/event identity appears in metrics or logs.
- Implementation: metrics will expose pending count/oldest age and closed publication outcomes by component/topic class; readiness will apply explicit Kafka, backlog-age, and blocked-event policies.
- Proof: metric/readiness test `publicationSignalsExposeUnknownBlockedAndLeaseRecoveryWithoutSensitiveLabels` will enumerate every outcome and scan all label keys/values and structured-log fields.
- Adversarial evidence: Broker unavailable, topic missing, authorization rejected, timeout, unknown acknowledgement, stale token, retry ceiling, backlog-age breach, and privacy canaries.
- Status: PASS

## C47-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: The topic has exactly six partitions and seven-day retention, producer safety settings are fixed, retry is bounded and recoverable, and configuration drift or unavailable required Kafka fails readiness without losing committed events.
- Failure sequence: Runtime creates a different partition count, retention shrinks silently, producer disables `acks=all` or idempotence, a send exceeds frozen timeouts, or retry 10 is discarded instead of moving the publication to blocked state.
- Implementation: startup validation will require topic/config compatibility, `acks=all`, idempotence, `10-second` request timeout, `30-second` delivery timeout, six partitions, seven-day retention, and at most 10 capped-exponential-backoff retries before `BLOCKED`.
- Proof: real-broker tests `publisherFailsReadinessOnTopicOrProducerConfigurationDrift` and `tenRetriesBlockWithoutDeletingEventAndRecoveryPublishesIt` will inspect broker configuration and durable PostgreSQL postconditions.
- Adversarial evidence: Five/seven partitions, retention below seven days, missing topic, broker unavailable, authorization error, delivery timeout, retries 9/10 and prohibited retry 11, restart with pending work, and Kafka-disabled test profile.
- Status: PASS

## Frozen boundary table

| Boundary | Valid | Rejected |
|---|---|---|
| Record | Stored canonical full-record bytes, full-record SHA-256, at most `64 KiB` | Reconstructed or edited bytes, payload-only identity, oversized event |
| Topic | `thaap.domain-events.v1`, six partitions, seven-day retention | In-place partition expansion, silent topic/config drift |
| Producer | `acks=all`, idempotence, request `10s`, delivery `30s` | Exactly-once claim, unbounded timeout, direct in-transaction send |
| Claim | Batch `100`, tenant `20`, stream `10`, lease `30s`, fenced finalization | Stale finalizer, predecessor bypass, noisy-stream starvation |
| Retry | Capped exponential backoff, at most ten retries, then durable `BLOCKED` | Retry 11, drop/delete after ceiling, unknown relabeled as success/failure |
| Recovery | Local live `DATA_OPERATOR`, idempotent, audited, safe metadata | Merchant/bootstrap authority, payload download/edit, foreign mutation |
| Telemetry | Closed bounded outcome/topic/component dimensions | Tenant/event/stream IDs, payload, credentials, exception text |
