# Payment Method Tokenization From First Principles

This note explains payment method tokenization in THAAP.

## The Core Idea

Tokenization means converting sensitive card input into a safe reference and safe metadata.

In plain language:

```text
Use the card long enough to create safe identifiers, then discard the card.
```

## The Problem It Solves

Raw card data is highly sensitive.

If a system stores raw PAN or CVV, it takes on much higher security and compliance risk.

THAAP is a simulator, not a PCI-certified card vault. It should teach payment concepts without storing real cardholder data.

## Why It Matters In Payments And Fraud

Fraud systems still need some card-derived signals:

- last4 for display
- BIN for issuer/card-type patterns
- fingerprint for repeat-card detection
- country/funding metadata
- CVC/AVS checks

Tokenization lets us keep useful simulated metadata while avoiding raw PAN/CVV persistence.

## How THAAP Uses It Today

`POST /payment_methods` accepts transient test card input.

The current implementation:

- validates card-number shape
- creates a simulated token
- creates a card fingerprint hash
- stores last4
- scopes the payment method by merchant and customer
- does not persist CVV

This is an early simulation boundary, not a real PSP tokenization flow.

## Design Tradeoffs

The current implementation is intentionally simple.

It teaches the shape of a low-scope tokenized integration without claiming PCI compliance.

Future real PSP integration should use hosted fields or PSP tokenization so THAAP does not handle raw cardholder data directly.

## Failure Modes

Tokenization can fail if:

- raw PAN is persisted
- raw CVV is accepted and logged
- logs include request bodies
- tokens are treated like secrets but returned too broadly
- fingerprints are not stable
- payment methods are not tenant-scoped

## Tests And Proof

Current tests prove payment method creation cannot use another merchant's customer.

Still needed:

- tests that raw PAN is not persisted
- tests that CVV is rejected or ignored
- tests for masked response behavior
- logging tests once request logging exists

## What Remains To Build

- Store BIN, expiry, country, funding, CVC/AVS simulation metadata.
- Add masked response format.
- Add safe logging policy.
- Add deterministic test card behavior for processor simulation.
- Add stronger tests around no raw PAN/CVV persistence.
