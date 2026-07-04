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
- `GET /refunds/{id}`
- `POST /chargebacks`
- `GET /ledger/entries`

### 4.3 Webhook APIs

- `POST /webhook_endpoints`
- `POST /webhook_endpoints/{id}/rotate_secret`
- `POST /webhook_endpoints/{id}/disable`
- `GET /webhook_deliveries`
- `GET /webhook_deliveries/{id}`
- `GET /webhook_deliveries/{id}/attempts`

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
- `merchantId`
- `paymentIntentId`
- `paymentAttemptId`
- `authorizationId`
- `amount`
- `currency`
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
- `decisionStartedAt`
- `decisionCompletedAt`
- `featureFreshnessStatus`
- `maxFeatureAgeMs`
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
- `computedAt`
- `oldestFeatureTimestamp`
- `maxFeatureAgeMs`
- `freshnessStatus`
- `staleFeatureNames`
- `missingFeatureNames`
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

### 9.3 Real-Time Feature Freshness Contract

Fraud quality is bounded by decision-time data freshness. A model or ruleset
must not appear more trustworthy than the features it received.

Every real-time fraud decision must record:

- when decisioning started and completed
- total decision latency in milliseconds
- the feature schema version
- when the feature snapshot was computed
- the oldest feature timestamp used by the decision
- the maximum feature age in milliseconds
- whether feature freshness was `FRESH`, `STALE`, or `MISSING`
- names of stale and missing features
- whether stale or missing features affected the decision action or confidence

V1 freshness rules:

- Payment request fields are fresh at request time.
- PostgreSQL-derived customer/payment history features are fresh at the
  transaction timestamp used by the query.
- Redis velocity counters must expose the counter update timestamp used for
  scoring.
- A feature with no trustworthy timestamp is `MISSING`, not fresh by default.
- Stale features may still be used by deterministic rules only when the rule
  records that freshness degraded confidence.

Initial SLO:

- fraud decision p95 latency below `100ms` inside `payments-api`
- hard timeout and degraded outcome behavior must be explicit before external
  model serving is introduced

What not to build yet:

- no Kafka/Flink pipeline until durable payment, attempt, feature, and replay
  contracts exist
- no GPU/Triton serving until deterministic rules, feature snapshots, offline
  replay, and model promotion gates prove a need

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

Current completion state:

- Merchant creation, customer creation, simulated card payment method creation,
  PaymentIntent creation, PaymentAttempt creation on confirm, idempotency replay,
  and basic state transitions are implemented and covered by contract tests.
- Chapter 15 capture with balanced ledger entries has already been pulled forward
  and implemented in the current worktree.
- Chapter 16 synthetic scenario generation is implemented. Phase 1 is now closed
  for the original payment-core requirements while Phase 2 money-movement
  hardening continues.

Phase 1 closure chapter:

1. Chapter 16: Synthetic Scenario Generator V1.

   Status: complete.

   Build:

   - A deterministic local generator for repeatable payment-core scenarios.
   - Scenario families for:
     - happy-path approved authorization
     - retryable processor decline
     - basic card-testing or suspicious repeated-attempt pattern
   - Generated merchants, customers, simulated card payment methods,
     PaymentIntents, and confirms through the public API rather than direct
     database writes.
   - A seed or run identifier so a founder, engineer, or future model pipeline
     can reproduce the same scenario structure.
   - A sanitized summary artifact containing merchant IDs, PaymentIntent IDs,
     PaymentAttempt IDs, scenario labels, final statuses, fraud decision IDs,
     and processor outcome codes.
   - Documentation for how to run the generator locally and how to interpret the
     output.

   Invariants:

   - Generated data must be tenant-scoped; one generated merchant cannot read or
     mutate another generated merchant's objects.
   - Generator runs must not persist raw PAN, CVV, raw buyer email, raw phone,
     API keys, idempotency hashes, or internal card fingerprints in summary
     output.
   - Generated scenarios must use the same public API path as demos and tests so
     generated truth exercises merchant auth, idempotency, validation,
     PaymentIntent lifecycle, PaymentAttempt creation, processor simulation, and
     fraud lineage.
   - Same seed and scenario config must create the same scenario labels, card
     fixture choices, and expected outcome classes.
   - Decline and suspicious scenarios must not create false capture or ledger
     truth.

   Required proof:

   - Generator smoke test or script verification for approved, decline, and
     basic suspicious/card-testing scenario families.
   - Determinism test for scenario plan output from the same seed.
   - Tenant isolation check across generated merchants.
   - Sensitive-data scan of generator summary output.
   - README or script docs showing exact local command usage.
   - Learning note explaining why synthetic payment data is Phase 1 product
     infrastructure for fraud/ML, not later polish.

   Defer:

   - Large-scale volume generation, delayed fraud labels, chargeback labels,
     graph-ring synthesis, model-training datasets, and offline replay reports to
     later fraud/ML chapters.
   - Direct database seeding unless a future data-pipeline chapter needs it;
     Chapter 16 should prove the public payment core loop first.

   Implemented:

   - Supporting API `GET /payment_attempts/{id}/authorization` exposes
     tenant-scoped safe processor authorization evidence needed by scenario
     summaries; the Chapter 16 product deliverable remains the generator and its
     evidence artifact.
   - `scripts/generate-synthetic-scenarios.py` creates approved,
     retryable-decline, and basic card-testing scenario families through public
     APIs only and rejects non-loopback targets.
   - The generator writes sanitized JSON summaries under
     `build/reports/synthetic-scenarios/` by default and includes tenant
     isolation and sensitive-data scan results.
   - Collision-resistant run IDs are persisted in PaymentIntent metadata, and
     summary files are written atomically.
   - Generator success requires exact PaymentIntent, PaymentAttempt,
     Authorization, processor-response, FraudDecision, FeatureSnapshot, and
     zero-ledger truth for the configured scenarios.
   - The admin bootstrap token is read from the environment rather than command
     arguments, and synthetic customer phones are deterministic valid E.164
     values.

   Resolved Chapter 16 debt:

   - `[TD-0001]` was resolved in Chapter 21B. A dedicated authenticated runner
     credential assigns immutable `SYNTHETIC_SCENARIO` origin, which propagates
     through payment, attempt, fraud decision, feature snapshot, webhook event,
     API, and generated evidence. Metadata remains non-authoritative.
   - `[TD-0002]` was resolved in Chapter 21C. The loopback runner now owns an
     owner-only SQLite run journal with stable run/step identities, exclusive
     leases, restart-safe completed-step replay, run-scoped API idempotency,
     and explicit abandonment for ambiguous non-idempotent steps.

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

Completed chapters:

- Chapter 15 adds full-amount capture from `REQUIRES_CAPTURE` after a
  tenant-scoped approved authorization.
- Capture writes a balanced ledger transaction group with debit
  `CUSTOMER_RECEIVABLE` and credit `MERCHANT_PAYABLE`.
- Capture state change, ledger entries, webhook delivery records, audit log, and
  idempotency completion share one transaction boundary.
- The ledger schema requires capture source rows to reference a same-merchant
  PaymentIntent and blocks duplicate capture account postings.
- `GET /ledger/entries` exposes safe tenant-scoped ledger rows through a bounded
  read API.
- Chapter 17 makes `AUTOMATIC` capture part of the approved confirm transaction
  while preserving the separate capture endpoint for `MANUAL` PaymentIntents.
- Pre-authorization cancel writes no processor fiction; cancel after an
  approved manual authorization appends tenant-bound `authorization_voids`
  evidence and exposes that evidence through the authorization read contract.
- Cancel state, authorization void, webhook delivery record, audit log, and
  idempotency completion share one transaction boundary.
- Chapter 18 replaces the thin refund response with a tenant-scoped durable
  Refund linked to the winning PaymentIntent, PaymentAttempt, and Authorization.
- Full refund exactly reverses the capture postings with debit
  `MERCHANT_PAYABLE` and credit `CUSTOMER_RECEIVABLE`; Refund state, ledger,
  `refund.created` delivery records, audit log, and idempotency completion share
  one transaction boundary.
- `GET /refunds/{id}` and extended ledger reads expose safe tenant-scoped
  payment/refund lineage.
- Chapter 19 adds immutable webhook events, signed at-least-once delivery,
  append-only attempt evidence, deterministic retries, SSRF-safe pinned
  transport, endpoint disable/rotation concurrency, bounded observability, and
  executable merchant deduplication guidance.
- Chapter 20 makes every simulator outcome one immutable documented catalog,
  adds a loopback-only all-outcome public-API runner, and certifies the complete
  Phase 2 processor/lifecycle/accounting/webhook boundary through a
  pre-implementation invariant matrix and closure dossier.

Phase 2 closure state:

- Phase 2 is complete for the canonical V1 simulator boundary.
- Fees belong to the trigger-based **Partial Money Accounting** slice only after
  THAAP introduces fee postings or partial capture/refund.
- Disputes and chargeback accounting remain owned by Phase 4 because they
  require analyst, label, evidence, and offset workflow truth.
- Payout settlement and full reconciliation remain outside V1.
- General domain outbox/Kafka publication remains owned by Phase 5; it is not
  required for the database-backed webhook delivery contract.

Detailed Phase 2 chapter plan:

1. Chapter 15: Capture With Balanced Ledger Entries.

   Status: complete in the current implementation chapter.

   Build:

   - Full-amount `POST /payment_intents/{id}/capture` from
     `REQUIRES_CAPTURE`.
   - Approved tenant-scoped authorization lookup before capture.
   - Append-only capture ledger group.
   - Database-backed ledger source integrity for capture rows.
   - `GET /ledger/entries` for bounded tenant-scoped ledger inspection.
   - `payment_intent.captured` webhook delivery records.
   - Safe `payment_intent.captured` audit log.

   Invariants:

   - No captured PaymentIntent without an approved authorization.
   - No captured PaymentIntent without balanced debit and credit ledger entries.
   - Capture ledger rows must reference a same-merchant PaymentIntent source.
   - One capture source cannot post the same ledger account row twice.
   - Capture state, ledger rows, webhook delivery records, audit log, and
     idempotency completion succeed or roll back together.
   - Idempotent replay must not duplicate money, webhook, or audit truth.
   - Merchant A must not capture or inspect Merchant B's payment or ledger rows.
   - Ledger listing must be bounded so merchant financial history is not exposed
     through an unbounded query.

   Required proof:

   - Approved capture contract test.
   - Balanced ledger entry test.
   - Database constraint test for orphan source, cross-merchant source,
     duplicate account row, and invalid capture account/direction pair.
   - Idempotent replay test.
   - Double-capture rejection test.
   - Declined and 3DS-required capture rejection tests.
   - Cross-merchant capture and ledger-read rejection tests.
   - Bounded ledger-read test.
   - Rollback test proving ledger persistence failure leaves no captured state.

   Defer:

   - Partial capture, fees, reserves, payouts, disputes, and refund ledger groups.

2. Chapter 17: Cancel/Void Authorization Semantics.

   Status: complete.

   Chapter 17 starts by hardening capture-method semantics before refund work:
   `MANUAL` PaymentIntents keep the separate capture call, while `AUTOMATIC`
   PaymentIntents must either capture during approved confirm or move through a
   clearly owned automatic-capture orchestration. This is not part of the Chapter
   15 ledger fix because it changes confirm behavior, audit/webhook truth,
   idempotency, and the PaymentIntent response contract.

   Build:

   - Distinguish `AUTOMATIC` from `MANUAL` capture behavior after approved
     authorization.
   - Harden `POST /payment_intents/{id}/cancel` for uncaptured payment states.
   - Distinguish pre-authorization cancellation from post-authorization void.
   - Persist enough void/cancel evidence to prove an approved authorization can no
     longer be captured after cancellation.
   - Write safe `payment_intent.canceled` audit logs with before/after state and
     authorization context when present.
   - Emit `payment_intent.canceled` webhook delivery records without duplication.

   Invariants:

   - A captured, refunded, disputed, failed, or already canceled PaymentIntent
     cannot be canceled.
   - `MANUAL` capture is the only capture method allowed to wait in
     `REQUIRES_CAPTURE` for the public capture endpoint.
   - `AUTOMATIC` capture must not silently behave like manual capture; its
     capture, ledger, webhook, audit, and idempotency truth must be explicit.
   - A canceled approved authorization cannot later be captured.
   - Cancel from `REQUIRES_CONFIRMATION` does not invent processor truth.
   - Cancel from `REQUIRES_CAPTURE` records void intent against the approved
     authorization before the payment becomes terminally canceled.
   - Cancel, webhook delivery record, audit log, and idempotency completion share
     one transaction boundary.
   - Tenant isolation applies to cancel, void evidence, audit rows, and webhook
     delivery rows.

   Required proof:

   - Cancel before confirm succeeds and creates safe audit/webhook truth.
   - Approved `MANUAL` confirm remains eligible for the separate capture endpoint.
   - Approved `AUTOMATIC` confirm follows the documented automatic-capture
     behavior and does not leave ambiguous manual-capture eligibility.
   - Cancel after approved authorization succeeds and blocks later capture.
   - Cancel after decline or 3DS-required follows the documented product rule.
   - Cancel after capture/refund/dispute is rejected.
   - Idempotent replay returns the original canceled response.
   - Changed-body or operation mismatch on the same idempotency key returns `409`.
   - Cross-merchant cancel is rejected.
   - Rollback test proves a webhook/audit failure does not leave false canceled
     state.

   Implemented:

   - Approved `AUTOMATIC` confirm now writes the balanced capture ledger group,
     moves the PaymentIntent to `CAPTURED`, and records capture webhook and
     audit truth inside the confirm idempotency transaction.
   - Approved `MANUAL` confirm remains in `REQUIRES_CAPTURE`; only manual
     PaymentIntents may call the public capture endpoint.
   - Forward-only migration V6 creates append-only `authorization_voids` with
     composite tenant, authorization, PaymentAttempt, and PaymentIntent lineage
     constraints plus one-void-per-authorization and one-void-per-intent
     uniqueness.
   - V6 converts legacy approved `AUTOMATIC` rows waiting in
     `REQUIRES_CAPTURE`; forward-only V7 completes the compatibility boundary
     for every other pre-Chapter-17 non-terminal row that could still progress
     toward capture. Existing confirms, retries, action flows, processing
     recovery, and approved payments retain the old explicit-capture behavior,
     while terminal rows remain untouched as historical truth.
   - Cancel from `REQUIRES_CONFIRMATION`, retryable decline, or 3DS-required
     states creates no void evidence. Cancel from `AUTHORIZED` or
     `REQUIRES_CAPTURE` appends a `RECORDED` / `MERCHANT_CANCELED` void linked
     to the approved authorization before terminal cancellation.
   - `GET /payment_attempts/{id}/authorization` now reports safe void status,
     reason, identifier, and timestamp while preserving the original approved
     authorization as historical processor truth.
   - Contract, rollback, migration, and PostgreSQL concurrency tests prove
     automatic/manual behavior, balanced ledger truth, replay non-duplication,
     terminal-state rejection, decline and 3DS cancellation, tenant isolation,
     database lineage constraints, V5-to-V7 compatibility for every open
     payment state, rollback of automatic capture or cancel when ledger/audit
     persistence fails, and one coherent winner under concurrent capture and
     cancel.
   - `scripts/demo-chapter-17-flow.sh` fails closed unless automatic capture,
     manual void, and ledger invariants agree, and rejects non-loopback targets
     unless an authenticated scenario-runner credential assigns trusted
     synthetic origin.

   Scope boundaries:

   - Real network reversal APIs remain outside the V1 simulator scope and begin
     only if a real PSP integration is selected.
   - Partial voids have no valid domain boundary until a future partial-capture
     slice introduces partial authorization/capture accounting.

3. Chapter 18: Refund Object Truth And Refund Ledger Entries.

   Status: complete.

   Build:

   - Replace the current refund state guard with a real refund mutation path.
   - Create durable refund truth for a full captured PaymentIntent refund.
   - Add refund ledger group entries that reverse capture accounting.
   - Emit canonical `refund.created` webhook delivery records.
   - Write safe refund audit logs with payment, refund, authorization, and ledger
     references.

   Invariants:

   - Refund is allowed only after capture.
   - V1 refund amount must equal the captured PaymentIntent amount until partial
     refund accounting exists.
   - Refund ledger entries must balance and must reference the original captured
     payment.
   - Refund state, refund record, ledger rows, webhook delivery records, audit
     log, and idempotency completion succeed or roll back together.
   - Double refund and over-refund are rejected.
   - Refunds must not mutate or rewrite the original FraudDecision.

   Required proof:

   - Full refund after capture succeeds.
   - Refund before capture, after cancel, after decline, and after 3DS-required
     are rejected.
   - Balanced refund ledger entries test.
   - Idempotent refund replay test.
   - Double-refund and over-refund rejection tests.
   - Tenant isolation tests for refund mutation and refund/ledger reads.
   - Rollback test proving ledger persistence failure leaves no refunded state.

   Deferred capability ownership:

   - Partial refunds and the separate Capture object are owned by the
     **Partial Money Accounting** Payments Core slice below. Chapter 18 cannot
     support them without replacing one-refund-per-PaymentIntent truth with
     explicit capture allocation and cumulative refundable-balance accounting.
     Start the slice when the product accepts a partial capture or refund. It is
     unblocked only when durable Capture lineage, multiple Refunds, atomic
     cumulative amount guards, and PostgreSQL concurrency tests prove that
     capture and refund totals cannot exceed the authorized/captured amounts.
   - Fee reversal is also owned by **Partial Money Accounting**. There is no fee
     ledger truth to reverse in Chapter 18. Start it when THAAP introduces a fee
     schedule or fee ledger postings; completion evidence must prove full and
     proportional fee treatment, rounding policy, balanced entries, and
     refund/dispute interaction.
   - Refund reason taxonomy and review-triggered refund approval are owned by
     the Phase 4 **Refund Operations And Review Actions** slice, with Risk
     Operations as product DRI and Payments Core as technical DRI. Start it when
     ManualReviewCase can initiate payment actions. Completion evidence must
     prove a constrained/versioned reason contract, authorization policy,
     tenant isolation, idempotency, safe audit evidence, and that review
     outcomes append labels without rewriting the original FraudDecision.
   - Refund/dispute offsets are owned by the Phase 4 **Dispute Offset
     Accounting** slice, with Payments Core as technical DRI and Risk Operations
     as workflow DRI. Start it with chargeback ledger implementation. Completion
     evidence must prove event-order handling, no customer over-credit or
     merchant double-debit, balanced ledger groups, and concurrent
     refund-versus-dispute behavior.
   - External acquirer refund calls are owned by the post-V1 **Real PSP Refund
     Execution** slice, with Payments Core as technical DRI and
     Security/Compliance as supporting owners. Start only after a PSP is
     selected and its hosted/tokenized payment, authentication, callback, and
     reconciliation contracts are approved. Completion evidence must prove
     provider idempotency, asynchronous requested/pending/succeeded/failed
     states, retry and callback races, provider-reference lineage,
     reconciliation, and that local `RECORDED` truth is never presented as
     network success.

   Implemented:

   - `POST /refunds` preserves the existing full-amount request contract and
     returns a durable `RECORDED` Refund with PaymentIntent, PaymentAttempt, and
     Authorization lineage.
   - `GET /refunds/{id}` retrieves the Refund only inside the authenticated
     merchant boundary.
   - Refund requires `CAPTURED` state, matching approved authorization truth,
     and a complete balanced capture ledger group before writing reversal
     entries.
   - Refund ledger rows use the Refund as their source, retain the original
     PaymentIntent ID, and exactly reverse capture with debit
     `MERCHANT_PAYABLE` and credit `CUSTOMER_RECEIVABLE`.
   - V8 backfills PaymentIntent lineage on capture rows, adds composite Refund
     and ledger constraints, and rejects legacy `REFUNDED` rows whose missing
     identifiers and accounting evidence cannot be reconstructed honestly.
   - Refund, PaymentIntent state, ledger entries, `refund.created` delivery
     records, safe audit evidence, and idempotency completion commit or roll
     back together.

   Proof:

   - H2 and PostgreSQL migration tests prove clean V8 application, capture
     lineage backfill, Hibernate validation, and fail-fast legacy refund
     rejection.
   - Contract tests prove automatic/manual refunds, durable reads, exact
     reversal postings, idempotent replay, invalid-state and amount rejection,
     tenant isolation, and immutable fraud lineage.
   - Injected ledger, webhook, and audit failures prove complete rollback.
   - A PostgreSQL race proves two distinct refund requests produce exactly one
     `201` winner, one `409` loser, one Refund, and one balanced reversal group.

   No Chapter 18 debt was accepted. `RECORDED` is intentionally simulator-local
   truth; real acquirer refund execution remains outside V1 rather than being
   represented as a successful network claim.

4. Chapter 19: Webhook Delivery Execution And Retry Behavior.

   Status: complete after the 2026-07-02 final pre-merge recovery pass covering
   exact request-target and response-field grammar, socket-duration conversion,
   migration preflight, signature freshness, readiness enforcement, and forced
   observability interleavings.

   Completion evidence:

   - Forward-only V9 passes clean and V8-to-V9 migration proof on H2 and real
     PostgreSQL, including immutable envelope backfill and honest legacy-secret
     rotation state.
   - Contract/security tests prove exact payload/hash/signature behavior,
     encrypted-secret authentication, target isolation, retries, exhaustion,
     lease recovery, stale finalization, disable races, bounded tenant reads,
     safe audit/metrics, and unchanged payment truth.
   - Staff adversarial hardening proves explicit global-address classification,
     a wall-clock slow-response deadline, bounded DNS resolution,
     just-in-time one-attempt claiming, recoverable unexpected runtime
     failures, versioned key-encryption-key rollover, cursor traversal, bulk
     disable, event/delivery lineage, tri-state attempt evidence, bounded HTTP
     status parsing, disabled expired in-flight recovery, and
     claimed-but-not-dispatch-started disable.
   - Follow-up hardening proves all-zero encryption keys fail closed and
     resolver deadline/capacity failures retry as known no-send infrastructure
     failures instead of terminal unsafe-target decisions.
   - The final DNS taxonomy also proves unknown host, empty address answers,
     and unexpected resolver failures remain retryable known-no-send outcomes;
     only explicit URL/allowlist/address-policy denial is terminal.
   - A final callback acknowledgement is accepted only after the bounded final
     HTTP response head is syntactically valid and structurally complete.
     Missing-colon, whitespace-name, invalid-token, control-bearing,
     obsolete-folded, truncated, and oversized field representations are
     post-send transport failures, not false success.
   - The status-line parser accepts only HTTP/1.0 or HTTP/1.1 text responses
     with exact status grammar; malformed version tokens remain post-send
     transport failures rather than merchant acknowledgement truth.
   - Real PostgreSQL races prove one claim/attempt winner, monotonic endpoint
     disable against concurrent secret rotation, no stranded queued delivery
     when event creation races disable, and no stranded expired in-flight
     delivery after endpoint disable.
     Rotation/disable and routing/disable now force both orderings and verify
     the competing backend is waiting on the expected PostgreSQL lock before
     releasing the owning transaction.
   - A forced PostgreSQL interleaving proves retry finalization acquires the
     endpoint shared lock before the delivery row and cannot recreate
     retry-scheduled work behind concurrent disable.
   - Protocol-boundary proof covers authenticated payload identity versus
     advisory headers, domain Host authority when DNS selects IPv6, IPv6
     literals, Unicode path/query percent-encoding without escape rewriting,
     and payload/database timestamp precision.
   - V9 fails before schema mutation when a V8 delivery claims execution
     without authoritative attempt evidence or contains an unsupported legacy
     event type, while pristine work on an already disabled legacy endpoint
     becomes terminal `ENDPOINT_DISABLED` instead of remaining stranded and
     unclaimable.
   - The payload limit is rechecked before secret access and at the socket
     boundary; one cumulative 32 KiB byte budget covers status lines, headers,
     framing, informational responses, and the final response.
   - One shared DNS/socket duration validator rejects null, negative, zero,
     fractional-millisecond, configured-maximum, and overflow values before
     Java future/socket APIs can reinterpret a truncated configuration.
   - Attempt signatures are minted after potentially blocking dispatch
     preparation. A signing failure is classified as retryable known-no-send
     evidence, and a 301-second preparation delay still produces a fresh
     merchant-verifiable timestamp.
   - Bulk endpoint disable executes one bounded update per canonical event type
     and emits terminal metrics from actual affected-row counts. A forced
     PostgreSQL claim/disable interleaving proves predicate re-evaluation cannot
     create premature or duplicate terminal telemetry.
   - Signed webhook payloads are now produced through Jackson JSON encoding in
     both the runtime envelope factory and V9 legacy backfill path, eliminating
     manual JSON concatenation before merchant-verifiable signatures are
     computed.
   - The Chapter 19 invariant-proof matrix traces 23 payment/fraud, tenant,
     security, concurrency, data/ML, API/product, observability, and operations
     claims through hostile failure sequences to implementation and proof.
   - The matrix honestly records `RETROACTIVE_RECOVERY`; Chapter 19 is the
     documented process-recovery case, not a precedent for retroactive future
     matrices. The closure dossier reconciles promises, hostile sequences,
     enforcement, exact proof, migrations, demo, operations, debt, custom
     infrastructure, escaped findings, and source-of-truth status.
   - Bounded failure-cause telemetry distinguishes HTTP rejection,
     resolver/transport failure, lease expiry, endpoint disable, target denial,
     and secret failure without merchant, endpoint, URL, or resource tags.
   - `ER-0001` through `ER-0020` record the findings that escaped earlier
     Chapter 19 reviews, their root-cause categories, resolution evidence, and
     process corrections. The executable closure gates now require a
     boundary-family review so an escaped grammar or representation defect
     cannot close from one example-level regression. ADR-0009 bounds the
     justified custom socket transport and establishes mature infrastructure
     as the default.
   - The complete backend suite passes: 225 tests, zero failures, zero skipped.
     The repository Python suite passes 44 tests, including callback-fixture
     restart/concurrency/payload-conflict proof,
     mandatory non-trivial test execution, provenance, boundary-family,
     escaped-review, severity, closure, and source-contradiction negative
     cases. Commit readiness passes with the Chapter 19 invariant matrix and
     closure dossier named explicitly and automatically runs both suites.
   - The loopback public-API demo starts against a fresh PostgreSQL 16 schema
     migrated through V9 and verifies a signed `503` then `204`, one stable
     event/payload across two attempts, exactly one durable callback effect, and
     unchanged PaymentIntent and ledger truth.

   First-principles model:

   - A `WebhookEvent` is the immutable fact THAAP is notifying a merchant about.
   - A `WebhookDelivery` is one event routed to one merchant endpoint.
   - A `WebhookDeliveryAttempt` is one append-only record of a claimed
     transmission attempt for one delivery. It can end before HTTP when secret
     or target policy fails closed.
   - Payment, fraud, authorization, capture, cancel, refund, ledger, and audit
     truth commits before external HTTP begins. A webhook failure never rewrites
     or rolls back the originating domain fact.
   - Delivery is **at least once**, not exactly once. A worker can send an HTTP
     request and crash before recording the response, so lease recovery may send
     the same event again. After verifying the exact-body signature, merchants
     deduplicate using the stable `id` inside the signed payload and reject a
     disagreeing `THAAP-Event-Id` header.

   Scope decisions:

   - Chapter 19 executes the delivery records already created by payment
     mutations and replaces their thin mutable description with an immutable
     webhook event envelope plus endpoint-specific delivery state.
   - The event envelope is stored transactionally with its originating payment
     mutation. It is not the general Phase 5 domain outbox and does not publish
     Kafka events.
   - Create one webhook event only when at least one active, subscribed endpoint
     matches. All matching endpoint deliveries share that event; no-endpoint
     mutations do not create orphan webhook event rows.
   - Payload V1 contains safe identifiers and event metadata only. It does not
     reconstruct a mutable PaymentIntent, PaymentAttempt, Authorization, Refund,
     or ledger object when a retry occurs.
   - A bounded Spring scheduler is only an adapter. A deterministic
     `processDueDeliveries(now, batchSize)` application boundary owns claim,
     send, and finalization behavior and can be exercised without wall-clock
     sleeps.
   - Delivery execution is safe by default: local/test profiles may call only
     loopback callback servers; non-local execution remains disabled unless an
     explicit HTTPS hostname allowlist and secret-encryption key are configured.
     Local/test HTTP is simulator-only plaintext loopback behavior: webhook
     payloads and signature headers are not exposed to a network hop outside
     the host, and this mode must not be used for shared non-local merchant
     delivery.

   V1 event and signature contract:

   - Persist the exact UTF-8 JSON payload bytes before creating delivery rows.
     Runtime and migration payload construction must use a JSON serializer, not
     string concatenation, because those exact bytes are the merchant signature
     contract.
   - The payload fields are:
     - `id`: stable webhook event UUID
     - `type`: canonical event type
     - `createdAt`: immutable UTC event time
     - `apiVersion`: `v1`
     - `merchantId`: owning merchant UUID
     - `data.object.id`: safe resource UUID
     - `data.object.type`: constrained resource type
   - Canonical Chapter 19 event types are `payment_intent.created`,
     `payment_attempt.created`, `payment_intent.captured`,
     `payment_intent.canceled`, and `refund.created`.
   - Send `Content-Type: application/json`, `User-Agent: THAAP-Webhook/1.0`,
     `THAAP-Event-Id`, `THAAP-Delivery-Id`, and `THAAP-Signature`.
     `THAAP-Event-Id` is correlation metadata and must equal the signed
     payload's `id`; it is not independently authenticated by the HMAC.
   - `THAAP-Signature` has the form
     `t=<unix-seconds>,v1=<lowercase-hex-hmac-sha256>`.
   - The signed input is the exact UTF-8 byte sequence
     `<unix-seconds>.<persisted-payload>`. Retries keep the payload and event ID
     stable but use a fresh attempt timestamp and signature.
   - Merchant verification documentation and the callback fixture require a
     bounded timestamp tolerance and constant-time MAC comparison before JSON
     processing, then UUID validation and header agreement before
     atomically deduplicating on the signed payload `id`. The fixture binds that
     ID to the payload hash in durable SQLite state, proves restart and
     concurrent duplicate safety, and rejects conflicting bytes under one ID.

   Chapter 19A: Durable event, delivery, and attempt schema.

   Build:

   - Add append-only `webhook_events` with `id`, `merchant_id`, `event_type`,
     `resource_id`, `resource_type`, `api_version`, exact canonical payload,
     payload SHA-256, `occurred_at`, and `created_at`.
   - Link every `webhook_deliveries` row to a same-merchant `webhook_event`.
     Retain endpoint, aggregate status, attempt count, next attempt, last
     attempt, delivered time, safe last error code, lease token, and lease
     expiry.
   - Add append-only `webhook_delivery_attempts` with merchant, delivery,
     attempt number, start/end timestamps, outcome, optional HTTP response
     status, durable dispatch-start time, tri-state HTTP-send evidence, safe
     error code, and latency. `false` means pre-dispatch known not sent, `true`
     means the request write completed, and `null` means post-dispatch truth is
     not yet or is no longer knowable. The database permits `null` only for an
     in-progress post-dispatch attempt or `OUTCOME_UNKNOWN`. Do not store
     response bodies, response headers, request headers, signatures, secrets,
     or URLs.
   - Add `webhook_endpoint_event_subscriptions`. Missing `enabledEvents` on the
     create request means all currently supported event types for backward
     compatibility; an explicit list must be non-empty and is validated against
     the canonical event catalog. V9 gives legacy endpoints all currently
     supported subscriptions.
   - Replace the endpoint `active` boolean with explicit `ACTIVE`, `DISABLED`,
     and `REQUIRES_SECRET_ROTATION` operational states.
   - Use delivery states `PENDING`, `DELIVERING`, `RETRY_SCHEDULED`,
     `SUCCEEDED`, and `EXHAUSTED`. Attempt failure is evidence on an attempt;
     ambiguous delivery-level `FAILED` is removed.
   - Add forward-only V9 migration and H2/PostgreSQL compatibility proof.
     Existing delivery rows receive safe immutable envelopes from their stored
     event type, resource ID, merchant ID, delivery creation time, and a
     deterministic resource-type mapping. Released V8 had no executor or
     attempt evidence, so V9 accepts only pristine `PENDING`, zero-attempt rows.
     Any row that claims retry, last-attempt, delivered, failed, or succeeded
     execution state fails the migration before new schema truth is created.
     Pristine queued rows for inactive legacy endpoints become terminal
     `EXHAUSTED` with `ENDPOINT_DISABLED`; queued rows for active legacy
     endpoints remain intact pending secret rotation.
     V8 stored no event identity, so V9 cannot prove which legacy deliveries
     were sibling routes from one historical emission. Each legacy delivery is
     therefore preserved as one synthetic event using the delivery ID, rather
     than inventing fan-out lineage by grouping on lossy attributes. Multi-
     endpoint fan-out lineage is authoritative for events created after V9.
   - Existing endpoints have only irreversible signing-secret hashes. V9 must
     mark them `REQUIRES_SECRET_ROTATION` and prevent delivery rather than
     pretending their original secret is available. Their queued deliveries
     remain ineligible but intact; successful rotation activates the endpoint
     and makes those deliveries due under the newly revealed secret.

   Database invariants:

   - A delivery event and endpoint must belong to the delivery merchant.
   - Delivery event type and resource ID must equal the linked immutable event;
     the composite foreign key prevents API/payload lineage disagreement.
   - `(webhook_event_id, webhook_endpoint_id)` is unique, so one event has at
     most one delivery per endpoint.
   - Attempt merchant and delivery merchant must match.
   - `(delivery_id, attempt_number)` is unique and attempt numbers are positive.
   - Successful deliveries have `delivered_at`, no next attempt, and at least
     one successful attempt.
   - Exhausted deliveries have no next attempt and cannot be claimed.
   - Retry-scheduled deliveries have a non-null next-attempt time.
   - Delivering rows have a claim token and lease expiry.
   - The persisted event payload hash must match the exact payload bytes used for
     signing and sending.
   - Event time is canonicalized to database microsecond precision before the
     payload, hash, event timestamps, and delivery timestamps are constructed.

   Chapter 19B: Signing-secret and outbound-network security.

   Build:

   - Preserve signing-secret prefix and SHA-256 hash for display and
     verification, but also encrypt the raw high-entropy signing secret with
     AES-256-GCM under a separately configured webhook key-encryption key.
   - Store encrypted secret material, nonce/authentication data, and key version;
     never log or return them. The raw signing secret remains reveal-once at
     endpoint creation or rotation.
   - Support a versioned decryption keyring during key-encryption-key rollover.
     Startup fails if an active endpoint references an unavailable version.
     Delivery access locks the endpoint and lazily re-encrypts old ciphertext
     under the current key without changing the merchant signing secret.
     Key parsing rejects absent execution keys through startup validation and
     rejects the known-bad all-zero 256-bit key before any endpoint secret is
     encrypted.
   - Add tenant-scoped secret rotation for `REQUIRES_SECRET_ROTATION` and active
     endpoints. Rotation replaces the signing key atomically, returns the new
     raw value once, and writes a safe audit log without secret material.
     Rotation and disable serialize on the endpoint row; rotating a `DISABLED`
     endpoint returns conflict rather than implicitly undoing the kill switch.
   - Add a tenant-scoped endpoint disable action as an operational kill switch.
     Disabled endpoints receive no new deliveries or claims; unclaimed queued
     deliveries become terminal `EXHAUSTED` with safe reason
     `ENDPOINT_DISABLED`. A claimed attempt that has not yet durably marked
     dispatch-started is stopped at `prepareForDispatch`, exhausted with
     known no-send evidence, and never emits HTTP after the merchant kill
     switch wins. An attempt already marked dispatch-started may finish, but it
     cannot schedule another retry. Queued rows are exhausted with one
     tenant-scoped bulk update rather than an unbounded entity load. Originating
     event transactions hold a shared endpoint lock while routing deliveries,
     so disable cannot miss a delivery inserted just after that bulk update.
     Dispatch preparation and retry finalization take the same endpoint shared
     lock before the delivery row lock, so disable cannot commit between
     endpoint-state validation and either starting HTTP or scheduling another
     attempt. Lease recovery still selects expired `DELIVERING` rows after
     disable, closes their attempt evidence, and exhausts them without creating
     replacement attempts.
   - Fail startup when execution is enabled without a valid encryption key.
     Database access alone must not be enough to forge merchant signatures.
   - Add `WebhookTargetPolicy`: reject URL user-info and fragments; disable
     redirects; require HTTPS outside local/test; require an explicit hostname
     allowlist outside local/test; resolve and reject unspecified, private,
     link-local, multicast, metadata-service, IPv6 unique-local, CGNAT,
     documentation, benchmark, and reserved addresses; allow HTTP only for
     loopback callback servers in local/test. Use explicit byte/CIDR
     classification rather than incomplete platform convenience predicates.
     Local/test HTTP is accepted only for loopback simulator fixtures; the
     signature header and payload are visible on plaintext loopback and are not
     an acceptable shared-environment transport.
   - The HTTP transport must use the address approved by target policy for that
     attempt while retaining the original hostname for TLS verification; it
     must not perform an unchecked second DNS resolution after policy approval.
     DNS resolution uses an application deadline plus a bounded worker pool and
     bounded queue so non-interruptible platform resolution cannot consume
     unbounded scheduler or memory resources. Resolver deadline, interruption,
     and capacity failures are classified as retryable no-send infrastructure
     failures, not terminal target-policy violations.
   - Use a bounded connect timeout plus a wall-clock request deadline spanning
     TLS negotiation, request write, and response-status parsing; discard
     response bodies. Accept only three-digit statuses in `100..599`, consume a
     bounded number of informational responses, and persist only the final
     response status plus a constrained safe error code.
     Reject every configured duration that cannot remain within
     `1..Integer.MAX_VALUE` milliseconds after conversion to the Java socket
     API representation.
   - Enforce the 64 KiB UTF-8 payload limit again before signing and at the
     socket boundary. Enforce one cumulative 32 KiB response-head budget across
     every status line, header line, CRLF, informational section, and final
     section with exact limit and limit-plus-one proof. Every non-empty
     response-field line must have an ASCII HTTP-token name, exact colon
     separator, and control-safe value before its status can become delivery
     truth.
   - Build payloads from a strict allowlist. Raw PAN, CVV, buyer email, buyer
     phone, API keys, signing secrets, internal card fingerprints, idempotency
     keys/hashes, processor secrets, and unrestricted merchant metadata are
     forbidden.

   Chapter 19C: Claim, send, lease recovery, and retry.

   Build:

   - Claim due `PENDING`, due `RETRY_SCHEDULED`, or lease-expired `DELIVERING`
     rows in a short PostgreSQL transaction using ordered bounded selection and
     `FOR UPDATE SKIP LOCKED`.
     Endpoint eligibility is evaluated through an `EXISTS` predicate so the
     top-level lock applies only to delivery rows; the claim query must not
     accidentally take endpoint write locks in an order that conflicts with the
     endpoint-first disable/finalization protocol.
   - Claiming creates the next append-only attempt, assigns an unguessable claim
     token, moves the delivery to `DELIVERING`, and sets a lease expiry.
     Immediately before HTTP, dispatch preparation durably marks the attempt and
     renews the claim under the delivery lock. Recovering an expired pre-dispatch
     lease records a known-no-send failure; only an expired attempt that crossed
     the durable dispatch boundary closes as `OUTCOME_UNKNOWN`. A disabled
     endpoint does not hide an expired in-flight row from recovery.
   - Commit the claim before HTTP. Never hold a database lock or payment
     transaction open while performing network I/O.
   - Claim one delivery immediately before dispatch. The scheduler batch is a
     maximum number of sequential claim/send/finalize cycles, not a set of
     leases waiting behind earlier network calls. Startup requires the lease to
     exceed connect timeout plus request deadline.
   - After secret loading and DNS policy validation, renew the matching claim
     under a delivery row lock immediately before HTTP. A worker whose token
     became stale during preparation must not send. Mint the signature
     timestamp only after that potentially blocking preparation returns, then
     sign immediately before transport. If signing fails, finalize the prepared
     attempt as retryable known-no-send `SIGNING_FAILED` evidence. Finalization
     also locks the delivery row before checking token/attempt identity, preventing a
     check-then-write race with lease recovery.
   - Finalize in a new transaction only when delivery ID, attempt number, and
     claim token still match. Finalization and lease recovery mutate attempt
     rows through explicit `FOR UPDATE` repository reads after the delivery row
     lock is owned, making the delivery-owned attempt-mutation invariant visible
     at both row boundaries. A stale worker cannot overwrite a newer attempt.
   - Endpoint disabled, signing-secret unavailable/tampered, and target-policy
     rejection are non-retryable safe failures and move the delivery directly
     to `EXHAUSTED`. They never perform HTTP.
   - An unclassified runtime/infrastructure failure must not invent terminal
     delivery truth. Leave the claim for lease recovery unless the failure is a
     specifically classified terminal or retryable outcome.
   - Treat any `2xx` response as success. Network errors, timeouts, `3xx`, `4xx`,
     and `5xx` responses use the same deterministic retry schedule in V1;
     redirects are never followed and `Retry-After` is not honored in V1.
   - Attempt 1 is immediate. Failed attempts schedule attempt 2 after 1 minute,
     attempt 3 after 5 minutes, attempt 4 after 30 minutes, and attempt 5 after
     2 hours. Failure of attempt 5 moves the delivery to `EXHAUSTED`.
   - Inject `Clock`, retry policy, HTTP transport, batch size, and lease duration
     so tests do not sleep and production settings are bounded.
   - Scheduler overlap, multiple application instances, and lease recovery may
     never create two active claims for the same attempt number. Lease recovery
     may make a duplicate HTTP call, but it creates a new attempt number and
     preserves the same stable event ID. An expired fifth-attempt post-dispatch
     lease closes as `OUTCOME_UNKNOWN` and exhausts rather than creating attempt
     six; a pre-dispatch fifth lease records terminal known-no-send evidence.

   Chapter 19D: Merchant operations and observability.

   Build:

   - Extend `POST /webhook_endpoints` with optional `enabledEvents`; creation
     persists endpoint, subscriptions, encrypted secret, and safe audit evidence
     atomically while returning the raw signing secret once.
   - Add `POST /webhook_endpoints/{id}/rotate_secret` and
     `POST /webhook_endpoints/{id}/disable`. Rotation returns a new secret once;
     if that response is lost, the merchant rotates again rather than relying on
     THAAP to reproduce a previously revealed secret. Chapter 21 later resolved
     endpoint creation replay under `TD-0005`.
   - Replace unbounded `GET /webhook_deliveries` with a bounded, deterministic
     page ordered by newest creation time and stable ID. Traverse older pages
     with the paired `beforeCreatedAt` and `beforeId` cursor.
   - Add tenant-scoped `GET /webhook_deliveries/{id}` and bounded
     `GET /webhook_deliveries/{id}/attempts` with only safe event, delivery,
     attempt, response-status, timing, and error-code fields.
   - Keep encrypted keys, hashes, payload internals, URLs, claim tokens, and
     response bodies out of delivery/attempt responses.
   - Audit endpoint create, secret rotation, and disable actions with actor,
     merchant, endpoint ID, request ID, safe before/after state, and no secret.
   - Emit bounded-cardinality Micrometer metrics:
     - `thaap.webhook.attempts` by event type and outcome
     - `thaap.webhook.delivery.terminal` by event type and terminal status
     - `thaap.webhook.attempt.duration`
     - `thaap.webhook.delivery.latency`
     - `thaap.webhook.queue.age`
     - `thaap.webhook.due`
     Attempt and terminal metrics include lease-recovered `OUTCOME_UNKNOWN`,
     fifth-lease exhaustion, and bulk-disable exhaustion. `due` is the actual
     claimable backlog remaining after a poll, not the poll's processed count.
     Bulk-disable metrics come from five bounded event-type updates and their
     actual affected-row counts, never from a pre-update count that can race a
     claim.
   - Never use merchant ID, endpoint ID, delivery ID, URL, raw error text, or
     resource ID as a metric tag.
   - Add structured logs with event, delivery, attempt, bounded outcome, and
     latency fields; never log payloads, URLs, signatures, or secrets.

   Chapter 19E: Demo and merchant integration evidence.

   Build:

   - Add a local loopback callback fixture that verifies the exact body and
     signature, atomically applies one durable effect per authenticated event
     ID, and can deterministically return success or configured failures.
   - Add a repeatable demo covering event creation, first failure, scheduled
     retry, eventual success, attempt history, and unchanged payment/ledger
     truth.
   - Document merchant-side signature verification, duplicate-event handling,
     retry behavior, timeout expectations, and one-time secret handling.

   Invariants:

   - Creating a delivery record is not the same as delivering the webhook.
   - One delivery attempt must be claimed by only one worker at a time.
   - Retry appends attempt evidence; it does not create a new event or delivery.
   - Event payload bytes, event ID, event type, resource identity, merchant, and
     API version are immutable across retries.
   - Delivery is at least once, and merchant duplicate handling is part of the
     public integration contract.
   - Delivery payloads must not expose raw PAN, CVV, API keys, card fingerprints,
     idempotency hashes, email, or phone.
   - Webhook execution must not roll back already committed payment truth.
   - Failed webhook delivery must be observable and retryable without changing
     payment, fraud, or ledger state.
   - Merchant A cannot route, claim, inspect, rotate, disable, or verify Merchant
     B's endpoint, event, delivery, attempt, or signing secret.
   - A database-only compromise must not reveal a usable webhook signing key.
   - Untrusted callback URLs cannot reach local services, cloud metadata, or
     private network ranges outside the explicit local loopback policy.
   - Endpoint create/rotation/disable state and its safe audit evidence commit or
     roll back together; secret plaintext never enters audit or idempotency
     storage.

   Required proof:

   - Clean and V8-to-V9 H2/PostgreSQL migration tests, including honest handling
     of legacy endpoint secrets, pristine queued rows, already-disabled endpoint
     exhaustion, delivery payload backfill, and fail-fast rejection of
     aggregate retry/success/failure state without attempt evidence.
   - Event-envelope determinism and payload-hash tests.
   - HMAC signature verification against the exact transmitted bytes, plus
     tampered body, tampered timestamp, wrong endpoint secret, and retry
     signature tests. The executable callback fixture must reject a validly
     signed body paired with a different `THAAP-Event-Id`, serialize concurrent
     duplicates to one effect, survive restart, and reject one event ID reused
     with different payload bytes.
   - Encryption round-trip, wrong-key, ciphertext-tampering, reveal-once,
     rotation, startup-fail-closed, and log/response secret-scan tests.
   - Successful callback moves one delivery to `SUCCEEDED` with one successful
     attempt and leaves originating payment, fraud, and ledger truth unchanged.
   - Failure schedules the exact retry time; attempt five exhaustion produces
     terminal `EXHAUSTED` truth.
   - Real PostgreSQL duplicate-worker race proves one claim and one attempt
     number winner.
   - Executor-order proof shows claim 2 does not occur until attempt 1 has
     completed its send/finalization path; no leased row waits inside a batch.
   - Pre-send stale-claim proof shows a worker that loses ownership during
     preparation performs no HTTP and writes no terminal outcome.
   - Pre-dispatch disable proof shows a worker that already has signing
     material still performs no HTTP when the merchant disables the endpoint
     before `dispatch_started_at` is persisted; the claim closes as terminal
     known no-send.
   - Pre-dispatch and post-dispatch crash/lease-expiry tests prove truthful
     tri-state send evidence, create one new numbered attempt, preserve event
     identity, and reject stale-worker finalization.
   - Disable-after-dispatch crash proof shows an expired in-flight attempt
     closes as unknown and the delivery exhausts without becoming stranded or
     creating a replacement attempt.
   - PostgreSQL disabled-expired-recovery proof exercises the same claim SQL on
     the production database and verifies no replacement attempt is created.
   - Callback timeout, malformed numeric status, informational response, and
     every final HTTP status class follow the documented policy. A final status
     line followed by malformed field grammar, without the terminating
     response-head boundary, or beyond the parser bound fails as a post-send
     transport error. Property proof classifies every ASCII field-name and
     field-value character, while raw sockets cover the malformed field
     families at the real boundary.
     Exact-byte tests accept a cumulative 32 KiB response head and reject
     32 KiB plus one across informational and final sections; oversized
     persisted and direct-transport payloads fail before connection.
   - Redirect, user-info, private/link-local/metadata target, non-allowlisted
     hostname, and non-HTTPS non-local tests fail before outbound HTTP.
   - Slow-trickle response proof exceeds the per-read timeout pattern but is
     still terminated by the total wall-clock request deadline.
   - Configuration-domain proof rejects negative, zero, fractional-millisecond,
     configured-maximum, and overflow DNS/socket durations while accepting
     exact one-millisecond, resolver-maximum, and socket-maximum boundaries.
   - A non-returning DNS lookup hits the application deadline; resolver thread
     and queue saturation reject additional work without unbounded growth.
   - Resolver deadline/capacity failure finalization records retryable
     `http_request_sent=false` evidence and never calls HTTP or terminal
     `TARGET_NOT_ALLOWED`.
   - Unknown host, empty DNS answers, and unexpected resolver failures follow
     the same retryable known-no-send boundary; explicit prohibited-address
     results remain terminal target policy.
   - All-zero 256-bit encryption-key configuration fails before encrypting any
     merchant signing secret.
   - Payload and persistence scans reject PAN, CVV, buyer PII, API keys, signing
     secrets, card fingerprints, idempotency material, and unrestricted metadata.
   - Event-subscription filtering creates only matching endpoint deliveries.
   - Disable-versus-delivery race proves no new claim or retry starts after
     disable while an already claimed attempt records one coherent outcome.
   - Real PostgreSQL endpoint tests force both rotation/disable and
     routing/disable orderings, observe the competing backend waiting on the
     expected lock, and prove rotation cannot reactivate a disabled endpoint or
     event creation leave a pending delivery behind disable.
   - A forced PostgreSQL interleaving proves retry finalization holds the
     endpoint shared lock before waiting for the delivery lock, so concurrent
     disable leaves no retry-scheduled or claimable work.
   - Protocol-boundary tests prove normal hostnames are not bracketed merely
     because DNS selected IPv6, IPv6 literals retain brackets, and event
     timestamps are canonicalized before payload hashing and database writes.
   - Tenant-isolation tests cover endpoint rotation/disable, event, delivery,
     attempt, and bounded listing/detail APIs.
   - Audit and metric tests prove safe bounded fields/tags, recovery and bulk
     terminal counts, true due-backlog semantics, and latency-bearing logs. A
     forced PostgreSQL claim/disable interleaving proves bulk metrics count only
     rows actually exhausted by disable.
   - The local failure-to-retry-to-success demo verifies the receiver signature
     and proves payment/ledger state does not change.

   Defer:

   - General domain outbox and Kafka publication remain owned by **Phase 5:
     Events And Analytics**. They start when cross-process consumers are
     introduced and complete only when domain state and outbox commit
     atomically, publication is retryable, and consumer replay is safe.
   - Merchant-triggered replay, dead-letter queue operations, and endpoint
     verification handshakes remain owned by **Phase 4.5: Product-Grade Risk
     Operations**, implementation slice **Developer/API Console Webhook
     Operations**. Chapter 21 has already supplied the creation idempotency,
     rotation-overlap, and fair bounded scheduling substrate.
   - High-scale distributed queueing and managed egress proxy infrastructure are
     owned by **Phase 9: Agents, Hardening, Scale**, implementation slice
     **Webhook Worker Scaling And Egress Hardening**. Start when measured queue
     age or delivery volume exceeds the bounded database-worker target, or
     before multi-region execution; prove partition-safe claiming, backpressure,
     regional failover, controlled egress, and unchanged at-least-once
     semantics.
   - Response-body retention is intentionally excluded from V1 because merchant
     bodies are untrusted, potentially sensitive, and unnecessary for delivery
     truth. Reconsider only in the Phase 4.5 webhook-operations slice with
     strict truncation, retention, redaction, and access-control proof.

   Resolved Chapter 19 debt:

   - `TD-0003` was resolved in Chapter 21E with claim-bound secret generations,
     encrypted current/previous material, an explicit expiry boundary, and
     merchant verification guidance.
   - `TD-0004` was resolved in Chapter 21F with a durable merchant/endpoint
     round-robin cursor, configurable per-poll budgets, and bounded metrics.
   - `TD-0005` was resolved in Chapter 21D with tenant-scoped idempotency-key
     hashing, encrypted response replay, changed-request conflict, and exact
     tenant-scoped URL identity.
   - No DNS-rebinding or endpoint-disable debt is accepted by this plan. Target
     policy validates every resolved address and the transport connects to the
     prevalidated address; endpoint disable is proven at the pre-dispatch
     no-bytes boundary. Any implementation that weakens secret recoverability,
     target-network isolation, immutable payload truth, claim exclusivity, or
     payment/webhook transaction separation must be fixed in Chapter 19 rather
     than registered for later.

5. Chapter 20: Deterministic Processor Catalog And Phase 2 Closure.

   Status: complete.

   Build:

   - Expand the simulator catalog into a documented processor fixture contract.
   - Cover approved, issuer decline, insufficient funds, suspected fraud, CVC
     failure, AVS line1 failure, AVS postal failure, 3DS-required, and unknown
     simulator card approval.
   - Add demo commands or a small scenario runner for the major processor paths.
   - Record sanitized evidence showing payment status, attempt status,
     authorization response, fraud lineage, audit log, webhook delivery record,
     and ledger effect where applicable.

   Invariants:

   - Processor outcomes are processor truth, not fraud-decision reason truth.
   - Declines and verification failures must not create capture eligibility.
   - 3DS-required must not create capture or ledger truth before challenge
     completion exists.
   - Unknown simulator cards must follow the documented default behavior.
   - Every processor outcome must remain tenant-scoped, idempotent, and
     replayable.

   Required proof:

   - Contract tests for each deterministic card path.
   - Demo evidence for approved, decline, and 3DS-required paths.
   - Regression test proving declined/3DS paths cannot be captured, canceled, or
     refunded contrary to the documented state machine.
   - Audit and lineage tests proving safe payloads across processor edge cases.

   Defer:

   - Real network error taxonomy, asynchronous processor callbacks, challenge
     completion, and acquirer reconciliation until the simulator contract is
     stable.

   Implemented:

   - `SimulatorProcessorCatalog` is the single immutable runtime mapping from
     safe stored BIN-plus-last4 metadata to eight named processor fixtures;
     unmapped valid simulator cards follow one explicit default-approval
     fixture.
   - `SimulatorProcessorAdapter` consumes that catalog without changing the
     ProcessorAdapter or public authorization API.
   - The processor-authorization concept note documents the full test-card
     contract and distinguishes issuer/processor `SUSPECTED_FRAUD` truth from
     THAAP's independent stub FraudDecision.
   - `scripts/demo-chapter-20-processor-catalog.py` accepts only a loopback API,
     rejects every HTTP redirect before credential forwarding, creates all nine
     outcome partitions through public APIs, reconciles
     PaymentIntent, PaymentAttempt, Authorization, FraudDecision,
     FeatureSnapshot, ledger, webhook, and read-only local PostgreSQL audit
     evidence, then atomically writes an owner-only sanitized report.
   - Chapter 20 is the integrated Phase 2 closure chapter. Its
     pre-implementation matrix covers the prior capture, cancel/void, refund,
     ledger, webhook, audit, tenant, migration, concurrency, and operational
     promises under the current closure system.

   Proof:

   - Catalog unit tests enumerate every fixture field, explicit unknown-card
     behavior, and consumer immutability.
   - Existing confirm contract tests prove all mapped decline/verification
     outcomes, 3DS-required, mapped approval, and unknown default approval
     through the public orchestration boundary.
   - Phase-wide lifecycle, rollback, tenant, migration, forced PostgreSQL
     concurrency, webhook protocol/security, and readiness suites remain green.
   - Fresh PostgreSQL Chapter 17, 18, 19, and 20 demos prove automatic capture,
     manual void, exact refund reversal, signed webhook retry, and complete
     processor catalog behavior.
   - The Chapter 20 invariant matrix and closure dossier reconcile the complete
     evidence and accept no new debt.

Deferred Phase 2 destination slices, not accepted Chapter 18 debt:

- **Partial Money Accounting** — Payments Core owns the durable Capture model,
  partial capture allocation, multiple-refund aggregate, refundable-balance
  locking, and fee-reversal ledger policy. Its entry trigger and required proof
  are recorded in Chapter 18; it is not dependency-ready while V1 promises only
  one full capture and one full refund with no fee postings.
- **Real PSP Refund Execution** — Payments Core owns processor orchestration and
  reconciliation; Security/Compliance owns tokenization, credential, card-data,
  and provider-control review. Its entry trigger and required proof are recorded
  in Chapter 18; it remains outside V1 because THAAP currently simulates rather
  than moves money.

Done when:

- Authorized payment can be captured.
- Captured payment can be refunded.
- Canceled authorization cannot be captured.
- Double capture and over-refund are rejected.
- Ledger transaction groups balance.
- Webhook delivery records are created, signed, retried, and durably explained.
- High-risk money movement and webhook configuration actions create audit logs
  with safe payloads.
- Every documented processor fixture and the unknown-card default are
  replayable through public APIs with processor, fraud-lineage, audit, webhook,
  and ledger evidence.

### Phase 2.5: Previous-Phase Debt Retirement

Phase 3 does not begin while debt originating in Chapters 16 or 19 remains
open. This phase retires the five registered previous-phase debts at their real
payment, security, data-lineage, orchestration, and scheduling boundaries. Debt
IDs remain immutable in the register after resolution.

1. Chapter 21: Previous-Phase Debt Retirement And Zero-Debt Entry Gate.

   Status: complete.

   Proof artifacts:

   - `docs/reviews/CHAPTER_21_INVARIANT_PROOF_MATRIX.md`
   - `docs/reviews/CHAPTER_21_CLOSURE_DOSSIER.md` at completion

   Chapter 21A: Test and dependency lifecycle hygiene.

   - Prevent scheduled webhook polling from surviving a test database's
     lifecycle and emitting false teardown failures.
   - Resolve build/runtime compatibility warnings when THAAP owns the boundary,
     or record executable evidence that a warning is dependency-owned and does
     not weaken payment, fraud, migration, DNS, or test truth.
   - Run every PostgreSQL migration and forced-interleaving test without skips.

   Chapter 21B: Trusted synthetic traffic origin (`TD-0001`).

   - Introduce an authenticated server-assigned traffic-origin boundary.
   - Merchants cannot promote ordinary API traffic into trusted synthetic
     traffic through metadata, headers, or request fields.
   - Propagate immutable origin through PaymentIntent, PaymentAttempt,
     FraudDecision, FeatureSnapshot, webhook events, and generated evidence so
     Phase 3 feature lineage and later datasets can distinguish synthetic from
     ordinary merchant traffic.
   - Prove tenant isolation, spoof resistance, rollback, migration, and
     sanitized API/report behavior.

   Chapter 21C: Resumable synthetic generator runs (`TD-0002`).

   - Add durable operator-local GeneratorRun and scenario-step/checkpoint state
     for the loopback-only generator. The journal owns references to every
     tenant created by the run; public payment objects remain tenant-scoped.
   - A seeded run has stable scenario identity and stable public-API idempotency
     keys across process restart.
   - Resume only incomplete steps; completed steps are reconciled rather than
     replayed into duplicate payment, attempt, fraud, ledger, or webhook truth.
   - Terminal failure and explicit abandonment remain durable and safe; bounded
     concurrency cannot create two active owners for one run.
   - `ER-0024` and `ER-0028` reopened closure: credentialed clients now ignore
     environment proxies as well as redirects, and a sanitized resource ledger
     retains actionable cleanup references after secret-bearing checkpoints
     are erased.

   Chapter 21D: Webhook endpoint creation idempotency (`TD-0005`).

   - Bind endpoint creation to merchant, operation, idempotency key, and
     normalized request identity.
   - Same-key replay returns the original endpoint and usable original
     one-time-secret response; changed requests conflict; cross-tenant key
     reuse remains isolated.
   - Duplicate URL behavior is explicit, audited, and cannot accidentally
     create duplicate active delivery streams after an ambiguous timeout.
   - `ER-0027` added V14 fenced reservation leases: fail-stop `IN_PROGRESS`
     rows become reclaimable, active ownership cannot be stolen, and a
     deterministic PostgreSQL interleaving converges to one endpoint/secret.

   Chapter 21E: Webhook signing-secret rotation overlap (`TD-0003`).

   - Retain current and previous encrypted signing material with explicit
     version and expiry, never raw secret persistence.
   - A delivery already claimed under the previous generation remains
     verifiable for the bounded overlap; new claims use the current generation.
   - Previous material is rejected after expiry, rotation and disable preserve
     lock ordering, and API/audit/log surfaces remain secret-safe.
   - `ER-0025` binds expiry to the exact signature timestamp after dispatch
     preparation. `ER-0026` makes startup inventory every still-decryptable
     current, previous, and endpoint-creation replay ciphertext key version.

   Chapter 21F: Webhook delivery fairness and rate controls (`TD-0004`).

   - Claiming prevents one merchant or endpoint backlog from starving another.
   - Per-merchant and per-endpoint limits are bounded, configurable, and
     observable without high-cardinality or secret-bearing dimensions.
   - Forced PostgreSQL orderings prove fairness controls do not duplicate
     claims, bypass disable, lose lease recovery, or strand due work.

   Chapter 21G: Integrated zero-debt closure.

   - `TD-0001` through `TD-0005` are `RESOLVED` with schema, implementation,
     test, demo, and operational evidence; the debt register has zero
     non-resolved entries.
   - Every Chapter 21 matrix entry passes a plan-to-implementation traceability
     review and a temporally separate hostile failure-sequence review.
   - Fresh H2 and PostgreSQL migrations, complete backend/Python suites,
     synthetic resume evidence, webhook operations evidence, and sanitized
     public-API demos pass.
   - The hostile end-to-end re-review records and resolves `ER-0024` through
     `ER-0028`; full proof passes 240 backend tests and 60 repository Python
     tests.
   - The Chapter 21 closure dossier recommends `MERGE`, and README and
     architecture sources agree. Branch integration into `develop` remains the
     normal post-review Git action and is not part of implementation truth.

Done when:

- No debt originating in a previous phase remains `OPEN` or `IN_PROGRESS`.
- Synthetic origin is authoritative and reaches decision-time lineage.
- Interrupted generator runs resume without duplicate product truth.
- Endpoint creation safely replays after ambiguous client outcomes.
- Signing-secret rotation has an executable old/new verification window.
- Webhook scheduling proves cross-merchant fairness under production-database
  concurrency.
- Test and dependency lifecycle output contains no THAAP-owned false failures.
- Chapter 21's matrix, dossier, learning artifacts, demos, and commit-readiness
  gate all pass.

### Phase 3: Fraud Decision MVP

Entry gate: Chapter 21 and Phase 2.5 must be complete with zero non-resolved
previous-phase debt before Phase 3 implementation begins.

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
- FeatureSnapshot records freshness metadata: computed time, oldest feature
  timestamp, max feature age, freshness status, stale features, and missing
  features.
- FraudDecision records decision latency and feature freshness status.
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
- **Refund Operations And Review Actions** owner slice:
  - Product DRI: **Risk Operations**.
  - Technical DRI: **Payments Core**.
  - Add the constrained refund-reason contract, review authorization policy,
    and audited/idempotent review-to-refund action defined by Chapter 18's
    deferral proof.
- **Dispute Offset Accounting** owner slice:
  - Technical DRI: **Payments Core**.
  - Workflow DRI: **Risk Operations**.
  - Define ordered refund/chargeback accounting and prove that overlapping or
    concurrent outcomes cannot over-credit the customer or double-debit the
    merchant, as required by Chapter 18's deferral proof.
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
- **Developer/API Console Webhook Operations** owner slice:
  - Product DRI: **Developer Experience**.
  - Technical DRI: **Payments Core**.
  - Add merchant-triggered delivery replay, dead-letter inspection, and
    endpoint verification handshakes on the Chapter 21 idempotency, rotation,
    and fairness substrate (`TD-0003`, `TD-0004`, `TD-0005` resolved).
  - Start before shared non-local merchant onboarding, after Chapter 19 proves
    immutable event envelopes, at-least-once delivery, tenant isolation,
    signing, retry, and safe attempt evidence.
  - Completion evidence must prove replay authorization and deduplication,
    verification ownership, rotation-window expiry, auditability, bounded rate
    behavior, safe endpoint-creation retries, and that operational actions never
    mutate payment or fraud truth.
- **Developer Scenario Runner And Traffic-Origin Governance** owner slice
  (`TD-0001`):
  - Primary technical DRI: **Payments Core**.
  - Product/consumer DRI: **Risk Operations**.
  - Extend the Chapter 21 server-assigned, tenant-scoped traffic-origin
    classification into console controls and analytics policy.
  - Only an authenticated scenario-runner/test-mode boundary may assign
    synthetic origin; merchant-supplied metadata is never authoritative.
  - Propagate origin through PaymentIntent, PaymentAttempt, FraudDecision,
    FeatureSnapshot, events, analytics projections, and later dataset joins.

Done when:

- A demo can show not only a risky transaction, but also a risky merchant, a
  card-testing attack, a dispute evidence packet, and a merchant-specific risk
  policy change.
- Analysts can filter by fraud type, merchant risk tier, risk context signal,
  review SLA, and dispute outcome.
- Developer documentation is good enough for a new merchant integration to run
  the simulator without reading source code.
- A shared scenario runner cannot create unclassified synthetic traffic, and
  merchant traffic cannot self-declare a trusted synthetic origin.
- Risk metrics, analyst filters, and downstream dataset builders can include or
  exclude synthetic traffic using server-assigned lineage.

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
- **Synthetic Generator V2 Run Orchestration** owner slice (`TD-0002`
  resolved by the Chapter 21 local-run substrate):
  - Primary technical DRI: **ML/Data Platform**.
  - Payments Core dependency owner: **Payments Core**, for idempotent public
    mutation APIs and trusted traffic-origin propagation.
  - Promote the proven local run journal into scheduled/dataset-scale
    orchestration only when Phase 7 requires shared workers.
  - Preserve stable per-step idempotency, explicit cleanup disposition, and
    duplicate-free payment/fraud/label truth from Chapter 21.
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
- An interrupted generator run can resume or terminate with an explicit cleanup
  disposition, and retries do not duplicate payment, fraud, or label truth.
- Every generated training/evaluation row carries the trusted traffic origin
  introduced by the Phase 4.5 owner slice.

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
- **Webhook Worker Scaling And Egress Hardening** owner slice:
  - Technical DRI: **Payments Core / Platform**.
  - Replace or partition the bounded database worker only when measured queue
    age or delivery volume requires it, and introduce managed egress controls
    before multi-region webhook execution.
  - Completion evidence must prove partition-safe claims, backpressure,
    regional failover, controlled outbound destinations, and preservation of
    Chapter 19 event identity, signature, attempt, and at-least-once semantics.

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
- Run the Senior Staff adversarial review gate from the commit readiness
  checklist: payment/fraud invariants, tenant/security, redaction, transaction
  rollback, observability/failure modes, test evidence, and
  Stripe/Mastercard-style reviewer objections.
- Compare accepted and deferred review items against the implementation.
- Pull forward cheap, valid fixes before handoff.
- Confirm tests cover the riskiest invariants, not only happy paths.
- Record surprises, accepted risks, rejected items, and explicit deferrals in
  the daily note. Every true deferral must name the owner slice/chapter and the
  evidence that would unblock it.

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
- Add a decision-engine interface for confirm orchestration. In Chapter 10 it
  returns an in-memory deterministic stub decision:
  - action: `ALLOW`
  - reason code: `RULES_NOT_ENABLED`
  - no persisted `FraudDecision`
  - no persisted `FeatureSnapshot`
- Apply the `ALLOW` action by moving the PaymentAttempt from `CREATED` to a new
  processor-ready status, `READY_FOR_PROCESSOR`.
- Move the PaymentIntent from `REQUIRES_CONFIRMATION` to `PROCESSING`.
- Keep processor authorization uncalled and unpersisted until Chapter 11.
- Record request ID and trace-ready operation names in logs without sensitive
  data.
- Add tests for success, replay, changed-body conflict, invalid PaymentIntent
  status, wrong-customer PaymentMethod, tenant rejection, no duplicate attempts
  on retry, PaymentAttempt status `READY_FOR_PROCESSOR`, PaymentIntent status
  `PROCESSING`, and no `FraudDecision`, `FeatureSnapshot`, processor
  authorization, or ledger side effects.

Done when:

- Confirm has a stable orchestration shape before processor and durable fraud
  details are filled in.
- The real-time path has clear extension points for features, rules, model
  timeout fallback, and processor simulation.
- Chapter 10 does not write misleading thin fraud lineage using the current
  placeholder `fraud_decisions` table.

Defer:

- Durable `FraudDecision`, `FeatureSnapshot`, risk score, risk level,
  ruleset version, latency, reason codes, and feature freshness metadata to
  Chapter 12.
- Non-`ALLOW` actions to Chapter 12/Phase 3.
- Processor authorization, authorization persistence, processor response codes,
  and final authorization/decline status mapping to Chapter 11.
- Ledger writes until capture/refund work.
- Kafka/outbox until Phase 5; webhook delivery records may remain local
  placeholders.

Learn:

- Transaction boundaries.
- Real-time payment path design.
- Where fraud decisioning fits without making the endpoint untestable.
- Why an interface-level decision stub is safer than persisting incomplete
  fraud lineage.

### Chapter 11: Processor Simulator

Deliver:

- Define `ProcessorAdapter` and `ProcessorAuthorizationResult`.
- Chapter 11 starts only from a PaymentAttempt in `READY_FOR_PROCESSOR` whose
  parent PaymentIntent is `PROCESSING`.
- Implement deterministic simulator outcomes from test card metadata or a
  simulator card catalog.
- Use this initial safe-metadata simulator catalog, derived from stored
  `card_bin` + `card_last4` after PaymentMethod tokenization:
  - `4999990000000001`: approved
  - `4999990000000002`: issuer decline
  - `4999990000000003`: insufficient funds
  - `4999990000000004`: suspected fraud decline
  - `4999990000000005`: CVC failure
  - `4999990000000006`: AVS line1 failure
  - `4999990000000007`: AVS postal failure
  - `4999990000000008`: 3DS required
  - unknown simulator cards, including `4242424242424242`: approved
- Keep the simulator lookup key explicit as `card_bin:card_last4`. This is a
  THAAP simulator catalog convention, not a real PSP routing model.
- Persist `Authorization` records in a dedicated `authorizations` table linked
  to `(merchant_id, payment_attempt_id)`. The authorization record, not the
  nullable `payment_attempts.processor_authorization_id` string alone, is the
  source of truth for processor results.
- Support initial outcomes: approved, issuer decline, insufficient funds,
  suspected fraud decline, CVC failure, AVS line1 failure, AVS postal failure,
  and 3DS required.
- Add PaymentAttempt statuses needed for processor results, at minimum:
  `AUTHORIZED`, `DECLINED`, and `REQUIRES_ACTION`.
- Map approved processor outcomes to:
  - PaymentAttempt `AUTHORIZED`
  - PaymentIntent `REQUIRES_CAPTURE`
  - no capture, ledger, or captured status yet, even when capture method is
    `AUTOMATIC`
- Map issuer decline, insufficient funds, suspected fraud decline, CVC failure,
  AVS line1 failure, and AVS postal failure to:
  - PaymentAttempt `DECLINED`
  - PaymentIntent `REQUIRES_PAYMENT_METHOD`
  - a retryable confirm state where a new idempotency key can create the next
    attempt
- Map 3DS-required outcomes to:
  - PaymentAttempt `REQUIRES_ACTION`
  - PaymentIntent `REQUIRES_ACTION`
  - `three_ds_status` populated with the initial required/pending state
- Persist processor name, processor authorization/reference ID, response code,
  network status, requested amount, authorized amount, currency, verification
  results, non-null 3DS result, raw-safe simulator outcome code, and created
  time.
- Approved outcomes set `authorized_amount` to the requested amount. Declined
  and 3DS-required outcomes set `authorized_amount` to `0` because they have
  not reserved funds.
- Add authorizations through a forward-only Flyway migration; do not mutate an
  already-applied migration.
- Add tests for each deterministic outcome, status mapping, retry after
  retryable decline, no retry while already authorized, and idempotent confirm
  replay after an authorization result exists.
- Add hardening tests proving non-`ALLOW` fraud decisions never call the
  processor, processor exceptions roll back from `PROCESSING`, and unknown
  simulator cards default to approval.

Done when:

- Confirm can produce a durable authorization or durable decline.
- Idempotent confirm replay never creates a second authorization record or
  calls the simulator twice.
- A declined retryable result reopens the PaymentIntent for another confirm
  attempt with a different idempotency key.
- An authorized result does not allow another confirm attempt.
- Processor behavior is replaceable behind an adapter.
- No raw card input is required downstream of PaymentMethod creation.
- Chapter 11 does not persist `FraudDecision` or `FeatureSnapshot`; Chapter 12
  still owns durable fraud lineage, including processor-decline attempts.

Defer:

- Automatic capture, manual capture endpoint behavior, cancel/void, refund, and
  ledger entries to Phase 2.
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
- Add the initial fraud decision reason code for stub decisioning:
  `RULES_NOT_ENABLED`. Keep processor simulator outcome codes on
  `Authorization`, because they are produced after the fraud decision and are
  processor truth, not fraud-decision reason truth.
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
- Create the next detailed chapter plan for Phase 2:
  - capture
  - cancel/void
  - refund
  - ledger invariants
  - webhook delivery retry behavior
  - deterministic processor edge cases
- Run the full backend test suite and commit readiness checklist.

Current completion evidence:

- The clean local database Chapter 13 demo evidence is recorded in
  `docs/learning/daily/2026-06-26-chapter-14-demo.md`.
- The Phase 1 closure chapter plan is recorded in the Phase 1 roadmap section
  above as Chapter 16: Synthetic Scenario Generator V1.
- The next detailed Phase 2 chapter plan is recorded in the Phase 2 roadmap
  section above. It covers capture, cancel/void, refund ledgering, webhook
  delivery retry behavior, and deterministic processor edge cases with
  invariants, required proof, and deferrals.
- Chapter 15 was pulled forward and implemented as capture with balanced ledger
  entries.

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
