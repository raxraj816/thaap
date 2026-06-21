# Fraud Decision Lineage From First Principles

This note explains fraud decision lineage as we learn it while building THAAP.
It is a learning note, not the implementation source of truth.

## The Core Idea

Fraud decision lineage is the record of what THAAP decided, what facts it used,
when those facts were computed, and why the decision happened.

In plain language: a fraud decision should not be only "allowed" or "blocked."
It should be explainable after the payment is gone from the real-time path.

## The Problem It Solves

Without lineage, analysts and merchants can see an outcome but not trust it.
Offline ML training also becomes unsafe because historical examples would be
rebuilt from current data instead of the facts that existed at decision time.

That creates false product truth: the system appears to know why it acted, but
cannot prove what it knew.

## Why It Matters In Payments And Fraud

Payments are latency-bound: confirm must decide quickly. Fraud operations are
audit-bound: the same decision must be explainable later. Lineage connects
those worlds by making the fast real-time decision replayable and reviewable.

For fraud and ML, `PaymentAttempt` is the scoring unit. `FraudDecision` records
the action, score, risk level, reasons, timing, and freshness summary.
`FeatureSnapshot` records the immutable feature values and freshness proof.

## How THAAP Uses It

Chapter 12 persists one `FraudDecision` and one `FeatureSnapshot` for every
successful confirm attempt before processor authorization.

The first decision engine is intentionally simple: `ALLOW`, score `0.00`, risk
level `LOW`, ruleset version `rules_stub_v1`, nullable model version, and reason
code `RULES_NOT_ENABLED`.

Processor simulator outcomes are not FraudDecision reason codes in Chapter 12.
They are stored on `Authorization.simulatorOutcomeCode` because the processor
result happens after the fraud decision and represents processor truth.

The first `features_v1` snapshot stores safe decision-time features such as
amount, currency, capture method, attempt number, BIN, last4, card country, and
card funding. It does not store raw PAN, CVV, raw PII, API keys, or secrets.

`features_v1` also carries lineage IDs for replay/export convenience. The
dedicated database columns remain canonical for joins, foreign keys, and tenant
integrity.

Old placeholder FraudDecision rows from before Chapter 12 are legacy audit
artifacts. They can be backfilled with `legacy_placeholder_v1`,
`RULES_NOT_ENABLED`, and `MISSING` freshness, but they must not be treated as
ML-valid training rows because they do not have PaymentAttempt or
FeatureSnapshot lineage.

## Design Tradeoffs

THAAP records honest stub lineage instead of fake intelligence. A nullable
model version is better than pretending a model scored the payment. A
`RULES_NOT_ENABLED` reason is better than inventing a rule that did not run.

Feature snapshots are stored in PostgreSQL inside `payments-api` because the
current system can meet the need without Kafka, Flink, or an external feature
store. Those tools become justified only when latency, scale, or replay needs
outgrow the modular monolith.

`payment_attempts.fraud_decision_id` is a correlation pointer for product/API
reads. Durable referential ownership points the other way:
`fraud_decisions.payment_attempt_id -> payment_attempts.id`. THAAP avoids a
reverse FK for now because circular attempt/decision constraints make insert
and rollback ordering more fragile without adding useful truth in this slice.

Feature snapshots have a conservative serialized-size guard. The current
`features_v1` payload is tiny, but the guard protects future feature extractors
from accidentally writing huge blobs into the confirm transaction.

## Failure Modes

- A confirm attempt without a fraud decision cannot be explained later.
- A feature snapshot with current-state data instead of decision-time data
  pollutes offline training.
- Missing freshness metadata lets stale data look trustworthy.
- Storing raw card or PII values in features expands payment-security risk.
- Duplicating decisions on idempotent replay creates conflicting history.
- Calling the processor before a fraud action is known creates side effects
  before the risk boundary has spoken.
- Treating legacy placeholder rows as training examples teaches the model from
  incomplete historical truth.

## Tests And Proof

Chapter 12 tests prove approved, declined, 3DS-required, retry, replay,
conflict, processor-failure rollback, feature-snapshot-failure rollback,
converter round trips, legacy migration, and tenant-isolation behavior.

The important invariant is exactly one `FraudDecision` and one
`FeatureSnapshot` per successful confirm attempt, and zero lineage when the
confirm transaction rolls back or fails before attempt creation.

## What Remains To Build

Real rules, velocity counters, model scoring, model timeout fallback, review
case creation, graph features, audit logs, and analyst-facing investigation
timelines remain future chapters.
