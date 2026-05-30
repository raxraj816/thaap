# ADR-0004: Limit V1 To Card Payments

Date: 2026-05-29
Status: Accepted

## Context

ACH, SEPA, wallets, payouts, reserves, and full reconciliation add additional domain models and regulatory surfaces. The first goal is mastery of card authorization, capture, cancel, refund, chargeback, idempotency, fraud decisions, and ledgering.

## Decision

V1 supports simulated card payments only.

Out of scope for V1:

- real money movement
- real PSP integration
- ACH, SEPA, wallets, and local payment methods
- payouts and reserves
- full reconciliation reports

## Consequences

- The first implementation can go deeper on the card lifecycle.
- The API and data model should avoid assumptions that make later payment methods impossible.
- Product documentation must be explicit that V1 is a simulator.
