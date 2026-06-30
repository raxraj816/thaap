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
- enforce merchant-tenant isolation
- treat card-testing, enumeration, bot abuse, and credential leakage as
  security events
- classify and minimize device, session, IP, email, phone, address, and bot
  signals
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
- raw API key secrets after initial one-time reveal

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
- session ID
- risk context values
- bot/friction scores

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
- merchant tenant resolution from `X-Api-Key`
- idempotency keys for mutating payment APIs
- request IDs
- structured errors
- safe logging

Later:

- Spring Security
- JWT or session auth
- RBAC
- service-to-service authentication
- secret management
- rate limiting

### 4.1 Merchant Tenant Isolation

`Merchant` is the V1 tenant root.

Runtime merchant-facing APIs must:

- require a valid `X-Api-Key`
- resolve `MerchantContext` from the API key
- ignore any client-supplied `merchantId` for authorization
- use tenant-scoped repository queries such as `(id, merchant_id)`
- return `404` for objects outside the current merchant scope
- bind idempotency records to `(merchant_id, key)`

Admin setup APIs may create merchants and issue test keys in local development. Before any non-local deployment, API key storage must move to hashed secrets with a non-secret prefix lookup and one-time secret reveal.

Cross-merchant isolation tests are required for each merchant-facing endpoint family.

High-risk APIs:

- confirm payment
- capture payment
- cancel payment
- refund
- chargeback
- review resolution
- rule activation
- merchant risk policy update
- card-testing mitigation update
- dispute evidence submission
- model promotion

High-risk APIs must be audited.

### 4.2 Rate Limiting Strategy

V1 rate limiting uses Redis-backed counters.

Limit dimensions:

- merchant ID
- API key
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

### 4.3 Card-Testing And Enumeration Security

Card-testing and enumeration attacks are security incidents because they can
damage merchant reputation, increase network risk, create dispute exposure, and
strain infrastructure.

Controls:

- detect spikes in failed authorizations and low-amount attempts
- detect many distinct card fingerprints from shared IP/device/session groups
- detect card setup attempts without successful purchases
- track enumeration ratio using approved and declined attempts
- create `CardTestingIncident` records for grouped attacks
- recommend mitigations such as rate-limit changes, CAPTCHA-required mode,
  temporary payment-method creation restrictions, and API-key rotation

Operational rule:

- Mitigations must create an audit log, incident update, metric, or explicit
  merchant/operator notification. Silent traffic suppression is not acceptable.

### 4.4 Payment-Page And Checkout-Script Security

THAAP V1 is API-first and does not host real payment pages. Future hosted-field
or checkout UI work must still model PCI-shaped browser risk:

- inventory checkout scripts
- restrict payment-page scripts to approved sources
- monitor payment-page integrity when a payment page exists
- avoid placing raw PAN/CVV handling in merchant-controlled JavaScript
- document how hosted fields/tokenization reduce card-data scope

This is a learning requirement, not a PCI-compliance claim.

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
- handle concurrent duplicate requests through locking or unique-constraint conflict handling

## 6. Webhook Security

Chapter 19 webhook controls:

- sign exact immutable payload bytes with timestamped HMAC-SHA256
- encrypt the raw per-endpoint signing secret with AES-256-GCM under a separate,
  versioned key-encryption key
- retain prior key versions in an explicit decryption keyring during rollover,
  fail startup when active ciphertext is unreadable, and lazily re-encrypt old
  ciphertext under the current key while holding the endpoint row lock
- retain prefix/hash only for safe display and verification; never use the hash
  as the signing key
- reveal a raw signing secret only at endpoint creation or rotation
- require endpoint rotation when legacy rows have only irreversible hashes
- serialize rotation/disable with an endpoint write lock, reject rotation of a
  disabled endpoint, and use shared routing locks so disable cannot miss a
  concurrently created delivery
- build payloads from a strict safe-field allowlist through JSON serialization,
  not string concatenation, because the exact bytes become the merchant
  signature contract
- execute HTTP after the originating payment transaction commits
- claim one attempt immediately before dispatch with a bounded lease and append
  delivery-attempt evidence
- bound DNS with an application deadline, fixed resolver capacity, and bounded
  queue while retaining approved-address pinning; classify timeout, saturation,
  interruption, unknown host, empty answers, and resolver infrastructure
  failures as retryable known-no-send outcomes, reserving terminal target denial
  for URL policy and prohibited resolved addresses
- disable redirects, bound connect time, enforce a wall-clock request deadline
  across TLS/write/read, validate final HTTP status grammar/range, require a
  complete bounded final response head, and discard response bodies
- durably distinguish pre-dispatch known-no-send state from in-flight,
  partial-write, and lease-recovered post-dispatch uncertainty
- allow only loopback HTTP in local/test; this is simulator-only plaintext
  loopback behavior for callback fixtures, not an acceptable shared-environment
  transport for merchant webhook payloads and signature headers
- require HTTPS and an explicit hostname allowlist when non-local execution is
  enabled
- reject URL user-info/fragments and non-public resolved targets, including
  private, link-local, multicast, unspecified, metadata-service, IPv6
  unique-local, CGNAT, benchmark, documentation, and reserved addresses
- keep merchant, endpoint, delivery, resource, URL, and raw error values out of
  metric tags
- recover expired in-flight rows even after endpoint disable, count
  recovered-unknown and terminal lease/disable outcomes, report actual due
  backlog, and include bounded latency in finalization logs

Later shared-environment controls:

- endpoint verification
- overlapping old/new secret rotation windows (`TD-0003`)
- merchant-triggered replay controls
- controlled egress proxy infrastructure
- endpoint-specific rate controls

Do not include raw PAN, CVV, buyer email/phone, API keys, signing secrets, card
fingerprints, idempotency material, processor secrets, or unrestricted merchant
metadata in webhook payloads, persistence, responses, logs, or metrics.

Webhook delivery is at least once. Merchants verify the exact-body signature,
then parse the signed payload `id`, require the `THAAP-Event-Id` convenience
header to agree, and deduplicate on the authenticated body value. THAAP must
not advertise exactly-once receipt.

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

Merchant risk policy controls:

- policy changes require actor, reason, and audit trail
- policy changes require replay/simulation once ReplayEngine exists
- emergency policy changes require explicit emergency reason and follow-up
  review
- broad policy changes must report authorization, 3DS friction, review load,
  dispute, and false-positive impact

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
- merchant risk profile and risk policy changes
- card-testing incident mitigation
- dispute evidence packet creation/submission/outcome
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
- Are card-testing incidents active?
- Are enumeration ratios rising?
- Are merchant fraud/dispute ratios approaching thresholds?
- Are dispute evidence deadlines at risk?
- Are risk policy changes harming authorization rate or review capacity?

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
- raw risk context values such as full IP, full address, raw email, raw phone,
  raw device ID, or raw session ID

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
- fraud/dispute ratio by merchant
- enumeration ratio
- card-testing incident count
- merchant risk tier distribution
- review SLA breach count
- dispute evidence win/loss rate

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
- fraud-type coverage
- merchant-risk-tier performance
- shadow-mode disagreement rate
- canary rollback count

## 13. Tracing

Trace confirm flow:

```text
HTTP request
  -> idempotency lookup
  -> payment intent load
  -> payment attempt creation
  -> risk context capture
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

### 14.6 Card-Testing Incident

Behavior:

- continue serving legitimate traffic when possible
- create or update a `CardTestingIncident`
- tighten configured rate limits only through explicit policy
- recommend CAPTCHA/bot-friction mode when appropriate
- alert operator or merchant for severe incidents
- preserve samples for investigation without storing raw sensitive values

### 14.7 Risk Policy Regression

Behavior:

- detect sudden authorization-rate drop, review-rate spike, 3DS-friction spike,
  or false-positive estimate spike after a policy/rule/model change
- rollback to previous policy/ruleset/model when configured guardrails trip
- preserve replay and activation metadata for investigation

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
- card-testing or enumeration spike
- webhook delivery failures
- Redis outage
- database migration failure
- model timeout spike
- rule rollback
- merchant risk policy rollback
- chargeback spike
- dispute evidence deadline spike
- manual review backlog
- credential or API-key leakage

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
- risk context data-minimization tests pass
- card-testing incident tests pass
- raw PAN/CVV persistence tests pass

### 17.2 Dashboard Readiness

Ready when:

- transaction search works
- transaction detail works
- review queue works
- review actions work
- rule list displays active rules
- card-testing incident detail works
- merchant risk profile detail works
- dispute evidence packet detail works

### 17.3 ML Readiness

Ready when:

- dataset builder works
- time split works
- baseline model trains
- model metrics are recorded
- scoring service returns versioned predictions
- timeout fallback works
- dataset/model cards exist for promoted models
- shadow-mode reports exist before active model rollout

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
- risk context values are minimized and masked
- card-testing and enumeration incidents create auditable records
- merchant risk policy changes are audited and simulated once replay exists
- dispute evidence workflows preserve reason code, source, and outcome
- payment/fraud/ledger metrics are emitted
- confirm flow is traceable end to end
- failure modes have controlled behavior
- future agent tools are audited and constrained
