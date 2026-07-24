# Safe RiskContext And Feature Lineage

## Mental model

RiskContext is merchant-observed evidence supplied when a payment is confirmed.
It is not trusted network attestation and it is not itself a fraud decision. It
answers: “what bounded context did this merchant provide for this exact payment
attempt, and what safe features did THAAP derive from it at decision time?”

The lineage is:

```text
PaymentIntent -> PaymentAttempt -> RiskContext
                              \-> FeatureSnapshot + typed FeatureValues
                              \-> FraudDecision
RiskContext ------------------------------------/
```

Every edge carries the same merchant and traffic origin. PostgreSQL validates
the final attempt/decision relationship; application code never relies on a
bare UUID as sufficient ownership proof.

## Why it matters in payments and fraud

IP, device, session, and user-agent values are useful correlation signals but
are privacy-sensitive, guessable, and high-cardinality. Storing raw values
expands breach impact. Publishing even their HMACs as model features creates an
unstable, identity-like feature surface. THAAP therefore stores only
tenant-bound, purpose-separated HMAC identities as restricted lineage.

The governed feature surface contains closed or bounded evidence: context
presence, countries, channel, account/login age, bot score, CAPTCHA outcome,
and country-match booleans. These values can be explained, typed, versioned,
and later evaluated by rules or models without exposing correlation identities.

## Missing is not low risk

A missing bot score is not zero. A missing country cannot truthfully produce a
false country match. THAAP always writes `risk.context_present`, writes optional
FeatureValues only when evidence exists, and lists absent codes in snapshot
missing-feature lineage. This prevents absent merchant telemetry from becoming
fabricated low-risk evidence or corrupting future training data.

## Security and key lifecycle

Each identity uses HMAC-SHA-256 with a separate domain:

- `risk-context:ip:v1`
- `risk-context:user-agent:v1`
- `risk-context:device:v1`
- `risk-context:session:v1`

The merchant UUID is included in the message, so the same input cannot be
correlated across tenants. The persisted key version makes rotation explicit.
Startup inventories every retained version and fails closed if its key is not
available. Raw identities remain request-memory values and are prohibited from
responses, errors, logs, metrics, audit, webhook, idempotency, and feature
surfaces.

## Transaction and database boundary

Confirm owns one transaction and locks the PaymentIntent first. It creates the
attempt and context, materializes typed features, persists the decision and
attaches its ID to the context, applies the payment action, and only then
commits idempotency truth. A failure at any local boundary rolls all of it back.

V34 separates PostgreSQL enforcement by lifecycle:

- BEFORE INSERT validates the existing payment-attempt edge;
- BEFORE UPDATE/DELETE permits only one null-to-decision attachment and rejects
  every history rewrite;
- a deferred constraint trigger re-reads the current row at commit and validates
  complete decision/attempt/origin lineage.

The re-read is essential because a deferred trigger event keeps its statement-
time `NEW` image even if the row changes later in the transaction.

## Failure modes and operational signals

Important failures are invalid/coerced input, raw identity leakage, wrong
tenant or attempt attachment, incomplete decision lineage, key retirement,
missing values fabricated as zeros, snapshot/typed-row disagreement, duplicate
confirm winners, and a context row surviving a rolled-back decision.

Closed metrics distinguish present/absent context, committed/rolled-back
transactions, bounded rejection causes, and key-readiness failure. They never
tag merchant, payment, identity, country, or raw request values.

## Tests as invariant proof

Chapter 29 proves strict grammar and numeric representation, exact HMAC domains,
purpose and tenant separation, full/partial/absent feature sets, all-action
outcome parity, raw-canary absence, forced PostgreSQL lock orderings,
rollback/retry, immutable history, deferred final lineage, key readiness,
fresh/retained V34 migration, and a live public all-action demo.

## Design-review explanation

THAAP separates privacy correlation from decision features, preserves missing
evidence explicitly, and binds every observation to the exact attempt and
decision that consumed it. Context does not change `rules_v1` in Chapter 29;
Chapter 30 may consume derived velocity/list evidence only after it adds its own
versioned semantics and conservative dependency-failure proof.
