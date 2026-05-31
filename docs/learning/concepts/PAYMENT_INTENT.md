# PaymentIntent From First Principles

This note explains the PaymentIntent concept in THAAP.

## The Core Idea

A `PaymentIntent` is the merchant-facing container for a payment.

It represents:

```text
The merchant intends to collect this amount, in this currency, from this customer.
```

It is not the same thing as an authorization, capture, or fraud decision.

## The Problem It Solves

Payments are multi-step workflows.

A merchant may need to:

- create the payment
- attach or use a payment method
- confirm the payment
- run fraud checks
- authorize the payment
- capture now or later
- cancel before capture
- refund after capture
- handle dispute later

Without a container object, these steps become scattered records with no single lifecycle.

`PaymentIntent` gives the workflow a stable identity.

## Why It Matters In Payments And Fraud

Fraud systems score attempts, but merchants reason about payments.

The `PaymentIntent` connects merchant-facing state to lower-level attempts and decisions.

Examples:

- one intent can have multiple attempts
- one attempt can have one fraud decision
- one successful attempt can produce authorization
- capture/refund state updates the intent

This separation keeps the product API understandable while preserving audit detail.

## How THAAP Uses It Today

THAAP currently supports a simplified `PaymentIntent` entity with:

- merchant ID
- customer ID
- amount
- currency
- status
- timestamps

Implemented status values are intentionally small for the first slice:

- `REQUIRES_CONFIRMATION`
- `CAPTURED`
- `CANCELED`
- `REFUNDED`

The full planned lifecycle is documented in the architecture docs and will expand as confirm, authorization, ledger, and fraud flows mature.

## Design Tradeoffs

We implemented a small status model first because the repository is still early.

That is acceptable if we do not confuse this scaffolding with the final payment lifecycle.

The key design choice is that `PaymentIntent` is merchant-scoped from the beginning.

## Failure Modes

PaymentIntent design can fail if:

- statuses are too vague
- invalid transitions are allowed
- capture happens before authorization
- refund happens before capture
- tenant scope is omitted
- idempotency does not guard mutations
- attempts and fraud decisions are not auditable

## Tests And Proof

Current tests prove:

- payment intents are merchant-scoped
- another merchant cannot read or mutate them
- create/capture/cancel/refund paths use tenant context and idempotency

## What Remains To Build

- Confirm flow.
- PaymentAttempt creation.
- FraudDecision creation.
- Authorization simulation.
- Ledger entries.
- Full state-transition validation.
- More complete status enum.
- Tests for invalid transitions.
