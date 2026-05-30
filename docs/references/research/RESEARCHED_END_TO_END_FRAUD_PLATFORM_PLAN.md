# Researched End-to-End Payment Fraud Platform Plan

Date: 2026-05-29

> Reference only. This document is preserved for research traceability and deeper reasoning. For implementation decisions, follow `docs/IMPLEMENTATION_MASTER_PLAN.md`.

## 0. Implementation-Ready Source Of Truth

This section records the final decisions that were later promoted into `docs/IMPLEMENTATION_MASTER_PLAN.md`:

1. Payment processing is first-class scope, not merely a wrapper around fraud detection.
2. V1 is a card-payment simulator only. ACH, SEPA, wallets, local payment methods, payouts, reserves, and real PSP integrations come later.
3. The simulator must teach real payment architecture: PaymentIntent, PaymentAttempt, authorization, capture, cancel/void, refund, disputes/chargebacks, idempotency, webhooks, audit logs, and double-entry ledgering.
4. `REQUEST_3DS` is the canonical step-up action. Do not introduce a separate `STEP_UP` decision enum in implementation.
5. Fraud detection runs inside the payment confirmation lifecycle and must persist an auditable decision for every PaymentAttempt.
6. No raw PAN or CVV is stored. Store only simulated token, card fingerprint, BIN, last4, expiry metadata, card country, funding type, and verification outcomes.
7. GenAI agents remain offline/async only. They inspect, explain, simulate, and recommend; they do not approve, decline, capture, refund, activate rules, or promote models.

## 1. Executive Summary

You are planning to build a Stripe Radar-like payment fraud platform with a GenAI-powered fraud intelligence layer. The documents you provided are useful and directionally strong. The main architectural idea is correct:

1. Keep real-time payment authorization deterministic, low-latency, observable, and auditable.
2. Keep GenAI agents out of the synchronous authorization path.
3. Use asynchronous intelligence workflows for investigation, pattern discovery, model evaluation, and rule recommendation.
4. Treat every payment decision as an auditable object with feature snapshots, model versions, rules fired, reason codes, and final action.
5. Treat every money movement as ledgered, even in a simulation platform.

My recommendation is to implement this in three product horizons:

1. Horizon 1: A working card payment-processing simulator and fraud decision MVP with PostgreSQL, Redis, deterministic rules, manual review, chargebacks/disputes, webhooks, double-entry ledgering, and a simple dashboard.
2. Horizon 2: ML, analytics, graph intelligence, rule simulation, and feedback loops.
3. Horizon 3: GenAI fraud intelligence agents, model governance, production hardening, and scale testing.

The existing 180-day plan is useful, but I would not start with thirteen independent microservices. For learning and execution speed, start with a small number of deployables and strict internal module boundaries. Split into separate services only when latency, ownership, scaling, or failure isolation makes the split valuable.

## 2. Usefulness Of Provided Documents And Links

### 2.1 `stripe_radar_fraud_detection_platform_prompt.md`

Usefulness: Very high.

This is the best source for product intent. It clearly separates the real-time decision path from the offline intelligence path, which is the most important design decision. It also correctly includes rule simulation, graph intelligence, model monitoring, human approval, and analyst workflows.

What I would keep:

- Real-time path: ingestion, feature extraction, ML score, rules, decision, event log.
- Offline path: event lake, analytics, graph store, agents, recommendations, approval workflow.
- Explicit rule that LLMs must not be called in real-time payment decisions.
- Requirement that decisions be explainable and auditable.
- Human approval before deploying rules.

What I would change:

- Do not start with all services as independent deployables.
- Make the first version a modular monolith plus a Python model service.
- Add clear latency budgets per component from day one.
- Add idempotency, ledger, and replay semantics earlier than dashboard polish.
- Add dataset/versioning discipline before advanced models.

### 2.2 `end_to_end_payment_fraud_platform_180_day_implementation_plan.md`

Usefulness: High.

This is a strong implementation skeleton. It already includes a week-by-week path from payment lifecycle to CI/CD. The best parts are the lifecycle orientation, auditability emphasis, manual review loop, and ML/graph/rule simulation phases.

What I would keep:

- PaymentIntent lifecycle.
- Double-entry ledger invariant.
- Kafka event flow.
- Chargeback and manual review feedback loop.
- MLflow/model registry.
- Rule governance and approval workflow.
- OpenTelemetry, Prometheus, Grafana, load testing, and Kubernetes later.

What I would change:

- Compress the number of services in the first 6 to 8 weeks.
- Move auth/security basics earlier, even if simple.
- Treat ClickHouse and Neo4j as Horizon 2 unless you are comfortable with operational overhead.
- Add a proper event schema versioning plan.
- Add explicit learning outcomes per phase.
- Add a local synthetic data generator earlier so every feature can be tested end to end.

### 2.3 `advanced-ai-technology-datasheet.pdf`

Usefulness: Medium to high.

This Mastercard Brighterion datasheet is useful for product benchmarking. It emphasizes what mature fraud platforms sell:

- Fast deployment.
- Increased financial crime detection.
- Lower false positives.
- Explainable scores.
- Rules management.
- Case management.
- Business insights.
- High uptime.
- Low-latency scoring.

Important target benchmark from the datasheet:

- Cloud response time: roughly 100 to 120 ms.
- On-premises response time: less than 10 ms.
- Uptime benchmark: 99.99%.
- Scale benchmark: over 150 billion decisions annually.

How to use it:

- Use it as a product capability benchmark, not as an implementation blueprint.
- Translate it into your own non-functional requirements.
- Make "explainable scores + rules + cases + analytics" a core product shape.

### 2.4 `IMG_20260528_103428.jpg`

Usefulness: Medium.

The image shows an AWS-style GenAI fraud optimization architecture:

- Orchestrator agent.
- Fraud detection agent.
- Model evaluator agent.
- Fraud rules agent.
- Tools for Athena, Neptune, DynamoDB, GNN, XGBoost, and Bedrock.
- Historical transactions in S3.
- Entity graph in Neptune.
- Fraud rules table.

This is useful for the offline intelligence path, especially the pattern:

```text
User or scheduled trigger
  -> Orchestrator
  -> Domain agents
  -> Audited tools
  -> Reports / recommendations
  -> Human approval
```

How to use it:

- Do not copy the AWS stack one-for-one locally.
- Implement the same concept using your local stack first: Postgres, ClickHouse, Neo4j, MLflow, and an LLM abstraction.
- Keep all tools read-only except explicit recommendation/simulation tools.
- Require human approval for any rule activation.

### 2.5 Useful Links RTF

Usefulness: High as a reading list. After deeper review, the ByteByteGo article is especially useful as a secondary synthesis of public Stripe engineering details.

Links found:

- [Stripe Radar](https://stripe.com/in/radar)
- [How Stripe detects fraudulent transactions - ByteByteGo](https://blog.bytebytego.com/p/how-stripe-detects-fraudulent-transactions)
- [Brighterion Transaction Risk Management overview - Corepay](https://corepay.net/articles/what-is-brighterion-transaction-risk-management-by-mastercard/)
- [Mastercard Advanced AI datasheet](https://www.mastercard.com/content/dam/mccom/shared/business/b2b/reports/advanced-ai-technology-datasheet.pdf)
- [How we built it: Stripe Radar](https://stripe.dev/blog/how-we-built-it-stripe-radar)

How to use them:

- Use Stripe's product pages and docs as primary product references.
- Use Stripe engineering blog as engineering inspiration.
- Use Mastercard/Brighterion as benchmarking inspiration.
- Use ByteByteGo as a useful architectural synthesis, but verify important claims against Stripe primary sources where possible.
- Use Corepay as secondary product explanation, not an authoritative implementation spec.

### 2.6 ByteByteGo Stripe Fraud Article Deep Notes

Usefulness: High, with the caveat that it is a secondary article.

Article reviewed:

- [How Stripe Detects Fraudulent Transactions Within 100 ms](https://blog.bytebytego.com/p/how-stripe-detects-fraudulent-transactions)

Important takeaways to incorporate:

1. The latency target is not just "fast"; the article frames Radar as scoring a transaction within roughly 100 ms. Our fraud decision sub-path should therefore have an explicit p95 latency budget and timeout behavior for every component.
2. Fraud is a rare-event classification problem. The article frames online payment fraud as roughly 1 in 1,000 transactions, which reinforces why accuracy is the wrong headline metric.
3. Stripe's model evolution matters as a learning path: logistic regression, then tree-based/deep approaches, then DNN-heavy architectures with embeddings and faster retraining. For this project, we should still start with logistic regression and XGBoost/LightGBM before exploring neural embeddings.
4. Network-level data is a major advantage for Stripe. We cannot reproduce Stripe's global network, but we can simulate a multi-merchant network and build cross-merchant aggregate features.
5. Labels are a product integration problem, not only an ML problem. Chargebacks, disputes, manual reviews, and processor/card-network outcomes must feed the training label pipeline automatically.
6. Feature engineering is part forensics and part experimentation. The platform should support rapid candidate-feature creation, offline evaluation, and promotion into real-time serving.
7. Embeddings are useful for high-cardinality categorical values such as merchant, issuer/BIN, country, device, and time bucket. These belong in Horizon 3 after the baseline ML/data platform is mature.
8. The central business tradeoff is precision vs recall. The platform must let each merchant choose a threshold or risk policy based on fraud cost, margin, lifetime value, and review capacity.
9. Manual review is not merely an operations queue. It is a lever for reshaping the precision/recall tradeoff around borderline transactions.
10. Explainability should be a product surface. Transaction detail should show top fraud factors, related transactions, geolocation mismatch, customer/payment history, and feature/rule/model reasons.
11. Related-transaction search is important. Stripe reportedly uses search tooling to surface related transactions; locally we should add an indexed related-payments query first, and consider OpenSearch/Elasticsearch later.
12. Model rollout must be segment-aware. A model that improves aggregate PR-AUC can still harm a small merchant. Before model promotion, compare old vs new score distributions, block rates, false positive estimates, authorization rates, and review rates by merchant segment.
13. Fraud models drift. Fresh-data retraining should be part of the operating model, not a one-time ML milestone.
14. Blocked payments create measurement ambiguity because their true labels are unobserved. The evaluation plan should explicitly call this counterfactual problem out, even if the first implementation only approximates it with exploration samples and manual review outcomes.

Concrete changes caused by this article:

- Add p95 latency budgets to real-time feature/model/rule paths.
- Add cross-merchant aggregate features to the synthetic data plan.
- Add merchant-specific risk policy and threshold configuration earlier.
- Add related transaction search to the dashboard plan.
- Add segment-level model release checks to model governance.
- Add counterfactual/unknown-outcome handling to model evaluation notes.
- Add embeddings as an advanced, not initial, ML milestone.

### 2.7 AWS / Mastercard Near-Zero Downtime Deployment Article

Usefulness: High for production hardening, deployment architecture, and rule-management availability.

Article reviewed:

- [How Mastercard Achieved Near-Zero Downtime Deployments for Fraud Detection](https://aws.amazon.com/blogs/industries/how-mastercard-achieved-near-zero-downtime-deployments-for-fraud-detection-2/)

Important takeaways to incorporate:

1. Fraud platforms are not only scoring systems; they include customer-facing rule-management applications that must remain available during releases.
2. Blue-green deployment is a strong fit for fraud systems because both backend scoring services and frontend operational consoles need low-risk release paths.
3. Application deployment and infrastructure deployment should be decoupled. Application changes, database changes, and Kubernetes/cloud infrastructure changes should not all ride the same blast radius.
4. Backward compatibility is non-negotiable. Blue and green versions must coexist, which means API contracts, event schemas, rule schemas, and database migrations need expand/contract discipline.
5. Pre-production validation should use the full green environment before traffic promotion. This should include payment confirmation, scoring, rule evaluation, dashboard flows, and rollback checks.
6. Frontend blue-green matters too. Analyst dashboards and rule-management UIs should be promoted with the same care as backend services.
7. The article reports outcomes above 99.999% availability for Brighterion's deployment approach. For our project, this is an aspirational production benchmark, not an MVP target.
8. Real-time rule updates are a competitive capability. The rule registry should support activating safe changes without taking the scoring path down.

Concrete changes caused by this article:

- Add blue-green deployment to Horizon 3 production hardening.
- Add backward-compatible DB and API migration rules to non-negotiable engineering rules.
- Add pre-production validation checklist before promotion.
- Add frontend deployment strategy for the analyst dashboard.
- Add rule-registry hot reload / safe refresh requirement.
- Keep infrastructure and application pipelines separate when CI/CD is introduced.

## 3. Research Synthesis

### 3.1 Stripe Radar Product Lessons

Stripe Radar's public product shape suggests these core ideas:

- Fraud prevention must combine network-scale signals, ML, custom rules, risk insights, and operational review workflows.
- Analyst workflows matter almost as much as scoring because fraud teams need search, explanations, manual review, rule tuning, and feedback loops.
- Risk scoring alone is not the product. The product is decisioning plus operations.

Useful Stripe references:

- [Stripe Radar product page](https://stripe.com/in/radar)
- [Stripe Radar documentation](https://docs.stripe.com/radar)
- [Optimize Radar risk factors](https://docs.stripe.com/radar/optimize-risk-factors)
- [Radar rules](https://docs.stripe.com/radar/rules)
- [Radar rules reference](https://docs.stripe.com/radar/rules/reference)
- [Radar risk settings and controls](https://docs.stripe.com/radar/risk-settings)
- [Radar reviews](https://docs.stripe.com/radar/reviews)
- [Radar risk insights](https://docs.stripe.com/radar/reviews/risk-insights)
- [Radar lists](https://docs.stripe.com/radar/lists)
- [Testing Radar](https://docs.stripe.com/radar/testing)
- [Stripe PaymentIntents](https://docs.stripe.com/payments/payment-intents)
- [Stripe PaymentIntent API object](https://docs.stripe.com/api/payment_intents/object)
- [How we built it: Stripe Radar](https://stripe.dev/blog/how-we-built-it-stripe-radar)

Implication for your build:

- Model your core payment API around PaymentIntent and PaymentAttempt.
- Model fraud output around FraudDecision, DecisionAction, RiskScore, RuleExecutionResult, and ReasonCode.
- Make transaction detail pages explain why a decision happened.
- Make analyst review a first-class workflow, not a later admin screen.

Detailed design lessons from the Radar docs:

- Integration data quality is part of fraud model quality. The system should explicitly collect customer email/name, billing/shipping address, IP address, user agent, checkout URL, device/session data, and merchant metadata where possible.
- A payment can have multiple attempts/scans during a PaymentIntent lifecycle. The design should treat PaymentAttempt as the unit of scoring and audit.
- Risk output should not be a single number only. Stripe separates overall risk level, fraud/dispute score, early-fraud-warning score, bot score, and legacy risk score. Our system should start with one score but keep the schema open for multiple score types.
- Supported actions are `REQUEST_3DS`, `ALLOW`, `BLOCK`, and `REVIEW`; do not create a separate step-up action in implementation.
- Rule ordering matters: request-3DS rules are evaluated before allow/block/review; allow short-circuits block/review; block short-circuits review.
- Rules need precise missing-attribute semantics. A comparison against a missing value should not silently evaluate as true; the rule DSL should support explicit `is_missing`.
- Velocity counters should exclude the current payment being evaluated. Counter window definitions and bucket behavior must be documented because they affect analyst expectations.
- Some attributes are post-authorization, such as CVC/AVS checks. A rule using issuer-returned fields may require authorization first and can temporarily create an authorization before the platform blocks or cancels.
- Lists are first-class rule inputs. The system should support allow/block/review lists for card fingerprints, BINs, countries, IPs, customers, email addresses, email domains, and generic strings.
- List items need author, created time, source, and optional expiration. Allowlists should be time-bounded by default because stale allowlist entries can bypass controls.
- Manual review should support assignment, timeline, approve/refund/refund-and-report-fraud actions, and webhooks/events such as review opened/closed.
- Auth-and-capture review flow matters. If a payment is placed in review with manual capture, approving the review should not automatically capture unless the merchant configured that automation.
- Rule testing/backtesting must show matches across disputes/early fraud warnings, refunded payments, blocked/failed payments, and succeeded payments. Allow-rule testing needs special caution because previously blocked payments have unknown true outcomes.
- Risk insights should show top fraud factors, factor ratios, related payments, geography mismatch, customer/payment history, and data-quality gaps.

### 3.2 Mastercard Brighterion Product Lessons

Brighterion's public datasheet emphasizes:

- Explainable models.
- Rules management.
- Case management.
- Business insights.
- Low-latency scoring.
- High uptime.
- Very high transaction volume.

Implication for your build:

- Your first demo should show: a payment, risk score, rules fired, final decision, and explanation.
- Your second demo should show: manual review, chargeback label, and a changed fraud metric.
- Your third demo should show: rule simulation before activation.

### 3.3 Agentic Fraud Intelligence Lessons

The AWS-style reference image is useful, but the key principle is provider-agnostic:

```text
Agents should not own production decisions.
Agents should inspect, explain, simulate, recommend, and generate reports.
Humans approve operational changes.
Every agent tool call should be logged.
```

Useful implementation rule:

- Agents can call tools like `search_transactions`, `get_graph_neighbors`, `run_rule_simulation`, and `get_model_metrics`.
- Agents should produce structured outputs, not free-form operational commands.
- Rule recommendations must be validated by a parser, simulated historically, and routed for approval.

### 3.4 ML/Fraud Modeling Lessons

Fraud ML is usually imbalanced, delayed-label, adversarial, and cost-sensitive.

Core modeling lessons:

- Accuracy is a bad primary metric.
- Use precision, recall, PR-AUC, ROC-AUC, false positive rate, chargeback rate, fraud capture rate, and dollar-weighted impact.
- Split data by time, not random row split.
- Track feature freshness and label delay.
- Store model version and feature snapshot for every prediction.
- Start with logistic regression as a sanity baseline, then XGBoost or LightGBM.
- SHAP-style explanations are useful, but they need to be mapped into domain reason codes.
- Evaluate model candidates by merchant and segment, not only global metrics.
- Track unknown outcomes for transactions that were blocked, stepped up, or never completed.
- Consider high-cardinality embeddings only after the core tabular ML loop is stable.

### 3.5 Graph Fraud Lessons

Graph is useful when fraud appears as shared infrastructure:

- Same device used by many customers.
- Same IP used by many risky transactions.
- Same card fingerprint used across accounts.
- Shared phone, email domain, address, or shipping address.
- Fraud-labeled customer within one or two hops.
- Merchant or customer cluster with abnormal chargeback density.

Graph should initially support investigation and feature extraction. Do not make graph the only decision source.

## 4. Target Product Scope

### 4.1 End State

The end state is a payment and fraud platform that supports:

1. Merchant onboarding.
2. Customer creation.
3. Payment method tokenization simulation.
4. PaymentIntent creation.
5. Payment confirmation.
6. Real-time fraud evaluation.
7. Processor authorization simulation.
8. Authorization, capture, refund, void, and chargeback flows.
9. Double-entry ledger.
10. Deterministic rules engine.
11. ML risk scoring.
12. Feature store and velocity counters.
13. Manual review queue.
14. Rule management and approval.
15. Rule simulation and backtesting.
16. Analytics dashboard.
17. Graph investigation.
18. Model monitoring.
19. Agentic fraud intelligence.
20. Observability and security.

V1 scope is intentionally narrower and decision-complete:

- Card payments only.
- Full simulator, no real money movement.
- No external PSP integration in V1.
- Manual and automatic capture.
- 3DS simulation through `REQUEST_3DS`.
- CVC and AVS simulation.
- Refunds, voids/cancels, disputes/chargebacks, webhooks, audit logs, idempotency, and double-entry ledger.

V1 public API surface:

- `POST /merchants`
- `POST /customers`
- `POST /payment_methods`
- `POST /payment_intents`
- `POST /payment_intents/{id}/confirm`
- `POST /payment_intents/{id}/capture`
- `POST /payment_intents/{id}/cancel`
- `POST /refunds`
- `POST /chargebacks`
- `GET /payment_intents/{id}`
- `GET /payment_attempts/{id}`
- `GET /ledger/entries`
- `POST /webhook_endpoints`
- `GET /webhook_deliveries`

V1 deterministic processor simulator outcomes:

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

### 4.2 Recommended Initial Deployables

Start with these deployables:

1. `payments-api`
   - Java 21, Spring Boot.
   - Owns payment lifecycle, fraud decision orchestration, rules, review, chargebacks, and ledger in early phases.
   - Internally organized into modules.

2. `model-serving-service`
   - Python, FastAPI.
   - Serves baseline model predictions.
   - Returns score, model version, top factors, and timeout-safe fallback.

3. `analytics-worker`
   - Java or Python.
   - Consumes Kafka events and writes analytics projections.
   - Can start inside `payments-api` and be split later.

4. `fraud-intelligence-agent-service`
   - Python or TypeScript.
   - Added later.
   - Calls audited tools for analytics, graph, model metrics, and rule simulation.

5. `analyst-dashboard-ui`
   - React + TypeScript.

Infrastructure:

- PostgreSQL from day one.
- Redis from week 3 or 4.
- Kafka from week 4 to 6.
- ClickHouse from analytics phase.
- Neo4j from graph phase.
- S3-compatible object storage from ML/data phase.

### 4.3 Why Not Start With Many Microservices

Starting with many services teaches distributed systems, but it slows learning and makes every feature expensive. For this project, the key learning is product architecture plus ML/fraud feedback loops. A modular monolith preserves clean boundaries while keeping integration friction low.

Split a module into a service only when one of these becomes true:

- It has different latency/SLO needs.
- It needs different scaling.
- It has a different data ownership boundary.
- It needs independent deployment.
- It is becoming operationally risky inside the main app.

## 5. System Architecture

### 5.1 Real-Time Decision Path

The synchronous path:

```text
POST /payment_intents/{id}/confirm
  -> load payment intent
  -> create payment attempt
  -> extract real-time features
  -> read Redis velocity counters
  -> call model service with timeout
  -> evaluate active rules
  -> combine signals in decision engine
  -> return ALLOW / BLOCK / REQUEST_3DS / REVIEW
  -> if ALLOW, call processor adapter
  -> if REQUEST_3DS, move payment to REQUIRES_ACTION
  -> if REVIEW, create manual review case
  -> if BLOCK, fail or cancel the attempt before authorization
  -> persist authorization result
  -> publish events
```

Latency budget for MVP:

- API validation and idempotency: 10 ms.
- Database reads/writes: 30 ms.
- Feature extraction and Redis counters: 25 ms.
- Model scoring: 75 ms timeout.
- Rules and decision engine: 10 ms.
- Processor simulation: 50 ms.
- Safety buffer: 50 ms.
- Initial target: under 250 ms p95 locally for the full confirm path excluding cold starts.

Production-style target:

- Fraud decision sub-path under 100 ms p95.
- Full authorization path under 300 ms p95, excluding real external processor behavior.

### 5.2 Offline Intelligence Path

The asynchronous path:

```text
Payment events
Fraud decision events
Review outcome events
Chargeback events
Model prediction logs
  -> Kafka
  -> PostgreSQL audit tables
  -> ClickHouse analytics
  -> Neo4j entity graph
  -> object storage training datasets
  -> ML training/evaluation
  -> rule simulation
  -> agent reports and recommendations
  -> human approval workflow
```

### 5.3 Data Store Responsibilities

PostgreSQL:

- Source of truth for transactional state.
- Payment intents, attempts, authorizations, captures, refunds.
- Fraud decisions, rules, reviews, chargebacks, labels.
- Audit logs.
- Ledger entries.

Redis:

- Idempotency acceleration.
- Velocity counters.
- Temporary feature cache.
- Rate limits.

Kafka:

- Domain event bus.
- Analytics ingestion.
- Graph ingestion.
- Replayable integration boundary.

ClickHouse:

- High-volume analytical queries.
- Dashboard metrics.
- Rule performance.
- Model score distribution.
- Backtesting inputs/outputs.

Neo4j:

- Entity graph and relationship queries.
- Fraud ring investigation.
- Graph-derived features.

Object storage:

- Raw event archive.
- Training datasets.
- Model artifacts.
- Backtest results.

MLflow:

- Experiment tracking.
- Model registry.
- Model version metadata.

## 6. Core Domain Model

### 6.1 Payment Domain

`Merchant`

- `id`
- `name`
- `status`
- `riskTier`
- `createdAt`

`Customer`

- `id`
- `merchantId`
- `emailHash`
- `phoneHash`
- `createdAt`

`PaymentMethod`

- `id`
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

V1 must never persist raw PAN or CVV. Test card numbers are accepted at API boundaries only long enough to produce a simulated token/fingerprint and deterministic processor behavior.

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

V1 statuses:

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

`LedgerEntry`

- `id`
- `transactionGroupId`
- `account`
- `direction`
- `amount`
- `currency`
- `createdAt`

Invariant:

```text
For each transactionGroupId:
sum(debits) == sum(credits)
```

V1 ledger accounts:

- `CUSTOMER_RECEIVABLE`
- `PLATFORM_CLEARING`
- `MERCHANT_PAYABLE`
- `FEE_REVENUE`
- `REFUND_LIABILITY`
- `DISPUTE_LOSS`

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

### 6.2 Fraud Domain

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

`FraudLabel`

- `id`
- `paymentAttemptId`
- `source`
- `label`
- `confidence`
- `labelTime`

## 7. Decision Engine Design

### 7.1 Inputs

The decision engine receives:

- Feature snapshot.
- ML score.
- ML reason factors.
- Rules fired.
- Graph risk signals, once available.
- Merchant risk policy.
- Payment amount and currency.
- Customer history.

### 7.2 Output Actions

Use a small, stable action enum:

- `ALLOW`
- `BLOCK`
- `REQUEST_3DS`
- `REVIEW`

### 7.3 Decision Priority

Recommended MVP ordering:

1. Compliance and request sanity checks.
2. `REQUEST_3DS` rules.
3. `ALLOW` rules.
4. `BLOCK` rules.
5. `REVIEW` rules.
6. ML thresholds and merchant risk policy.
7. Default `ALLOW`.

Rule action ordering must follow the Radar-style behavior documented earlier: `REQUEST_3DS`, then `ALLOW`, then `BLOCK`, then `REVIEW`. `ALLOW` short-circuits block/review rules, and `BLOCK` short-circuits review rules.

### 7.4 Risk Levels

Example:

- `LOW`: score < 0.30.
- `MEDIUM`: 0.30 to 0.65.
- `HIGH`: 0.65 to 0.85.
- `CRITICAL`: > 0.85.

These should be configuration, not hard-coded forever.

## 8. Rules Engine Design

### 8.1 MVP Rule Language

Do not start with arbitrary JavaScript or untrusted dynamic code.

Use a constrained expression format:

```json
{
  "all": [
    { "field": "amount", "op": "gte", "value": 50000 },
    { "field": "velocity.customer_1h_count", "op": "gte", "value": 5 },
    { "field": "card_country", "op": "neq", "valueFrom": "billing_country" }
  ]
}
```

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
- `contains`

Supported composition:

- `all`
- `any`
- `not`

### 8.2 Rule Lifecycle

Statuses:

- `DRAFT`
- `SIMULATING`
- `PENDING_APPROVAL`
- `ACTIVE`
- `CANARY`
- `DISABLED`
- `ARCHIVED`

Activation flow:

```text
Draft rule
  -> syntax validation
  -> historical simulation
  -> impact report
  -> reviewer approval
  -> canary
  -> active
```

### 8.3 Rule Simulation Metrics

For a candidate rule, show:

- Transactions matched.
- Fraud transactions matched.
- Legitimate transactions matched.
- Fraud dollars captured.
- Legitimate dollars blocked.
- Precision.
- Recall contribution.
- False positive rate.
- Estimated operational review load.
- Top merchants affected.
- Example matched transactions.

## 9. Feature Engineering Plan

### 9.1 Real-Time Features

Payment:

- Amount.
- Currency.
- Capture method.
- Payment method age.
- Card BIN country.
- Billing country.
- Shipping country.

Customer:

- Account age.
- Prior successful payments.
- Prior declines.
- Prior reviews.
- Prior chargebacks.

Velocity:

- Customer attempts in 5 minutes, 1 hour, 24 hours.
- Card fingerprint attempts in 5 minutes, 1 hour, 24 hours.
- IP attempts in 5 minutes, 1 hour, 24 hours.
- Device attempts in 5 minutes, 1 hour, 24 hours.
- Merchant attempts in 5 minutes, 1 hour, 24 hours.

Behavioral:

- Amount deviation from customer's historical median.
- Number of distinct cards per customer.
- Number of distinct customers per card fingerprint.
- Country mismatch flags.

Graph, later:

- Fraud-labeled neighbors within one hop.
- Fraud-labeled neighbors within two hops.
- Shared device count.
- Shared IP count.
- Connected component risk score.

### 9.2 Feature Store Strategy

MVP:

- Compute features inside the app.
- Store every feature snapshot as JSONB in PostgreSQL.
- Use Redis for velocity counters.

Horizon 2:

- Add offline feature generation from events.
- Create training dataset snapshots.
- Add feature versioning.

Horizon 3:

- Adopt Feast or a custom online/offline feature store if complexity justifies it.

## 10. ML Plan

### 10.1 Dataset

Start with synthetic data, then use generated labels from simulated chargebacks and manual review outcomes.

Training row:

- One row per payment attempt.
- Label: fraud or legitimate.
- Event time: payment attempt time.
- Label time: review or chargeback time.
- Features: only features known at decision time.

Avoid leakage:

- Do not train on features created after the payment.
- Do not random split when labels are time dependent.
- Do not include future chargeback-derived fields as real-time features.

### 10.2 Models

Build in this order:

1. Logistic regression baseline.
2. XGBoost or LightGBM.
3. Calibrated probability model.
4. Optional graph-derived features.
5. Optional GNN experiment only after graph data is mature.

### 10.3 Metrics

Primary:

- PR-AUC.
- Recall at fixed false positive rate.
- Precision at manual review capacity.
- Dollar-weighted fraud capture.
- False positive dollar impact.

Secondary:

- ROC-AUC.
- Calibration.
- Feature drift.
- Score distribution drift.
- Segment metrics by merchant, country, BIN, and amount band.

### 10.4 Model Serving Contract

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

Timeout strategy:

- Model timeout should not crash payment confirmation.
- If model service times out, decision engine uses rules-only fallback and records `MODEL_TIMEOUT`.

## 11. Graph Intelligence Plan

### 11.1 Graph Entities

Nodes:

- Customer.
- Device.
- IP.
- CardFingerprint.
- EmailHash.
- PhoneHash.
- AddressHash.
- Merchant.
- PaymentAttempt.
- Chargeback.
- FraudCase.

Relationships:

- Customer `USED` Device.
- Customer `USED` CardFingerprint.
- Customer `USED` IP.
- PaymentAttempt `FOR` Merchant.
- PaymentAttempt `BY` Customer.
- PaymentAttempt `WITH` CardFingerprint.
- PaymentAttempt `FROM` IP.
- Chargeback `AGAINST` PaymentAttempt.
- FraudCase `LABELS` Customer or PaymentAttempt.

### 11.2 First Graph Queries

1. Customers sharing a device with fraud-labeled customers.
2. Cards used by multiple customers in a short time window.
3. IPs with high distinct customer count and high decline rate.
4. Two-hop fraud proximity from customer to known fraud cluster.
5. Merchant-level cluster of high chargeback density.

### 11.3 Graph Output

Graph service should return:

- Risk score contribution.
- Neighbor counts.
- Path explanations.
- Example linked entities.
- Query latency.

Do not return unexplained graph scores.

## 12. GenAI Fraud Intelligence Plan

### 12.1 Correct Agent Role

Agents should:

- Investigate.
- Summarize.
- Explain.
- Recommend.
- Generate candidate rules.
- Run simulations through tools.
- Produce analyst reports.

Agents should not:

- Approve or decline live payments.
- Activate rules without human approval.
- Modify model production status directly.
- Query raw sensitive data unless masked.

### 12.2 Agents

`FraudIntelligenceOrchestrator`

- Accepts user or scheduled investigation request.
- Chooses tools and specialist agents.
- Produces final structured report.

`FraudPatternDetectionAgent`

- Searches suspicious clusters.
- Uses analytics and graph tools.
- Produces pattern candidates.

`ModelEvaluatorAgent`

- Reviews false positives and false negatives.
- Checks drift and segment performance.
- Recommends feature or training improvements.

`RuleRecommendationAgent`

- Converts patterns into candidate rules.
- Validates rule syntax.
- Runs backtests.
- Sends rule to approval workflow.

`FraudExplanationAgent`

- Explains an individual transaction to an analyst.
- Uses only stored facts: features, rules, model reasons, graph links, and labels.

### 12.3 Agent Tool Contracts

Tools should be narrow and auditable:

- `search_transactions(filters)`
- `get_transaction_detail(paymentAttemptId)`
- `get_rule_performance(ruleId, timeWindow)`
- `simulate_rule(candidateRule, timeWindow)`
- `get_model_metrics(modelVersion, timeWindow)`
- `get_false_positive_examples(filters)`
- `get_false_negative_examples(filters)`
- `get_graph_neighbors(entityId, depth)`
- `create_rule_recommendation(candidateRule, simulationId)`

Every tool call should log:

- Agent name.
- User or scheduler.
- Input parameters.
- Output hash or summary.
- Duration.
- Data sensitivity class.

### 12.4 Structured Agent Output

Rule recommendation output should be structured:

```json
{
  "recommendationType": "NEW_RULE",
  "title": "High velocity new customer high amount rule",
  "candidateRule": {
    "all": [
      { "field": "customer_age_days", "op": "lt", "value": 2 },
      { "field": "amount", "op": "gte", "value": 50000 },
      { "field": "ip_velocity_1h", "op": "gte", "value": 5 }
    ]
  },
  "proposedAction": "REVIEW",
  "evidence": [],
  "simulationId": "sim_123",
  "risks": [],
  "approvalRequired": true
}
```

## 13. Dashboard Plan

### 13.1 Pages

1. Overview
   - Payment volume.
   - Approval rate.
   - Decline rate.
   - Manual review rate.
   - Chargeback rate.
   - Fraud dollars prevented.
   - False positive estimate.

2. Transactions
   - Search and filters.
   - Risk level.
   - Decision action.
   - Merchant.
   - Customer.
   - Amount.
   - Timestamp.

3. Transaction Detail
   - Payment lifecycle.
   - Fraud score.
   - Rules fired.
   - Feature snapshot.
   - Graph signals.
   - Explanation.
   - Events timeline.

4. Manual Review Queue
   - Assigned cases.
   - SLA.
   - Risk reasons.
   - Approve/decline workflow.

5. Rules
   - Rule list.
   - Rule editor.
   - Version history.
   - Approval flow.
   - Canary/rollback.

6. Rule Simulation
   - Candidate rule input.
   - Historical match results.
   - Business impact.
   - Sample transactions.

7. Model Performance
   - Score distribution.
   - PR-AUC.
   - Segment metrics.
   - Drift.
   - False positives/false negatives.

8. Graph Investigation
   - Entity search.
   - Relationship graph.
   - Fraud proximity.
   - Cluster explanation.

9. Agent Intelligence
   - Investigation requests.
   - Agent reports.
   - Recommendations.
   - Approval handoff.

### 13.2 Dashboard Design Principle

This should feel like an operational fraud console, not a marketing page. Prioritize dense tables, filters, timelines, explanations, and analyst actions.

## 14. Security And Compliance Plan

### 14.1 Data Protection

- Never store raw card numbers.
- Use simulated tokens and fingerprints.
- Hash email, phone, IP where possible.
- Mask last4, BIN, country, and non-sensitive metadata for display.
- Keep audit logs immutable in spirit.
- Separate roles for admin, analyst, viewer, and model operator.

### 14.2 API Security

- Add auth early, even if local JWT first.
- Require idempotency key for mutating payment APIs.
- Validate all request bodies.
- Use request IDs and trace IDs.
- Rate-limit sensitive endpoints.

### 14.3 Agent Security

- No direct SQL from LLMs.
- No raw PII in prompts.
- Tool allowlist only.
- Structured outputs.
- Tool-level authorization.
- Human approval for rule/model changes.

## 15. Observability Plan

### 15.1 Technical Metrics

- API latency p50/p95/p99.
- Error rate.
- Database latency.
- Redis latency.
- Kafka publish/consume lag.
- Model service latency.
- Rules engine latency.
- Decision action distribution.

### 15.2 Fraud Metrics

- Approval rate.
- Decline rate.
- Manual review rate.
- Chargeback rate.
- Fraud capture rate.
- False positive rate.
- Rule hit rate.
- Rule precision.
- 3DS request rate.
- Model score distribution.

### 15.3 Trace Requirements

One trace should connect:

```text
payment confirmation
  -> feature extraction
  -> model score
  -> rule evaluation
  -> fraud decision
  -> processor authorization
  -> event publishing
```

## 16. Detailed Build And Learn Roadmap

### Phase 0: Setup And Architecture Baseline

Goal:

- Create the repo, local dev environment, architecture decision records, and service boundaries.

Learn:

- Payment lifecycle basics.
- Fraud decisioning concepts.
- Spring Boot project structure.
- Docker Compose for local infrastructure.
- Domain modeling and ADRs.

Build:

1. Create monorepo structure.
2. Add `payments-api` Spring Boot app.
3. Add `model-serving-service` FastAPI skeleton.
4. Add `analyst-dashboard-ui` React skeleton.
5. Add Docker Compose for PostgreSQL and Redis first.
6. Add ADRs:
   - Real-time vs offline path.
   - Why no LLM in authorization.
   - Modular monolith first.
   - Card-only simulator first.
   - Double-entry ledger from the first money-movement phase.
   - Data store responsibilities.
7. Add OpenAPI skeleton.
8. Add health endpoints.

Verify:

- Apps start locally.
- Health checks work.
- Database migrations run.
- README explains local startup.

### Phase 1: PaymentIntent MVP

Goal:

- Implement the payment lifecycle up to confirmation without real fraud logic.

Learn:

- REST API design.
- Idempotency.
- State machines.
- PostgreSQL transactions.
- Flyway migrations.

Build:

1. Merchant seed data.
2. Customer creation.
3. Payment method creation with simulated token/fingerprint.
4. `POST /payment_intents`.
5. `POST /payment_intents/{id}/confirm`.
6. PaymentAttempt creation.
7. Basic status transitions.
8. Idempotency table and request replay behavior.
9. API validation and error model.

Verify:

- Repeating a request with same idempotency key returns same result.
- Invalid status transition fails.
- Integration tests cover create, attach, confirm.

### Phase 2: Processor Simulation And Ledger

Goal:

- Add authorization, capture, refund, void, and double-entry ledger.

Learn:

- Processor adapter pattern.
- Financial state transitions.
- Ledger invariants.
- Transactional consistency.

Build:

1. Processor simulator.
2. Authorization records.
3. Capture endpoint.
4. Refund endpoint.
5. Void/cancel endpoint.
6. Ledger accounts:
   - Customer receivable.
   - Merchant payable.
   - Platform clearing.
   - Fee revenue.
   - Refund liability.
   - Dispute loss.
7. Ledger invariant checks.
8. Webhook endpoint and webhook delivery tables.
9. Webhook signing-secret simulation and retry metadata.

Verify:

- Authorized payment can be captured.
- Captured payment can be refunded.
- Ledger group balances to zero.
- Invalid double capture is rejected.
- Webhook delivery records are created for payment lifecycle events.

### Phase 3: Fraud Decision Stub

Goal:

- Insert a fraud decision step into confirmation.

Learn:

- Decision orchestration.
- Audit object design.
- Reason codes.
- Latency measurement.

Build:

1. FraudDecision table.
2. FeatureSnapshot table.
3. Decision engine interface.
4. Static initial decision rules:
   - `ALLOW` low amount.
   - `REVIEW` suspicious amount.
   - `BLOCK` blocked test card.
   - `REQUEST_3DS` for configured test card.
5. Persist decision for every attempt.
6. Return decision in confirm response.

Verify:

- Every attempt has a decision.
- Decision includes reason codes.
- Confirmation respects `BLOCK`, `REVIEW`, and `REQUEST_3DS` actions.

### Phase 4: Feature Extraction And Redis Velocity

Goal:

- Build real-time features and velocity counters.

Learn:

- Feature engineering.
- Redis counters and TTLs.
- Feature versioning.
- Leakage prevention.

Build:

1. Feature extractor module.
2. Feature schema version `features_v1`.
3. Redis counters:
   - customer 5m/1h/24h.
   - card 5m/1h/24h.
   - IP 5m/1h/24h.
   - merchant 5m/1h/24h.
4. Feature snapshot persistence.
5. Test data generator for varied fraud patterns.

Verify:

- Velocity features update correctly.
- Feature snapshot has only decision-time fields.
- Redis TTL behavior is tested.

### Phase 5: Rules Engine MVP

Goal:

- Implement a safe deterministic rules engine.

Learn:

- DSL design.
- Expression evaluation.
- Rule governance.
- Testing rule behavior.

Build:

1. Rule JSON DSL.
2. Rule parser and validator.
3. Rule evaluation engine.
4. RuleExecutionResult persistence.
5. Rule priority/action handling.
6. Initial rule admin APIs.
7. Draft/active/disabled statuses.

Verify:

- Rule parser rejects unknown fields/operators.
- Rules are deterministic.
- Rule result audit is persisted.
- Rule latency is measured.

### Phase 6: Kafka Events And Analytics Foundation

Goal:

- Publish domain events and create analytics projections.

Learn:

- Event-driven architecture.
- Outbox pattern.
- Event schema versioning.
- Replayability.

Build:

1. Domain events:
   - `payment_intent.created`
   - `payment_attempt.created`
   - `fraud_decision.created`
   - `authorization.created`
   - `capture.created`
   - `refund.created`
   - `payment_intent.canceled`
   - `chargeback.created`
   - `review.opened`
   - `review.closed`
   - `webhook.delivery_succeeded`
   - `webhook.delivery_failed`
2. Outbox table.
3. Kafka publisher.
4. Consumer skeleton.
5. Local event replay command.
6. Analytics projection tables in PostgreSQL first.

Verify:

- Events are published after DB commit.
- Consumer can be restarted safely.
- Event replay rebuilds projection.

### Phase 7: Manual Review And Chargebacks

Goal:

- Create feedback loop from human decisions and chargebacks.

Learn:

- Fraud operations workflow.
- Label generation.
- Case management.
- Delayed feedback.

Build:

1. ManualReviewCase lifecycle.
2. Analyst decision APIs.
3. Review notes and audit logs.
4. Chargeback ingestion API.
5. FraudLabel table.
6. Label creation from reviews and chargebacks.
7. Events for review/chargeback outcomes.

Verify:

- Manual review case opens from fraud decision.
- Analyst decision updates payment outcome where appropriate.
- Chargeback creates fraud label.
- Labels are linked to attempts.

### Phase 8: Dashboard MVP

Goal:

- Build the first usable analyst experience.

Learn:

- React app structure.
- TanStack Query.
- Operational UI design.
- Fraud explanation UX.

Build:

1. Overview dashboard.
2. Transaction search.
3. Transaction detail.
4. Manual review queue.
5. Rule list.
6. Basic rule editor.
7. Chargeback list.

Verify:

- Analyst can investigate a transaction.
- Analyst can resolve a review.
- Analyst can see why a decision happened.

### Phase 9: ML Baseline

Goal:

- Train and serve the first fraud model.

Learn:

- Imbalanced classification.
- Time-based splitting.
- Model evaluation.
- MLflow.
- SHAP or feature contribution explanations.

Build:

1. Synthetic data generator.
2. Training dataset builder.
3. Time-based train/validation/test split.
4. Logistic regression baseline.
5. XGBoost or LightGBM model.
6. Evaluation report.
7. MLflow tracking.
8. FastAPI scoring endpoint.
9. Integration with decision engine.

Verify:

- Model version is stored on each prediction.
- Model timeout fallback works.
- Metrics are reproducible.
- No future leakage in features.

### Phase 10: ClickHouse Analytics

Goal:

- Move high-volume analytics and backtesting data into ClickHouse.

Learn:

- Columnar analytics.
- Data modeling for dashboards.
- Aggregations.
- Analytics-performance tradeoffs.

Build:

1. ClickHouse Docker Compose service.
2. Transaction analytics table.
3. Fraud decision analytics table.
4. Rule execution analytics table.
5. Model prediction analytics table.
6. Kafka/worker ingestion.
7. Dashboard query APIs backed by ClickHouse.

Verify:

- Analytics results match source-of-truth samples.
- Dashboard queries are fast.
- Backfill works.

### Phase 11: Rule Simulation And Approval

Goal:

- Simulate candidate rules against history before activation.

Learn:

- Backtesting.
- Business impact metrics.
- Rule governance.
- Approval workflows.

Build:

1. Rule simulation service/module.
2. Historical feature replay.
3. Candidate rule validation.
4. Simulation result persistence.
5. Business impact report.
6. Rule approval workflow.
7. Canary and rollback states.

Verify:

- Rule cannot become active without approval.
- Simulation shows false positive estimate.
- Activation creates audit log.
- Rollback works.

### Phase 12: Graph Intelligence

Goal:

- Add graph-based fraud investigation and graph features.

Learn:

- Entity resolution.
- Graph modeling.
- Cypher queries.
- Fraud ring detection.

Build:

1. Neo4j Docker Compose service.
2. Graph schema constraints.
3. Graph event consumer.
4. Customer/device/IP/card/merchant graph.
5. Fraud-labeled node marking.
6. Graph query API.
7. Graph features for decision engine.
8. Graph investigation dashboard.

Verify:

- Known synthetic fraud rings are detected.
- Graph query latency is bounded.
- Transaction detail explains graph risk.

### Phase 13: Model Monitoring

Goal:

- Monitor model health and fraud outcomes.

Learn:

- Drift detection.
- Segment performance.
- Delayed labels.
- Model governance.

Build:

1. Prediction logging.
2. Score distribution charts.
3. Feature distribution logging.
4. Segment metrics.
5. Drift scripts.
6. False positive/false negative views.
7. Model registry promotion states.

Verify:

- Metrics by model version are visible.
- Drift reports run.
- Model promotion requires recorded evaluation.

### Phase 14: Fraud Intelligence Agents

Goal:

- Add GenAI-powered investigation and recommendation safely.

Learn:

- Tool calling.
- Structured outputs.
- Agent orchestration.
- Prompt safety.
- Human-in-the-loop design.

Build:

1. Agent service skeleton.
2. Tool registry.
3. Tool audit log.
4. Transaction analytics tool.
5. Graph query tool.
6. Model metrics tool.
7. Rule simulation tool.
8. Fraud pattern agent.
9. Model evaluator agent.
10. Rule recommendation agent.
11. Explanation agent.
12. Agent report UI.

Verify:

- Agents cannot activate rules.
- Every tool call is logged.
- Rule recommendations are structured and validated.
- Human approval handoff works.

### Phase 15: Security, Reliability, And Scale

Goal:

- Harden the platform like a production system.

Learn:

- RBAC.
- Secrets.
- Observability.
- Load testing.
- Resilience.
- Deployment.

Build:

1. Spring Security.
2. Role-based access control.
3. Sensitive data masking.
4. OpenTelemetry traces.
5. Prometheus metrics.
6. Grafana dashboards.
7. Structured logs.
8. Resilience4j timeouts/retries.
9. Load tests at 100, 500, and 1000 TPS.
10. Dockerfiles.
11. CI pipeline.
12. Kubernetes/Helm later.
13. Blue-green deployment strategy for backend services.
14. Blue-green or preview deployment strategy for dashboard UI.
15. Separate application and infrastructure pipelines.
16. Pre-production green-environment validation before traffic promotion.
17. Rollback playbooks for app, model, rule, and database changes.

Verify:

- p95 latency target is measured.
- Error budget dashboard exists.
- Load test report exists.
- Security review checklist passes.
- Blue and green versions can coexist.
- A release candidate can be validated before traffic switch.
- Rollback works without schema or event compatibility failures.

## 17. First 14-Day Execution Plan

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

### Day 3: Spring Boot Foundation

Deliver:

- `payments-api`.
- Error response format.
- Validation.
- Request ID filter.
- Actuator.

Learn:

- Spring Boot 3 conventions.
- API error handling.

### Day 4: Database Migrations

Deliver:

- Flyway setup.
- Merchant, customer, payment method, payment intent tables.

Learn:

- Migration discipline.
- Relational modeling.

### Day 5: Merchant And Customer APIs

Deliver:

- Seed merchant.
- Create customer endpoint.
- Create payment method endpoint.

Learn:

- Entity boundaries.
- Sensitive data simulation.

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

- Staff-level habit: weekly design review.

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
- Stub approval.

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

## 18. Recommended Repository Structure

```text
payments/
  docs/
    adr/
    architecture/
    references/
  services/
    payments-api/
    model-serving-service/
    fraud-intelligence-agent-service/
    analytics-worker/
  apps/
    analyst-dashboard-ui/
  infra/
    docker-compose.yml
    postgres/
    redis/
    kafka/
    clickhouse/
    neo4j/
  datasets/
    synthetic/
  scripts/
    dev/
    load-test/
    data-generation/
```

## 19. Acceptance Criteria By Horizon

### Horizon 1 Acceptance Criteria

- You can create a PaymentIntent.
- You can confirm a payment.
- Every confirmation creates a PaymentAttempt and FraudDecision.
- Rules can `ALLOW`, `BLOCK`, `REQUEST_3DS`, or `REVIEW`.
- Redis velocity features affect decisions.
- Manual reviews can create labels.
- Chargebacks can create fraud labels.
- Dashboard shows transactions, decisions, reviews, and rules.

### Horizon 2 Acceptance Criteria

- ML model trains from historical attempts.
- Model service scores real-time attempts.
- Every prediction stores model version and reasons.
- Rule simulation works on historical data.
- ClickHouse powers analytics.
- Neo4j identifies synthetic fraud rings.
- Dashboard shows graph and model performance.

### Horizon 3 Acceptance Criteria

- Agents can investigate fraud patterns.
- Agents can evaluate model performance.
- Agents can recommend rules with simulation evidence.
- Agents cannot activate rules directly.
- Observability dashboards exist.
- Load tests produce measured p95/p99 latency.
- CI builds and tests all services.

## 20. Non-Negotiable Engineering Rules

1. No LLM calls in real-time authorization.
2. No payment decision without persisted audit trail.
3. No rule activation without simulation and approval.
4. No model prediction without model version.
5. No feature snapshot with future information.
6. No raw card storage.
7. No ledger write unless debits equal credits.
8. No agent tool without audit logging.
9. No dashboard metric without a source definition.
10. No production-like workflow without integration tests.
11. No breaking API, event, rule, or database schema change without an expand/contract migration plan.
12. No production deployment without rollback and pre-production validation criteria.
13. No rule-registry deployment that requires the real-time scoring path to go down.

## 21. Best Immediate Next Step

The next implementation step should be Horizon 1, Phase 0:

1. Create the repo structure.
2. Add ADRs for the four core decisions.
3. Create Docker Compose with PostgreSQL and Redis.
4. Create `payments-api` Spring Boot skeleton.
5. Implement PaymentIntent create/confirm with idempotency and a fraud decision stub.

This gives you the fastest path to a real, inspectable system while preserving the architecture needed for ML, graph intelligence, and GenAI agents later.
