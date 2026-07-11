# Point-In-Time Feature Lineage

## Mental model

A fraud decision is only defensible if THAAP can prove what was knowable when
the decision began. Current entity state is not that proof: customers,
payments, reference data, and counters can all change later.

Chapter 22 stores two complementary artifacts:

- `FeatureSnapshot`: the exact lexical document used for explanation and
  replay.
- `FeatureValue`: one normalized typed row per definition and PaymentAttempt,
  carrying observation, computation, tenant, version, and traffic-origin
  lineage.

The snapshot answers “what did the engine see?” Typed rows answer “what did
each value mean, where did it come from, and was it available in time?”

That statement is enforced structurally: the materializer creates one exact
`DecisionFeatureContext`, `MaterializedFeatureSet` rejects disagreement between
that context and its typed rows, the engine receives only that context, and the
snapshot persists the same map. Processor-only card fields and resource IDs
never enter the fraud evaluation interface.

## Point-in-time rule

For a decision that starts at time `T`, an eligible feature must satisfy:

```text
observed_at <= T
```

A later observation is future leakage. It is rejected rather than treated as
stale or silently omitted. Offline joins repeat the same predicate; they never
rebuild history from current PaymentIntent, PaymentMethod, or customer state.

## Type truth

JSON alone is insufficient for governed ML lineage because `"1200"`, `1200`,
and `1200.0` can carry different meanings. Each FeatureValue declares LONG,
STRING, BOOLEAN, or DECIMAL and populates exactly one matching database column.
The database rejects zero, multiple, or mismatched populated columns. Numeric
and UTF-8 bounds are validated before persistence, and snapshot deserialization
preserves LONG and DECIMAL Java types.

## Payment and security invariants

- The PaymentIntent is locked before the attempt and features are created.
- Exactly seven required V1 features materialize; missing input aborts confirm.
- Decision, snapshot, seven values, attempt, processor/money truth, audit,
  webhook, and idempotency response share one transaction.
- Typed lineage is explicitly flushed before processor authorization.
- Definition ID, code, version, and value type form one PostgreSQL foreign-key
  identity; the declared value type cannot disagree with its definition.
- Same-tenant and traffic-origin composite foreign keys reject substituted
  lineage.
- Raw PAN, CVV, fingerprint, BIN, last4, PII, resource IDs, metadata,
  descriptions, credentials, and idempotency material are not features.
- Database triggers prevent rewriting definitions and historical decision,
  snapshot, or value evidence.

## Freshness versus leakage

Freshness asks whether an otherwise valid past observation is older than its
definition permits. Leakage asks whether the observation occurred after the
decision began. They are different failure families:

- old but within policy: `FRESH`
- old beyond a positive `max_age_ms`: `STALE`
- absent required value: fail the decision
- observed after decision start: reject as future leakage

The initial durable V1 definitions have no expiry and therefore resolve to
`FRESH`; Chapter 22 still implements and tests the stale partition so later
definitions cannot reinterpret the contract.

## Design-review explanation

In a Stripe/Mastercard-style review: THAAP creates immutable, tenant-bound,
typed evidence at the decision transaction boundary; rejects information not
available at decision start; flushes that evidence before external processor
use; and queries training data through the same time predicate. That prevents
silent leakage, explanation drift, cross-tenant attachment, and JSON type
ambiguity without prematurely adding a remote feature-store dependency.
