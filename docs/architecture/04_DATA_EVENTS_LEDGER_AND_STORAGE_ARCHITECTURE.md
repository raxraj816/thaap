# 04. Data, Events, Ledger, And Storage Architecture

Date: 2026-05-29

This document defines the storage architecture, transactional boundaries, ledger model, outbox/event strategy, webhook delivery model, analytics stores, and replay/backfill strategy.

`docs/IMPLEMENTATION_MASTER_PLAN.md` is the authoritative cross-phase roadmap.
For Phase 5, it incorporates
`docs/PHASE_5_DETAILED_IMPLEMENTATION_PLAN.md`; that detailed plan is the exact
Phase 5 implementation contract, and both sources must remain consistent.
Founder implementation authorization on 2026-07-27 froze the Chapter 44–52
`PRE_IMPLEMENTATION` contracts before implementation began. Chapters 44–52 now
carry passing invariant matrices and merge-recommending closure dossiers. The
integrated product path and the architecture, detailed plan, master plan,
learning trail, and root/product READMEs were reconciled again after the
2026-08-01 hostile review. V83–V85 make fraud event source identity relational
and immutable across the exact decision, feature snapshot, attempt, and payment
stream; timestamp equality is never used as event-source identity.

Phase 5 implementation status: COMPLETE

## 1. Data Architecture Goals

The data architecture must support:

- merchant-tenant isolation
- durable payment state
- auditable fraud decisions
- idempotent request replay
- balanced money movement
- webhook delivery tracking
- event replay
- analytics projections
- ML datasets
- graph ingestion
- future service splits

Core principle:

```text
PostgreSQL is the transactional source of truth.
Merchant-owned data is scoped by merchant_id in a shared schema.
```

V1 uses a shared database and shared schema. `merchant_id` is the tenant discriminator for merchant-owned rows.

Every table that stores merchant-owned state must either:

- include `merchant_id` directly, or
- be reachable only through a parent row that includes `merchant_id`.

Runtime queries must use tenant-scoped lookup paths. Database row-level security is deferred for V1, but the schema should not block adding it later.

## 2. Storage Responsibilities

### 2.1 PostgreSQL

Owns:

- merchants
- platform operators and credentials, beginning in Phase 5
- customers
- payment methods
- payment intents
- payment attempts
- authorizations
- captures
- refunds
- chargebacks
- ledger entries
- idempotency keys
- fraud decisions
- feature definitions
- feature values
- feature snapshots
- fraud rules
- rule execution results
- manual review cases
- fraud labels
- webhook endpoints
- webhook deliveries
- processor events
- audit logs
- immutable domain events, mutable publication state, and publication attempts
- immutable, tenant-qualified links from fraud-decision events to the exact
  `FraudDecision` and `FeatureSnapshot` source rows
- consumer inbox, quarantine, projection generations, stream positions,
  checkpoints, bootstrap facts, immutable per-stream cutoff manifests,
  append-only rebuild events, reconciliation results, and rebuild state

### 2.2 Redis

Owns:

- velocity counters
- idempotency acceleration
- temporary feature cache, later
- rate-limit counters

Redis does not own durable truth.

### 2.3 Kafka

Introduced in Phase 5 for the internal event-to-projection boundary.

Owns:

- the fixed internal topic `thaap.domain-events.v1`, with exactly six
  partitions, seven-day retention, and a `64 KiB` complete-record limit
- analytics ingestion
- graph ingestion
- model monitoring ingestion
- replayable integration boundary

Phase 5 producers use `acks=all`, Kafka idempotence, a 10-second request
timeout, a 30-second delivery timeout, and at most 10 capped exponential
retries before durable `BLOCKED` disposition. The first durable consumer/group
identity is `payment-risk-projection-v1` and uses manual acknowledgement only
after its PostgreSQL inbox/projection transaction commits. These settings are
compatibility contracts; drift fails readiness.

### 2.4 ClickHouse

Deferred until measured analytics scale justifies a second store.

Owns:

- transaction analytics
- fraud decision analytics
- rule performance analytics
- model prediction logs
- backtesting tables

All merchant-derived analytics rows must include `merchant_id` unless the dataset is explicitly global/anonymized.

### 2.5 Neo4j

Added for graph intelligence.

Owns:

- entity graph
- fraud proximity queries
- fraud ring exploration
- graph features

Graph nodes and relationships derived from merchant data must carry `merchant_id` or an explicit global/anonymized scope marker so graph features cannot leak tenant data accidentally.

### 2.6 Object Storage

Added for ML/data artifacts.

Owns:

- raw event archive
- training datasets
- model artifacts
- backtesting outputs

Training and backtesting datasets must include `merchant_id` for segmentation, leakage checks, and tenant-scoped evaluation.

### 2.7 MLflow

Added for model lifecycle.

Owns:

- experiments
- parameters
- metrics
- model artifacts
- model versions
- registry metadata

## 3. Transactional Boundaries

### 3.1 Payment Confirmation Transaction

The durable transaction should include:

- merchant-scoped idempotency lookup/reservation
- PaymentAttempt creation
- FraudDecision persistence
- FeatureSnapshot persistence
- RuleExecutionResult persistence
- payment state update
- processor result persistence, when called
- authorization persistence, when approved
- capture and ledger entries, if automatic capture
- outbox event rows
- webhook delivery records, if created synchronously
- idempotency response record

Avoid:

- external HTTP webhook delivery inside the payment transaction
- long-running ML training or analytics work inside transaction
- LLM calls inside transaction

### 3.2 Capture Transaction

Must include:

- capture record
- payment state update
- ledger transaction group
- outbox event
- idempotency response

### 3.3 Refund Transaction

Must include:

- refund record
- payment/capture refund amount update
- ledger transaction group
- outbox event
- idempotency response

### 3.4 Chargeback Transaction

Must include:

- chargeback record
- payment state update
- ledger transaction group
- FraudLabel if fraud reason
- outbox event

## 4. Idempotency Data Model

`IdempotencyKey`

- `id`
- `merchantId`
- `key`
- `method`
- `path`
- `requestHash`
- `responseStatus`
- `responseBody`
- `status`
- `createdAt`
- `expiresAt`

Statuses:

- `IN_PROGRESS`
- `COMPLETED`
- `FAILED_RETRYABLE`
- `FAILED_FINAL`

Rules:

- uniqueness on `(merchantId, key)`
- same key + same hash returns original response
- same key + different hash returns error
- completed idempotency records are replayable until expiry
- in-progress records require timeout handling
- concurrent duplicate requests require locking or unique-constraint conflict handling

## 4.1 Tenant-Scoped Data Rule

Merchant-owned transactional tables must include `merchant_id` directly for hot-path access and indexing whenever practical.

Minimum direct `merchant_id` tables:

- customers
- payment methods
- payment intents
- idempotency records
- webhook endpoints
- webhook deliveries
- fraud decisions
- fraud rules
- manual review cases
- ledger entries or ledger transaction groups
- outbox events
- audit logs
- feature snapshots
- model prediction logs

Do not add merchant-facing repository methods that load by object ID alone. Use `(id, merchant_id)` or a parent join that proves tenant ownership.

## 5. Ledger Architecture

### 5.1 Ledger Goals

The ledger exists to prove that simulated money movement is internally consistent.

Goals:

- represent financial effects explicitly
- prevent unbalanced money movement
- support auditing
- support future reconciliation
- teach payment accounting fundamentals

### 5.2 Ledger Accounts

V1 accounts:

- `CUSTOMER_RECEIVABLE`
- `PLATFORM_CLEARING`
- `MERCHANT_PAYABLE`
- `FEE_REVENUE`
- `REFUND_LIABILITY`
- `DISPUTE_LOSS`

### 5.3 Ledger Entry

Fields:

- `id`
- `transactionGroupId`
- `account`
- `direction`
- `amount`
- `currency`
- `createdAt`

Direction:

- `DEBIT`
- `CREDIT`

Invariant:

```text
For each transactionGroupId and currency:
sum(debits) == sum(credits)
```

### 5.4 Capture Ledger Pattern

Illustrative capture entries:

```text
Debit  CUSTOMER_RECEIVABLE
Credit PLATFORM_CLEARING

Debit  PLATFORM_CLEARING
Credit MERCHANT_PAYABLE

Debit  MERCHANT_PAYABLE
Credit FEE_REVENUE
```

The exact accounting model may evolve. The invariant cannot.

### 5.5 Refund Ledger Pattern

Chapter 18 full-refund entries exactly reverse the capture accounts:

```text
Debit  MERCHANT_PAYABLE
Credit CUSTOMER_RECEIVABLE
```

The Refund is the ledger source and every row also retains the original
PaymentIntent ID. A future asynchronous acquirer integration may introduce a
refund-liability clearing account, but the synchronous V1 simulator must not
claim that intermediate state exists.

### 5.6 Chargeback Ledger Pattern

Illustrative chargeback entries:

```text
Debit  DISPUTE_LOSS or MERCHANT_PAYABLE
Credit CUSTOMER_RECEIVABLE
```

### 5.7 Ledger Failure Behavior

If ledger entries do not balance:

- reject operation
- roll back transaction
- do not emit event
- do not mark idempotency as completed

## 6. Outbox Architecture

### 6.1 Why Outbox

Outbox prevents this failure:

```text
database commit succeeds
event publish fails
```

Instead:

```text
domain state + outbox event commit together
publisher retries later
```

### 6.2 Immutable Event And Mutable Publication State

The founder-confirmed Phase 5 detailed plan separates the immutable semantic
fact from its mutable transport progress:

- `domain_events` owns tenant, event identity, domain stream identity and
  version, event type/version, occurrence/recording time, correlation and
  causation, safe actor/request/trace/origin lineage, canonical payload bytes
  and digest, and exact full event-record bytes plus full-record digest.
- `event_publications` owns claim token, lease, retry schedule, acknowledgement,
  and blocked-state progress.
- `event_publication_attempts` owns append-only safe outcome evidence.

This prevents a publisher retry from changing what the event means. Repeated
failure blocks publication and alerts operators; it does not discard the
immutable event as `FAILED_FINAL`.

### 6.3 Event Publishing Flow

```text
domain transaction commits
  -> immutable event + publication are durable
  -> publisher claims bounded next-per-stream work with a lease/token
  -> exact stored full event-record bytes publish to Kafka outside the database
     transaction
  -> matching token records acknowledgement, retry, unknown, or blocked truth
```

Kafka delivery is at least once. Acknowledgement followed by a database crash
may create a duplicate, so each consumer requires a durable inbox committed in
the same PostgreSQL transaction as its projection effect. Webhook delivery
records remain a separate merchant-callback product and are not regenerated
from analytics consumption.

## 7. Domain Events

Payment events:

- `payment_intent.created`
- `payment_intent.canceled`
- `payment_attempt.completed`
- `authorization.created`
- `authorization.voided`
- `capture.created`
- `refund.created`

Fraud events:

- `fraud_decision.created`
- `manual_review.opened`
- `manual_review.assigned`
- `manual_review.resolved`
- `chargeback.created`
- `fraud_label.recorded`
- `fraud_label.superseded`

Webhook events:

- `webhook.delivery_succeeded`
- `webhook.delivery_failed`

Model events, later:

- `model.prediction_logged`
- `model.version_registered`
- `model.version_promoted`

Rule events, later:

- `rule.created`
- `rule.simulated`
- `rule.approved`
- `rule.activated`
- `rule.disabled`

## 8. Event Versioning

Rules:

- include `eventVersion`
- additive changes are preferred
- consumers must tolerate unknown fields
- removals require new version
- meaning changes require new version
- replay compatibility must be preserved

Bad:

- renaming fields without version bump
- changing semantics of an existing field
- deleting fields before consumers migrate

Good:

- add new nullable field
- publish both old and new field temporarily
- migrate consumers
- deprecate old field later

## 9. Webhook Delivery Architecture

### 9.1 Three Durable Records

A webhook is not one mutable row. Chapter 19 separates:

1. `WebhookEvent`: immutable fact, canonical payload, and stable deduplication
   identity.
2. `WebhookDelivery`: routing and current aggregate state for one endpoint.
3. `WebhookDeliveryAttempt`: append-only evidence for one claimed transmission
   attempt, including safe failures that stop before HTTP.

This separation prevents a retry from rebuilding historical payload truth from
a resource that may have changed after the original event.

The canonical event time is truncated to the database's microsecond precision
before the JSON payload, hash, `occurred_at`, and delivery creation time are
constructed. Canonicalizing once prevents an apparently immutable payload from
disagreeing with its persisted lineage after a write/read round trip.

### 9.2 Webhook Endpoint

Endpoint state includes tenant, URL, enabled-event subscriptions, operational
status, signing-secret prefix/hash, AES-GCM encrypted secret material and key
version, and creation/update times. The raw signing secret is revealed only at
creation or rotation.

A one-way secret hash alone is not usable for HMAC signing. Using that hash as
the signing key would make a database-only compromise sufficient to forge
merchant webhook signatures. Chapter 19 therefore introduces separately
configured key encryption.

### 9.3 Webhook Event

The immutable event stores:

- event ID, merchant ID, event type, resource ID and constrained resource type
- API version and event time
- exact canonical UTF-8 payload
- SHA-256 of those exact payload bytes

The event envelope is written in the same transaction as the originating
payment mutation and its endpoint-specific deliveries. It is not the Phase 5
general domain outbox.

### 9.4 Webhook Delivery

The delivery stores:

- delivery, merchant, endpoint, and event identities
- aggregate status and attempt count
- next/last attempt and delivered times
- safe last error code
- claim token and lease expiry while delivering

Delivery statuses are `PENDING`, `DELIVERING`, `RETRY_SCHEDULED`, `SUCCEEDED`,
and `EXHAUSTED`. Attempt failures are evidence on attempt rows; there is no
ambiguous delivery-level `FAILED` state.

### 9.5 Webhook Delivery Attempt

Each attempt stores:

- merchant, delivery, and monotonically increasing attempt number
- started/completed times, bounded outcome, and latency
- durable dispatch-start time, nullable HTTP-send evidence, optional response
  status, or constrained safe error code

`http_request_sent = false` is pre-dispatch known-no-send evidence, `true` means
the request write completed, and `null` means dispatch began but send truth is
not yet or is no longer knowable. The database permits `null` for an in-progress
post-dispatch attempt or `OUTCOME_UNKNOWN`, and rejects it before dispatch.
This avoids converting a secret-loading or DNS failure into false evidence that
a merchant may have received a callback.

Response bodies, headers, URLs, payloads, signatures, claim tokens, and secret
material are not attempt evidence and are not persisted.

### 9.6 Delivery Flow

```text
originating payment mutation creates immutable WebhookEvent
  -> matching active endpoints selected under shared row locks
  -> delivery rows created in the same transaction
  -> worker claims bounded due rows in a short transaction
  -> claim and numbered attempt commit
  -> worker loads the key and validates the target
  -> worker atomically marks dispatch and renews lease
  -> worker mints a fresh timestamp and signs exact payload bytes
  -> worker sends outside the transaction
  -> matching claim finalizes attempt and delivery in a new transaction
```

PostgreSQL claiming uses ordered bounded selection with
`FOR UPDATE SKIP LOCKED`. Each row is claimed immediately before its network
dispatch; a scheduler batch is a bound on completed claim/send/finalize cycles,
not a pre-leased waiting list. A claim lease permits crash recovery, and a
claim token prevents a stale worker from finalizing over a newer attempt.
Preparation may include key access and DNS. DNS runs under an application
deadline in a bounded executor and queue. Resolution infrastructure failures,
unknown hosts, and empty answers remain retryable known-no-send attempt truth;
only explicit URL policy or prohibited-address decisions are terminal. The
worker then locks the delivery,
marks dispatch start, and renews the matching claim immediately before HTTP; a
stale token skips the send. Only after that potentially blocking preparation
returns does the worker read the clock and sign. A local signing failure is
finalized as retryable known-no-send evidence.
Finalization locks the same delivery row before validating ownership. The lease
must exceed connect timeout plus the total request deadline.
Expired delivering rows are recovered even when their endpoint was disabled
after claim. Recovery closes the append-only attempt evidence and exhausts the
delivery without creating another attempt.

Rotation and disable take an exclusive endpoint-row lock. A disabled endpoint
cannot be rotated back to active, and the shared routing lock ensures a
concurrent payment transaction cannot insert a pending delivery after disable's
bulk exhaustion. Endpoint sets are locked in stable order.

The delivery-to-event foreign key includes merchant, event ID, event type, and
resource ID. This makes the delivery's operational projection agree with the
immutable payload lineage even when data is written outside the ORM.

V8 had no webhook executor or delivery-attempt table. V9 therefore accepts only
pristine zero-attempt queued rows and fails before schema mutation when aggregate
state claims retry, success, or failure that cannot be reconciled with attempt
evidence. Pristine work for an inactive legacy endpoint becomes terminal
`ENDPOINT_DISABLED`; work for an active legacy endpoint remains queued until
the irrecoverable legacy signing secret is rotated.

This provides at-least-once delivery. It cannot provide exactly-once receipt
across an HTTP/network failure boundary.

### 9.7 Retry Strategy

Initial retry schedule:

- immediate first attempt
- attempt 2 after 1 minute
- attempt 3 after 5 minutes
- attempt 4 after 30 minutes
- attempt 5 after 2 hours
- exhausted after failure of attempt 5

Any non-`2xx` response, timeout, or transport error follows this deterministic
V1 policy. Redirects are not followed, and `Retry-After` is not interpreted in
Chapter 19.

Disabled endpoints, unusable/tampered signing secrets, and target-policy
rejections are non-retryable configuration/security outcomes. They exhaust
without an outbound HTTP call.

The complete schema, signing, target-network, API, observability, migration, and
test decisions live in Chapter 19 of the implementation master plan.

## 10. Analytics Architecture

### 10.1 PostgreSQL Projections First

Before ClickHouse:

- maintain simple PostgreSQL projection tables
- support dashboard MVP
- validate event semantics
- keep local dev simple

### 10.2 ClickHouse Later

Move high-volume analytics to ClickHouse when:

- dashboard queries become heavy
- rule simulation needs larger historical scans
- model monitoring needs score distribution analysis

ClickHouse tables:

- transaction analytics
- fraud decision analytics
- rule execution analytics
- model prediction analytics
- chargeback analytics

### 10.3 Backfill

Backfill must be possible from:

- immutable domain events
- PostgreSQL source tables
- archived raw events, later

Backfill must be idempotent. Historical rows that predate domain events are
bootstrapped into immutable, safe, digest-bound projection seed facts through
an explicitly labelled source snapshot at a fixed cutoff. Future rebuilds reuse
those exact seed bytes before applying later events; THAAP does not fabricate
retroactive domain-event IDs or occurrence times.

### 10.4 Offline Replay Architecture

Phase 5 separates projection rebuild from counterfactual evaluation:

- projection rebuild applies immutable events to a shadow generation,
  reconciles it, catches it up, and atomically activates it without touching
  domain state
- an offline dataset manifest binds tenant, event cutoff, label-availability
  cutoff, traffic-origin policy, feature/evaluator version, query digest, and
  output digest
- the Phase 4.5 governed historical simulator remains authoritative for
  policy/rule activation until event-derived cohort equivalence is proven

The later rule/model ReplayEngine evaluates candidates against historical data.

Inputs:

- PaymentAttempts
- FraudDecisions
- FeatureSnapshots
- FraudLabels
- ManualReviewCase outcomes
- Chargebacks
- candidate ruleset
- candidate model predictions, later

Hard rules:

- do not fetch current Redis velocity counters
- do not recompute decision-time features from mutable current state
- do not overwrite original FraudDecision rows
- write replay outputs to separate simulation tables

Outputs:

- simulation run ID
- candidate ruleset/model version
- matched attempts
- proposed actions
- fraud captured
- false positives
- review load
- 3DS request rate
- merchant/segment impact
- unknown-outcome count

The Phase 4.5 simulator already gates policy/rule activation. Model replay and
promotion remain Phase 7 work; Phase 5 creates stable, point-in-time inputs.

## 11. ML Dataset Architecture

### 11.1 V1 Feature Store Tables

`FeatureDefinition`

- `id`
- `featureCode`
- `entityType`
- `valueType`
- `owner`
- `featureVersion`
- `description`
- optional positive `maxAgeMs`
- `createdAt`

Definitions are seeded reference data. The code/version and full
ID/code/version lineage are unique, and database triggers reject inserts,
updates, and deletes outside migrations.

`FeatureValue`

- `id`
- `merchantId` and `paymentAttemptId`
- `featureDefinitionId`, `featureCode`, and `featureVersion`
- `valueType`
- exactly one of `longValue`, `stringValue`, `booleanValue`, or `decimalValue`
- `observedAt`
- `computedAt`
- trusted `trafficOrigin`
- `createdAt`

Purpose:

- provide a simple online/offline feature abstraction
- support point-in-time joins for training
- avoid training data being built from current mutable state
- delay Feast/custom feature-store complexity until needed

Point-in-time join rule:

```text
For PaymentAttempt decision_started_at T:
use only same-tenant FeatureValues for that attempt where observed_at <= T.
```

Composite foreign keys bind merchant, attempt, origin, and the immutable
definition identity `(id, featureCode, featureVersion, valueType)`. A
type/value check requires exactly the column named by `valueType`; the
four-column definition foreign key separately prevents a structurally valid
typed column from disagreeing with its definition. `observedAt <= computedAt`
is enforced in SQL, while the
cross-table `observedAt <= decisionStartedAt` rule is enforced during
materialization and repeated by the canonical point-in-time query. V19 rejects
updates to FeatureValue, FeatureSnapshot, and FraudDecision evidence.

`FeatureSnapshot` remains the immutable exact feature set used for a
FraudDecision. Chapter 22 writes its keys in lexical order and configures JSON
round trips to preserve LONG and DECIMAL domain types. The same immutable
`DecisionFeatureContext` is passed to the decision engine and persisted into
the snapshot; a parallel entity-derived decision input does not exist.

### 11.2 Dataset Source

Dataset source:

- PaymentAttempt
- FeatureSnapshot
- FraudDecision
- FraudLabel
- Chargeback
- ManualReviewCase
- ModelPrediction, later

Dataset row:

- one row per PaymentAttempt
- event time
- label time
- label
- features known at event time
- feature version
- merchant segment

Dataset artifact:

- stored in object storage later
- versioned
- linked to MLflow run

## 12. Graph Ingestion Architecture

Graph source:

- payment attempt events
- payment method metadata
- customer metadata
- fraud labels
- chargebacks
- review outcomes

Ingestion flow:

```text
Kafka event
  -> graph ingestion worker
  -> resolve entity IDs
  -> upsert nodes
  -> upsert relationships
  -> update fraud labels
```

Graph writes should be idempotent.

## 13. Audit Architecture

`AuditLog` already exists in the completed product. Phase 5 reconciles its
coverage rather than creating a second audit table. Audit and domain events are
not interchangeable: audit answers who acted and why, while a domain event
states which business fact committed.

Audit events should record:

- actor
- action
- aggregate type
- aggregate ID
- request ID
- trace ID
- timestamp
- before/after state where useful
- reason where available

Audit high-risk actions:

- confirm payment
- capture
- cancel
- refund
- chargeback
- review decision
- rule activation
- model promotion
- agent tool call
- blocked-event retry or quarantine disposition
- projection rebuild start, cancel, activate, or rollback

## 14. Data Retention And Privacy

V1 local policy:

- keep all simulation data for learning
- never store raw PAN/CVV
- mask sensitive fields in logs

Future production-shaped policy:

- define retention per data class
- expire idempotency keys after configured period
- expire webhook delivery bodies after configured period
- minimize stored PII
- support deletion/anonymization workflows where appropriate

## 15. Consistency Model

Strong consistency required:

- payment state changes
- idempotency records
- ledger entries
- fraud decision persistence
- capture/refund/chargeback state
- domain mutation plus immutable event insertion
- consumer inbox plus projection effect and checkpoint
- reconciled projection-generation activation

Eventual consistency acceptable:

- Kafka publication after the source transaction commits
- webhook delivery
- analytics projections
- graph ingestion
- ML datasets
- agent reports

## 16. Data Architecture Acceptance Criteria

Complete when:

- PostgreSQL schema supports all V1 entities
- all mutating APIs use idempotency
- ledger groups balance
- immutable domain events and publication state are written atomically with
  each in-scope state change
- webhook delivery records exist
- at-least-once publication plus inbox-idempotent consumption is restart-safe
- analytics projections expose freshness/coverage and can be rebuilt through a
  reconciled shadow generation without source-state mutation
- ML dataset rows can be built from persisted snapshots
- graph ingestion has idempotent event handling
- audit logs exist for high-risk actions

## 17. Frozen Phase 6 Investigation Read Architecture

Phase 6 does not introduce an authoritative case projection. Its investigation
composition layer uses three distinct sources:

- domain tables for current payment, fraud, review, label, dispute, webhook,
  and ledger truth
- canonical payment-stream versions for immutable business ordering
- active Phase 5 projections for discovery and aggregate insight with exact
  generation, cutoff, freshness, coverage, and origin policy

Potentially growing collections use separate signed cursors. A source detail
snapshot is bounded and repeatable-read; projection and transport sections keep
their own declared as-of/freshness state.

V86–V87 are frozen to normalize only already-safe same-merchant correlation
HMAC aliases into tenant/source-qualified investigation anchors. These rows are
an immutable lookup child of the original payment/risk lineage, never a fraud
input or cross-merchant graph. The API returns relation reason codes, not HMACs
or raw values. V88–V90 are proposed for typed investigation saved views and
restart-safe bounded bulk-assignment evidence; V91 is limited to measured query
support. The 2026-08-02 founder promise freeze has a pre-implementation proof
contract for every Chapter 53–59 slice; Chapters 53 and 57 are the first
dependency-ready implementation entries.
