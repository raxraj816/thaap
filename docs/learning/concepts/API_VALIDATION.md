# API Validation From First Principles

This note explains API validation as a platform concept. It is a learning note, not the implementation source of truth.

## The Core Idea

API validation means rejecting malformed, incomplete, or unsafe input before domain logic runs.

In plain language:

```text
Do not let bad requests enter the payment state machine.
```

Validation is the boundary between untrusted client input and trusted application behavior.

## The Problem It Solves

Without validation, every service method has to defend itself against impossible inputs:

- missing customer IDs
- negative amounts
- invalid currencies
- malformed emails
- invalid card-number shapes
- blank merchant names

That spreads defensive logic everywhere and makes payment behavior harder to reason about.

With validation, request contracts are explicit:

```text
request enters controller
  -> validate shape and required fields
  -> reject invalid input
  -> run domain logic only for structurally valid requests
```

## Why It Matters In Payments And Fraud

Payment systems are state machines. Bad input can create bad states.

Examples:

- a zero or negative payment amount should never become a `PaymentIntent`
- an invalid customer ID should never create a payment for another merchant
- malformed card input should never be persisted
- missing idempotency keys should never reach mutation logic

Validation also protects fraud and ML systems. Feature pipelines depend on stable input shape. If requests are inconsistent, feature snapshots and model training rows become noisy.

## How THAAP Uses It Today

THAAP uses Jakarta Bean Validation annotations on request records.

Examples:

- `@NotBlank` for required strings
- `@Email` for customer email shape
- `@Min(1)` for positive amounts
- `@Pattern` for simple currency and card-number shapes
- `@NotNull` for required IDs

Current validation exists on merchant, customer, payment method, payment intent, refund, and webhook endpoint requests.
Payment method creation also validates the normalized card number after spaces are removed, because annotation-level length checks run before normalization.

## Design Tradeoffs

Validation should stay close to the API boundary for request shape checks.

Domain invariants still belong in domain/application logic.

Examples:

- `amount > 0` is API validation
- "customer belongs to merchant" is authorization/domain validation
- "captured amount cannot exceed authorized amount" is domain validation

Keeping those separate avoids turning controllers into the whole application.

## Failure Modes

Validation can fail if:

- annotations are missing on new request types
- validation errors return inconsistent response bodies
- validation logic leaks sensitive input in error messages
- domain invariants are mistaken for simple shape validation

Day 3 will add a standard error contract so validation failures are predictable.

## Tests And Proof

Current tests prove tenant, idempotency, API-key rejection, and one normalized card-number validation failure through HTTP requests.

Still needed:

- tests for invalid email
- tests for missing required fields
- tests for negative amount
- tests for invalid currency
- tests that validation errors use the standard Day 3 error body

## What Remains To Build

- Global validation error formatting.
- Standard JSON error response body.
- Field-level validation error list.
- Tests for representative invalid requests.
- Documentation examples for clients.
