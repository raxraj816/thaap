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

## TD-0003: Webhook Signing-Secret Rotation Grace Windows

- Status: OPEN
- Debt type: SECURITY_DEBT
- Priority: P2
- Introduced: 2026-06-30
- Origin: Chapter 19 — Webhook Delivery Execution And Retry Behavior
- Resolution target: Phase 4.5 — Product-Grade Risk Operations, Developer/API Console Webhook Operations slice
- Primary DRI: Payments Core
- Supporting owners: Developer Experience, Security
- Risk / invariant: A worker can load an endpoint's signing secret, then the merchant can rotate the endpoint secret before that already-claimed delivery reaches HTTP; without an explicit old/new verification window, the merchant may reject a legitimate in-flight delivery signed with the just-rotated secret.
- Deferral reason: Chapter 19 V1 has a single active reveal-once signing secret and a local/test delivery wedge; product-grade rotation windows require schema/API support for previous-secret retention, expiry, merchant-facing verification guidance, and replay/deduplication behavior that belongs with the webhook operations console before shared non-local onboarding.
- Trigger / deadline: Resolve before shared non-local merchant webhook onboarding, before recommending webhook secret rotation as an operational playbook, or before enabling endpoint verification/replay workflows for external merchants.
- Done when: Endpoint rotation stores current and previous signing-secret material with explicit expiry, delivery signing and merchant verification docs define the accepted overlap window, tests prove old-secret in-flight delivery acceptance until expiry and rejection after expiry, audit/API responses remain secret-safe, and replay/deduplication behavior is unchanged.
- Resolution evidence: Pending
- Last reviewed: 2026-06-30

## TD-0004: Webhook Delivery Fairness And Rate Controls

- Status: OPEN
- Debt type: OPERATIONS_DEBT
- Priority: P2
- Introduced: 2026-06-30
- Origin: Chapter 19 — Webhook Delivery Execution And Retry Behavior
- Resolution target: Phase 4.5 — Product-Grade Risk Operations, Developer/API Console Webhook Operations slice
- Primary DRI: Payments Core
- Supporting owners: Developer Experience, Risk Operations
- Risk / invariant: In a shared merchant environment, one merchant or endpoint with a large delivery burst must not consume all webhook worker capacity or starve other merchants' delivery attempts.
- Deferral reason: Chapter 19 V1 bounds total local worker behavior with disabled-by-default execution, capped batch size, one-at-a-time claims, leases, and network deadlines; per-merchant/per-endpoint fairness requires product policy, operator controls, metrics, and shared-environment workload targets that belong with product-grade webhook operations.
- Trigger / deadline: Resolve before shared non-local merchant webhook onboarding, before enabling high-volume endpoint subscriptions for external merchants, or when measured webhook queue age shows one tenant can starve another.
- Done when: Delivery claiming or scheduling enforces configurable per-merchant and/or per-endpoint fairness limits, burst behavior is observable, tests prove a noisy merchant cannot starve another merchant, and operational docs define default limits and override procedures.
- Resolution evidence: Pending
- Last reviewed: 2026-06-30

## TD-0005: Webhook Endpoint Creation Idempotency

- Status: OPEN
- Debt type: IMPLEMENTATION_DEBT
- Priority: P2
- Introduced: 2026-06-30
- Origin: Chapter 19 — Webhook Delivery Execution And Retry Behavior
- Resolution target: Phase 4.5 — Product-Grade Risk Operations, Developer/API Console Webhook Operations slice
- Primary DRI: Developer Experience
- Supporting owners: Payments Core, Security
- Risk / invariant: A retried endpoint-creation request after a network timeout can create duplicate active endpoints with distinct signing secrets, causing duplicate delivery streams and confusing one-time secret handling.
- Deferral reason: Chapter 19 V1 supports explicit duplicate cleanup through endpoint disable; safe idempotent creation requires an API contract for idempotency keys, URL uniqueness or replay behavior, and one-time secret reveal semantics that must be designed with the developer console and merchant onboarding workflow.
- Trigger / deadline: Resolve before shared non-local merchant webhook onboarding, before recommending automated endpoint provisioning, or before exposing webhook endpoint creation through a public developer console.
- Done when: Endpoint creation has a tenant-scoped idempotency/retry contract that prevents accidental duplicate active endpoints, safely handles lost one-time secret responses, preserves audit evidence, and includes tests for same-key replay, changed-request conflict, cross-tenant isolation, and duplicate URL behavior.
- Resolution evidence: Pending
- Last reviewed: 2026-06-30
