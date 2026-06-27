# THAAP Technical And Implementation Debt Register

This is the canonical source of truth for accepted technical, implementation,
security, data/ML, and operational debt.

A debt is not safely deferred because it appears in chat, a daily note, or a
roadmap paragraph. It is safely deferred only when it has a stable ID here,
appears in its owning phase or chapter in the implementation master plan, and
passes `scripts/technical-debt-check.py`.

## Required Workflow

1. Fix dependency-ready work immediately when its invariants can be protected.
2. If work must be deferred, allocate the next immutable `TD-NNNN` ID.
3. Add every required field below in the same change that creates or accepts the
   debt.
4. Reference the ID at both the originating chapter and destination owner slice
   in `docs/IMPLEMENTATION_MASTER_PLAN.md`.
5. Record the debt ID in the current daily note and weekly review when relevant.
6. Run `scripts/technical-debt-check.py` and the commit-readiness gate.
7. Never delete or reuse an ID. Resolve it in place with evidence.
8. Review every non-resolved entry at least once every 35 days; stale debt
   records fail commit readiness until `Last reviewed` and the roadmap reality
   are reconciled.

Allowed statuses are `OPEN`, `IN_PROGRESS`, and `RESOLVED`. “Accepted risk” is
a review disposition, not a debt lifecycle state: once accepted work is
deferred, its registered status is `OPEN` until implementation starts.
`P0` work cannot be deferred. Debt priority therefore uses `P1`, `P2`, or `P3`.

## TD-0001: Trusted Synthetic Traffic Origin

- Status: OPEN
- Debt type: DATA_ML_DEBT
- Priority: P2
- Introduced: 2026-06-27
- Origin: Chapter 16 — Synthetic Scenario Generator V1
- Resolution target: Phase 4.5 — Developer Scenario Runner And Traffic-Origin Governance
- Primary DRI: Payments Core
- Supporting owners: Risk Operations
- Risk / invariant: Synthetic traffic must never be indistinguishable from live merchant traffic in payment operations, risk metrics, analyst views, events, or ML datasets.
- Deferral reason: The current payment schema has no trusted server-assigned origin boundary, and merchant-controlled metadata cannot become an authorization, analytics, or training-data trust signal.
- Trigger / deadline: Resolve before Phase 4.5 enables any shared non-local scenario runner; trusted origin must also exist before Phase 7 builds training or evaluation datasets.
- Done when: An authenticated server boundary assigns tenant-scoped origin, merchants cannot self-declare trusted synthetic origin, lineage propagates through payment, fraud, event, analytics, and dataset records, and inclusion/exclusion behavior has tenant and tamper-resistance tests.
- Resolution evidence: Pending
- Last reviewed: 2026-06-27

## TD-0002: Resumable Synthetic Generator Runs

- Status: OPEN
- Debt type: OPERATIONS_DEBT
- Priority: P2
- Introduced: 2026-06-27
- Origin: Chapter 16 — Synthetic Scenario Generator V1
- Resolution target: Phase 7 — Synthetic Generator V2 Run Orchestration
- Primary DRI: ML/Data Platform
- Supporting owners: Payments Core
- Risk / invariant: Interrupted or retried dataset-scale generation must not duplicate merchants, payments, attempts, fraud decisions, labels, or other durable product truth.
- Deferral reason: The current bounded local generator has collision-resistant run identity but no durable GeneratorRun state, step checkpoint model, cleanup API, or safe resume protocol.
- Trigger / deadline: Resolve before Phase 7 introduces scheduled generation, dataset-scale volume, automatic retry, concurrency, or long-running shared-environment generator jobs.
- Done when: GeneratorRun and step state are durable, retries use stable idempotency identities, interrupted runs resume or terminate with explicit cleanup disposition, concurrency is bounded, and failure/retry tests prove no duplicate payment, fraud, or label truth.
- Resolution evidence: Pending
- Last reviewed: 2026-06-27
