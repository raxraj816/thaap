# ADR-0005: Enforce Double-Entry Ledger Invariants

Date: 2026-05-29
Status: Accepted

## Context

Even simulated money movement needs accounting discipline. Capture, refund, fee, and chargeback flows can otherwise appear correct while hiding inconsistent financial state.

## Decision

Every ledger transaction group must balance:

```text
sum(debits) == sum(credits)
```

No capture, refund, fee, or chargeback write is valid unless its ledger entries balance.

## Consequences

- Payment flow tests must include ledger invariants.
- Ledger writes should be transactional with the state transition they represent.
- Operational debugging gets a reliable accounting trail from the beginning.
