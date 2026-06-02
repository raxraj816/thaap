# Senior Staff / ML Architect Growth Rubric

Use this rubric every week. The purpose is to measure whether learning is
turning into independent engineering judgment.

Scoring:

- `0`: Not yet understood.
- `1`: Can recognize the idea after reading notes.
- `2`: Can explain the idea in plain English.
- `3`: Can trace it in THAAP code and tests.
- `4`: Can modify or extend it safely with guidance.
- `5`: Can design, defend, and review it independently.

## 1. Code Reading And Tracing

- Trace a request from controller to domain object to repository to response.
- Identify where validation, tenant context, idempotency, and errors happen.
- Explain which tests prove the behavior.
- Find the production invariant protected by each key class or table.

Current score:

- Score:
- Evidence:
- Gap:
- Next action:

## 2. Payments Architecture

- Explain `PaymentIntent` versus `PaymentAttempt`.
- Explain authorization, capture, cancel/void, refund, and chargeback.
- Explain idempotency for mutating payment APIs.
- Explain why ledger transaction groups must balance.

Current score:

- Score:
- Evidence:
- Gap:
- Next action:

## 3. Security And Tenant Isolation

- Explain why merchant context comes from credentials, not request bodies.
- Explain API key hashing, prefixes, and one-time secret reveal.
- Explain PCI-style card data minimization.
- Review a schema for cross-tenant leakage risks.

Current score:

- Score:
- Evidence:
- Gap:
- Next action:

## 4. Fraud Decisioning

- Explain `ALLOW`, `BLOCK`, `REQUEST_3DS`, and `REVIEW`.
- Explain rule ordering and why hard safety rules take precedence.
- Explain why each fraud decision needs an audit trail.
- Explain manual review and chargeback feedback loops.

Current score:

- Score:
- Evidence:
- Gap:
- Next action:

## 5. ML Architecture And Lineage

- Explain why `PaymentAttempt` is the ML dataset unit.
- Explain `FeatureSnapshot`, point-in-time joins, and leakage prevention.
- Explain delayed labels from reviews and chargebacks.
- Explain time-based splits, calibration, threshold tuning, and segment-level
  model evaluation.
- Explain why advanced models are evidence-driven, not preselected.

Current score:

- Score:
- Evidence:
- Gap:
- Next action:

## 6. Reliability And Operations

- Explain request IDs and error contracts.
- Explain retry-safe idempotency behavior.
- Explain outbox, replay, observability, and rollback concepts when they land.
- Identify p95/p99 latency and timeout risks in real-time payment/fraud paths.

Current score:

- Score:
- Evidence:
- Gap:
- Next action:

## 7. Staff Communication

- Write a design doc with goals, non-goals, APIs, schema, risks, test plan, and
  rollout.
- Explain tradeoffs clearly without overclaiming.
- Separate what is implemented now from what is intentionally deferred.
- Give an interview-ready narrative for a technical decision.

Current score:

- Score:
- Evidence:
- Gap:
- Next action:

## Weekly Summary

- Strongest improvement this week:
- Weakest area this week:
- One concept to revisit:
- One code area to modify next:
- One design-review narrative to practice:
