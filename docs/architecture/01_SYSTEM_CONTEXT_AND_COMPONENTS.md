# 01. System Context And Components

Date: 2026-05-29

This document defines the system context, runtime components, module boundaries, integration boundaries, and ownership model.

For implementation decisions, follow `docs/IMPLEMENTATION_MASTER_PLAN.md`.

## 1. System Purpose

The platform exists to teach and demonstrate payment processing and fraud detection end to end.

It must support:

- merchant-based multi-tenancy
- card payment simulation
- PaymentIntent and PaymentAttempt lifecycle
- real-time fraud decisioning
- deterministic processor outcomes
- manual and automatic capture
- cancel/void
- refunds
- chargebacks/disputes
- double-entry ledger
- idempotency
- webhooks
- audit logs
- manual review
- fraud labels
- ML scoring, later
- graph intelligence, later
- GenAI fraud intelligence, later

V1 is a simulator. It does not move real money and does not integrate with a real PSP.

## 2. Context Diagram

```text
Merchant Backend / Client
  |
  | REST APIs + X-Api-Key + idempotency keys
  v
payments-api
  |
  | transactional reads/writes
  v
PostgreSQL

payments-api
  |
  | velocity counters / idempotency acceleration
  v
Redis

payments-api
  |
  | deterministic auth/capture/refund outcomes
  v
Processor Simulator

payments-api
  |
  | webhook delivery records + retry worker
  v
Merchant Webhook Endpoint

Analyst Dashboard
  |
  | internal/admin APIs
  v
payments-api

Later:

payments-api -> Kafka -> analytics-worker -> ClickHouse
payments-api -> model-serving-service
Kafka -> graph-ingestion-worker -> Neo4j
Analytics / graph / model tools -> fraud-intelligence-agent-service
```

## 3. Primary Actors

### 3.1 Merchant Developer

Uses APIs to:

- create customers
- create payment methods
- create payment intents
- confirm payments
- capture payments
- cancel payments
- refund payments
- receive webhooks
- retry requests safely

### 3.2 Fraud Analyst

Uses dashboard to:

- search payments
- inspect risk decisions
- resolve manual reviews
- inspect chargebacks
- evaluate rules
- view rule simulation results
- investigate graph clusters, later
- read agent reports, later

### 3.3 ML / Fraud Platform Engineer

Uses platform data to:

- build datasets
- train models
- evaluate performance
- monitor drift
- inspect false positives and false negatives
- promote models through governance

### 3.4 System Operator

Owns:

- service health
- latency
- error rates
- data durability
- event delivery
- observability
- deployments
- rollback readiness

## 4. Runtime Components

### 4.1 `payments-api`

Primary backend for V1.

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

Responsibilities:

- merchant admin setup APIs
- merchant tenant resolution
- customer APIs scoped by merchant
- payment method simulation
- PaymentIntent lifecycle
- PaymentAttempt lifecycle
- idempotency
- fraud decision orchestration
- rules engine
- manual review
- processor simulation orchestration
- authorization, capture, cancel, refund, chargeback
- ledger writes
- webhook records
- audit logs
- outbox events

Non-responsibilities in V1:

- real PSP integration
- real money movement
- ACH/SEPA/wallets
- production LLM decisions
- cloud deployment

### 4.2 PostgreSQL

Transactional source of truth.

Owns:

- stateful domain records
- audit trail
- idempotency source of truth
- outbox records
- ledger entries

PostgreSQL must be treated as the durable system of record.

### 4.3 Redis

Low-latency operational store.

Owns:

- fraud velocity counters
- temporary feature cache, later
- idempotency acceleration
- rate-limit counters

Redis must not be the only durable copy of important state.

### 4.4 Processor Simulator

In-process or module-level adapter behind `ProcessorAdapter`.

Responsibilities:

- simulate issuer/network outcomes
- simulate CVC checks
- simulate AVS checks
- simulate 3DS required/success/failure
- produce deterministic responses from configured test cards

Design goal:

- future real PSP adapter can be introduced without rewriting payment orchestration.

### 4.5 Webhook Worker

Can start inside `payments-api`.

Responsibilities:

- read pending webhook deliveries
- sign payloads using simulated signing secret
- attempt delivery
- persist status and response code
- schedule retries

V1 can store delivery records without making external network calls if local callback endpoints are not available.

### 4.6 `analyst-dashboard-ui`

Frontend for investigation and operations.

Responsibilities:

- overview metrics
- transaction search
- transaction detail
- review queue
- rule management
- chargeback list
- model performance, later
- graph investigation, later
- agent reports, later

### 4.7 `model-serving-service`

Added after deterministic fraud MVP.

Technology:

- Python
- FastAPI
- scikit-learn
- logistic regression baseline
- XGBoost or LightGBM only if EDA and baseline error analysis justify it
- SHAP
- MLflow

Responsibilities:

- load model artifact
- serve fraud score
- return model version and reason codes
- keep scoring contract stable

### 4.8 `analytics-worker`

Added after outbox and Kafka.

Responsibilities:

- consume events
- build analytics projections
- backfill/replay projections
- feed ClickHouse
- feed model monitoring tables

### 4.9 `fraud-intelligence-agent-service`

Added late.

Responsibilities:

- offline investigations
- pattern summaries
- model evaluation reports
- rule recommendations
- fraud explanations

Hard boundary:

- cannot mutate live payment, rule, or model production state directly.

## 5. `payments-api` Internal Modules

### 5.1 Merchant Module

Owns:

- merchant records
- merchant status
- API key credential records
- merchant risk tier
- merchant risk policy, later

Key questions it answers:

- Is this merchant active?
- Which merchant context does this API key resolve to?
- What risk policy applies?
- Which webhook endpoints belong to the merchant?

Key invariant:

- `Merchant` is the V1 tenant root.
- Merchant-facing runtime APIs must derive tenant scope from credentials, not trusted request-body `merchantId`.

### 5.2 Customer Module

Owns:

- customer identity within merchant scope
- hashed email
- hashed phone
- customer creation time

Key invariant:

- customers are merchant-scoped.
- customer lookups from merchant-facing APIs must include `merchant_id`.

### 5.3 Payment Method Module

Owns:

- simulated card tokenization
- token
- card fingerprint
- BIN
- last4
- expiry metadata
- card country
- funding type
- CVC/AVS metadata

Key invariants:

- raw PAN is not persisted
- CVV is not persisted
- card fingerprint is stable for the same simulated card

### 5.4 Payment Module

Owns:

- PaymentIntent
- PaymentAttempt
- state transitions
- confirmation orchestration
- capture orchestration
- cancel orchestration

Key invariants:

- PaymentIntent is the merchant-facing payment container.
- PaymentAttempt is the scoring and audit unit.
- invalid state transitions fail.
- every confirm creates a new attempt unless idempotency returns a prior response.

### 5.5 Idempotency Module

Owns:

- idempotency key persistence
- request hash comparison
- original response replay
- expiry handling

Key invariants:

- same key + same request returns original result
- same key + different request is rejected
- mutating payment APIs require idempotency

### 5.6 Fraud Module

Owns:

- decision orchestration
- feature snapshot creation
- risk score and risk level
- decision action
- reason codes
- model integration, later

Key invariants:

- no LLM calls in real-time
- every fraud decision is persisted
- every feature snapshot is decision-time only

### 5.7 Rules Module

Owns:

- rule DSL
- validation
- evaluation
- rule versions
- rule execution results
- rule lifecycle
- simulation, later

Key invariants:

- no arbitrary code execution
- missing-value behavior is explicit
- active rules are versioned

### 5.8 Review Module

Owns:

- manual review cases
- review assignment
- analyst decisions
- review timeline
- review outcome events

### 5.9 Processor Module

Owns:

- `ProcessorAdapter`
- simulator implementation
- processor request/response records
- CVC/AVS/3DS outcomes

### 5.10 Ledger Module

Owns:

- account definitions
- ledger transaction group creation
- ledger entries
- balancing checks

Key invariant:

```text
sum(debits) == sum(credits)
```

### 5.11 Chargeback Module

Owns:

- chargeback creation
- chargeback state
- dispute ledger entries
- fraud labels from chargebacks

### 5.12 Webhook Module

Owns:

- webhook endpoints
- event filtering
- delivery attempts
- signing-secret simulation
- retry metadata

### 5.13 Audit Module

Owns:

- audit event recording
- actor context
- request ID
- trace ID
- before/after state where useful

## 6. Dependency Rules

Within `payments-api`, dependencies should flow inward toward domain logic.

Allowed:

```text
controllers -> application services -> domain services -> repositories
application services -> integration adapters
application services -> audit/outbox
```

Avoid:

```text
domain entities -> controllers
domain entities -> external HTTP clients
domain entities -> Kafka clients
domain entities -> Redis clients
```

Fraud can depend on feature and rule abstractions. Payment orchestration can depend on fraud decision abstraction. Fraud should not directly mutate payment state.

## 7. Split-Service Criteria

Do not split a module into a service just because it sounds like a service.

Split only when one of these is true:

- the module needs independent scaling
- it has a different latency/SLO profile
- it owns a separate durable data boundary
- deployments need to be independent
- failure isolation is important
- a separate team would naturally own it

Likely split order:

1. model-serving-service
2. analytics-worker
3. fraud-intelligence-agent-service
4. graph-intelligence worker/service
5. webhook worker, if delivery load grows

## 8. Architecture Invariants

- Real-time payment authorization path is deterministic.
- LLMs are offline only.
- PaymentAttempt is the fraud scoring and audit unit.
- Mutating payment APIs require idempotency.
- Raw PAN and CVV are never persisted.
- Every money movement has balanced ledger entries.
- Every important state change emits an outbox event.
- Every fraud decision has reason codes.
- Rule activation requires validation, simulation, and approval.
- Feature snapshots must not include future information.
- Agents cannot directly mutate live production state.
