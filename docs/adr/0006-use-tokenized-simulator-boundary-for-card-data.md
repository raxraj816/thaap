# ADR-0006: Use A Tokenized Simulator Boundary For Card Data

Date: 2026-05-29
Status: Accepted

## Context

V1 is not PCI-certified and must not claim PCI compliance. Still, the architecture should teach PCI-shaped design and data minimization.

## Decision

Raw PAN is accepted only as transient test input and is never persisted. CVV is never persisted. Payment method creation immediately converts test card input into safe simulated metadata:

- token
- fingerprint
- BIN
- last4
- expiry metadata
- card country
- funding type
- verification outcomes

Logs must never contain raw PAN or CVV.

## Consequences

- The domain model stays close to a low-scope tokenized integration.
- Future real PSP integration should use hosted fields or PSP tokenization.
- Tests must verify sensitive values are not stored or logged.
