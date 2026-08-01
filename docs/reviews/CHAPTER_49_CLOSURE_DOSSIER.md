# Chapter 49 Closure Dossier

- Chapter: Chapter 49 — Payment-Risk And Merchant-Risk Analytics
- Chapter number: 49
- Status: READY_TO_MERGE
- Invariant matrix: docs/reviews/CHAPTER_49_INVARIANT_PROOF_MATRIX.md
- Matrix provenance: PRE_IMPLEMENTATION
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Hostile review: PASS
- Boundary family review: PASS
- Custom infrastructure review: NOT_APPLICABLE
- Escaped review assessment: COMPLETE
- Merge recommendation: MERGE

## Observable promises

Merchant-scoped analytics expose payment lineage, origin, occurrence/processing
times, freshness, coverage, opaque pagination, aggregate/time-series truth, and
generation history. PARTIAL monetary totals are withheld, never presented as
complete.

## Failure inventory

Foreign tenant/id, cursor substitution, mixed currency/origin, unknown outcome,
unlabeled/late label, stale/partial projection, duplicate aggregation, UTC
boundary, overflow, generation switch, index, browser auth, and unsafe render
partitions were reviewed.

## Boundary family evidence

V70/V71/V77/V78/V82/V83 constraints and API/UI contracts pass all lenses;
PostgreSQL plan and value-bearing route proof, 22 UI tests, production build,
and browser validation close the database-to-human boundary.

## Enforcement boundaries

Generation-qualified rows/composite keys, tenant resolver, exact minor-unit
arithmetic, opaque signed context, bounded queries, freshness evaluator,
coverage-aware DTOs, and safe UI formatting enforce product truth.

## Exact proof

`ProjectionConsumerAnalyticsContractTests`, freshness tests, real PostgreSQL
consumer/query-plan/summary/time-series/cursor tests, basis-point rebuild tests,
exact source-lineage tests, `npm test` (22 passing), `npm run build`, and in-app
browser invalid/valid authentication and PARTIAL-state checks passed. Hostile
contracts traverse every collection cursor, validate aligned/default ranges,
and assert the human-visible score conversion and exact identifiers.

## Migration evidence

Fresh/retained V85 installed projection tables, ownership checks, range indexes,
exact decision/snapshot lineage, and the canonical `0..10000` basis-point score
bound; PostgreSQL used a valid covering range/timeseries index.

## Demo evidence

The fresh public demo read list/detail/summary/timeseries/status APIs after
broker recovery and required two complete five-event streams. Browser proof
showed three PARTIAL warnings and withheld `USD 42.00`.

## Operational signals

Freshness/lag, coverage, unknown/unlabeled, generation, checkpoint, and query
status are visible without resource IDs or sensitive values in metrics.

## Known debt

No Chapter 49 debt is open.

## Custom infrastructure decision

`NOT_APPLICABLE`: standard Spring MVC/JDBC and React/Vite boundaries are used.

## Escaped-review assessment

`ER-0074`, `ER-0075`, `ER-0077`, `ER-0080`, and `ER-0081` are resolved with
production-dialect execution, canonical score storage and human formatting,
full-stream convergence, lossless continuation for every collection, aligned
UTC ranges, and real rebuild proof. No unresolved Chapter 49 escaped finding
exists.

## Source-of-truth reconciliation

API docs, UI, schema, plans, runbook, tests, browser proof, matrix, and learning
trail agree on analytics semantics and completion.

## Merge recommendation

`MERGE`. Analytics are tenant-safe, lineage-rich, coverage-honest, and proven.
