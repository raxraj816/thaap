# THAAP Product Vision

Date: 2026-05-29

This document explains the product and learning vision for the platform. For implementation decisions, follow `docs/IMPLEMENTATION_MASTER_PLAN.md`.

## 1. Vision Statement

Build THAAP, Threat Hunting & Adaptive Analytics for Payments, as a production-shaped payment processing and fraud detection platform that teaches the complete lifecycle of modern online payments:

```text
Merchant
  -> customer
  -> payment method
  -> payment intent
  -> payment attempt
  -> fraud decision
  -> authorization
  -> capture
  -> refund / dispute
  -> ledger
  -> webhooks
  -> analytics
  -> fraud feedback loop
```

The goal is not to clone Stripe, Radar, Mastercard, or any specific provider. The goal is to master the domain by building the important primitives yourself:

- payment processing
- security and data minimization
- deterministic real-time decisioning
- fraud rules
- machine learning scoring
- graph intelligence
- analyst operations
- GenAI-assisted investigation
- observability and production readiness

## 2. Product Identity

THAAP is a learning-grade, production-shaped simulator.

It should feel like a serious payment and fraud platform, but it will not move real money in V1. That is intentional. Simulating the processor lets us focus on the domain mechanics without early compliance, banking, or PSP-integration overhead.

The platform should answer:

- What happens when a merchant confirms a payment?
- How do authorization and capture differ?
- How do idempotency, state machines, and retries prevent duplicated money movement?
- How do fraud rules and ML influence payment decisions?
- How do manual review, disputes, and chargebacks feed future fraud learning?
- How do ledger entries prove money movement correctness?
- How do webhooks make payment state observable to merchants?
- How do analysts investigate risk and tune controls?

## 2.1 No-Compromise Product Bar

THAAP should not become an architecture museum or a generic fraud demo. It
should become a real, useful payment-risk operating system simulator.

The winning wedge is not early parity with Stripe or Mastercard network data.
Their production data moat is real. THAAP wins by being the most transparent,
production-shaped fraud decisioning lab for:

- payment lifecycle correctness
- simulation
- analyst workflows
- rule governance
- ML evaluation
- explainability
- agent-assisted fraud operations

No feature is real until it has:

- durable state
- tests
- audit trail
- explanation surface
- product workflow
- operational signal

The product should be optimized for complete, useful slices rather than broad
surface area. A serious slice should be demoable end to end:

```text
merchant -> customer -> payment method -> payment intent -> attempt
  -> fraud decision -> processor result -> review case
  -> analyst decision -> label -> rule/model feedback
```

THAAP should be a fraud lab, not only a fraud detector. Synthetic scenarios
should let the system demonstrate card testing, friendly fraud, account
takeover, refund abuse, merchant fraud, dispute patterns, model drift, rule
regressions, and analyst feedback loops.

Every proposed product idea should pass this filter:

- Does it improve payment correctness?
- Does it improve fraud decision quality?
- Does it improve analyst or merchant leverage?
- Does it improve explainability, governance, or auditability?
- Does it protect security, privacy, or tenant boundaries?
- Can it be persisted, tested, replayed, operated, and demoed?

If not, it is not yet product work.

## 3. Target Users

### 3.1 Builder / Learner

The primary user is the engineer building the system.

Needs:

- Learn payment processing end to end.
- Learn fraud detection end to end.
- Understand why each subsystem exists.
- Build incrementally without skipping the hard domain concepts.
- See clear invariants, tests, and acceptance criteria.

Success means:

- The engineer can explain and implement authorization, capture, refund, dispute, idempotency, ledgering, webhooks, rules, ML scoring, and manual review.

### 3.2 Merchant Developer

The merchant developer integrates with the payment APIs.

Needs:

- Create customers and payment methods.
- Create and confirm payment intents.
- Handle 3DS-required flows.
- Capture, cancel, and refund.
- Receive webhooks.
- Retry safely with idempotency keys.

Success means:

- A merchant integration can treat this simulator like a real payment API shape.

### 3.3 Fraud Analyst

The fraud analyst investigates suspicious payment attempts.

Needs:

- Search transactions.
- Understand why a payment was allowed, blocked, sent to 3DS, or sent to review.
- See rules fired, features, model reasons, related transactions, and chargeback history.
- Resolve review cases.
- Propose or approve rules.

Success means:

- An analyst can make informed decisions without reading raw database rows.

### 3.4 ML / Fraud Platform Engineer

The ML engineer builds the feedback loop.

Needs:

- Generate datasets from historical payment attempts.
- Avoid feature leakage.
- Track labels from reviews and chargebacks.
- Train baseline and improved models.
- Evaluate precision, recall, false positives, fraud captured, drift, and segment impact.
- Serve models safely in the real-time path.

Success means:

- Model scores become auditable production signals, not notebook-only experiments.

## 4. Product Principles

### 4.1 Payment Processing Is First-Class

Fraud detection only makes sense inside the payment lifecycle. V1 must build payment processing primitives deeply enough that fraud decisions affect real payment state transitions.

### 4.2 Simulate First, Integrate Later

Real PSP integration comes later. The first milestone is a deterministic card-payment simulator with realistic states, processor outcomes, and failure modes.

### 4.3 Every Important Action Is Auditable

Payment attempts, fraud decisions, rule executions, review actions, refunds, chargebacks, ledger writes, webhooks, and future agent tool calls must leave an audit trail.

### 4.4 Determinism In The Real-Time Path

The synchronous authorization path must be deterministic, bounded, observable, and safe. LLMs never run inside real-time payment authorization.

### 4.5 Money Movement Requires Ledger Discipline

Even simulated money movement must obey double-entry invariants. If ledger entries do not balance, the operation is invalid.

### 4.6 Security Starts In The Domain Model

Raw PAN and CVV are never persisted. Sensitive values should be tokenized, fingerprinted, hashed, or masked depending on use case.

### 4.7 ML Is A Feedback System

Fraud ML is not just training a model. It requires labels, feature snapshots, versioning, evaluation, monitoring, drift detection, and safe fallback behavior.

### 4.8 Agents Assist, Humans Decide

GenAI agents can investigate, explain, simulate, and recommend. They cannot approve live payments, activate rules, capture/refund money, or promote models directly.

## 5. Learning Outcomes

By the end of the core implementation, the builder should understand:

- PaymentIntent vs PaymentAttempt.
- Authorization vs capture.
- Cancel/void vs refund.
- Dispute/chargeback lifecycle.
- Idempotency keys and request replay.
- Processor adapters.
- Webhook delivery and retries.
- Double-entry ledger invariants.
- Card tokenization and fingerprinting.
- CVC, AVS, and 3DS outcomes.
- Real-time fraud decisioning.
- Rule DSLs and rule ordering.
- Manual review workflows.
- Fraud labels from reviews and chargebacks.
- Time-based ML splits and leakage prevention.
- Precision/recall and false positive tradeoffs.
- Graph-based fraud ring detection.
- Agent tool safety and human approval.
- Observability and production hardening.

## 6. Product Horizons

### Horizon 1: Payment And Fraud MVP

Build:

- Card-payment simulator.
- PaymentIntent and PaymentAttempt.
- Authorization, capture, cancel, refund, chargeback.
- Double-entry ledger MVP.
- Idempotency.
- Webhooks.
- Deterministic fraud rules.
- Manual review.
- Synthetic data generator v1 for repeatable payment and basic fraud scenarios.
- Basic dashboard.

Outcome:

- You can run an end-to-end payment and see how fraud decisions affect payment state.

### Horizon 2: Intelligence And Feedback

Build:

- Kafka events.
- Analytics projections.
- Synthetic data generator expansion with richer fraud patterns and delayed labels.
- ML baseline.
- Model serving.
- Rule simulation.
- ClickHouse analytics.
- Neo4j graph intelligence.
- Model monitoring.

Outcome:

- The platform learns from historical outcomes and supports data-driven fraud operations.

### Horizon 3: Production Shape And Agents

Build:

- GenAI fraud intelligence agents.
- RBAC.
- Observability dashboards.
- Load testing.
- CI/CD.
- Blue-green deployment.
- Rollback playbooks.
- Security hardening.

Outcome:

- The system demonstrates how a serious payment/fraud platform would be operated safely.

## 7. Non-Goals For V1

V1 will not:

- move real money
- integrate with real PSPs
- store raw card numbers
- store CVV
- implement ACH, SEPA, wallets, or local payment methods
- implement payouts or reserves
- implement full reconciliation reports
- run LLMs in authorization
- use GNNs as a core dependency
- depend on cloud infrastructure to run locally

These are future extensions after the core lifecycle is correct.

## 8. Product Success Criteria

The platform is successful when:

- A merchant can create, confirm, capture, refund, and dispute a simulated card payment.
- Every mutating payment request is idempotent.
- Every money movement balances in the ledger.
- Every payment attempt has an auditable fraud decision after fraud MVP is active.
- Fraud decisions can block, allow, request 3DS, or create review cases.
- Analysts can inspect why a decision happened.
- Chargebacks and reviews become fraud labels.
- ML models can train from historical attempts without leakage.
- Model predictions are versioned and safely served.
- Rule changes can be simulated before activation.
- GenAI agents can produce recommendations without direct production authority.

## 9. North Star

The north star is domain mastery:

```text
Understand payments deeply enough to build the processing path.
Understand fraud deeply enough to protect that path.
Understand ML deeply enough to improve that protection.
Understand operations deeply enough to run it safely.
```
