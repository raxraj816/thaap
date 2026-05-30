# ADR-0002: Build A Simulator Before Real PSP Integration

Date: 2026-05-29
Status: Accepted

## Context

Real PSP integration introduces compliance, credentials, network behavior, external contracts, and operational constraints before the core payment and fraud domain is implemented.

## Decision

Build a deterministic processor simulator first. Place it behind a `ProcessorAdapter` interface so a real PSP adapter can be introduced later without rewriting payment orchestration.

## Consequences

- Test card behavior can deterministically cover approvals, issuer declines, insufficient funds, suspected fraud, CVC failures, AVS failures, and 3DS flows.
- The platform can teach payment lifecycle mechanics without moving real money.
- Real PSP integration remains a later adapter and compliance project.
