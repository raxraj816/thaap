# Synthetic Payment Scenarios From First Principles

Synthetic payment scenarios are deliberately generated payment stories used to
test, demo, and later evaluate fraud systems.

In plain language: instead of hand-clicking one payment at a time, THAAP can ask
for a repeatable world like "approved card," "issuer decline," or "card-testing
pattern" and get durable payment attempts, fraud decisions, processor outcomes,
and summary evidence.

## Why This Matters

Fraud and ML systems need examples before they can be improved. A model needs
training and evaluation data. A fraud analyst tool needs cases. A rule engine
needs scenarios that prove a rule helped instead of creating noise.

Synthetic scenarios are the first safe source of that evidence because they are
controlled, repeatable, and private.

## Product Invariant

Synthetic data must exercise the real product path.

For Chapter 16, that means the generator calls the public APIs for merchant
creation, customer creation, payment method creation, PaymentIntent creation,
confirm, fraud-decision reads, payment-attempt reads, authorization reads, and
ledger reads. It does not write database rows directly.

This protects the truth of the generated data:

- tenant authentication is exercised
- idempotency is exercised
- validation is exercised
- payment lifecycle transitions are exercised
- fraud lineage is created by the same confirm path
- processor response codes are read from durable authorization truth
- a successful run fails closed if uncaptured scenarios create ledger truth

## Security Boundary

A synthetic summary is useful only if it is safe to share internally.

The summary may include safe IDs, scenario labels, final statuses, fraud
decision IDs, and processor outcome codes. It must not include raw PAN, CVV, raw
buyer email, raw buyer phone, API keys, idempotency keys, hashes, webhook
secrets, or internal card fingerprints.

The V1 generator accepts loopback targets only. That is a data-governance
boundary: until THAAP has a trusted server-side synthetic-origin field,
generated records must not enter a shared environment where risk metrics or
future ML datasets could confuse them with real traffic.

## Fraud And ML Boundary

Chapter 16 scenarios are not a model-training dataset yet. They are payment-core
scenario evidence.

Later chapters can build delayed labels, chargeback labels, graph patterns,
model-training datasets, and replay reports on top of this foundation. The
important thing now is that the generated examples are reproducible and honest
about what they prove. A collision-resistant `syntheticRunId` in PaymentIntent
metadata provides local operational traceability, but merchant-controlled
metadata is not an authoritative ML classification boundary.

## Stripe/Mastercard-Style Review Frame

A reviewer should ask:

1. Did generated data use the same API path as real merchant traffic?
2. Can the same seed reproduce the same scenario plan?
3. Are processor outcome codes read from durable authorization truth?
4. Does tenant isolation hold across generated merchants?
5. Does the summary omit sensitive values?
6. Do decline and suspicious scenarios avoid false capture or ledger truth?

Chapter 16 answers yes to those questions for the V1 generator.
