# PaymentIntent State Machine

## First-Principles Model

A `PaymentIntent` is the merchant-facing container for one attempt to collect
money from a customer. It is not itself the processor authorization, the fraud
decision, or the ledger entry. It is the durable object that says where the
payment is in its lifecycle.

State-machine discipline means a payment can only move through transitions that
make business sense. The system must reject impossible operations instead of
recording them and hoping a later subsystem fixes the history.

## Why It Matters In Payments And Fraud

Fraud, processor, review, refund, dispute, and ledger workflows all read payment
state as evidence. If a `PaymentIntent` can be captured before authorization, or
refunded before capture, every downstream system inherits false payment truth.

That is dangerous because:

- fraud labels can attach to the wrong lifecycle moment
- analysts see impossible timelines
- merchants receive misleading webhook events
- ledgers can be asked to balance money movement that never existed
- idempotent retries can replay bad state

## Current THAAP Rules

The V1 create path starts each PaymentIntent in `REQUIRES_CONFIRMATION`.

Current guarded transitions:

- `cancel` is allowed from pre-terminal or uncaptured states such as
  `REQUIRES_CONFIRMATION`, `AUTHORIZED`, and `REQUIRES_CAPTURE`
- `capture` is allowed only from `REQUIRES_CAPTURE`
- `refund` is allowed only from `CAPTURED`
- incompatible transitions return `409 conflict`

The enum already includes future lifecycle states such as `PROCESSING`,
`AUTHORIZED`, `REQUIRES_CAPTURE`, `DISPUTED`, and `FAILED`, but only transitions
with current product meaning are active.

## Schema And API Implications

PaymentIntent stores:

- amount and supported currency
- capture method
- status
- optional description
- constrained metadata
- created and updated timestamps

The public response includes `createdAt` and `updatedAt` because merchants,
operators, and analysts need timestamps for reconciliation, webhook correlation,
and investigation timelines.

## Security And Privacy

Metadata is useful for merchant reconciliation, but it is not a safe dumping
ground. THAAP constrains metadata keys and values and documents that metadata
must not contain PAN, CVV, secrets, or sensitive personal data.

## Tests As Proof

`PaymentIntentLifecycleContractTests` proves:

- cancel from `REQUIRES_CONFIRMATION` succeeds
- capture before authorization fails without mutating state
- refund before capture fails without mutating state
- repeat cancel after terminal cancel fails without duplicate webhook delivery

`PaymentIntentCreateContractTests` proves:

- unsupported currencies are rejected
- excessive amounts are rejected
- description, metadata, `createdAt`, and `updatedAt` are persisted and returned

## Deferrals

- Processor chapters will own transitions into `PROCESSING`, `AUTHORIZED`, and
  `REQUIRES_CAPTURE`.
- Capture/ledger chapters will own money-movement records and double-entry
  proofs.
- Refund chapters will add refund entities, amount bounds against captured
  amount, and partial refund behavior.
- Dispute chapters will own `DISPUTED` and chargeback workflow transitions.
