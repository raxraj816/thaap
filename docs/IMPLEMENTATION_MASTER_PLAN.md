# THAAP Implementation Master Plan

Date: 2026-05-29

This is the single source of truth for implementation. The files under `docs/references/` remain useful references, but when implementation decisions differ, this master plan wins.

## 1. Product Mission

Build THAAP, Threat Hunting & Adaptive Analytics for Payments, as an end-to-end payment processing and fraud detection platform for mastery of:

- Payment processing architecture.
- Card authorization, capture, refund, cancel/void, and dispute flows.
- Idempotency, webhooks, auditability, and ledgering.
- Payment security and PCI-style data minimization.
- Real-time fraud decisioning.
- Rules, ML scoring, graph intelligence, analyst operations, and GenAI-assisted fraud intelligence.

THAAP is not a toy checkout demo. It is a production-shaped simulator designed to teach and demonstrate how payment systems and fraud systems work together.

## 2. Final Scope Decisions

### 2.0 Multi-Tenant Boundary

THAAP is a multi-tenant platform with `Merchant` as the V1 tenant root.

Tenant model:

- Use a shared PostgreSQL database and shared schema.
- Use `merchant_id` as the tenant discriminator for merchant-owned data.
- Resolve merchant tenant context from API credentials, not from trusted request-body `merchantId`.
- V1 merchant-facing APIs use `X-Api-Key` to resolve `MerchantContext`.
- Admin-only setup APIs may create merchants and issue API keys.
- Runtime payment, webhook, fraud, rule, review, ledger, analytics, event, feature, graph, and ML paths must carry or derive `merchant_id`.

Isolation rule:

- A merchant must never read, mutate, replay, deliver webhook data for, train merchant-scoped features from, or operate on another merchant's objects.
- Application-level tenant enforcement and cross-merchant tests are mandatory from the first domain endpoints.
- PostgreSQL row-level security is deferred for V1 but remains a later defense-in-depth option.

### 2.1 V1 Scope

V1 is a full card-payment simulator with integrated fraud detection.

In scope:

- Merchant onboarding.
- Customer creation.
- Simulated card payment methods.
- PaymentIntent lifecycle.
- PaymentAttempt lifecycle.
- Authorization simulation.
- Manual and automatic capture.
- Cancel/void.
- Refunds.
- Disputes/chargebacks.
- Double-entry ledger MVP.
- Idempotency.
- Webhooks.
- Audit logs.
- Real-time fraud decisioning.
- Deterministic rules.
- Manual review.
- Basic analyst dashboard.

Out of scope for V1:

- Real money movement.
- Real PSP integration.
- ACH, SEPA, wallets, local payment methods.
- Payouts, reserves, full reconciliation reports.
- PCI-certified storage or handling.
- Real card PAN/CVV persistence.
- Production GenAI agents.
- GNN models.

Later phases can add these once the core lifecycle is stable.

### 2.2 PCI DSS Scope Position

V1 is not PCI-certified and must not claim PCI compliance.

However, PCI DSS still shapes the architecture:

- Raw PAN is accepted only as transient test input and is never persisted.
- CVV is never persisted.
- Logs must never contain raw PAN or CVV.
- Stored card data is limited to simulated token, fingerprint, BIN, last4, expiry metadata, country, funding type, and verification outcomes.
- The architecture should behave like a low-scope tokenized integration: payment method creation converts test card input into safe metadata immediately.
- Future real PSP integration should use hosted fields/tokenization so this platform does not enter high-scope card-data storage.

V1 goal:

- Teach PCI-shaped design and data minimization without handling real cardholder data.

### 2.3 Deployment Target Default

Default deployment path:

1. Local Docker Compose for all V1 development.
2. Local Kubernetes only after Docker Compose flows are stable.
3. AWS as the production-shaped cloud reference.

AWS reference mapping:

- PostgreSQL -> Amazon RDS/Aurora PostgreSQL
- Redis -> ElastiCache
- Kafka -> MSK
- Object storage -> S3
- Containers -> EKS as the primary reference target
- Alternative simpler container target -> ECS/Fargate
- Observability -> OpenTelemetry, Prometheus/Grafana locally, CloudWatch/AWS managed options later

GCP and Azure are out of scope unless explicitly chosen later.

### 2.4 Canonical Terms

Use these terms consistently:

- Payment container: `PaymentIntent`.
- Scoring/audit unit: `PaymentAttempt`.
- Step-up action: `REQUEST_3DS`.
- Fraud actions: `ALLOW`, `BLOCK`, `REQUEST_3DS`, `REVIEW`.
- Cancel before settlement: `CANCELED`.
- Post-capture customer claim: `DISPUTED` / chargeback.

Do not introduce `STEP_UP`, `APPROVE`, `DECLINE`, or `MANUAL_REVIEW` as fraud decision enum values. Those ideas map to `REQUEST_3DS`, `ALLOW`, `BLOCK`, and `REVIEW`.

## 3. Architecture

### 3.1 Initial Deployables

Start with a modular monolith and split later only when latency, scale, ownership, or operational risk requires it.

Initial deployables:

- `payments-api`
  - Java 25, Spring Boot 4.0.6.
  - Owns payment lifecycle, fraud orchestration, rules, review, chargebacks, ledger, idempotency, webhooks, and audit logs in early phases.

- `analyst-dashboard-ui`
  - React, TypeScript, TanStack Query, Tailwind CSS, Recharts.
  - Added after the core backend flows are testable.

Later deployables:

- `model-serving-service`
  - Python, FastAPI, scikit-learn/XGBoost/LightGBM, SHAP, MLflow.

- `analytics-worker`
  - Consumes domain events and writes analytics projections.

- `fraud-intelligence-agent-service`
  - Offline/async GenAI investigation and recommendation service.

### 3.2 Infrastructure Sequence

Use infrastructure in this order:

1. PostgreSQL from day one.
2. Redis for idempotency acceleration and velocity counters.
3. Synthetic data generator from Phase 1 onward.
4. V1 feature store tables before ML work begins.
5. Kafka after core payment/fraud flows are stable.
6. ClickHouse for high-volume analytics and backtesting.
7. Neo4j for graph intelligence.
8. Object storage for raw events, datasets, model artifacts, and backtest outputs.
9. MLflow for experiment tracking and model registry.

### 3.3 Real-Time Payment And Fraud Path

```text
POST /payment_intents/{id}/confirm
  -> validate request and idempotency key
  -> load PaymentIntent
  -> create PaymentAttempt
  -> extract real-time features
  -> read velocity counters
  -> evaluate fraud rules
  -> call model service when available, with timeout fallback
  -> combine signals in decision engine
  -> persist FraudDecision and FeatureSnapshot
  -> if BLOCK, fail/cancel attempt before authorization
  -> if REQUEST_3DS, move intent to REQUIRES_ACTION
  -> if REVIEW, create ManualReviewCase
  -> if ALLOW, call processor simulator
  -> persist authorization result
  -> publish domain events
  -> create webhook delivery records
```

The real-time path must not call LLMs.

### 3.4 Offline Intelligence Path

```text
Payment events
Fraud decision events
Review outcome events
Chargeback events
Model prediction logs
  -> Kafka
  -> PostgreSQL audit tables
  -> ClickHouse analytics
  -> Neo4j graph
  -> training datasets
  -> model evaluation
  -> rule simulation
  -> agent reports and recommendations
  -> human approval workflow
```

## 4. Public API Surface For V1

### 4.1 Payment APIs

- `POST /admin/merchants`
- `POST /customers`
- `POST /payment_methods`
- `POST /payment_intents`
- `GET /payment_intents/{id}`
- `POST /payment_intents/{id}/confirm`
- `POST /payment_intents/{id}/capture`
- `POST /payment_intents/{id}/cancel`
- `GET /payment_attempts/{id}`

### 4.2 Money Movement And Dispute APIs

- `POST /refunds`
- `POST /chargebacks`
- `GET /ledger/entries`

### 4.3 Webhook APIs

- `POST /webhook_endpoints`
- `GET /webhook_deliveries`

### 4.4 Fraud And Review APIs

These can start internal/admin-only, then become dashboard-backed:

- `GET /fraud/decisions/{id}`
- `GET /reviews`
- `GET /reviews/{id}`
- `POST /reviews/{id}/approve`
- `POST /reviews/{id}/cancel`
- `POST /reviews/{id}/refund`
- `POST /reviews/{id}/refund_and_report_fraud`
- `GET /rules`
- `POST /rules`
- `POST /rules/{id}/activate`
- `POST /rules/{id}/disable`

All mutating payment APIs must require an idempotency key.
Merchant-facing APIs derive tenant scope from `X-Api-Key`; do not trust client-supplied `merchantId`.

## 5. Core Domain Model

### 5.1 Payment Domain

`Merchant`

- `id`
- `name`
- `status`
- `riskTier`
- `apiKeyHash`
- `apiKeyPrefix`
- `createdAt`

Current local scaffolding may expose a generated test API key once at merchant creation. Production-shaped implementation must store only a hashed API key secret and a non-secret prefix.

`Customer`

- `id`
- `merchantId`
- `emailHash`
- `phoneHash`
- `createdAt`

`PaymentMethod`

- `id`
- `merchantId`
- `customerId`
- `type`
- `token`
- `cardFingerprint`
- `cardBin`
- `cardLast4`
- `cardExpMonth`
- `cardExpYear`
- `cardCountry`
- `cardFunding`
- `billingCountry`
- `cvcCheck`
- `avsLine1Check`
- `avsPostalCodeCheck`
- `createdAt`

Never persist raw PAN or CVV. Test card numbers may enter API boundaries only long enough to produce a simulated token, fingerprint, and deterministic processor behavior.

`PaymentIntent`

- `id`
- `merchantId`
- `customerId`
- `amount`
- `currency`
- `status`
- `captureMethod`
- `description`
- `metadata`
- `createdAt`
- `updatedAt`

PaymentIntent statuses:

- `REQUIRES_PAYMENT_METHOD`
- `REQUIRES_CONFIRMATION`
- `REQUIRES_FRAUD_CHECK`
- `REQUIRES_ACTION`
- `PROCESSING`
- `AUTHORIZED`
- `REQUIRES_CAPTURE`
- `CAPTURED`
- `CANCELED`
- `REFUNDED`
- `DISPUTED`
- `FAILED`

`PaymentAttempt`

- `id`
- `paymentIntentId`
- `paymentMethodId`
- `idempotencyKey`
- `status`
- `attemptNumber`
- `fraudDecisionId`
- `processorAuthorizationId`
- `threeDsStatus`
- `createdAt`

`Authorization`

- `id`
- `paymentAttemptId`
- `processor`
- `processorResponseCode`
- `networkStatus`
- `authorizedAmount`
- `currency`
- `status`
- `cvcCheck`
- `avsLine1Check`
- `avsPostalCodeCheck`
- `threeDsResult`
- `createdAt`

`Capture`

- `id`
- `authorizationId`
- `amount`
- `currency`
- `status`
- `createdAt`

`Refund`

- `id`
- `captureId`
- `amount`
- `currency`
- `reason`
- `status`
- `createdAt`

`Chargeback`

- `id`
- `paymentIntentId`
- `paymentAttemptId`
- `captureId`
- `amount`
- `currency`
- `reasonCode`
- `status`
- `receivedAt`

### 5.2 Ledger Domain

`LedgerEntry`

- `id`
- `transactionGroupId`
- `account`
- `direction`
- `amount`
- `currency`
- `createdAt`

V1 ledger accounts:

- `CUSTOMER_RECEIVABLE`
- `PLATFORM_CLEARING`
- `MERCHANT_PAYABLE`
- `FEE_REVENUE`
- `REFUND_LIABILITY`
- `DISPUTE_LOSS`

Invariant:

```text
For each transactionGroupId:
sum(debits) == sum(credits)
```

No capture, refund, fee, or chargeback write is valid unless the ledger transaction group balances.

### 5.3 Reliability And Integration Domain

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

`ProcessorEvent`

- `id`
- `paymentAttemptId`
- `eventType`
- `processor`
- `requestJson`
- `responseJson`
- `createdAt`

`WebhookEndpoint`

- `id`
- `merchantId`
- `url`
- `enabledEvents`
- `signingSecret`
- `status`
- `createdAt`

`WebhookDelivery`

- `id`
- `webhookEndpointId`
- `eventId`
- `attemptNumber`
- `status`
- `responseStatus`
- `nextRetryAt`
- `createdAt`
- `deliveredAt`

### 5.4 Fraud Domain

`FraudDecision`

- `id`
- `paymentAttemptId`
- `riskScore`
- `riskLevel`
- `action`
- `modelVersion`
- `rulesetVersion`
- `latencyMs`
- `reasonCodes`
- `createdAt`

`FeatureSnapshot`

- `id`
- `fraudDecisionId`
- `featuresJson`
- `featureVersion`
- `createdAt`

`FraudRule`

- `id`
- `name`
- `description`
- `conditionExpression`
- `action`
- `priority`
- `status`
- `version`
- `createdBy`
- `approvedBy`
- `createdAt`
- `activatedAt`

`RuleExecutionResult`

- `id`
- `fraudDecisionId`
- `ruleId`
- `ruleVersion`
- `matched`
- `action`
- `reasonCode`
- `latencyMs`

`ManualReviewCase`

- `id`
- `paymentAttemptId`
- `fraudDecisionId`
- `status`
- `assignedTo`
- `analystDecision`
- `notes`
- `createdAt`
- `closedAt`

`FraudLabel`

- `id`
- `paymentAttemptId`
- `source`
- `label`
- `confidence`
- `labelTime`

## 6. Processor Simulator

The simulator must be deterministic and test-card driven.

Required outcomes:

- approval
- issuer decline
- insufficient funds
- suspected fraud decline
- CVC failure
- AVS line1 failure
- AVS postal code failure
- 3DS required
- 3DS success
- 3DS failure

The simulator should be behind a `ProcessorAdapter` interface so a real PSP adapter can be added later without rewriting payment orchestration.

## 7. Fraud Decisioning

### 7.1 Decision Actions

Only these fraud decision actions are valid:

- `ALLOW`
- `BLOCK`
- `REQUEST_3DS`
- `REVIEW`

### 7.2 Rule Ordering

Evaluate active rules in this behavior order:

1. `REQUEST_3DS`
2. `ALLOW`
3. `BLOCK`
4. `REVIEW`

`ALLOW` short-circuits block/review rules. `BLOCK` short-circuits review rules.

### 7.3 Rule DSL

Use a constrained JSON DSL. Do not execute arbitrary JavaScript or user-provided code.

Supported operators:

- `eq`
- `neq`
- `gt`
- `gte`
- `lt`
- `lte`
- `in`
- `not_in`
- `exists`
- `is_missing`
- `contains`

Supported composition:

- `all`
- `any`
- `not`

Rules must have explicit missing-value behavior. A comparison against a missing value must not silently evaluate as true.

### 7.4 Initial Deterministic Rules

Seed these rules for learning and testing:

- `ALLOW` low-risk low-amount card payment.
- `BLOCK` blocked test card.
- `REVIEW` suspicious high amount.
- `REQUEST_3DS` configured 3DS test card.
- `BLOCK` excessive card velocity.
- `REVIEW` billing/card country mismatch.

### 7.5 Risk Levels

Use configurable thresholds. Start with:

- `LOW`: score < `0.30`
- `MEDIUM`: `0.30` to `0.65`
- `HIGH`: `0.65` to `0.85`
- `CRITICAL`: score > `0.85`

These thresholds are initial defaults and must be merchant-configurable later.

### 7.6 Rule Lifecycle

Rule statuses:

- `DRAFT`
- `SIMULATING`
- `PENDING_APPROVAL`
- `ACTIVE`
- `CANARY`
- `DISABLED`
- `ARCHIVED`

Activation flow:

```text
Draft
  -> syntax validation
  -> historical simulation
  -> impact report
  -> human approval
  -> canary
  -> active
```

Rule activation must create audit logs and preserve prior active versions for rollback.

### 7.7 Rule Simulation Metrics

Rule simulation must report:

- Transactions matched.
- Fraud transactions matched.
- Legitimate transactions matched.
- Fraud dollars captured.
- Legitimate dollars blocked.
- Precision.
- Recall contribution.
- False positive rate.
- Estimated manual review load.
- Top merchants affected.
- Example matched transactions.

Allow-rule simulations must call out unknown outcomes for payments that were historically blocked.

## 8. Security Requirements

- Never store raw PAN.
- Never store CVV.
- Mask sensitive data in logs and responses.
- Hash email, phone, and IP where feasible.
- Resolve merchant tenant context from `X-Api-Key` for V1 runtime APIs.
- Store API keys as hashed secrets with non-secret lookup prefixes before any non-local deployment.
- Require idempotency keys for mutating payment APIs.
- Bind idempotency keys to merchant, operation, and request hash.
- Add request IDs and trace IDs.
- Audit payment, fraud, review, rule, refund, chargeback, and webhook actions.
- Add cross-merchant isolation tests for every merchant-facing endpoint family.
- Keep LLMs out of the real-time authorization path.
- Do not allow GenAI agents to mutate production payment, rule, or model state.

Rate limiting strategy:

- Use Redis-backed counters in V1.
- Limit by merchant ID, API key, IP address, and endpoint family.
- Apply stricter limits to payment method creation, payment confirmation, refund, chargeback, login/auth, and rule-management endpoints.
- Use fail-closed behavior for high-risk mutation endpoints if rate-limit state is unavailable and abuse risk is high.
- Emit metrics for allowed, throttled, and blocked requests.

## 9. Feature Engineering

### 9.1 Real-Time Features

Payment features:

- amount
- currency
- capture method
- payment method age
- card BIN country
- card funding type
- billing country
- shipping country
- CVC result
- AVS result
- 3DS status/result

Customer features:

- account age
- prior successful payments
- prior declines
- prior reviews
- prior refunds
- prior chargebacks

Velocity features:

- customer attempts in 5 minutes, 1 hour, 24 hours
- card fingerprint attempts in 5 minutes, 1 hour, 24 hours
- IP attempts in 5 minutes, 1 hour, 24 hours
- device attempts in 5 minutes, 1 hour, 24 hours
- merchant attempts in 5 minutes, 1 hour, 24 hours

Behavioral features:

- amount deviation from customer historical median
- number of distinct cards per customer
- number of distinct customers per card fingerprint
- country mismatch flags
- related transaction count

Graph features, added later:

- fraud-labeled neighbors within one hop
- fraud-labeled neighbors within two hops
- shared device count
- shared IP count
- connected component risk score

### 9.2 Feature Store Strategy

V1:

- Compute features inside `payments-api`.
- Store every feature snapshot as JSONB in PostgreSQL.
- Use Redis for velocity counters.
- Version the feature schema as `features_v1`.
- Add a simple feature store abstraction before ML training:
  - `FeatureDefinition` for name, entity type, value type, owner, version, and description.
  - `FeatureValue` for entity type, entity ID, feature name, value, feature timestamp, computed timestamp, and feature version.
  - `FeatureSnapshot` remains the immutable decision-time record linked to FraudDecision.

Later:

- Generate offline features from events.
- Create training dataset snapshots.
- Add online/offline feature parity checks.
- Consider Feast or a custom feature store only when complexity justifies it.

Velocity counters must exclude the current payment being evaluated.

Point-in-time correctness rule:

- Training joins must use feature values whose feature timestamp is less than or equal to the PaymentAttempt event time.
- Training must not recompute historical rows by reading current customer/payment state.

## 10. ML Plan

### 10.1 Dataset Rules

Training data uses one row per PaymentAttempt.

Required fields:

- payment attempt ID
- event time
- label time
- fraud/legitimate/unknown label
- feature version
- merchant ID
- model-eligible feature values

Avoid leakage:

- Use only features known at decision time.
- Do not random split time-dependent data.
- Split by time.
- Do not train on future chargeback-derived fields as real-time features.
- Track unknown outcomes for payments that were blocked, abandoned, or stepped up but never completed.

### 10.2 Data-First Model Strategy

Do not prescribe advanced model architecture before data exists.

Required sequence:

1. Generate realistic synthetic payment/fraud scenarios.
2. Run exploratory data analysis.
3. Validate labels, class imbalance, leakage risks, and segment distributions.
4. Train a simple logistic regression baseline.
5. Perform error analysis on false positives, false negatives, and unknown outcomes.
6. Add XGBoost or LightGBM only if baseline/error analysis justifies it.
7. Add calibration only after model scores show useful rank ordering.

Conditional later work:

- Graph-derived features only if EDA shows relationship-based fraud patterns.
- High-cardinality embeddings only if categorical cardinality and volume justify them.
- GNN experiments only if graph data is mature and simpler graph features are insufficient.

Advanced ML is optional and evidence-driven, not a predetermined milestone.

### 10.3 Metrics

Primary metrics:

- PR-AUC.
- Recall at fixed false positive rate.
- Precision at manual review capacity.
- Dollar-weighted fraud capture.
- False positive dollar impact.

Secondary metrics:

- ROC-AUC.
- Calibration.
- Feature drift.
- Score distribution drift.
- Segment metrics by merchant, country, BIN, funding type, and amount band.

Model promotion requires segment-level checks. A model that improves global metrics but harms a merchant segment must not be promoted without explicit approval.

### 10.4 Offline Evaluation And Replay

Add a `ReplayEngine` before serious rule/model changes.

Inputs:

- historical PaymentAttempts
- persisted FeatureSnapshots
- labels from reviews and chargebacks
- candidate ruleset or model version

Rules:

- replay must not re-fetch real-time Redis counters
- replay must not recompute features from current state
- replay must score exactly what was known at historical decision time
- replay output must separate fraud, legitimate, and unknown outcomes

Replay outputs:

- action distribution
- fraud captured
- false positives
- legitimate dollars blocked
- manual review load
- 3DS request rate
- merchant/segment impact
- example matched transactions

### 10.5 Threshold Tuning And Calibration

Model score calibration and decision thresholds are product controls, not just ML details.

Threshold tuning must consider:

- fraud capture rate
- false positive rate
- false positive dollar impact
- manual review team capacity
- 3DS friction rate
- merchant risk tolerance
- customer lifetime value, later

Initial threshold policy:

- start with global defaults
- evaluate by merchant segment
- expose merchant-specific policy later
- use calibration curves before treating model scores as probabilities
 
Decision threshold changes must be backtested through ReplayEngine before activation.

### 10.6 Model Serving Contract

Endpoint:

```text
POST /score
```

Request:

```json
{
  "paymentAttemptId": "pa_123",
  "featureVersion": "features_v1",
  "features": {
    "amount": 12999,
    "customer_age_days": 14,
    "ip_velocity_1h": 4
  }
}
```

Response:

```json
{
  "score": 0.82,
  "riskLevel": "HIGH",
  "modelVersion": "xgb_2026_06_15_001",
  "reasonCodes": ["HIGH_IP_VELOCITY", "NEW_CUSTOMER_HIGH_AMOUNT"],
  "topFactors": [
    { "name": "ip_velocity_1h", "impact": 0.18 },
    { "name": "customer_age_days", "impact": 0.14 }
  ]
}
```

Timeout behavior:

- Model scoring has a strict timeout.
- Timeout falls back to rules-only decisioning.
- The decision must record `MODEL_TIMEOUT`.

## 11. Graph Intelligence

### 11.1 Graph Entities

Nodes:

- Customer
- Device
- IP
- CardFingerprint
- EmailHash
- PhoneHash
- AddressHash
- Merchant
- PaymentAttempt
- Chargeback
- FraudCase

Relationships:

- Customer `USED` Device
- Customer `USED` CardFingerprint
- Customer `USED` IP
- PaymentAttempt `FOR` Merchant
- PaymentAttempt `BY` Customer
- PaymentAttempt `WITH` CardFingerprint
- PaymentAttempt `FROM` IP
- Chargeback `AGAINST` PaymentAttempt
- FraudCase `LABELS` Customer or PaymentAttempt

### 11.2 First Graph Queries

Implement:

- Customers sharing a device with fraud-labeled customers.
- Cards used by multiple customers in a short time window.
- IPs with high distinct customer count and high decline rate.
- Two-hop fraud proximity from customer to known fraud cluster.
- Merchant-level cluster with high chargeback density.

Graph output must include risk contribution, neighbor counts, path explanations, example linked entities, and query latency. Do not return unexplained graph scores.

## 12. Dashboard Plan

Dashboard pages:

- Overview dashboard.
- Transaction search.
- Transaction detail.
- Manual review queue.
- Review case detail.
- Rules management.
- Rule simulation.
- Chargeback analytics.
- Model performance.
- Graph investigation.
- Agent intelligence reports.

Transaction detail must show:

- Payment lifecycle.
- Fraud score and risk level.
- Rules fired.
- Feature snapshot.
- Model version and top factors.
- Graph signals when available.
- Related transactions.
- Event timeline.
- Audit trail.

The UI should feel like an operational fraud console: dense tables, filters, timelines, and analyst actions. Do not design it like a marketing landing page.

## 13. GenAI Fraud Intelligence

### 13.1 Agent Roles

Agents may:

- Investigate.
- Summarize.
- Explain.
- Recommend.
- Generate candidate rules.
- Run simulations through tools.
- Produce analyst reports.

Agents may not:

- Approve or decline live payments.
- Capture or refund payments.
- Activate rules.
- Promote models.
- Query raw sensitive data unless masked.

### 13.2 Agents

Implement later:

- `FraudIntelligenceOrchestrator`
- `FraudPatternDetectionAgent`
- `ModelEvaluatorAgent`
- `RuleRecommendationAgent`
- `FraudExplanationAgent`

### 13.3 Agent Tool Contracts

Tools:

- `search_transactions(filters)`
- `get_transaction_detail(paymentAttemptId)`
- `get_rule_performance(ruleId, timeWindow)`
- `simulate_rule(candidateRule, timeWindow)`
- `get_model_metrics(modelVersion, timeWindow)`
- `get_false_positive_examples(filters)`
- `get_false_negative_examples(filters)`
- `get_graph_neighbors(entityId, depth)`
- `create_rule_recommendation(candidateRule, simulationId)`

Every tool call must log:

- agent name
- user or scheduler
- input parameters
- output hash or summary
- duration
- data sensitivity class

Agent outputs that recommend operational changes must be structured JSON and routed to human approval.

## 14. Observability

### 14.1 Technical Metrics

Track:

- API latency p50/p95/p99.
- Error rate.
- Database latency.
- Redis latency.
- Kafka publish/consume lag.
- Model service latency.
- Rules engine latency.
- Processor simulator latency.
- Webhook delivery success/failure.

### 14.2 Fraud And Payment Metrics

Track:

- Payment volume.
- Authorization rate.
- Capture rate.
- Refund rate.
- Chargeback rate.
- Fraud decision action distribution.
- 3DS request rate.
- Manual review rate.
- Rule hit rate.
- Rule precision.
- Model score distribution.
- Fraud capture rate.
- False positive rate.

### 14.3 Trace Requirement

One trace should connect:

```text
payment confirmation
  -> idempotency validation
  -> feature extraction
  -> velocity lookup
  -> model score, when available
  -> rule evaluation
  -> fraud decision
  -> processor authorization
  -> ledger writes, when applicable
  -> event publishing
  -> webhook delivery creation
```

## 15. Event And Webhook Requirements

### 15.1 Domain Events

Publish these events through an outbox first, then Kafka:

- `payment_intent.created`
- `payment_intent.canceled`
- `payment_attempt.created`
- `fraud_decision.created`
- `authorization.created`
- `capture.created`
- `refund.created`
- `chargeback.created`
- `review.opened`
- `review.closed`
- `webhook.delivery_succeeded`
- `webhook.delivery_failed`

### 15.2 Webhook Delivery

Webhook delivery must support:

- Endpoint registration.
- Enabled-event filtering.
- Signing-secret simulation.
- Attempt records.
- Retry metadata.
- Success/failure status.

## 16. Synthetic Data And Scenario Generation

Synthetic data is critical path, not an ML afterthought.

Purpose:

- validate payment lifecycle flows
- validate rules engine behavior
- create repeatable fraud scenarios
- create labels for ML pipeline development
- test dashboard investigation workflows
- test graph fraud ring queries later

V1 generator must create:

- merchants with different risk tiers
- customers with varied account ages
- simulated cards with deterministic processor outcomes
- payment intents and attempts
- successful authorizations
- issuer declines
- CVC/AVS failures
- 3DS-required flows
- refunds
- chargebacks
- manual review outcomes
- basic card-testing velocity patterns
- country mismatch patterns

Later generator versions add:

- fraud rings sharing devices/IPs/cards
- merchant-specific fraud rates
- delayed chargeback labels
- friendly fraud patterns
- false positive examples
- seasonality and drift

Synthetic datasets must be deterministic when seeded.

## 17. Implementation Roadmap

### Phase 0: Foundation

Build:

- Monorepo structure.
- `payments-api` Spring Boot app.
- PostgreSQL and Redis via Docker Compose.
- Flyway.
- Validation and error model.
- Request ID filter.
- Actuator.
- ADRs for simulator-first, no LLM in authorization, modular monolith, card-only V1, double-entry ledger.
- ADR for PCI DSS scope position and tokenized-simulator boundary.
- ADR for deployment target default: local Docker Compose first, AWS/EKS reference later.

Done when:

- App starts locally.
- Health checks pass.
- Migrations run.
- README explains startup.

### Phase 1: Payment Core

Build:

- Merchant creation.
- Customer creation.
- Simulated card payment method creation.
- PaymentIntent creation.
- PaymentAttempt creation on confirm.
- Idempotency table and replay behavior.
- Basic state transitions.
- Synthetic data generator v1 for merchants, customers, cards, payment intents, and simple fraud patterns.

Done when:

- Create and confirm flow works.
- Repeated idempotent requests return original response.
- Same key with different body is rejected.
- Invalid status transitions fail.
- Synthetic data can generate repeatable happy-path, decline, and basic fraud scenarios.

### Phase 2: Processor, Capture, Refund, Ledger, Webhooks

Build:

- `ProcessorAdapter`.
- Deterministic processor simulator.
- Authorization records.
- Capture endpoint.
- Cancel/void endpoint.
- Refund endpoint.
- Ledger entries and invariant enforcement.
- Webhook endpoint and delivery records.

Done when:

- Authorized payment can be captured.
- Captured payment can be refunded.
- Canceled authorization cannot be captured.
- Double capture and over-refund are rejected.
- Ledger transaction groups balance.
- Webhook delivery records are created.

### Phase 3: Fraud Decision MVP

Build:

- FraudDecision table.
- FeatureSnapshot table.
- FeatureDefinition and FeatureValue tables for V1 feature store abstraction.
- RuleExecutionResult table.
- Decision engine interface.
- Initial deterministic rules.
- `ALLOW`, `BLOCK`, `REQUEST_3DS`, `REVIEW` behavior in confirm.

Done when:

- Every PaymentAttempt has a FraudDecision.
- FeatureSnapshot records immutable decision-time values.
- FeatureValue supports point-in-time offline joins.
- `BLOCK` prevents authorization.
- `REQUEST_3DS` moves intent to `REQUIRES_ACTION`.
- `REVIEW` creates a ManualReviewCase.
- `ALLOW` proceeds to processor authorization.

### Phase 4: Features, Velocity, Lists, Reviews, Chargebacks

Build:

- Real-time feature extractor.
- Redis velocity counters.
- Fraud lists for card fingerprint, BIN, country, IP, customer, email, email domain, generic string.
- Manual review lifecycle.
- Chargeback ingestion.
- Fraud labels from review and chargeback outcomes.
- Rate limiting policy using Redis token bucket or fixed-window counters for merchant/API-key/IP dimensions.

Done when:

- Velocity counters affect rules.
- Velocity excludes the current payment being evaluated.
- Review approve/cancel/refund/refund-and-report-fraud works.
- Chargeback creates FraudLabel.
- Rate limits can throttle abusive confirm/payment-method creation traffic.

### Phase 5: Events And Analytics

Build:

- Outbox pattern.
- Kafka publisher.
- Consumer skeleton.
- PostgreSQL analytics projections.
- Replay command.
- ReplayEngine for offline rules/model evaluation against historical PaymentAttempts and FeatureSnapshots.

Done when:

- Events publish after DB commit.
- Consumers can restart safely.
- Replay rebuilds projections.
- Candidate rule/model changes can be evaluated without re-fetching real-time data.

### Phase 6: Dashboard MVP

Build:

- Overview dashboard.
- Transaction search.
- Transaction detail.
- Review queue.
- Rule list/editor.
- Chargeback list.

Done when:

- Analyst can inspect a payment lifecycle.
- Analyst can see fraud reasons, rules fired, and feature snapshot.
- Analyst can resolve a review.

### Phase 7: ML Baseline

Build:

- Synthetic data generator v2 with richer fraud patterns and labels.
- Exploratory data analysis report.
- Data quality report for labels, imbalance, missingness, leakage risks, and segment coverage.
- Training dataset builder.
- Time-based train/validation/test split.
- Calibration curve report.
- Decision threshold tuning report.
- Logistic regression baseline.
- XGBoost or LightGBM baseline only if EDA and logistic regression error analysis justify it.
- MLflow tracking.
- FastAPI model serving.
- Model timeout fallback.

Done when:

- Model score is stored with version and reasons.
- Rules-only fallback works on model timeout.
- Evaluation avoids future leakage.

### Phase 8: Analytics, Simulation, Graph

Build:

- ClickHouse analytics.
- Rule simulation and approval workflow.
- Neo4j entity graph.
- Graph-derived investigation features.

Done when:

- Rule simulation reports fraud captured, false positives, and business impact.
- Rule activation requires approval.
- Synthetic fraud rings are visible in graph investigation.

### Phase 9: Agents, Hardening, Scale

Build:

- Offline fraud intelligence agents.
- Tool audit logs.
- Model evaluator agent.
- Rule recommendation agent.
- Fraud explanation agent.
- RBAC.
- OpenTelemetry, Prometheus, Grafana.
- Load testing.
- CI/CD.
- Blue-green deployment and rollback playbooks.

Done when:

- Agents cannot mutate live payment/rule/model state directly.
- Tool calls are audited.
- p95/p99 latency is measured.
- Blue and green versions can coexist.

## 18. Staff-Level Implementation Review Workflow

Every implementation day must run through this workflow before code changes,
after implementation, and when external reviews arrive. The goal is to catch
payment, fraud, security, ML-lineage, and operations issues before they become
schema or API churn.

### 18.1 Day Readiness Review

Before coding a day, write a short readiness review in the daily note or working
thread:

- What the master plan says for the day.
- What already exists in code and schema.
- What production-shaped payments, fraud, security, ML, and operations systems
  would need from this slice.
- What to implement now.
- What to explicitly defer, with the target day or phase.
- What tests will prove the slice.

The day should not start from the deliverable list alone. The deliverable list
is necessary but not sufficient; it must be filtered through the review lenses
below.

### 18.2 Mandatory Review Lenses

Apply these lenses before and after implementation:

- **Payments lifecycle lens:** Does this create or change a stable payment
  primitive such as authorization, capture method, attempt, refund, void,
  dispute, ledger entry, or webhook delivery?
- **Security lens:** Are any secrets, API keys, webhook signing secrets, raw PAN,
  CVV, tokens, PII, or sensitive operational values stored raw, returned
  unnecessarily, logged, or indexed unsafely?
- **PCI/data-minimization lens:** Is persisted card data limited to safe
  simulator metadata such as token, fingerprint, BIN, last4, expiry metadata,
  country, funding type, and verification outcomes?
- **Tenant isolation lens:** Does every merchant-owned row carry `merchant_id`,
  and are cross-object relationships protected by tenant-scoped lookups or
  composite foreign keys?
- **Fraud/ML lineage lens:** Does a fraud, feature, label, model, graph, or rule
  field have a real lineage source such as `PaymentAttempt`, `FeatureSnapshot`,
  review outcome, chargeback, or model version? If not, defer it.
- **Operations/SRE lens:** Can persisted operational records support retries,
  debugging, audit, status inspection, and future metrics without immediate
  churn?
- **Migration lens:** Is the schema change tested through Flyway and Hibernate
  validation, and is there a PostgreSQL-backed test path for PostgreSQL-specific
  behavior?
- **API contract lens:** Does the response expose only what clients need, and do
  errors preserve the standard error/request-id contract?

### 18.3 Review Disposition Rules

Every review item must be classified before implementation:

- **Accept now:** The item fixes a current security, tenant, data-minimization,
  operational, or stable payment-domain gap.
- **Accept later:** The item is correct but depends on a later domain object,
  behavior, or test harness. Assign the target day or phase.
- **Reject:** The item conflicts with the master plan, creates speculative
  schema, weakens tenant/security guarantees, or violates the simulator scope.
- **Needs design:** The item is plausible but needs an ADR, architecture update,
  or first-principles concept note before code.

Do not implement review feedback merely because it sounds production-grade.
Production-shaped architecture also means sequencing fields when their lineage,
constraints, and behavior can be tested.

### 18.4 Staff Checklist

Before marking a day complete, answer these questions:

- Are any secrets stored raw?
- Is any payment-sensitive or cardholder data persisted beyond the V1
  tokenized-simulator boundary?
- Are merchant tenant boundaries enforced in the database, not only controller
  code?
- Are operational records useful enough for retry, debugging, audit, and future
  metrics?
- Are payment lifecycle primitives complete enough for the next two to three
  implementation days?
- Are fraud/ML fields backed by real lineage, constraints, and behavior tests?
- Are H2-only assumptions covered by a PostgreSQL-backed path when the SQL is
  PostgreSQL-relevant?
- Did the implementation update docs, learning notes, and tests together?

### 18.5 Post-Implementation Self-Review

After coding and before finalizing:

- Re-run the mandatory review lenses against the actual diff.
- Compare accepted and deferred review items against the implementation.
- Confirm tests cover the riskiest invariants, not only happy paths.
- Record surprises and explicit deferrals in the daily note.

### 18.6 Staff Engineer Apprenticeship Output

THAAP is also an apprenticeship project. The current learning goal is to grow
from hands-on implementation comfort toward Senior Staff Engineer / ML
Architect judgment for payments security and fraud detection within six months.
The implementation should remain Staff-level, but it must be paired with
learning artifacts that make the design, code, and tradeoffs fully explainable.

Every non-trivial implementation day must include:

- **Plain-English design brief:** What we are building, why a payments/fraud
  platform needs it, what can fail in production, and what is intentionally
  deferred.
- **Implementation walkthrough:** File-by-file map, request/data flow, database
  invariants, security invariants, tests, and the Staff-level review points.
- **Learning drill:** Explain-back questions, code-reading exercises, and one
  small extension or reasoning task.
- **Staff narrative:** Interview-ready language for the tradeoff, invariant,
  risk reduction, deferral, and proof.

The standard artifact is:

```text
docs/learning/daily/YYYY-MM-DD-deep-dive.md
```

No major implementation day is complete until the learner can answer:

- What problem did we solve?
- Why did we solve it this way?
- What production risk did this reduce?
- What did we defer and why?
- What tests prove the important invariants?
- How would this be explained in a Stripe/Mastercard-style design review?

## 19. First 14-Day Execution Plan

### Day 1: Product Scope And Repo

Deliver:

- Monorepo structure.
- README.
- ADR folder.
- Architecture diagram.
- Glossary.

Learn:

- PaymentIntent lifecycle.
- Fraud decision lifecycle.

### Day 2: Local Infrastructure

Deliver:

- Docker Compose with PostgreSQL and Redis.
- Database connection from Spring Boot.
- Health endpoints.

Learn:

- Local infra setup.
- Spring profiles.

### Day 3: API Foundation And Error Contract

Deliver:

- Global exception handler.
- Standard JSON error response body.
- Validation error formatting for request-body and parameter errors.
- Request ID filter using `X-Request-Id`, generating one when missing.
- Request ID propagation in response headers.
- Request ID propagation into logging MDC.
- Consistent 400, 401, 404, 409, and 500-style API errors.
- Tenant authentication errors mapped into the standard error format.
- Tests for request ID propagation and representative error responses.
- README examples for request IDs and error response shape.

Already in place before Day 3:

- `payments-api` scaffold.
- Actuator health, liveness, and readiness.
- Local/test Spring profiles.
- Initial validation annotations on tenant-scoped endpoints.
- Merchant tenant context from `X-Api-Key`.

Learn:

- Spring Boot 4 conventions.
- API error handling.
- Servlet filters and request correlation.
- Production-shaped validation contracts.

### Day 4: Database Migrations

Deliver:

- Flyway setup.
- Tenant-scoped merchant, customer, payment method, payment intent, idempotency, webhook, fraud decision, rule, and review tables.
- Hashed merchant API key storage with one-time raw secret reveal.
- Safe card metadata columns for tokenized simulator storage.
- PaymentIntent capture method storage.
- Webhook endpoint signing-secret hash storage and delivery retry/attempt metadata.
- Unique constraints and indexes for `(merchant_id, key)` idempotency and hot merchant-scoped lookups.
- Remove reliance on Hibernate `ddl-auto: update` for local durable schema evolution.
- PostgreSQL-backed migration test path when Docker/Testcontainers is available.

Learn:

- Migration discipline.
- Relational modeling.
- Tenant-safe indexing and constraints.
- Security and operations review before schema finalization.

### Day 5: Merchant And Customer APIs

Deliver:

- Harden the remaining local merchant admin setup.
- Add any missing merchant creation validation and response contract tests.
- Confirm API key hash/prefix behavior remains covered after endpoint refinements.
- Create customer endpoint refinement if needed after error contract and migrations.
- Create payment method endpoint refinement if needed after error contract and migrations.

Learn:

- Entity boundaries.
- Sensitive data simulation.
- API credential lifecycle.

### Day 6: PaymentIntent Create

Deliver:

- `POST /payment_intents`.
- Validation.
- Integration tests.

Learn:

- Payment state machine.

### Day 7: Review And Refactor

Deliver:

- Architecture review.
- Test cleanup.
- README update.

Learn:

- Weekly staff-level design review habit.

### Day 8: Idempotency

Deliver:

- Idempotency table.
- Idempotency middleware/service.
- Tests for replay.

Learn:

- Payment API reliability.

### Day 9: PaymentAttempt

Deliver:

- Payment attempt table.
- Attempt creation on confirm.

Learn:

- Attempt vs intent distinction.

### Day 10: Confirm Endpoint

Deliver:

- `POST /payment_intents/{id}/confirm`.
- Basic status transition.
- Stub `ALLOW` decision.

Learn:

- Transaction boundaries.

### Day 11: Processor Simulator

Deliver:

- Processor adapter interface.
- Processor simulator.
- Authorization table.

Learn:

- Adapter pattern.

### Day 12: Fraud Decision Stub

Deliver:

- FraudDecision table.
- Static decision engine.
- Reason codes.

Learn:

- Audit-first decision design.

### Day 13: End-To-End Confirm

Deliver:

- Confirm creates attempt, fraud decision, authorization, and events placeholder.
- Integration tests.

Learn:

- E2E flow thinking.

### Day 14: Demo And Notes

Deliver:

- Demo script.
- Known gaps.
- Next sprint plan.

Learn:

- Engineering communication.

## 20. Test Plan

### 20.1 Payment Lifecycle Tests

- Create, confirm, authorize, capture, cancel, refund, chargeback.
- Invalid state transitions fail.
- Manual capture and automatic capture behave correctly.
- 3DS-required payment pauses in `REQUIRES_ACTION`.

### 20.2 Idempotency Tests

- Same key and same body returns original response.
- Same key and different body is rejected.
- Capture/refund retries do not duplicate money movement.

### 20.3 Processor Tests

- Each deterministic test card triggers expected outcome.
- CVC, AVS, and 3DS outcomes persist.
- Processor events are recorded.

### 20.4 Ledger Tests

- Capture entries balance.
- Refund entries balance.
- Fee entries balance.
- Chargeback entries balance.
- Double capture and over-refund are rejected.

### 20.5 Fraud Tests

- `BLOCK` prevents authorization.
- `REVIEW` creates review case.
- `REQUEST_3DS` moves intent to `REQUIRES_ACTION`.
- Rule results and feature snapshots persist.
- Velocity counters exclude the current payment.

### 20.6 Security Tests

- Raw PAN and CVV are never persisted.
- Sensitive values are masked.
- Mutating APIs require idempotency keys.
- Missing or invalid `X-Api-Key` is rejected on merchant-facing APIs.
- Raw API keys and webhook signing secrets are never persisted; only prefixes
  and hashes are stored.
- Merchant A cannot read, create child records for, mutate, refund, replay, or inspect webhook deliveries for Merchant B's objects.
- Same idempotency key is reusable by different merchants but conflicts within the same merchant when the request hash differs.
- Fraud, rule, review, ledger, analytics, feature, event, graph, and ML query surfaces return only current-tenant data unless explicitly admin-scoped.
- Audit logs exist for sensitive operations.

## 21. Non-Negotiable Engineering Rules

1. No LLM calls in real-time authorization.
2. No payment decision without persisted audit trail.
3. No PaymentAttempt without a FraudDecision once fraud MVP is active.
4. No model prediction without model version.
5. No feature snapshot with future information.
6. No raw PAN or CVV persistence.
7. No ledger write unless debits equal credits.
8. No mutating payment API without idempotency.
9. No agent tool without audit logging.
10. No dashboard metric without source definition.
11. No rule activation without validation, simulation, and approval.
12. No breaking API, event, rule, or database schema change without expand/contract migration plan.
13. No merchant-facing data access without tenant context and tenant-scoped lookup.
14. No event, feature, training row, graph node, model score, or analytics row without `merchant_id` or an explicit documented global/anonymized scope.
15. No non-trivial platform concept, feature, or dependency without a first-principles learning note under `docs/learning/concepts/`.
16. No deployment without rollback criteria once CI/CD begins.
17. No implementation day is complete without the staff-level readiness and
    post-implementation review workflow in Section 18.
18. No non-trivial implementation day is complete without a deep-dive learning
    artifact that makes the Staff-level code understandable end to end.

## 22. Coverage And Traceability

This master plan is shorter than the research plan because it removes:

- source usefulness reviews
- repeated rationale
- exploratory notes
- article summaries
- duplicated phase descriptions
- research prose that is useful for learning but not a direct implementation decision

Nothing implementation-critical should live only in the research documents. Coverage mapping:

- Product mission and scope: sections 1-2.
- PCI DSS scope and deployment target defaults: section 2.
- Architecture and deployables: section 3.
- Public APIs: section 4.
- Domain model: section 5.
- Processor simulator: section 6.
- Fraud decisioning, rules, lifecycle, simulation: section 7.
- Security and rate limiting: section 8.
- Feature engineering: section 9.
- ML plan: section 10.
- Graph intelligence: section 11.
- Dashboard: section 12.
- GenAI agents: section 13.
- Observability: section 14.
- Events and webhooks: section 15.
- Synthetic data: section 16.
- Roadmap: section 17.
- Staff-level implementation review workflow: section 18.
- First sprint: section 19.
- Tests: section 20.
- Engineering rules: section 21.

## 23. Reference Documents

Use these for background and deeper reasoning:

- `docs/references/research/RESEARCHED_END_TO_END_FRAUD_PLATFORM_PLAN.md`
- `docs/references/research/ORIGINAL_180_DAY_IMPLEMENTATION_PLAN.md`
- `docs/references/research/STRIPE_RADAR_FRAUD_PLATFORM_PROMPT.md`
- `docs/references/source-materials/mastercard_advanced_ai_technology_datasheet.pdf`
- `docs/references/source-materials/aws_genai_fraud_detection_reference_architecture.jpg`
- `docs/references/source-materials/useful_links.txt`

This master plan is authoritative for implementation.
