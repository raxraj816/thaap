# Chapter 43R Closure Dossier

- Chapter: Chapter 43R — Phase 4.5 Product Closure Recovery
- Chapter number: 43
- Status: READY_TO_MERGE
- Invariant matrix: docs/reviews/CHAPTER_43R_INVARIANT_PROOF_MATRIX.md
- Matrix provenance: PRE_IMPLEMENTATION
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Hostile review: PASS
- Boundary family review: PASS
- Custom infrastructure review: NOT_APPLICABLE
- Escaped review assessment: COMPLETE
- Merge recommendation: MERGE

This recovery dossier first closed `ER-0059`–`ER-0063`, then absorbed the later
`ER-0064`–`ER-0073` end-to-end review. The matrix was frozen before recovery
changes; all implementation, browser, PostgreSQL, and governance evidence now
passes without weakening the original Phase 4.5 promises.

## Observable promises

- The cockpit exposes exact monitoring/cohort lineage and merchant endpoint
  verification in addition to all previously shipped Phase 4.5 workflows.
- Authenticated server context—not UI inference—owns role and tenant truth;
  lower roles never see manager mutations.
- Every scenario mutation combines actor-scoped transport idempotency with the
  existing run/worker/lease/epoch/resource fence.
- Loading, empty, partial, denied, stale, dependency, retry, and success states
  are explicit, accessible, and secret-safe.
- Phase completion requires every Chapter 36–43 and 43R artifact/gate, not only
  the current matrix and dossier.

## Failure inventory

The frozen inventory covers payment/fraud mutation from operational reads,
tenant substitution, guessed role, disabled actor, secret/error persistence,
missing or conflicting retry key, stale worker, zero-for-absence monitoring,
origin/as-of drift, partial/dependency optimism, endpoint authority confusion,
unbounded client caching, migration drift, and validate-only-current governance.
The live pass additionally found and closed an invalid monitoring-origin enum
and an unconditional dispute load that prevented VIEWER entry.

## Boundary family evidence

VIEWER/ANALYST/RISK_MANAGER, valid/invalid/disabled identity, live/synthetic and
complete/no-data/partial/history coverage, exact/conflicting/missing key,
current/stale lease, success/401/403/409/503, empty/populated cohort, and
present/missing inherited artifact partitions are tied to API, transaction,
PostgreSQL, browser, client, or governance tests. The original recovery families
and all four newly expanded inherited PostgreSQL families pass.

## Enforcement boundaries

`/risk/operators/me` projects only authenticated identity and role. Monitoring
uses the existing tenant-qualified PostgreSQL queries and exact server window.
Endpoint verification uses merchant authority and a safe lifecycle projection.
Scenario controller authorization executes before generic replay, while service
locks/leases still fence progress. TanStack Query stores only bounded non-secret
session/monitoring state and is cleared on disconnect. The aggregate phase gate
enumerates and independently validates all nine closure pairs.

## Exact proof

- Backend: 759 tests pass with zero failures/errors/skips, including operator
  session and developer-mutation idempotency contracts plus production-PostgreSQL
  migration/concurrency suites and all four expanded recovery families.
- Repository: 140 Python tests pass, including missing-artifact and all-coordinate
  aggregate phase-closure behavior.
- Cockpit: 20 Vitest tests, TypeScript validation, accessibility, role, partial,
  denied/dependency, exact retry-key, endpoint-verification, and production build pass.
- Browser: isolated V64 runtime proves manager monitoring, exact cohort empty
  state, VIEWER monitoring with manager mutations absent, and empty credential
  fields after reload.

## Migration evidence

The recovery changes no migration or schema. Full fresh and retained PostgreSQL
proof still converges through V64. An isolated fresh V1–V64 database started,
all seven workflows ran, a second retained V64 start validated no migration was
needed, and the temporary database was removed after proof.

## Demo evidence

All seven public clients pass on the isolated stack: merchant monitoring,
analyst workspace, historical simulation, governed controls, dispute operations,
trusted developer scenario, and webhook verification/dead-letter/redelivery.
They prove exact origin, digest, lifecycle, dual-control, immutable evidence,
redelivery generation, and unchanged payment/fraud/ledger truth. The governed
demo alone uses the documented local-only `1`/`0` evidence threshold; defaults
remain `100`/`50`.

## Operational signals

No new backend metric or log dimension was added. Existing readiness contributors
were `UP` on fresh and retained starts. Client failures use a closed status family
and safe request IDs; monitoring coverage and endpoint lifecycle remain server
vocabularies. Refresh is explicit and query state is bounded and cleared.

## Known debt

No Chapter 43R debt is accepted. The canonical register remains at zero open
entries. Hosted identity, broad analytics visualization, and real network dispute
semantics remain rejected Phase 4.5 scope rather than hidden debt.

## Custom infrastructure decision

`NOT_APPLICABLE`. The recovery uses Spring MVC, existing idempotency and
PostgreSQL boundaries, React, TypeScript, TanStack Query, and the established
governance scripts. Handwritten CSS and accessible tables remain intentionally
small; no custom queue, protocol, cryptography, charting engine, or auth system
was introduced.

## Escaped-review assessment

`ER-0059`–`ER-0073` are resolved. The later ten-finding review re-proved
point-in-time monitoring, mixed-control simulation, webhook dispatch permits,
live-actor fencing, positive note-read audit, run-owned scenario reports,
authorization consistency, and cockpit proxy/resilience boundaries.

## Source-of-truth reconciliation

The detailed plan, master plan, root/service/cockpit READMEs, daily/deep-dive
notes, weekly review, escaped-review register, Chapter 36–43 artifacts, this
matrix/dossier, and the aggregate gate identify Phase 4.5 as complete through
Chapter 43R. The four required PostgreSQL runs and the full 759-test backend
regression pass with zero skips.

## Merge recommendation

`MERGE`. The cockpit recovery, all inherited production-boundary proofs, 759
backend tests, 140 repository tests, 20 UI tests/build, and aggregate governance
gates pass; no escaped finding or technical debt remains open.
