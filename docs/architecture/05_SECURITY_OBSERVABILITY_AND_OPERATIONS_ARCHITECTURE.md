# 05. Security, Observability, And Operations Architecture

Date: 2026-05-29

This document defines security boundaries, sensitive-data handling, operational controls, observability, reliability, deployment strategy, and readiness gates.

For implementation decisions, follow `docs/IMPLEMENTATION_MASTER_PLAN.md`.

## 1. Security Goals

The platform must teach production-shaped payment security without claiming real PCI certification.

Security goals:

- never persist raw PAN
- never persist CVV
- avoid logging sensitive data
- minimize PII
- hash or mask identity fields where practical
- require idempotency for mutating payment APIs
- audit high-risk actions
- separate real-time payment decisions from offline GenAI
- design for future RBAC
- keep secrets out of source code

### 1.1 PCI DSS Scope Position

V1 is not PCI-certified and does not process real cardholder data.

PCI DSS still shapes the architecture:

- Treat raw test PAN as transient input only.
- Never persist raw PAN.
- Never persist CVV.
- Never log raw PAN or CVV.
- Convert card input immediately into simulated token, fingerprint, BIN, last4, expiry metadata, country, funding type, and verification outcomes.
- Keep the architecture close to a low-scope tokenized integration.
- Future real PSP integration should use hosted fields or PSP tokenization so this platform does not store cardholder data.

Architecture implication:

- Payment method creation is the only boundary where test card details are accepted.
- All downstream modules use tokenized/safe metadata only.

## 2. Data Classification

### 2.1 Highly Sensitive

Must not be persisted:

- raw PAN
- CVV
- raw authentication secrets

Must not be logged:

- raw PAN
- CVV
- signing secrets
- auth tokens

### 2.2 Sensitive

Persist only if needed and preferably hashed/masked:

- email
- phone
- IP address
- address
- user agent
- device ID

### 2.3 Payment Metadata

Allowed with care:

- card BIN
- card last4
- card country
- card funding type
- expiry month/year
- token
- fingerprint
- CVC check result
- AVS check result
- 3DS result

### 2.4 Operational Metadata

Allowed:

- payment IDs
- attempt IDs
- event IDs
- rule IDs
- model versions
- request IDs
- trace IDs
- timestamps
- statuses

## 3. Card Data Handling

Payment method creation flow:

```text
API receives test card data
  -> validate test card shape
  -> derive token
  -> derive fingerprint
  -> derive BIN/last4/country/funding
  -> discard raw PAN
  -> discard CVV
  -> persist safe metadata
```

Rules:

- do not log request body containing card details
- do not persist PAN
- do not persist CVV
- do not expose token secrets unnecessarily
- use masked card display only

## 4. API Security

V1 local security:

- request validation
- idempotency keys for mutating payment APIs
- request IDs
- structured errors
- safe logging
- basic auth placeholder or local auth when dashboard begins

Later:

- Spring Security
- JWT or session auth
- RBAC
- service-to-service authentication
- secret management
- rate limiting

High-risk APIs:

- confirm payment
- capture payment
- cancel payment
- refund
- chargeback
- review resolution
- rule activation
- model promotion

High-risk APIs must be audited.

### 4.1 Rate Limiting Strategy

V1 rate limiting uses Redis-backed counters.

Limit dimensions:

- merchant ID
- API key when introduced
- IP address
- endpoint family
- card fingerprint for payment-method/confirm abuse patterns

High-risk endpoint families:

- payment method creation
- payment confirmation
- capture
- refund
- chargeback
- login/auth when added
- rule management

Initial algorithms:

- fixed-window counters for simple endpoint limits
- token bucket for burst-sensitive endpoints later

Failure behavior:

- fail closed for high-risk mutation endpoints when abuse risk is high
- fail open only for low-risk read endpoints during Redis degradation

Metrics:

- allowed requests
- throttled requests
- blocked requests
- rate-limit Redis errors

## 5. Idempotency Security

Threats:

- duplicate capture
- duplicate refund
- replay with changed body
- retry after network timeout

Controls:

- require idempotency key for mutating payment APIs
- bind key to merchant
- bind key to method/path/request hash
- reject same key with different request hash
- return stored response for same key and same request
- expire old keys after configured period

## 6. Webhook Security

Webhook requirements:

- signing-secret simulation
- event payload signing
- delivery attempt tracking
- retry metadata
- response status capture

Later production-shaped controls:

- timestamped signatures
- replay window validation
- secret rotation
- endpoint verification

Do not include sensitive card data in webhook payloads.

## 7. Fraud And Rule Security

Rules engine controls:

- constrained JSON DSL
- no arbitrary code execution
- validated field names
- validated operators
- explicit missing-value semantics
- versioned rules
- activation approval
- audit logs

Rule risks:

- stale allowlists bypass fraud controls
- broad block rules cause false positives
- missing-value mistakes create unintended behavior
- unapproved rule activation affects live payment decisions

Controls:

- default allowlist expiration where possible
- simulation before activation
- human approval
- canary state later
- rollback to prior version

## 8. GenAI Agent Security

Agents are offline.

Allowed:

- inspect transactions through tools
- summarize patterns
- explain decisions
- recommend rules
- run simulations through approved tools

Forbidden:

- approve live payments
- block live payments
- capture payments
- refund payments
- activate rules
- promote models
- direct SQL over sensitive tables
- raw PII in prompts

Agent tool controls:

- tool allowlist
- structured inputs
- structured outputs
- audit every tool call
- mask sensitive fields
- require human approval for operational changes

## 9. Audit Architecture

Audit records should include:

- actor
- action
- aggregate type
- aggregate ID
- request ID
- trace ID
- timestamp
- reason
- before/after state where useful

Audit required for:

- merchant/customer/payment method creation
- payment confirmation
- authorization
- capture
- cancel
- refund
- chargeback
- fraud decision
- review action
- rule creation/activation/disable
- list changes
- model promotion
- agent tool call

## 10. Observability Goals

Observability should answer:

- Is the API healthy?
- Are payment confirmations fast?
- Are fraud decisions fast?
- Are processor simulator outcomes behaving as expected?
- Are ledger writes balanced?
- Are webhook deliveries succeeding?
- Are rules overfiring?
- Are model predictions timing out?
- Are chargebacks increasing?
- Are reviews backing up?

## 11. Logging

Use structured logs.

Include:

- timestamp
- level
- service
- request ID
- trace ID
- merchant ID where safe
- payment intent ID
- payment attempt ID
- fraud decision ID
- operation
- outcome
- latency

Never log:

- raw PAN
- CVV
- signing secrets
- auth tokens
- full sensitive request bodies

## 12. Metrics

### 12.1 Technical Metrics

- API latency p50/p95/p99
- API error rate
- DB latency
- Redis latency
- rule evaluation latency
- fraud decision latency
- processor simulator latency
- webhook delivery latency
- webhook success/failure rate
- Kafka lag, later
- model service latency, later
- model timeout rate, later

### 12.2 Payment Metrics

- payment intent creation count
- confirmation count
- authorization rate
- processor decline rate
- capture rate
- refund rate
- chargeback rate
- cancel rate
- average payment amount
- payment volume by merchant

### 12.3 Fraud Metrics

- `ALLOW` rate
- `BLOCK` rate
- `REQUEST_3DS` rate
- `REVIEW` rate
- rule hit rate
- manual review queue size
- manual review SLA age
- fraud label count
- false positive estimate
- fraud capture rate
- chargeback rate by merchant

### 12.4 ML Metrics

Added later:

- PR-AUC
- precision
- recall
- false positive rate
- score distribution
- feature drift
- model version traffic
- segment metrics

## 13. Tracing

Trace confirm flow:

```text
HTTP request
  -> idempotency lookup
  -> payment intent load
  -> payment attempt creation
  -> feature extraction
  -> velocity lookup
  -> rule evaluation
  -> model score, later
  -> fraud decision persistence
  -> processor simulator
  -> authorization persistence
  -> capture and ledger, if automatic capture
  -> outbox event creation
  -> webhook delivery creation
  -> HTTP response
```

Trace refund flow:

```text
HTTP request
  -> idempotency lookup
  -> capture lookup
  -> refund validation
  -> refund persistence
  -> ledger writes
  -> outbox event creation
  -> webhook delivery creation
  -> HTTP response
```

## 14. Reliability Strategy

### 14.1 Database Failure

Behavior:

- return controlled error
- rollback transaction
- do not mark idempotency as completed
- do not emit events outside committed transaction

### 14.2 Redis Failure

Early behavior:

- fail closed for velocity-dependent decisions if velocity is required

Later behavior:

- degraded rules-only mode using durable history where possible
- alert on Redis failure

### 14.3 Processor Simulator Failure

Behavior:

- avoid partial authorization state
- return retryable error if no definitive processor outcome
- preserve idempotency semantics

### 14.4 Model Service Timeout

Behavior:

- bounded timeout
- rules-only fallback
- record `MODEL_TIMEOUT`
- emit metric

### 14.5 Webhook Failure

Behavior:

- payment operation succeeds
- delivery failure is recorded
- retry is scheduled
- merchant can inspect deliveries

## 15. Deployment Architecture

### 15.1 Local Development

Local stack starts with:

- `payments-api`
- PostgreSQL
- Redis

Later local dependencies:

- dashboard UI
- Kafka
- ClickHouse
- Neo4j
- MLflow
- model-serving-service

### 15.2 Deployment Target Default

Default sequence:

1. Local Docker Compose for V1.
2. Local Kubernetes after Docker Compose flows are stable.
3. AWS as the production-shaped reference environment.

AWS reference mapping:

- PostgreSQL -> Amazon RDS/Aurora PostgreSQL
- Redis -> ElastiCache
- Kafka -> MSK
- Object storage -> S3
- Containers -> EKS as primary reference
- Simpler container alternative -> ECS/Fargate
- Observability -> OpenTelemetry, Prometheus/Grafana locally, CloudWatch/AWS managed options later

GCP and Azure are out of scope unless explicitly selected later.

### 15.3 CI Pipeline

Add:

- backend compile
- backend unit tests
- backend integration tests
- migration validation
- frontend typecheck
- frontend tests
- Docker image build

### 15.4 CD Strategy Later

Production-shaped release strategy:

- blue-green backend deployment
- dashboard preview/blue-green deployment
- separate application and infrastructure pipelines
- pre-promotion validation
- rollback playbook

Compatibility requirements:

- API compatibility
- event schema compatibility
- rule schema compatibility
- database expand/contract migrations

## 16. Operational Runbooks

Create runbooks later for:

- payment confirmation latency spike
- processor decline spike
- fraud block-rate spike
- webhook delivery failures
- Redis outage
- database migration failure
- model timeout spike
- rule rollback
- chargeback spike
- manual review backlog

Each runbook should include:

- symptoms
- dashboards
- logs
- immediate mitigation
- rollback steps
- follow-up investigation

## 17. Readiness Gates

### 17.1 V1 Backend Readiness

Ready when:

- app starts locally
- migrations run
- create/confirm/capture/refund tests pass
- idempotency tests pass
- ledger tests pass
- fraud decision tests pass
- raw PAN/CVV persistence tests pass

### 17.2 Dashboard Readiness

Ready when:

- transaction search works
- transaction detail works
- review queue works
- review actions work
- rule list displays active rules

### 17.3 ML Readiness

Ready when:

- dataset builder works
- time split works
- baseline model trains
- model metrics are recorded
- scoring service returns versioned predictions
- timeout fallback works

### 17.4 Production-Shape Readiness

Ready when:

- OpenTelemetry traces exist
- metrics dashboards exist
- structured logging exists
- load tests run
- rollback playbooks exist
- blue-green strategy is documented

## 18. Security And Operations Acceptance Criteria

Complete when:

- no raw PAN/CVV persistence path exists
- sensitive data is masked in logs
- mutating payment APIs require idempotency
- high-risk actions are audited
- webhook payloads are signed
- payment/fraud/ledger metrics are emitted
- confirm flow is traceable end to end
- failure modes have controlled behavior
- future agent tools are audited and constrained
