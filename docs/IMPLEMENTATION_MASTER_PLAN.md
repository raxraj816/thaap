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

### 1.1 Challenger Product Bar

THAAP's long-term ambition is to become a credible challenger to products in
the Stripe Radar and Mastercard Brighterion class. That means the platform must
not stop at a checkout simulator, a rules demo, or a notebook model. It must
grow into a full payment-risk operating system.

The challenger standard:

- **Payment-native fraud:** Fraud decisions are embedded in real payment
  authorization, capture, refund, dispute, webhook, idempotency, and ledger
  flows.
- **Real-time decision quality:** The authorization path is deterministic,
  bounded by explicit latency budgets, observable, resilient to model timeouts,
  and safe under retries.
- **Network-intelligence path:** V1 starts with merchant-scoped signals and
  synthetic multi-merchant scenarios. Later phases must add privacy-safe global
  and cross-merchant aggregate features, segment-level evaluation, and graph
  intelligence so THAAP is not limited to single-merchant rules.
- **Explainable controls:** Every decision exposes risk score, risk level,
  rules fired, reason codes, feature snapshot, model version, related
  transactions, and safe audit timeline.
- **Analyst operations:** Manual review, case management, search, timelines,
  rule governance, chargeback workflows, and business insights are core product
  surfaces, not admin afterthoughts.
- **Post-authorization protection:** Fraud work continues after authorization
  through early-warning simulation, Smart Refund-style recommendations,
  friendly-fraud/dispute workflows, and feedback into rules and models.
- **Rule simulation and governance:** Rule changes require validation,
  historical simulation, impact reports, approval, canary/rollback strategy,
  and audit logs.
- **ML feedback loop:** Models require point-in-time datasets, delayed-label
  handling, unknown-outcome tracking, calibration, drift monitoring, model
  registry metadata, segment impact checks, and timeout-safe serving.
- **Graph and pattern intelligence:** Graph is used for investigation,
  explainable risk contributions, fraud-ring discovery, and later features,
  not as an opaque score.
- **Adaptive intelligence with human authority:** GenAI agents investigate,
  explain, simulate, and recommend. Humans approve operational changes.
- **Security and trust:** Tenant isolation, PCI-style data minimization,
  secret hygiene, auditability, RBAC, rate limiting, and rollback playbooks are
  product requirements, not compliance theater.
- **Merchant risk is part of fraud:** The platform must model risky merchants,
  not only risky buyers. Underwriting, merchant risk tiers, velocity limits,
  dispute thresholds, reserve/hold simulation, and merchant monitoring are part
  of the product path.
- **Card-testing and enumeration defense:** Card setup abuse, low-value payment
  testing, credential/key leakage, retry storms, bot friction, and network
  enumeration metrics are first-class threats with incident workflows.
- **Dispute evidence and friendly fraud:** Chargebacks are not just delayed ML
  labels. The product must support evidence packets, reason-code workflows,
  pre-dispute signals, friendly-fraud classification, and dispute performance
  reporting.
- **Identity and session intelligence:** Real payment risk depends on checkout
  context: device, session, IP, user agent, email, phone, address, shipping,
  account age, login signals, and bot/friction signals. These must have safe
  data handling and clear lineage.
- **Developer product quality:** A credible payments product needs OpenAPI,
  API versioning, webhook signing docs, test-card catalogs, idempotency examples,
  SDK/demo scripts, and an error catalog.

Strategic honesty:

- Stripe and Mastercard have massive production network data advantages. THAAP
  cannot honestly claim parity with their data network early.
- THAAP's challenger path is to build better transparency, stronger learning
  discipline, richer simulator/control surfaces, and eventually privacy-safe
  network intelligence.
- No feature should be called challenger-grade until it has durable state,
  tests, explainability, operational metrics, and a demo path through the
  payment lifecycle.

### 1.2 Real-World Product Gaps To Close

The deep-research review identified the following gaps as product-critical.
They are promoted into this master plan so they are not treated as optional
polish:

- Merchant risk and underwriting.
- Card-testing and enumeration incident management.
- Risk context, device/session intelligence, and bot/friction signals.
- Fraud taxonomy beyond binary fraud labels.
- Dispute representment, evidence, and friendly-fraud workflows.
- Merchant risk policy and threshold configuration.
- Privacy-safe network intelligence and cross-merchant aggregate features.
- Model governance, including dataset cards, model cards, shadow mode,
  champion/challenger rollout, and rollback.
- Analyst workflow depth: queues, assignment, SLA, saved searches, bulk actions,
  and investigation timelines.
- Developer-facing quality: API contracts, versioning, examples, and sandbox
  documentation.

These gaps do not change the V1 simulator boundary. They change what
"production-shaped" means as the platform matures.

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
- Merchant risk profile and local risk-tier configuration.
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
- Decision-time risk context capture.
- Deterministic rules.
- Manual review.
- Fraud taxonomy and fraud-label source tracking.
- Card-testing and enumeration simulation.
- Dispute evidence MVP.
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

### 2.1.1 Fraud Taxonomy

Binary `fraud` / `legitimate` / `unknown` labels are necessary but not enough
for real-world learning. Labels and simulated scenarios should also support a
fraud type when known.

Initial fraud types:

- `STOLEN_CARD`
- `CARD_TESTING`
- `ACCOUNT_TAKEOVER`
- `SYNTHETIC_IDENTITY`
- `FRIENDLY_FRAUD`
- `REFUND_ABUSE`
- `PROMO_ABUSE`
- `RESELLER_ABUSE`
- `TRIANGULATION`
- `MERCHANT_FRAUD`
- `SCAM_OR_SOCIAL_ENGINEERING`
- `UNKNOWN_FRAUD`

Rules:

- Fraud type is optional because many labels start ambiguous.
- Fraud type must record its source: review, chargeback, post-authorization
  signal, analyst update, synthetic generator, or agent recommendation.
- ML evaluation must report both binary fraud metrics and fraud-type coverage
  once enough labeled examples exist.

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

`MerchantRiskProfile`

- `id`
- `merchantId`
- `riskTier`
- `merchantCategory`
- `country`
- `businessModel`
- `websiteUrlHash`
- `monthlyVolumeLimit`
- `singleTransactionLimit`
- `dailyAuthLimit`
- `dailyCardTestingLimit`
- `reserveStrategy`
- `reviewCapacityPerDay`
- `status`
- `createdAt`
- `updatedAt`

Merchant risk is not a V1 underwriting engine. It is a product-shaped control
surface for tenant-specific limits, simulator scenarios, risk policies, and
future merchant monitoring.

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

`OutboxEvent`

- `id`
- `merchantId`
- `aggregateType`
- `aggregateId`
- `eventType`
- `eventVersion`
- `payloadJson`
- `status`
- `retryCount`
- `lastError`
- `createdAt`
- `publishedAt`

Outbox events are written in the same transaction as the domain state change.
Before Kafka exists, they may feed local webhook delivery records and analytics
projections. After Kafka exists, they become the durable publish/retry boundary.

`AuditLog`

- `id`
- `merchantId`
- `actorType`
- `actorId`
- `action`
- `aggregateType`
- `aggregateId`
- `requestId`
- `traceId`
- `reason`
- `beforeJson`
- `afterJson`
- `createdAt`

Audit logs are required for high-risk payment, fraud, rule, review, webhook,
model, and future agent-tool actions. Sensitive values must be masked or omitted
from audit payloads.

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

`RiskContext`

- `id`
- `merchantId`
- `paymentAttemptId`
- `ipHash`
- `ipCountry`
- `userAgentHash`
- `deviceIdHash`
- `sessionIdHash`
- `emailHash`
- `phoneHash`
- `billingAddressHash`
- `shippingAddressHash`
- `shippingCountry`
- `checkoutChannel`
- `customerAccountAgeDays`
- `loginAgeMinutes`
- `botScore`
- `captchaOutcome`
- `createdAt`

RiskContext stores decision-time identity, device, session, and checkout
signals. It must contain hashed or classified-safe values only. Raw IP, raw
user agent, full address, raw email, and raw phone should not be logged or
returned in public responses.

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
- `fraudType`
- `confidence`
- `labelTime`

`CardTestingIncident`

- `id`
- `merchantId`
- `status`
- `startedAt`
- `endedAt`
- `detectedBy`
- `severity`
- `attemptCount`
- `declineCount`
- `distinctCardFingerprintCount`
- `distinctBinCount`
- `distinctIpCount`
- `lowAmountAttemptCount`
- `mitigationSummary`
- `createdAt`
- `updatedAt`

Card-testing incidents group many small authorization/setup attempts into one
operational object. They support analyst triage, rate-limit tuning, bot-friction
recommendations, and network-style enumeration metrics.

`DisputeEvidencePacket`

- `id`
- `merchantId`
- `chargebackId`
- `paymentAttemptId`
- `reasonCode`
- `status`
- `evidenceJson`
- `submittedAt`
- `dueAt`
- `outcome`
- `createdAt`
- `updatedAt`

Dispute evidence is the bridge between fraud decisions and merchant outcomes.
The MVP can simulate evidence fields such as AVS/CVC result, 3DS/liability
shift, delivery confirmation, customer communications, prior undisputed
payments, refund history, and device/IP continuity.

`MerchantRiskPolicy`

- `id`
- `merchantId`
- `riskTier`
- `blockThreshold`
- `reviewThreshold`
- `requestThreeDsThreshold`
- `maxReviewQueuePerDay`
- `maxThreeDsFrictionRate`
- `cardTestingSensitivity`
- `allowRulesetVersion`
- `blockRulesetVersion`
- `status`
- `createdAt`
- `updatedAt`

Merchant risk policy turns fraud thresholds into product controls. Policy
changes must be replayed or simulated before activation once ReplayEngine
exists.

`PostAuthorizationRiskSignal`

- `id`
- `merchantId`
- `paymentAttemptId`
- `source`
- `riskLevel`
- `confidence`
- `recommendedAction`
- `status`
- `reasonCodes`
- `createdAt`

Post-authorization risk signals represent early fraud warnings, Smart
Refund-style recommendations, friendly-fraud/dispute indicators, and other
signals that arrive after the initial authorization decision. They must never
retroactively rewrite the original FraudDecision; they create new auditable
recommendations and labels.

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
- `BLOCK` card-testing pattern with many low-amount attempts.
- `REVIEW` new customer, high amount, mismatched shipping/card geography.
- `REQUEST_3DS` elevated risk above merchant policy threshold.

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
- 3DS friction impact.
- Authorization-rate impact.
- Merchant risk policy impact.
- Card-testing/enumeration reduction estimate.
- Dispute/VAMP-style fraud-and-dispute ratio impact, once dispute data exists.

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
- Treat payment-page script integrity, bot abuse, credential leakage, and
  checkout automation as security concerns even in the simulator.
- Keep device, session, IP, email, phone, and address signals hashed, masked, or
  minimized according to their use.

Rate limiting strategy:

- Use Redis-backed counters in V1.
- Limit by merchant ID, API key, IP address, and endpoint family.
- Apply stricter limits to payment method creation, payment confirmation, refund, chargeback, login/auth, and rule-management endpoints.
- Use fail-closed behavior for high-risk mutation endpoints if rate-limit state is unavailable and abuse risk is high.
- Emit metrics for allowed, throttled, and blocked requests.

Card-testing controls:

- Detect spikes in failed authorizations, blocked payments, low-amount attempts,
  card setup attempts, and distinct-card attempts from shared IP/device/session
  groups.
- Create `CardTestingIncident` rows when thresholds are exceeded.
- Support mitigations such as rate-limit tightening, CAPTCHA-required mode,
  temporary payment-method creation restrictions, API-key rotation
  recommendation, and merchant notification.
- Track enumeration-style metrics using both approved and declined attempts.

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

Risk context features:

- IP country
- device age
- session age
- login age
- user agent family
- checkout channel
- shipping/billing/card country mismatch
- email domain age bucket, simulated
- phone country mismatch
- address reuse count
- CAPTCHA outcome
- bot score
- 3DS liability-shift expectation

Merchant risk features:

- merchant risk tier
- merchant category
- merchant authorization rate
- merchant decline rate
- merchant chargeback rate
- merchant fraud/dispute ratio
- merchant enumeration ratio
- merchant review backlog

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
- fraud type, when known
- label source
- feature version
- merchant ID
- merchant risk tier
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

### 10.2.1 Model Governance

Every production-shaped model version must have:

- dataset version
- feature version
- training time window
- label definition
- label delay policy
- unknown-outcome handling
- model card
- dataset card
- approval record
- rollback target
- owner

Promotion flow:

```text
candidate model
  -> offline evaluation
  -> segment regression report
  -> replay
  -> shadow mode
  -> limited canary
  -> approval
  -> active
```

No model should move from notebook or experiment tracking into real-time serving
without explicit champion/challenger comparison and a rollback path.

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
- Fraud-type coverage.
- Merchant-risk-tier performance.
- 3DS friction rate.
- Review queue pressure.
- Dispute ratio and false-positive cost.

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
- fraud-type impact
- merchant risk policy impact
- review-capacity breach count
- dispute and chargeback impact estimate

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
- MerchantRiskProfile
- Session
- AddressHash
- EmailDomain
- CardTestingIncident

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
- Session `USED` Device
- Customer `USED` AddressHash
- Customer `HAS` EmailDomain
- CardTestingIncident `INVOLVES` PaymentAttempt
- Merchant `HAS_RISK_PROFILE` MerchantRiskProfile

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
- Risk policy management.
- Merchant risk profile.
- Card-testing incidents.
- Manual review queue.
- Review case detail.
- Rules management.
- Rule simulation.
- Chargeback analytics.
- Dispute evidence packets.
- Dispute and post-authorization risk signals.
- Business insights and KPI reporting.
- Model performance.
- Model governance.
- Graph investigation.
- Developer/API console.
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
- Post-authorization risk signals and recommended actions.

The UI should feel like an operational fraud console: dense tables, filters, timelines, and analyst actions. Do not design it like a marketing landing page.

Manual review and case-management surfaces must support assignment, queue
filters, SLA age, timeline, reviewer actions, refund recommendations, and safe
escalation paths. Business insight surfaces must connect fraud prevention to
authorization rate, review load, refund rate, chargeback rate, false-positive
cost, and dollar-weighted fraud capture.

Developer/API surfaces must include API version visibility, request IDs,
idempotency examples, webhook signing verification, event delivery inspection,
test-card catalog, simulator scenario catalog, and safe copy-paste examples.

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
- Card-testing incident count.
- Enumeration ratio.
- Fraud/dispute ratio.
- Merchant risk tier distribution.
- Review SLA breach count.
- Dispute evidence win/loss rate.

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
- `dispute_evidence_packet.created`
- `dispute_evidence_packet.submitted`
- `review.opened`
- `review.closed`
- `card_testing_incident.created`
- `card_testing_incident.resolved`
- `merchant_risk_policy.updated`
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
- merchant risk profiles and risk policies
- customers with varied account ages
- risk contexts with device, session, IP, email, phone, and address signals
- simulated cards with deterministic processor outcomes
- payment intents and attempts
- successful authorizations
- issuer declines
- CVC/AVS failures
- 3DS-required flows
- refunds
- chargebacks
- dispute evidence packets
- manual review outcomes
- basic card-testing velocity patterns
- card-testing incidents
- country mismatch patterns
- fraud labels with fraud types where known

Later generator versions add:

- fraud rings sharing devices/IPs/cards
- merchant-specific fraud rates
- delayed chargeback labels
- friendly fraud patterns
- refund abuse, promo abuse, reseller abuse, synthetic identity, account
  takeover, triangulation, merchant fraud, and scam/social-engineering patterns
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
- AuditLog MVP for capture, cancel/void, refund, ledger, and webhook endpoint
  actions.

Done when:

- Authorized payment can be captured.
- Captured payment can be refunded.
- Canceled authorization cannot be captured.
- Double capture and over-refund are rejected.
- Ledger transaction groups balance.
- Webhook delivery records are created.
- High-risk money movement and webhook configuration actions create audit logs
  with safe payloads.

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
- RiskContext capture for device, session, IP, checkout, and bot/friction
  signals.
- Redis velocity counters.
- Fraud lists for card fingerprint, BIN, country, IP, customer, email, email domain, generic string.
- MerchantRiskProfile and MerchantRiskPolicy MVP.
- Manual review lifecycle.
- Chargeback ingestion.
- DisputeEvidencePacket MVP.
- Fraud labels from review and chargeback outcomes.
- Post-authorization risk signal ingestion for simulated early fraud warnings,
  refund recommendations, friendly-fraud indicators, and dispute-prevention
  signals.
- CardTestingIncident detection and resolution workflow.
- Rate limiting policy using Redis token bucket or fixed-window counters for merchant/API-key/IP dimensions.

Done when:

- RiskContext is persisted and included in FeatureSnapshot without leaking raw
  sensitive values.
- Velocity counters affect rules.
- Velocity excludes the current payment being evaluated.
- Merchant risk policy can tune block, review, and 3DS thresholds.
- Review approve/cancel/refund/refund-and-report-fraud works.
- Chargeback creates FraudLabel.
- Dispute evidence packets can be created and linked to chargebacks.
- Post-authorization risk signals create auditable recommendations without
  rewriting the original FraudDecision.
- Card-testing incidents group abusive attempts and recommend mitigations.
- Rate limits can throttle abusive confirm/payment-method creation traffic.

### Phase 4.5: Product-Grade Risk Operations

Build:

- Fraud taxonomy in labels, synthetic scenarios, analytics, and dashboard
  filters.
- Merchant risk monitoring for authorization rate, decline rate, chargeback
  rate, fraud/dispute ratio, enumeration ratio, review backlog, and sudden
  volume spikes.
- Risk policy simulation before policy activation.
- Saved analyst searches and queue filters.
- Dispute evidence workflow with simulated reason-code deadlines and outcomes.
- Developer/API console with test-card catalog, idempotency examples, webhook
  signing examples, and scenario runner.

Done when:

- A demo can show not only a risky transaction, but also a risky merchant, a
  card-testing attack, a dispute evidence packet, and a merchant-specific risk
  policy change.
- Analysts can filter by fraud type, merchant risk tier, risk context signal,
  review SLA, and dispute outcome.
- Developer documentation is good enough for a new merchant integration to run
  the simulator without reading source code.

### Phase 5: Events And Analytics

Build:

- OutboxEvent table and writer integrated into state-changing payment, fraud,
  review, refund, chargeback, rule, and webhook actions.
- AuditLog table and audit writer for high-risk state transitions and
  operational actions.
- Kafka publisher.
- Consumer skeleton.
- PostgreSQL analytics projections.
- Replay command.
- ReplayEngine for offline rules/model evaluation against historical PaymentAttempts and FeatureSnapshots.

Done when:

- Domain state and outbox events commit atomically.
- Events publish after DB commit through retryable outbox publishing.
- Consumers can restart safely.
- Audit logs exist for high-risk actions and include actor, aggregate, request
  ID, trace ID, action, timestamp, and safe before/after context where useful.
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
- Card-testing incident list.
- Merchant risk profile detail.
- Developer/API console basics.

Done when:

- Analyst can inspect a payment lifecycle.
- Analyst can see fraud reasons, rules fired, and feature snapshot.
- Analyst can resolve a review.
- Analyst can inspect card-testing incidents and merchant risk indicators.

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
- Dataset card and model card for every promoted model.
- Shadow-mode scoring before active serving.

Done when:

- Model score is stored with version and reasons.
- Rules-only fallback works on model timeout.
- Evaluation avoids future leakage.
- Candidate model has model card, dataset card, segment report, replay report,
  and rollback target.

### Phase 8: Analytics, Simulation, Graph

Build:

- ClickHouse analytics.
- Rule simulation and approval workflow.
- Risk policy simulation and approval workflow.
- Neo4j entity graph.
- Graph-derived investigation features.
- Privacy-safe cross-merchant aggregate features.

Done when:

- Rule simulation reports fraud captured, false positives, and business impact.
- Rule activation requires approval.
- Synthetic fraud rings are visible in graph investigation.
- Cross-merchant features are aggregate/anonymized and cannot expose another
  merchant's raw objects or identifiers.

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
- API versioning and compatibility checks.
- Security runbooks for credential leakage, card-testing spikes, dispute spikes,
  model regression, and merchant risk spikes.

Done when:

- Agents cannot mutate live payment/rule/model state directly.
- Tool calls are audited.
- p95/p99 latency is measured.
- Blue and green versions can coexist.
- API, event, model, rule, and database changes have compatibility and rollback
  checks.

## 18. Staff-Level Implementation Review Workflow

Every implementation chapter must run through this workflow before code
changes, after implementation, and when external reviews arrive. A chapter is a
sequenced product-building unit, not a calendar day. Multiple chapters may be
completed in one day when their dependencies, invariants, tests, and learning
artifacts are satisfied. The goal is to catch payment, fraud, security,
ML-lineage, and operations issues before they become schema or API churn.

### 18.1 Chapter Readiness Review

Before coding a chapter, write a short readiness review in the daily note or
working thread:

- What the master plan says for the chapter.
- What already exists in code and schema.
- What production-shaped payments, fraud, security, ML, and operations systems
  would need from this slice.
- What to implement now.
- What to explicitly defer, with the target chapter or phase.
- What tests will prove the slice.

The chapter should not start from the deliverable list alone. The deliverable
list is necessary but not sufficient; it must be filtered through the review
lenses below.

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
  behavior, or test harness. Assign the target chapter or phase.
- **Reject:** The item conflicts with the master plan, creates speculative
  schema, weakens tenant/security guarantees, or violates the simulator scope.
- **Needs design:** The item is plausible but needs an ADR, architecture update,
  or first-principles concept note before code.

Do not implement review feedback merely because it sounds production-grade.
Production-shaped architecture also means sequencing fields when their lineage,
constraints, and behavior can be tested.

### 18.4 Deferral Discipline

Do not defer work merely because it belongs to a later named chapter if the
current slice has satisfied its invariants and the next work is
dependency-ready.

Defer only when a required domain invariant, schema/API boundary, tenant or
security boundary, fraud/ML lineage source, ledger correctness guarantee, or
side-effect review is missing. Every deferral must name the exact reason, the
owner chapter or implementation slice, and the evidence that would unblock it.

When a slice finishes early and tests are green, pull forward the next safest
vertical step toward the payment lifecycle instead of stopping at a narrow
horizontal milestone.

### 18.5 Staff Checklist

Before marking a chapter complete, answer these questions:

- Are any secrets stored raw?
- Is any payment-sensitive or cardholder data persisted beyond the V1
  tokenized-simulator boundary?
- Are merchant tenant boundaries enforced in the database, not only controller
  code?
- Are operational records useful enough for retry, debugging, audit, and future
  metrics?
- Are payment lifecycle primitives complete enough for the next two to three
  implementation chapters?
- Are fraud/ML fields backed by real lineage, constraints, and behavior tests?
- Are H2-only assumptions covered by a PostgreSQL-backed path when the SQL is
  PostgreSQL-relevant?
- Did the implementation update docs, learning notes, and tests together?

### 18.6 Post-Implementation Self-Review

After coding and before finalizing:

- Re-run the mandatory review lenses against the actual diff.
- Compare accepted and deferred review items against the implementation.
- Confirm tests cover the riskiest invariants, not only happy paths.
- Record surprises and explicit deferrals in the daily note.

### 18.7 Staff Engineer Apprenticeship Output

THAAP is also an apprenticeship project. The current learning goal is to grow
from hands-on implementation comfort toward Senior Staff Engineer / ML
Architect judgment for payments security and fraud detection within six months.
The implementation should remain Staff-level, but it must be paired with
learning artifacts that make the design, code, and tradeoffs fully explainable.

Every non-trivial implementation chapter must include:

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

No major implementation chapter is complete until the learner can answer:

- What problem did we solve?
- Why did we solve it this way?
- What production risk did this reduce?
- What did we defer and why?
- What tests prove the important invariants?
- How would this be explained in a Stripe/Mastercard-style design review?

## 19. First 14-Chapter Execution Plan

This plan is sequenced as chapters, not calendar days. Chapters may be completed
faster than one per day, and multiple chapters may land in one focused session
when the prior chapter's invariants are proven. The goal is to move from secure
object creation into a complete authorization simulator path:

```text
merchant
  -> customer
  -> payment method
  -> payment intent
  -> confirm
  -> payment attempt
  -> fraud decision
  -> processor authorization
  -> webhook delivery record
```

Product-readiness gates for Chapters 6-14:

- Every merchant-facing mutation requires tenant context and idempotency.
- Every cross-object lookup is tenant-scoped.
- No raw PAN, CVV, API key, signing secret, or raw low-entropy PII is persisted
  or returned.
- Every confirm path creates a durable PaymentAttempt before fraud or processor
  side effects.
- Every confirm path creates a FraudDecision and FeatureSnapshot, even while the
  decision engine is still a deterministic stub.
- Every processor result is persisted so later capture, refund, dispute, fraud
  labels, and analyst views have lineage.
- Every chapter records explicit deferrals so payment, fraud, ML, and
  operations gaps are visible instead of accidental.

### Chapter 1: Product Scope And Repo

Deliver:

- Monorepo structure.
- README.
- ADR folder.
- Architecture diagram.
- Glossary.

Learn:

- PaymentIntent lifecycle.
- Fraud decision lifecycle.

### Chapter 2: Local Infrastructure

Deliver:

- Docker Compose with PostgreSQL and Redis.
- Database connection from Spring Boot.
- Health endpoints.

Learn:

- Local infra setup.
- Spring profiles.

### Chapter 3: API Foundation And Error Contract

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

Already in place before Chapter 3:

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

### Chapter 4: Database Migrations

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

### Chapter 5: Merchant And Customer APIs

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

### Chapter 6: PaymentIntent Create

Deliver:

- Bring existing `POST /payment_intents` scaffolding to production-shaped
  contract quality.
- Require `Idempotency-Key` and bind it to merchant, operation, and request
  hash through `IdempotencyService`.
- Validate `customerId`, positive `amount`, ISO-style three-letter `currency`,
  and optional `captureMethod`.
- Normalize currency to uppercase before persistence and response.
- Default missing `captureMethod` to `AUTOMATIC`.
- Create PaymentIntent in `REQUIRES_CONFIRMATION`.
- Persist `merchant_id`, `customer_id`, `amount`, `currency`, `capture_method`,
  `status`, `created_at`, and `updated_at`.
- Ensure customer lookup is tenant-scoped by `(id, merchant_id)`.
- Emit/create `payment_intent.created` webhook delivery records only after the
  PaymentIntent write is successful.
- Return a public response that does not expose internal hashes, idempotency
  hashes, or unrelated merchant state.
- Add contract tests for successful create, missing idempotency key,
  idempotency replay, same key with different body conflict, validation errors,
  default capture method, uppercase currency normalization, and cross-merchant
  customer rejection.

Done when:

- A merchant can create a PaymentIntent for its own customer.
- Replaying the same request with the same idempotency key returns the original
  response without creating another PaymentIntent or duplicate webhook delivery
  intent.
- Reusing the same idempotency key with a changed body returns `409`.
- A merchant cannot create a PaymentIntent for another merchant's customer.
- Tests prove response shape, tenant scoping, idempotency behavior, and
  validation error contract.

Defer:

- Atomic duplicate-key race handling and lock/unique-conflict hardening to
  Chapter 8.
- Confirm, PaymentAttempt creation, authorization, ledgering, and fraud
  decisions to Chapters 9-13.

Learn:

- Payment state machine.
- PaymentIntent as the customer-visible payment container.
- Why idempotency starts at object creation, not only money movement.
- Why cross-object tenant checks must be repository/database-backed.

### Chapter 7: Review And Refactor

Deliver:

- Staff-level architecture review for Chapters 1-6.
- Pull forward lifecycle hardening discovered during review before deeper
  payment side effects:
  - complete the PaymentIntent status vocabulary
  - add transition guards for capture, cancel, and refund
  - reject refund amounts that do not equal the captured PaymentIntent amount
    until partial refund accounting exists
  - return PaymentIntent `createdAt` and `updatedAt`
  - add optional description and constrained metadata
  - replace generic three-letter currency validation with a supported V1
    currency allowlist
  - add a simulator amount ceiling until processor/merchant-risk limits exist
- Update the weekly architecture review with:
  - payment lifecycle progress
  - tenant/security risks reduced
  - remaining idempotency, attempt, processor, fraud, and ledger risks
  - explicit deferrals with target chapters
- Run test cleanup only where it improves clarity without changing product
  behavior.
- Review root README, service README, and learning docs for stale Chapter 5/6
  status.
- Add or update the Chapter 6 daily note and deep dive.
- Run the commit readiness checklist.

Done when:

- Documentation accurately describes the runnable state.
- The next implementation risks are visible before deeper payment side effects
  are added.
- Invalid PaymentIntent transitions fail with `409` and do not mutate state.
- Refunds are rejected unless the PaymentIntent is captured and the requested
  V1 refund amount equals the captured PaymentIntent amount.
- PaymentIntent create rejects unsupported currencies and excessive amounts.
- PaymentIntent responses include reconciliation timestamps.
- No speculative schema or unused fraud/ML fields were added without lineage.

Learn:

- Weekly staff-level design review habit.
- Distinguishing current product gaps from intentional deferrals.
- Reviewer narrative: invariant, tradeoff, risk reduced, proof.

### Chapter 8: Idempotency

Deliver:

- Harden `IdempotencyService` for processor-safe future side effects.
- Ensure records store merchant scope, key, operation or method/path, request
  hash, status, response status, response body, creation time, and expiration.
- Reserve a key before running the mutation and mark it `COMPLETED` only after
  the mutation response is durable.
- Reject in-progress duplicates deterministically.
- Handle concurrent same-key requests through transaction boundaries,
  uniqueness constraints, and duplicate-key race handling.
- Ensure failed mutations do not cache success responses or hide retryable
  errors.
- Add tests for same request replay, changed request conflict, missing key,
  concurrent same-key requests, response replay shape, merchant-scoped key
  reuse, and rollback behavior after mutation failure.

Done when:

- Idempotency is safe enough to wrap future confirm, capture, cancel, refund,
  and chargeback operations.
- Duplicate retries cannot create duplicate durable side effects.
- The service behavior is explained in docs before processor simulation lands.

Defer:

- Redis idempotency acceleration to a later reliability phase.
- Long-term cleanup job for expired idempotency keys unless it is trivial.

Learn:

- Payment API reliability.
- Why idempotency is a state machine, not a request cache.
- Database uniqueness and transaction boundaries under retry storms.

### Chapter 9: PaymentAttempt

Deliver:

- Bring `PaymentAttempt` from schema/scaffold into the domain model.
- Create attempt entity/repository and status enum.
- Model attempt fields: `payment_intent_id`, `payment_method_id`,
  `idempotency_key`, `status`, `attempt_number`, `fraud_decision_id`,
  `processor_authorization_id`, `three_ds_status`, and timestamps.
- Add confirm request shape with `paymentMethodId`.
- On confirm, load PaymentIntent and PaymentMethod through tenant-scoped
  lookups.
- Create a new PaymentAttempt only when the PaymentIntent is in a valid
  confirmable status.
- Number attempts monotonically per PaymentIntent.
- Keep fraud decision and authorization fields nullable until Chapters 10-13
  populate them.
- Add tests for attempt creation, invalid status rejection, cross-merchant
  PaymentIntent rejection, cross-merchant PaymentMethod rejection, and attempt
  numbering.

Done when:

- PaymentAttempt becomes the immutable scoring/audit unit for confirm.
- Confirm no longer mutates PaymentIntent without a durable attempt record.
- Every future fraud decision has a clear lineage target.

Defer:

- Processor authorization details to Chapter 11.
- FraudDecision and FeatureSnapshot population to Chapter 12.
- 3DS completion endpoint until `REQUEST_3DS` behavior exists.

Learn:

- Attempt vs intent distinction.
- Why fraud and ML lineage belongs to PaymentAttempt, not only PaymentIntent.
- Confirmable payment state transitions.

### Chapter 10: Confirm Endpoint

Deliver:

- Implement `POST /payment_intents/{id}/confirm` as the first real-time
  orchestration path.
- Require `Idempotency-Key`.
- Validate PaymentIntent status before confirm.
- Validate PaymentMethod belongs to the PaymentIntent customer and current
  merchant.
- Create PaymentAttempt inside the idempotent transaction.
- Add a decision-engine interface that returns a deterministic stub `ALLOW`
  with reason code `RULES_NOT_ENABLED`.
- Apply the `ALLOW` action by moving the attempt to the next processor-ready
  state and the PaymentIntent to `PROCESSING` or authorization-ready status
  agreed for Chapter 11.
- Record request ID and trace-ready operation names in logs without sensitive
  data.
- Add tests for success, replay, changed-body conflict, invalid PaymentIntent
  status, wrong-customer PaymentMethod, tenant rejection, and no duplicate
  attempts on retry.

Done when:

- Confirm has a stable orchestration shape before processor and fraud details
  are filled in.
- The real-time path has clear extension points for features, rules, model
  timeout fallback, and processor simulation.

Defer:

- Non-`ALLOW` actions to Chapter 12/Phase 3.
- Ledger writes until capture/refund work.
- Kafka/outbox until Phase 5; webhook delivery records may remain local
  placeholders.

Learn:

- Transaction boundaries.
- Real-time payment path design.
- Where fraud decisioning fits without making the endpoint untestable.

### Chapter 11: Processor Simulator

Deliver:

- Define `ProcessorAdapter` and `ProcessorAuthorizationResult`.
- Implement deterministic simulator outcomes from test card metadata or a
  simulator card catalog.
- Persist `Authorization` records linked to PaymentAttempt.
- Support initial outcomes: approved, issuer decline, insufficient funds,
  suspected fraud decline, CVC failure, AVS line1 failure, AVS postal failure,
  and 3DS required.
- Map processor outcomes to PaymentAttempt and PaymentIntent statuses.
- Persist processor response code, network status, authorized amount, currency,
  verification results, and created time.
- Add tests for each deterministic outcome and for idempotent confirm replay
  after an authorization result exists.

Done when:

- Confirm can produce a durable authorization or durable decline.
- Processor behavior is replaceable behind an adapter.
- No raw card input is required downstream of PaymentMethod creation.

Defer:

- Capture, cancel/void, refund, and ledger entries to Phase 2.
- Real PSP integration outside V1.

Learn:

- Adapter pattern.
- Authorization vs capture.
- Why processor result persistence matters for disputes, fraud labels, and
  audit.

### Chapter 12: Fraud Decision Stub

Deliver:

- Persist `FraudDecision` for every PaymentAttempt.
- Persist immutable `FeatureSnapshot` with `features_v1`, even if the first
  feature set is small.
- Add `RuleExecutionResult` scaffolding only if initial rules are evaluated;
  otherwise defer until deterministic rules land.
- Store action, risk score, risk level, ruleset version, latency, reason codes,
  and created time.
- Keep model version nullable until the model service exists.
- Add initial reason codes for stub decisioning and processor simulator
  outcomes.
- Ensure `BLOCK`/`REQUEST_3DS`/`REVIEW` enum values exist but do not activate
  behavior until rules decide them.
- Add tests proving every confirm attempt gets exactly one FraudDecision and
  FeatureSnapshot, including processor-decline attempts.

Done when:

- THAAP has auditable fraud lineage for every confirm attempt.
- Fraud data is tied to PaymentAttempt and tenant scope.
- The product can show why a payment was allowed, even before real rules/ML.

Defer:

- Real-time velocity counters to Phase 4.
- Model scoring to Phase 7.
- Graph signals to Phase 8.
- Manual review case creation until `REVIEW` behavior is enabled.

Learn:

- Audit-first decision design.
- Feature snapshots as the bridge between production decisions and offline ML.
- Why nullable model fields are better than fake model lineage.

### Chapter 13: End-To-End Confirm

Deliver:

- Wire confirm end to end:
  - idempotency
  - tenant-scoped PaymentIntent load
  - tenant/customer-scoped PaymentMethod load
  - PaymentAttempt creation
  - FeatureSnapshot creation
  - FraudDecision creation
  - ProcessorAdapter call when action is `ALLOW`
  - Authorization persistence
  - PaymentIntent/PaymentAttempt final status update
  - webhook delivery record creation for relevant events
  - audit log records for confirm, fraud decision, authorization, and terminal
    decline/block outcomes
- Add integration tests for approved authorization, issuer decline, suspected
  fraud decline, 3DS-required simulator outcome, idempotent replay, and invalid
  transition.
- Add one smoke/demo script that creates merchant, customer, payment method,
  PaymentIntent, confirm, and then fetches resulting objects.

Done when:

- A local user can run the full create-to-confirm card-payment simulator flow.
- The transaction detail data exists for a future analyst dashboard.
- E2E behavior is repeatable with deterministic test cards.
- Confirm and authorization decisions leave both domain lineage records and
  audit records with safe payloads.

Defer:

- Capture/refund/ledger completeness to Phase 2.
- Real fraud rules and manual review to Phase 3/4.
- Dashboard UI until Phase 6.

Learn:

- E2E flow thinking.
- Product demo design from durable records, not mocked screenshots.
- How payments, fraud, webhook, and audit surfaces connect.

### Chapter 14: Demo And Notes

Deliver:

- Run the full Chapter 13 demo script from a clean local database.
- Record a Chapter 14 demo note with:
  - exact commands
  - sample API responses with sensitive fields removed
  - known product gaps
  - known reliability gaps
  - known fraud/ML lineage gaps
  - next sprint risks
- Update README current status from Chapter 5 to the completed Chapter 14
  state.
- Update service README and architecture docs if implementation changed source
  of truth decisions.
- Create the next detailed sprint plan for Phase 2:
  - capture
  - cancel/void
  - refund
  - ledger invariants
  - webhook delivery retry behavior
  - deterministic processor edge cases
- Run the full backend test suite and commit readiness checklist.

Done when:

- The first complete authorization simulator demo is reproducible.
- The next sprint is ready before starting capture/refund/ledger work.
- Documentation, tests, and learning artifacts tell the same story.

Learn:

- Engineering communication.
- Demo-driven architecture review.
- Turning gaps into an executable next plan.

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
- RiskContext persists safe decision-time context and never exposes raw
  sensitive values.
- Velocity counters exclude the current payment.
- Card-testing incidents are created from abusive low-amount or setup patterns.
- Post-authorization risk signals create recommendations and labels without
  mutating the original FraudDecision.
- Refund-and-report-fraud creates a label and can update list inputs according
  to explicit policy.
- Fraud labels support fraud type and source.

### 20.5.1 Merchant Risk And Dispute Tests

- Merchant risk policy thresholds affect block, review, and 3DS actions.
- Policy activation requires simulation once ReplayEngine exists.
- Merchant risk metrics include authorization rate, decline rate, chargeback
  rate, fraud/dispute ratio, and enumeration ratio.
- Dispute evidence packets link to chargebacks and payment attempts.
- Friendly-fraud labels do not overwrite original real-time FraudDecision rows.
- Card-testing mitigations can be recommended without silently changing merchant
  policy.

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
15. No post-authorization signal may rewrite an original FraudDecision; it must
    create a new auditable recommendation, label, or case event.
16. No raw identity, device, session, IP, email, phone, or address signal may be
    persisted or exposed unless it has an explicit data classification and
    minimization rule.
17. No merchant risk policy, rule, or model threshold change without simulation
    or an explicit documented emergency exception.
18. No dispute workflow may treat every chargeback as fraud; reason code,
    evidence, source, and label confidence must be preserved.
19. No card-testing mitigation may silently disable legitimate merchant traffic;
    it must create an incident record, metric, or audit trail.
20. No feature may be called challenger-grade until it has durable state,
    tests, explainability, operational metrics, and a demo path through the
    payment lifecycle.
21. No non-trivial platform concept, feature, or dependency without a first-principles learning note under `docs/learning/concepts/`.
22. No deployment without rollback criteria once CI/CD begins.
23. No implementation chapter is complete without the staff-level readiness and
    post-implementation review workflow in Section 18.
24. No non-trivial implementation chapter is complete without a deep-dive learning
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
- Challenger product bar and real-world product gaps: section 1.
- PCI DSS scope and deployment target defaults: section 2.
- Fraud taxonomy: section 2.
- Architecture and deployables: section 3.
- Public APIs: section 4.
- Domain model, merchant risk profile, risk context, dispute evidence, and
  card-testing incidents: section 5.
- Processor simulator: section 6.
- Fraud decisioning, rules, lifecycle, risk policy, and simulation: section 7.
- Security, rate limiting, and card-testing controls: section 8.
- Feature engineering: section 9.
- ML plan and model governance: section 10.
- Graph and privacy-safe network intelligence: section 11.
- Dashboard and developer/API surfaces: section 12.
- GenAI agents: section 13.
- Observability: section 14.
- Events and webhooks: section 15.
- Synthetic data and fraud scenarios: section 16.
- Roadmap, including product-grade risk operations: section 17.
- Staff-level implementation review workflow: section 18.
- First sprint: section 19.
- Tests: section 20.
- Engineering rules: section 21.

## 23. Reference Documents

Use these for background and deeper reasoning:

- `docs/references/research/RESEARCHED_END_TO_END_FRAUD_PLATFORM_PLAN.md`
- `docs/references/research/ORIGINAL_180_DAY_IMPLEMENTATION_PLAN.md`
- `docs/references/research/STRIPE_RADAR_FRAUD_PLATFORM_PROMPT.md`
- `docs/references/source-materials/README.md`
- `docs/references/source-materials/mastercard_advanced_ai_technology_datasheet.pdf`
- `docs/references/source-materials/aws-agentic-genai-fraud-detection-reference-architecture.jpg`
- `docs/references/source-materials/useful_links.txt`

This master plan is authoritative for implementation.
