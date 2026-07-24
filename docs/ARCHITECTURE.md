# THAAP System Architecture

Date: 2026-05-29

This document explains how THAAP is structured. For implementation decisions, follow `docs/IMPLEMENTATION_MASTER_PLAN.md`.

For deeper architecture detail, use the architecture pack:

- [System Context And Components](./architecture/01_SYSTEM_CONTEXT_AND_COMPONENTS.md)
- [Payment Processing Flows](./architecture/02_PAYMENT_PROCESSING_FLOWS.md)
- [Fraud Decisioning And ML Architecture](./architecture/03_FRAUD_DECISIONING_AND_ML_ARCHITECTURE.md)
- [Data, Events, Ledger, And Storage Architecture](./architecture/04_DATA_EVENTS_LEDGER_AND_STORAGE_ARCHITECTURE.md)
- [Security, Observability, And Operations Architecture](./architecture/05_SECURITY_OBSERVABILITY_AND_OPERATIONS_ARCHITECTURE.md)

## 1. Architecture Goals

The architecture must support:

- merchant-based multi-tenancy
- realistic card payment processing simulation
- deterministic real-time fraud decisions
- auditable state transitions
- safe retries through idempotency
- double-entry ledger invariants
- webhook-based merchant integration
- historical analytics and replay
- ML training and serving
- graph-based fraud investigation
- human review and rule governance
- offline GenAI fraud intelligence
- local-first development with production-shaped boundaries

The first implementation should favor clear domain boundaries over early microservice sprawl.

## 2. Architectural Style

Start with a modular monolith:

```text
payments-api
  payment module
  processor module
  fraud module
  rules module
  review module
  chargeback module
  ledger module
  webhook module
  audit module
```

Split into separate services later when justified by:

- different latency requirements
- different scaling needs
- independent ownership
- data ownership boundaries
- operational risk
- deployment independence

This gives us a fast local learning loop while preserving service boundaries in the code.

## 3. System Context

```text
Merchant / Client
  -> payments-api
      -> merchant tenant resolution from X-Api-Key
      -> PostgreSQL
      -> Redis
      -> processor simulator
      -> model-serving-service, later
      -> Kafka, later
      -> webhook delivery worker

Analyst Dashboard
  -> payments-api
      -> transactions
      -> reviews
      -> rules
      -> analytics
      -> graph, later
      -> agent reports, later

Offline Intelligence
  -> Kafka events
  -> ClickHouse
  -> Neo4j
  -> object storage
  -> ML training
  -> GenAI agent service
```

## 4. Runtime Components

### 4.1 `payments-api`

Primary backend for V1.

Responsibilities:

- merchant admin setup APIs
- tenant context resolution
- customer/payment method APIs scoped by merchant
- PaymentIntent and PaymentAttempt lifecycle
- idempotency
- processor simulator orchestration
- authorization, capture, cancel, refund, chargeback
- fraud decision orchestration
- deterministic rules
- manual review
- double-entry ledger writes
- domain event outbox
- webhook endpoint and delivery records
- audit logging

Tenant model:

- `Merchant` is the V1 tenant root.
- Merchant-facing runtime APIs resolve `MerchantContext` from `X-Api-Key`.
- Shared PostgreSQL schema uses `merchant_id` as the tenant discriminator.
- Idempotency is scoped by `(merchant_id, key)`.
- Events, feature snapshots, model predictions, analytics rows, and graph records must carry or derive `merchant_id`.

Technology:

- Java 25
- Spring Boot 4.0.6
- Spring Web
- Spring Validation
- Spring Data JPA
- Flyway, planned before durable schema evolution
- Spring Actuator
- PostgreSQL
- Redis

### 4.2 `analyst-dashboard-ui`

Operational UI for fraud and payment investigation.

Responsibilities:

- transaction search
- transaction detail
- review queue
- rule management
- chargeback views
- model metrics, later
- graph investigation, later
- agent reports, later

Technology:

- React
- TypeScript
- TanStack Query
- Tailwind CSS
- Recharts

### 4.3 `model-serving-service`

Added after deterministic fraud MVP.

Responsibilities:

- load registered model artifact
- expose `POST /score`
- return score, risk level, model version, reason codes, top factors
- support timeout-safe fallback in `payments-api`

Technology:

- Python
- FastAPI
- scikit-learn
- logistic regression baseline
- XGBoost or LightGBM only if EDA and baseline error analysis justify it
- SHAP
- MLflow

### 4.4 `analytics-worker`

Added after event outbox and Kafka.

Responsibilities:

- consume payment and fraud events
- write analytics projections
- support replay
- feed ClickHouse
- feed graph ingestion

### 4.5 `fraud-intelligence-agent-service`

Added in later phases.

Responsibilities:

- orchestrate offline fraud investigations
- call audited tools
- summarize patterns
- evaluate models
- recommend rules
- explain transactions

Hard rule:

- no direct mutation of live payment, rule, or model production state

## 5. Data Stores

### 5.1 PostgreSQL

Source of truth for transactional state.

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
- fraud decisions
- feature snapshots
- rules
- rule execution results
- manual review cases
- fraud labels
- ledger entries
- idempotency records
- webhook endpoints
- webhook deliveries
- audit logs
- outbox events

### 5.2 Redis

Low-latency operational state.

Owns:

- velocity counters
- temporary feature cache
- idempotency acceleration
- rate-limit counters

PostgreSQL remains the durable source for idempotency. Redis is an optimization.

### 5.3 Kafka

Event backbone, added after core flows are stable.

Owns:

- durable domain event stream
- analytics ingestion
- graph ingestion
- model monitoring ingestion
- replayable integration boundary

### 5.4 ClickHouse

High-volume analytical store.

Owns:

- transaction analytics
- fraud decision analytics
- rule performance
- model prediction logs
- backtesting datasets and outputs

### 5.5 Neo4j

Graph intelligence store.

Owns:

- customer-device-IP-card-merchant graph
- fraud proximity queries
- fraud ring investigation
- graph-derived features

### 5.6 Object Storage

Artifact and dataset storage.

Owns:

- raw event archives
- training datasets
- model artifacts
- backtest outputs

### 5.7 MLflow

Model lifecycle tracking.

Owns:

- experiments
- metrics
- parameters
- model versions
- model registry metadata

## 6. Core Modules In `payments-api`

### 6.1 Payment Module

Owns:

- PaymentIntent lifecycle
- PaymentAttempt lifecycle
- state transitions
- confirm/capture/cancel orchestration

Important invariants:

- invalid state transitions fail
- each confirm creates a PaymentAttempt
- mutating requests require idempotency
- fraud decision is required once fraud MVP is active

### 6.2 Payment Method Module

Owns:

- simulated card tokenization
- card fingerprinting
- BIN/last4/expiry/country/funding metadata
- CVC/AVS metadata

Important invariants:

- raw PAN is not persisted
- CVV is not persisted
- test cards produce deterministic simulator behavior

### 6.3 Processor Module

Owns:

- `ProcessorAdapter` interface
- deterministic processor simulator
- authorization responses
- CVC/AVS/3DS outcomes
- processor event records

Design intent:

- make a future real PSP adapter possible without changing payment orchestration

### 6.4 Fraud Module

Owns:

- feature extraction orchestration
- model scoring orchestration, later
- decision engine
- FraudDecision persistence
- FeatureSnapshot persistence
- reason codes

Important invariants:

- real-time path has bounded latency
- LLMs are not called
- every decision is auditable

### 6.5 Rules Module

Owns:

- safe JSON rule DSL
- rule validation
- rule evaluation
- rule versions
- rule execution results
- rule lifecycle
- rule simulation, later

Important invariants:

- no arbitrary code execution
- missing values are explicit
- activation requires validation, simulation, and approval

### 6.6 Review Module

Owns:

- manual review cases
- assignment
- review timeline
- approve/cancel/refund/refund-and-report-fraud actions
- review outcome events

### 6.7 Chargeback Module

Owns:

- chargeback ingestion
- chargeback state
- fraud labels from disputes
- dispute ledger effects

### 6.8 Ledger Module

Owns:

- ledger transaction groups
- ledger entries
- account definitions
- balance invariant checks

Important invariant:

```text
sum(debits) == sum(credits)
```

No money movement operation is valid if its ledger transaction group does not balance.

### 6.9 Webhook Module

Owns:

- webhook endpoint registration
- enabled event filtering
- delivery attempt records
- signing-secret simulation
- retry metadata
- success/failure status

### 6.10 Audit Module

Owns:

- audit event recording
- actor tracking
- request IDs
- important before/after state where useful

## 7. Payment Lifecycle Architecture

### 7.1 Create PaymentIntent

```text
merchant request
  -> validate amount/currency/customer
  -> enforce idempotency
  -> create PaymentIntent
  -> emit payment_intent.created
  -> return PaymentIntent
```

Initial status:

- `REQUIRES_PAYMENT_METHOD`, if no payment method exists
- `REQUIRES_CONFIRMATION`, if payment method is already attached

### 7.2 Confirm PaymentIntent

```text
confirm request
  -> enforce idempotency
  -> load PaymentIntent
  -> validate confirmable state
  -> create PaymentAttempt
  -> run fraud decision
  -> apply fraud action
  -> maybe call processor simulator
  -> persist result
  -> emit events
  -> create webhook delivery records
```

Fraud actions:

- `BLOCK`: fail/cancel attempt before authorization
- `REQUEST_3DS`: move intent to `REQUIRES_ACTION`
- `REVIEW`: create review case
- `ALLOW`: call processor simulator

### 7.3 Capture

```text
capture request
  -> enforce idempotency
  -> load authorization
  -> validate capturable amount
  -> create Capture
  -> write balanced ledger entries
  -> update PaymentIntent
  -> emit capture.created
```

Invalid:

- capture before authorization
- capture after cancel
- double capture beyond authorized amount
- capture after full refund

### 7.4 Cancel / Void

```text
cancel request
  -> enforce idempotency
  -> validate cancelable state
  -> cancel authorization if needed
  -> update PaymentIntent
  -> emit payment_intent.canceled
```

Cancel is pre-capture. Refund is post-capture.

### 7.5 Refund

```text
refund request
  -> enforce idempotency
  -> validate captured amount
  -> validate refundable remaining amount
  -> create Refund
  -> write balanced ledger entries
  -> update PaymentIntent if fully refunded
  -> emit refund.created
```

Invalid:

- refund before capture
- over-refund
- duplicate refund on idempotency retry

### 7.6 Chargeback

```text
chargeback request
  -> validate captured payment
  -> create Chargeback
  -> write dispute ledger entries
  -> create FraudLabel if reason indicates fraud
  -> update PaymentIntent to DISPUTED
  -> emit chargeback.created
```

Chargebacks are a key source of delayed fraud labels.

## 8. Fraud Decision Architecture

### 8.1 Decision Inputs

Inputs:

- payment amount/currency
- merchant risk policy
- customer history
- payment method metadata
- CVC/AVS/3DS outcomes where available
- velocity counters
- rules
- model score, later
- graph signals, later

### 8.2 Decision Output

Output:

- action
- risk score
- risk level
- reason codes
- rule execution results
- model version, when available
- ruleset version
- latency

### 8.3 Decision Ordering

Evaluate all deterministic controls and aggregate matched candidates using:

1. `BLOCK`
2. `REVIEW`
3. `REQUEST_3DS`
4. `ALLOW` when no stronger control matches

Do not short-circuit evaluation; lower-severity matches remain explanation
evidence. Phase 4 adds list, velocity, and merchant-policy candidates without
allowing them to suppress a stronger protected action. Combine with calibrated
ML thresholds only after model scoring and governance exist.

### 8.4 Latency Budget

Initial local p95 target for full confirm path:

- under 250 ms excluding cold starts

Production-style target:

- fraud decision sub-path under 100 ms p95
- full authorization path under 300 ms p95, excluding real external processor behavior

## 9. Event Architecture

### 9.1 Outbox First

All domain events should be written through an outbox table in the same database transaction as the state change.

Why:

- prevents state/event mismatch
- allows retries
- supports eventual Kafka publishing
- enables replay

### 9.2 Event Categories

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

Webhook events:

- `webhook.delivery_succeeded`
- `webhook.delivery_failed`

### 9.3 Event Compatibility

Events must be versioned once Kafka is introduced.

Rules:

- add fields before removing fields
- keep old consumers working during rollout
- avoid meaning changes without version bump
- preserve replay compatibility

## 10. Security Architecture

### 10.1 Data Minimization

Do not persist:

- raw PAN
- CVV
- raw sensitive authentication data

Persist:

- token
- fingerprint
- BIN
- last4
- expiry metadata
- card country
- funding type
- verification outcomes

### 10.2 Trust Boundaries

External boundary:

- merchant/client APIs
- dashboard UI
- webhook destinations

Internal boundary:

- database
- Redis
- model service
- Kafka
- workers
- agent service

High-risk operations:

- payment confirmation
- capture
- refund
- chargeback creation
- rule activation
- model promotion
- review resolution

High-risk operations need audit logs.

### 10.3 Agent Boundary

Agents are offline assistants.

They can:

- inspect
- summarize
- explain
- simulate
- recommend

They cannot:

- approve live payments
- block live payments
- capture money
- refund money
- activate rules
- promote models

## 11. Observability Architecture

### 11.1 Logs

Use structured logs with:

- request ID
- trace ID
- merchant ID where safe
- payment intent ID
- payment attempt ID
- decision ID where applicable
- latency
- outcome

Do not log raw PAN, CVV, secrets, or sensitive request bodies.

### 11.2 Metrics

Technical:

- API latency
- DB latency
- Redis latency
- processor simulator latency
- model latency
- rule evaluation latency
- webhook delivery success/failure
- Kafka lag, later

Business/payment:

- payment volume
- authorization rate
- capture rate
- refund rate
- chargeback rate

Fraud:

- fraud action distribution
- manual review rate
- 3DS request rate
- rule hit rate
- rule precision
- model score distribution
- false positive rate

### 11.3 Traces

One trace should connect:

```text
request
  -> idempotency
  -> payment state load
  -> feature extraction
  -> fraud decision
  -> processor simulator
  -> ledger writes
  -> outbox events
  -> webhook delivery records
```

## 12. Deployment Architecture

### 12.1 Local First

Local environment:

- Java app
- PostgreSQL
- Redis
- dashboard
- later Kafka, ClickHouse, Neo4j, MLflow

### 12.2 CI/CD Later

Add once core flows are stable:

- build checks
- unit tests
- integration tests
- container builds
- migration checks
- frontend checks

### 12.3 Blue-Green Later

Production-shaped release strategy:

- blue-green backend deployment
- preview/blue-green dashboard deployment
- application pipeline separate from infrastructure pipeline
- pre-promotion validation
- rollback playbooks

Compatibility rule:

- API, event, rule, and database changes need expand/contract migration plans.

## 13. Architecture Decision Defaults

Default decisions:

- modular monolith first
- Java/Spring Boot for core backend
- PostgreSQL as transactional source of truth
- Redis for low-latency counters/cache
- Kafka only after core domain flows exist
- ClickHouse only after analytics needs are real
- Neo4j only after graph use cases are testable
- Python/FastAPI for model serving
- React/TypeScript for dashboard
- no LLM in authorization path
- no raw card storage
- double-entry ledger for money movement

## 14. Architecture Readiness Checklist

Before implementation begins, confirm:

- `docs/IMPLEMENTATION_MASTER_PLAN.md` is final source of truth.
- V1 is card-only simulator.
- `REQUEST_3DS` is canonical.
- `payments-api` is the first backend.
- PostgreSQL and Redis are first infrastructure dependencies.
- synthetic data starts in Phase 1.
- PCI DSS scope position is explicit.
- raw PAN and CVV are not persisted.
- idempotency is required for mutating payment APIs.
- fraud decisions are auditable.
- ledger groups must balance.
- webhooks are part of V1.
- ML work is data-first; advanced models are conditional.
- ReplayEngine is required before serious rule/model/threshold changes.

This checklist is satisfied as of this document version.
