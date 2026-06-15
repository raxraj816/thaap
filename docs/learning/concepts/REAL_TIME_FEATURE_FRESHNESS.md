# Real-Time Feature Freshness

## Mental Model

A fraud model does not decide from reality. It decides from the features handed
to it at inference time.

If those features are stale, missing, or computed from the wrong timestamp, the
model can be mathematically strong and operationally wrong. In payments, that is
not just an ML-quality problem. It can become failed authorization, avoidable
fraud loss, false declines, analyst confusion, and poor merchant trust.

## THAAP Invariant

No production-shaped fraud decision is complete unless it can answer:

- Which feature schema version was used?
- When was the feature snapshot computed?
- What was the oldest feature timestamp used?
- How old was the stalest feature at decision time?
- Which features were stale?
- Which features were missing?
- Did stale or missing data change the decision action or confidence?

## Why It Matters

Fraud systems are latency-bound truth systems. A decision has to be fast, but it
also has to be honest about the freshness of the facts used.

For THAAP, this means:

- `PaymentAttempt` remains the scoring and audit unit.
- `FraudDecision` records action, latency, and freshness status.
- `FeatureSnapshot` records immutable decision-time feature values and
  freshness metadata.
- Offline replay must score from stored `FeatureSnapshot` rows, not from current
  customer or velocity state.

## V1 Contract

The first implementation should stay inside `payments-api`.

- Compute initial features synchronously during confirm.
- Store the snapshot in PostgreSQL.
- Use Redis only when velocity counters are needed.
- Treat a feature without a trustworthy timestamp as `MISSING`.
- Treat outdated features as `STALE`, not silently fresh.
- Start with a p95 fraud-decision latency target under `100ms`.

## What We Should Not Build Yet

Do not introduce Kafka, Flink, GPU serving, Triton, or an external feature store
only because those appear in mature fintech architectures.

Those tools become justified when the monolith cannot meet freshness, latency,
or replay requirements with simpler infrastructure.

## Design Review Answer

THAAP treats feature freshness as part of fraud correctness. Every future fraud
decision must preserve both the values used and the freshness proof for those
values, so merchants and analysts can trust not only what the system decided,
but what it knew at the time.
