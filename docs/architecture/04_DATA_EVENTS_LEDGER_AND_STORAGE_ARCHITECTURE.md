# 04. Data, Events, Ledger, And Storage Architecture

Date: 2026-05-29

This document defines the storage architecture, transactional boundaries, ledger model, outbox/event strategy, webhook delivery model, analytics stores, and replay/backfill strategy.

For implementation decisions, follow `docs/IMPLEMENTATION_MASTER_PLAN.md`.

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
- outbox events

### 2.2 Redis

Owns:

- velocity counters
- idempotency acceleration
- temporary feature cache, later
- rate-limit counters

Redis does not own durable truth.

### 2.3 Kafka

Added after core flows.

Owns:

- domain event stream
- analytics ingestion
- graph ingestion
- model monitoring ingestion
- replayable integration boundary

### 2.4 ClickHouse

Added for analytics.

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

Illustrative refund entries:

```text
Debit  REFUND_LIABILITY
Credit CUSTOMER_RECEIVABLE

Debit  MERCHANT_PAYABLE
Credit REFUND_LIABILITY
```

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

### 6.2 Outbox Event

Fields:

- `id`
- `aggregateType`
- `aggregateId`
- `eventType`
- `eventVersion`
- `payloadJson`
- `status`
- `createdAt`
- `publishedAt`
- `retryCount`
- `lastError`

Statuses:

- `PENDING`
- `PUBLISHED`
- `FAILED_RETRYABLE`
- `FAILED_FINAL`

### 6.3 Event Publishing Flow

```text
domain transaction commits
  -> outbox event is PENDING
  -> publisher polls pending events
  -> publish to Kafka, when Kafka exists
  -> mark PUBLISHED
  -> retry failures
```

Before Kafka exists, outbox can still feed internal projections and webhook delivery rows.

## 7. Domain Events

Payment events:

- `payment_intent.created`
- `payment_intent.canceled`
- `payment_attempt.created`
- `authorization.created`
- `capture.created`
- `refund.created`

Fraud events:

- `fraud_decision.created`
- `review.opened`
- `review.closed`
- `chargeback.created`
- `fraud_label.created`

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

### 9.1 Webhook Endpoint

Fields:

- `id`
- `merchantId`
- `url`
- `enabledEvents`
- `signingSecret`
- `status`
- `createdAt`

### 9.2 Webhook Delivery

Fields:

- `id`
- `webhookEndpointId`
- `eventId`
- `attemptNumber`
- `status`
- `responseStatus`
- `nextRetryAt`
- `createdAt`
- `deliveredAt`

Statuses:

- `PENDING`
- `DELIVERING`
- `SUCCEEDED`
- `FAILED`
- `RETRY_SCHEDULED`
- `EXHAUSTED`

### 9.3 Delivery Flow

```text
domain event created
  -> matching endpoints selected
  -> delivery row created
  -> worker signs payload
  -> worker sends HTTP request
  -> response stored
  -> success marks SUCCEEDED
  -> failure schedules retry
```

### 9.4 Retry Strategy

Initial retry schedule:

- immediate first attempt
- 1 minute
- 5 minutes
- 30 minutes
- 2 hours
- exhausted after max attempts

This can be configurable later.

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

- outbox events
- PostgreSQL source tables
- archived raw events, later

Backfill must be idempotent.

### 10.4 Offline Replay Architecture

ReplayEngine evaluates candidate rules or models against historical data.

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

ReplayEngine is required before activating major rule, threshold, or model changes.

## 11. ML Dataset Architecture

### 11.1 V1 Feature Store Tables

`FeatureDefinition`

- `id`
- `featureName`
- `entityType`
- `valueType`
- `owner`
- `version`
- `description`
- `createdAt`

`FeatureValue`

- `id`
- `entityType`
- `entityId`
- `featureName`
- `valueJson`
- `featureTimestamp`
- `computedAt`
- `featureVersion`

Purpose:

- provide a simple online/offline feature abstraction
- support point-in-time joins for training
- avoid training data being built from current mutable state
- delay Feast/custom feature-store complexity until needed

Point-in-time join rule:

```text
For PaymentAttempt event_time T:
use latest FeatureValue for the entity where featureTimestamp <= T.
```

`FeatureSnapshot` remains the immutable exact feature set used for a FraudDecision.

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

Eventual consistency acceptable:

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
- outbox events are written with state changes
- webhook delivery records exist
- analytics projections can be rebuilt
- ML dataset rows can be built from persisted snapshots
- graph ingestion has idempotent event handling
- audit logs exist for high-risk actions
