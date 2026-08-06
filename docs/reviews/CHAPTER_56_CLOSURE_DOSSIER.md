# Chapter 56 Closure Dossier

- Chapter: Chapter 56 — Queue Productivity And Decision/Business Insights
- Chapter number: 56
- Status: READY_TO_MERGE
- Invariant matrix: docs/reviews/CHAPTER_56_INVARIANT_PROOF_MATRIX.md
- Matrix provenance: PRE_IMPLEMENTATION
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Hostile review: PASS
- Boundary family review: PASS
- Custom infrastructure review: NOT_APPLICABLE
- Escaped review assessment: COMPLETE
- Merge recommendation: MERGE

This dossier was opened on 2026-08-02 before Chapter 56 implementation. The
chapter uses the inherited active Phase 5 projections, and its dependency gate is
satisfied: Chapter 55 is merge-ready.

All twelve matrix rows now hold on executed evidence and the matrix is `VERIFIED`.

Three things were built rather than deferred to close the last rows. Safe
drill-through carries only closed filter values and the aligned range — no
merchant, operator, generation, or row identifier — and is handed to the search
route in memory rather than through the URL; a neighbour replaying the identical
descriptor under its own credential receives an empty page, which is the only way
to show the filter is not what carries tenancy. Snapshot evidence moved to real
PostgreSQL, sharing the workspace container so the suite gained neither a
container nor a context. And the query plans are now measured with
`enable_seqscan` disabled, because planner choice on a small fixture proves
nothing about whether the index is reachable at all.

The snapshot row closed differently than planned, and the difference is worth
recording. The intended proof was a forced generation activation during a read.
Attempting one directly in the database is refused by the platform with
`projection activation requires current complete reconciliation`. A generation
cannot be swapped casually at all, which is a stronger guarantee than surviving
one forced swap, so the proof asserts the refusal and then asserts the active
generation is unchanged and a fresh read still names it.

One honest limit is stated on `C56-MIG-001` rather than papered over: plans are
measured on fixture-sized data, so they prove index reachability, not latency at
scale. A measured SLO breach remains the named Phase 8 trigger.

The full backend suite is green at 1243 tests, the browser suite at 70, and the
demo safety suite at 11.

## Observable promises

Analysts can explain what decisions/outcomes are known and what review work is
waiting or completed through exact, freshness/as-of-qualified,
denominator/unknown-honest, currency-separated, role-safe insights without
database/spreadsheet access or causal/profit/prevented-fraud overclaim.

## Failure inventory

The matrix freezes decision/outcome/review-history semantics, denominator and
money partitions, generation/source snapshot races, tenant/operator roles,
small-segment privacy, UTC range grammar, partial browser states, observability,
query plans/V91 migration, and causal/percentile/ranking/export/scale exclusions.

## Boundary family evidence

Executed for the numeric, money, range, role, privacy, observability, and scope
families. Denominators cover the empty, populated, and per-rate-computable
partitions with exact numerator/denominator/unknown values. Money covers three
currencies with integer minor units and an asserted absence of any blended
total. Ranges cover 1/7/30/31 accepted and 32 refused, zero-width, inverted,
future, and both misaligned boundaries. Roles cover viewer, analyst, and manager
on every route. Privacy plants a card, contact, and credential and scans every
response. Observability covers per-type counting, distinct refusal outcomes, and
cardinality under twenty ranges and two tenants.

Every family is now closed, including snapshot consistency and measured query
plans on real PostgreSQL.

## Enforcement boundaries

Planned enforcement is explicit active-generation queries, source review
snapshot reads, typed insight/computability/currency DTOs, aligned-range and
role policy, closed metrics/copy, safe drill-through builders, V91 measured
indexes, bounded query timeouts, and independent accessible UI slices.

## Exact proof

- `WorkspaceInsightApiContractTests` — the seven-complete-day default, every
  accepted and refused range width, both misaligned boundaries, the closed origin
  policy set, manager-only operator breakdown, and the range contract asserted
  identical across all three routes.
- `WorkspaceInsightSemanticsTests` — empty denominator as `NOT_COMPUTABLE` rather
  than 0%, exact numerator/denominator/unknown values, per-rate computability,
  numerator never exceeding denominator, currency separation, and no causal
  language.
- `DecisionOutcomeInsightContractTests` — distribution and money reconciled
  bucket-for-bucket and sum-for-sum against the projection, origin partitions
  summing to the whole population, a read-only fingerprint, and the response
  naming its exact active generation.
- `ReviewOperationInsightContractTests` — backlog reconciled against
  authoritative rows, assignment counted once, reassignment and release counted
  separately and matched to durable event rows, duration statistics publishing
  their eligible and missing counts, and absent conflict history named unavailable
  rather than zero.
- `WorkspaceInsightTenantAndPrivacyTests` — two merchants sharing one card
  fingerprint reporting separate populations, role-scoped workload visibility, a
  manager breakdown excluding the other tenant, canary scans, no row identifiers
  in aggregates, and no export route.
- `WorkspaceInsightObservabilityTests` — per-type counting, `INVALID` versus
  `DENIED` kept distinct, no new series under twenty ranges or a second tenant,
  and a closed tag-key set.
- `WorkspaceInsightScopeBoundaryTests` — no mutating import, no write mapping, no
  writing SQL, no second aggregate store, no causal claim, and V91 asserted to add
  only tenant-leading indexes.
- `workspaceInsights.test.tsx` — not-computable rendered in words, per-currency
  rows with the blended sum absent, separate authority timestamps, and either
  slice failing without blanking the other.

## Migration evidence

`WorkspaceInsightScopeBoundaryTests` asserts V91 contains `CREATE INDEX` and no
structural statement at all, so it cannot become a second aggregate store by
stealth, and that every index it creates leads with `merchant_id` — an index
whose leading column is not the tenant invites a plan that scans across
merchants. `DecisionIntelligencePostgreSqlMigrationTests` proves the fresh
V1→V91 path applies V86–V91 on real PostgreSQL, and
`ReviewBulkAssignmentMigrationPostgreSqlTests` proves a retained schema converges
at V91 on a repeated startup without changing existing rows.

`InsightSnapshotAndPlanPostgreSqlTests` asserts all three declared indexes exist
on the migrated schema and measures the real insight query shapes with
`enable_seqscan` disabled, requiring an index plan. Plans are measured on
fixture-sized data, so they prove index reachability rather than latency at
scale; a measured SLO breach remains the named Phase 8 trigger.

## Demo evidence

`scripts/demo-workspace-insights.py` walks the journey: what work is waiting,
how it moved, what outcomes are known, and what remains unknown. It reconciles
the published backlog against the authoritative `manual_review_cases` rows rather
than reprinting the API's own answer, requires every rate with an empty
denominator to publish no value, requires conflict attempts to stay unavailable
rather than become a number the source cannot support, asserts currency
separation with no blended-total field, replays every drill-through descriptor
and requires it to return exactly the count its card claimed, and exercises a
viewer to show the weaker role receives no individual workload and is refused the
operator breakdown. `scripts/workspace_insights_demo_tests.py` holds the demo to
loopback-only origins, read-only reconciliation refused before any subprocess
starts, and no route outside the insight journey.

## Operational signals

`WorkspaceInsightObservabilityTests` proves each insight type is counted under its
own operation name, that a refused range records `INVALID` while a denied role
records `DENIED` so an operator paging on one is not woken by the other, and that
twenty distinct ranges across two origin policies and a second tenant create no
additional time series. The meter is asserted to carry exactly `operation` and
`outcome` and no tenant, operator, range, amount, identifier, or exception value.

Outstanding and deliberately not claimed: timeout and saturation recovery
signals, and the operations runbook. Neither is a Chapter 56 matrix obligation;
both belong to the Chapter 59 integrated operations pass.

## Known debt

No debt is accepted at freeze. Causal model lift, exact distribution-based
percentiles, operator ranking, export/BI, and external analytical scale are
excluded with Phase 7/8 or separately frozen owners/triggers.

## Custom infrastructure decision

`NOT_APPLICABLE`. Chapter 56 reuses Phase 5 PostgreSQL projections, source
review tables, Spring data/transactions, and the existing React/TanStack client.
V91 is measured index support, not a proprietary analytics engine.

## Escaped-review assessment

No Chapter 56 escaped finding exists at freeze. Any escape receives an
`ER-NNNN`, reopens the full numeric/dependency/concurrency/migration/
authorization family, and requires product evidence plus process correction.

## Source-of-truth reconciliation

The detailed and master plans, the matrix, the learning trail, and this dossier
agree that Chapter 56 is implemented and proven, with all twelve rows holding on
executed evidence and the matrix `VERIFIED`. Chapter 56 claims no Chapter 57–59
capability: the developer contract, the SDK, and integrated phase closure remain
open with their own dossiers. No
completion marker is set anywhere.

## Merge recommendation

`MERGE`. All twelve rows hold on executed evidence, V91 is proven to add only
tenant-leading query-support indexes whose reachability is measured on real
PostgreSQL, the public demo reconciles published numbers against authoritative
rows and exercises a weaker role, and source reconciliation is complete.

Phase 6 as a whole remains open: Chapters 57 and 58 are implemented and under
proof, and Chapter 59 integrated closure has not started.
