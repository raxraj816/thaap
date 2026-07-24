# THAAP Architecture Decision Records

ADRs capture durable engineering decisions and the reasoning behind them.

## Status Values

- `Proposed`
- `Accepted`
- `Superseded`
- `Deprecated`

## Index

- [ADR-0001: Use A Modular Monolith First](0001-use-modular-monolith-first.md)
- [ADR-0002: Build A Simulator Before Real PSP Integration](0002-build-simulator-before-real-psp-integration.md)
- [ADR-0003: Keep LLMs Out Of Real-Time Authorization](0003-keep-llms-out-of-real-time-authorization.md)
- [ADR-0004: Limit V1 To Card Payments](0004-limit-v1-to-card-payments.md)
- [ADR-0005: Enforce Double-Entry Ledger Invariants](0005-enforce-double-entry-ledger-invariants.md)
- [ADR-0006: Use A Tokenized Simulator Boundary For Card Data](0006-use-tokenized-simulator-boundary-for-card-data.md)
- [ADR-0007: Start With Docker Compose And Use AWS As Reference Cloud](0007-start-with-docker-compose-and-aws-reference.md)
- [ADR-0008: Use Merchant As The V1 Tenant Boundary](0008-use-merchant-as-v1-tenant-boundary.md)
- [ADR-0009: Minimize Custom Infrastructure And Prove Required Protocol Boundaries](0009-minimize-custom-infrastructure.md)
- [ADR-0010: Use Redis As Ephemeral Risk Coordination](0010-use-redis-as-ephemeral-risk-coordination.md)

## Template

```text
# ADR-NNNN: Title

Date: YYYY-MM-DD
Status: Proposed | Accepted | Superseded | Deprecated

## Context

## Decision

## Consequences
```
