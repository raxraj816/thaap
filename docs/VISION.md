# THAAP Product Vision

Date: 2026-05-29

This document explains the product and learning vision for the platform. For implementation decisions, follow `docs/IMPLEMENTATION_MASTER_PLAN.md`.

## 1. Vision Statement

Build THAAP, Threat Hunting & Adaptive Analytics for Payments, as an
explainable, replayable, merchant-controlled fraud and risk platform. THAAP
first learns and proves the complete lifecycle of modern online payments in a
production-shaped simulator, then integrates with existing PSPs to protect real
merchant payment traffic without initially becoming the money-moving PSP:

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

THAAP's V1 is a learning-grade, production-shaped simulator. THAAP's final
destination is a real, hosted, merchant-facing fraud/risk platform that first
integrates with existing PSPs.

V1 will not move real money. That is intentional: simulating the processor lets
us master payment and fraud mechanics without prematurely taking on acquiring,
settlement, licensing, or high-scope card-data obligations. This boundary is a
sequencing decision, not a ceiling. Later production horizons ingest real PSP
events, produce risk decisions and analyst workflows for real merchants, and
progress from shadow evidence to controlled enforcement only after safety and
outcome gates pass.

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

THAAP should not become an architecture museum, a generic fraud demo, or a
simulator that never earns contact with real merchant outcomes. It should
become a real, useful payment-risk operating system; the simulator is its safe
proving ground.

The winning wedge is not early parity with Stripe or Mastercard network data.
Their production data moat is real. THAAP wins first by being the most
transparent production-shaped fraud decisioning lab, then by turning that
discipline into a merchant-controlled production risk platform for:

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

## 2.2 Speed-To-Real-Product Rule

THAAP is competing against mature payment and fraud platforms, so it must avoid
parity chasing. Early speed comes from a narrow, painful, believable wedge:

```text
simulate realistic payment risk -> explain the decision -> let an analyst act
  -> feed the outcome back into rules, labels, and model evaluation
```

The fastest credible product path is not to build every payment feature first.
It is to make one vertical fraud-risk loop feel real end to end:

- Merchant can create and confirm a card payment.
- The platform captures decision-time risk context.
- Fraud rules produce an auditable decision with reason codes.
- Risky attempts become review cases or blocked payments.
- An analyst can understand, resolve, and label the case.
- The outcome updates rule evaluation and future ML training data.
- The whole flow can be replayed, demonstrated, tested, and operated locally.

Work should be sequenced by shortest path to this loop. Infrastructure,
framework, and UI work are only urgent when they unlock the loop or protect a
critical invariant.

For aggressive execution, prefer:

- one merchant segment before many segments
- one card-payment rail before many payment methods
- one analyst queue before broad dashboards
- deterministic rules before complex ML
- synthetic repeatable scenarios before real integrations
- local operability before cloud polish
- explainability and auditability before opaque automation

The product should be demoable at all times. A feature that cannot be shown in
a merchant, payment, fraud, analyst, or learning workflow is not on the fastest
path unless it protects correctness, security, tenant isolation, or operational
resilience.

## 2.3 Executable Vision Coverage

[`THAAP_VISION_COVERAGE_MATRIX.md`](THAAP_VISION_COVERAGE_MATRIX.md) maps every
challenger promise to current evidence, the exact remaining gap, owner
phase/slice, DRI, trigger, merchant/analyst demo, completion evidence, and
review date. `scripts/vision-coverage-check.py` makes that ownership part of
commit readiness.

This creates four permanent rules:

1. A phase cannot close merely because infrastructure, a framework, or a test
   suite works. It must advance a named product promise or prove a critical
   boundary that directly unlocks one.
2. “Later,” “future,” `TBD`, and unassigned gaps are invalid. Every retained
   promise has a concrete owner and unblock evidence.
3. A later phase must extend already-proven product truth rather than rebuild
   it under a new name.
4. The matrix is reviewed at every promise freeze, scope change, and phase
   closure and at least every 35 days while incomplete promises remain.
5. V1 simulator proof cannot be presented as real merchant outcome proof, and
   production ambition cannot be used to weaken the simulator's explicit
   synthetic/external-source boundary.

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

- In V1, a merchant integration can treat the simulator like a real payment API
  shape; in the production horizon, the same developer can integrate a real
  PSP data path without adopting a second, contradictory risk contract.

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

### 3.5 Merchant Risk Leader

The merchant risk leader owns fraud loss, approval quality, operational cost,
and deployment safety.

Needs:

- Connect an existing PSP without replacing the merchant's payment stack.
- Observe decisions in shadow mode before THAAP influences live traffic.
- Configure risk appetite, approvals, rollback, and enforcement boundaries.
- Measure fraud loss, false positives, approval rate, review workload, and
  decision latency against an agreed baseline.
- Understand which outcomes are observed, delayed, disputed, or still unknown.

Success means:

- The merchant can demonstrate measurable risk or operational improvement and
  can disable THAAP influence without losing payment, fraud, or audit truth.

## 4. Product Principles

### 4.1 Payment Processing Is First-Class

Fraud detection only makes sense inside the payment lifecycle. V1 must build payment processing primitives deeply enough that fraud decisions affect real payment state transitions.

### 4.2 Simulate First, Integrate Later

The first milestone is a deterministic card-payment simulator with realistic
states, processor outcomes, and failure modes. Real PSP integration follows
through an adapter and provenance boundary that keeps externally observed facts
distinct from THAAP decisions and synthetic fixtures. Simulation is the safety
and regression environment for the production product, not its final identity.

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

Current state: achieved through Phase 4.5 for the production-shaped simulator
boundary.

Delivered:

- Card-payment simulator.
- PaymentIntent and PaymentAttempt.
- Authorization, capture, cancel, refund, chargeback.
- Double-entry ledger MVP.
- Idempotency.
- Webhooks.
- Deterministic fraud rules.
- Manual review.
- Synthetic data generator v1 for repeatable payment and basic fraud scenarios.
- Thin analyst/developer cockpit with monitoring, investigation, governed
  controls, dispute, webhook, and trusted scenario workflows.

Outcome:

- You can run an end-to-end payment and see how fraud decisions affect payment state.

### Horizon 2: Intelligence And Feedback

Owner phases: Phases 5–8.

Build from current truth:

- Durable domain events, inbox-idempotent consumption, freshness-aware
  analytics, and rebuildable point-in-time datasets.
- Unified payment-risk explanation, analyst investigation, business insights,
  OpenAPI/error catalogue/SDK, and merchant sandbox.
- Synthetic data expansion, governed ML baseline, calibrated shadow/active
  serving, champion/challenger, drift, and rollback.
- Privacy-safe network aggregates and explainable temporal graph intelligence.
- Merchant underwriting/exposure/reserve/hold simulation, advanced identity,
  friendly-fraud/representment, and post-authorization outcome optimization.
- ClickHouse only if measured PostgreSQL query/volume evidence justifies the
  second analytics-store boundary.

Outcome:

- The platform learns from historical outcomes and supports data-driven fraud operations.

### Horizon 3: Production Shape And Agents

Owner phase: Phase 9.

Build:

- Hosted identity, platform RBAC, and lifecycle controls.
- OpenTelemetry/SLOs, load/chaos, backup/restore, retention/deletion, and
  incident recovery.
- API/event/model/rule/database compatibility, CI/CD, blue-green/canary, and
  rollback.
- Audited fraud intelligence agents with source citations and mandatory human
  authority.
- External integration readiness and evidence-triggered worker, egress, and
  multi-region scale.

Outcome:

- The system is ready to enter a controlled merchant integration without
  pretending that infrastructure readiness is customer-value proof.

### Horizon 4: Merchant Integration And Measured Validation

Owner phase: Phase 10.

Build:

- A versioned external PSP adapter with authenticated ingress, event provenance,
  reconciliation, replay, idempotency, and ambiguity handling.
- Hosted merchant onboarding, tenant lifecycle, risk configuration, support,
  audit export, deletion, and incident communication.
- Explicit `SHADOW`, `ADVISORY`, and narrowly approved `ENFORCED` deployment
  modes with kill switch, rollback, and merchant-owned risk appetite.
- A design-partner pilot governed by pre-agreed safety, fraud-loss,
  false-positive, approval-rate, review-workload, and latency measures.
- A delayed-outcome and label-quality feedback loop that can improve merchant
  and privacy-safe network intelligence without overstating causality.

Outcome:

- At least one real merchant can integrate through an existing PSP, validate
  THAAP safely, and inspect trustworthy evidence of product impact.

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

These are excluded from V1, not from THAAP's destination. Real PSP integration
and a controlled merchant pilot are explicit Phase 10 commitments. Becoming a
full acquiring, settlement, or money-moving PSP remains a separate future
strategy decision and is not required to validate THAAP's fraud/risk product.

## 8. Product Success Criteria

The V1 platform is successful when:

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

THAAP reaches its declared product destination when:

- A merchant can connect an existing PSP through an authenticated, versioned,
  reconciled adapter without exposing raw PAN or CVV to THAAP.
- Shadow decisions can be compared with observed PSP and merchant outcomes
  before any live influence is enabled.
- Advisory or enforced decisions require explicit merchant approval, bounded
  policy, rollback, a kill switch, and complete audit evidence.
- Product impact is reported against an agreed baseline with coverage,
  confidence, delayed-label, segment, and causality limitations visible.
- A real design-partner workflow closes the loop from payment event to decision,
  analyst action, label, rule/model improvement, and measured outcome.

## 9. North Star

The engineering north star is domain mastery in service of merchant outcomes:

```text
Understand payments deeply enough to build the processing path.
Understand fraud deeply enough to protect that path.
Understand ML deeply enough to improve that protection.
Understand operations deeply enough to run it safely.
Earn real merchant trust through measurable fraud reduction and approval quality.
```
