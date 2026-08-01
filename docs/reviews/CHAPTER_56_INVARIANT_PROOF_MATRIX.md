# Chapter 56 Invariant Proof Matrix

- Chapter: Chapter 56 — Queue Productivity And Decision/Business Insights
- Matrix provenance: PRE_IMPLEMENTATION
- Scope status: FROZEN
- Review status: IN_PROGRESS
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PENDING
- Fresh hostile review: PENDING
- Boundary family review: PENDING
- Custom infrastructure review: NOT_APPLICABLE
- Escaped review assessment: COMPLETE

This matrix was drafted and frozen on 2026-08-02 before Chapter 56 production
code, V91, UI, fixtures, or demos. Chapter 56 remains dependency-blocked until
Chapter 55 is merge-ready; active Phase 5 projections remain an inherited
dependency. Every row is a proof obligation and remains `PENDING`.

Chapter 56 owns denominator-honest decision/outcome and review-operation
insights. It does not claim causal lift, prevented fraud, profit, interchange
savings, blended-currency money, approximate percentiles, operator ranking, a
warehouse/ClickHouse migration, or mutation authority.

## C56-OUT-001

- Lens: PAYMENT_FRAUD
- Invariant: Decision/outcome insights report decision action distribution, review states/outcomes, confirmed-fraud/legitimate/unknown/unlabeled partitions, label coverage, review yield, legitimate-review proxy, chargeback counts, and captured/refunded/disputed amounts from the active `payment-risk-v1` generation without rewriting original decisions, labels, disputes, or ledger truth.
- Failure sequence: Current label is projected backward to decision time, review open and resolved are double-counted, chargeback/refund money is inferred from events instead of reconciled source projection, synthetic traffic enters live totals, or an insight write changes source truth.
- Implementation: `DecisionOutcomeInsightService` queries explicit active generation/cutoff/origin buckets from Phase 5 projection repositories and shapes descriptive DTOs only; no source mutation/event/ledger dependencies are permitted.
- Proof: Planned `DecisionOutcomeInsightContractTests`, source-to-projection reconciliation fixtures, live/synthetic partitions, before/after domain fingerprints, and public payment-to-outcome demo.
- Adversarial evidence: Allow/block/review/3DS; open/assigned/resolved/canceled; fraud/legitimate/unknown/unlabeled/superseded; chargeback/refund overlap; captured/refunded/disputed; bootstrap/canonical; live/synthetic; and projection unavailable/partial.
- Status: PENDING

## C56-DEN-001

- Lens: DATA_ML_LINEAGE
- Invariant: Every rate exposes exact numerator, denominator, unknown/unlabeled count, eligibility definition, UTC range, traffic-origin filter, and computability; zero denominator returns `NOT_COMPUTABLE`, and partial coverage withholds money and dependent rates rather than rendering 0 or an apparently complete value.
- Failure sequence: Unknown labels enter the negative class, empty denominator renders 0%, numerator exceeds denominator silently, partial projection returns money, percentage rounding hides raw counts, or eligibility changes without a contract version.
- Implementation: typed `InsightRate`/`Computability` DTOs, integer count arithmetic with explicit checked invariants, closed metric definitions from the frozen formulas, coverage gates before derived values, and raw numerator/denominator alongside display percentage.
- Proof: Planned `InsightDenominatorPropertyTests`, table partitions for every known/unknown/empty/partial combination, overflow and rounding tests, schema/golden definitions, and browser rendering assertions.
- Adversarial evidence: 0/1/max counts; numerator 0/equal/greater; unknown 0/all/mixed; unlabeled/delayed/superseded; partial/missing coverage; rounding edges; invalid retained aggregate; and metric definition mismatch.
- Status: PENDING

## C56-MNY-001

- Lens: PAYMENT_FRAUD
- Invariant: Money insights remain integer minor units grouped by exact currency and outcome; no endpoint, UI total, drill-through, log, or metric sums or compares USD/EUR/INR/other currencies as one amount, and partial coverage withholds affected money.
- Failure sequence: A map is reduced without currency key, decimal/float conversion loses cents, refund/dispute amounts are counted twice, an unknown currency is silently normalized, or partial coverage displays a grand total.
- Implementation: currency-keyed money DTOs and projection queries, checked integer arithmetic, no floating-point aggregation, explicit source outcome buckets, deterministic ordering, and UI components that render one currency row/card at a time.
- Proof: Planned `CurrencySeparatedInsightTests`, multi-currency/overflow/partial fixtures, source/ledger/projection reconciliation, JSON precision tests, and UI assertions rejecting a blended total.
- Adversarial evidence: One/multiple/unknown currencies; zero/max/overflow amounts; capture/refund/chargeback overlap; partial/stale generation; sort/order changes; JSON/JavaScript integer boundary; and empty money.
- Status: PENDING

## C56-REV-001

- Lens: PAYMENT_FRAUD
- Invariant: Review-operation insights derive current backlog/SLA from authoritative review state at one `sourceAsOf` and range activity only from durable assignment/release/resolution evidence; absent conflict/reopen history is reported unavailable rather than inferred from current state or audit prose.
- Failure sequence: Current status is treated as historical throughput, one reassignment counts as two resolutions, missing history becomes zero attempts, SLA uses request time instead of durable due/transition time, or source review metrics are forced to the projection cutoff.
- Implementation: `ReviewOperationInsightService` uses bounded source queries over review cases, assignment events, resolutions, and explicit durable history; typed availability distinguishes unsupported/missing activity from zero; source `sourceAsOf` is independent of projection freshness.
- Proof: Planned `ReviewOperationInsightContractTests`, transition-history tables, source snapshot tests, SLA boundary fixtures, missing-evidence partitions, and exact current-row/event/resolution reconciliation.
- Adversarial evidence: Open/unassigned/assigned/resolved/canceled; assign/reassign/release/no-op; resolved at SLA before/exact/after; missing assignment event; later state change; source timeout; and projection cutoff before/after sourceAsOf.
- Status: PENDING

## C56-SNP-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: One decision/outcome response freezes one active projection generation/cutoff across all projection queries, while one review-operation response reads authoritative review facts under a bounded source snapshot; a concurrent generation activation or review transition cannot produce internally mixed totals, and the two authorities never claim one shared as-of time.
- Failure sequence: Generation swaps between numerator and denominator queries, review resolves between backlog and SLA reads, retry silently mixes old/new pages, or UI labels projection cutoff as sourceAsOf.
- Implementation: resolve active generation once and pass it explicitly through every projection query; use read-only bounded repeatable read for source review aggregates; include separate typed `projectionContext` and `sourceContext` in combined UI composition.
- Proof: Planned `InsightSnapshotPostgreSqlTests` force activate-before/read-before and review-transition-before/read-before orderings with barriers/lock probes and exact internally consistent response/database postconditions.
- Adversarial evidence: Old/new generation; activate/rollback; bootstrap/partial/fresh/stale; assignment/release/resolution during read; source rollback/timeout; unrelated merchant progress; retry; and combined UI refresh.
- Status: PENDING

## C56-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: Every projection/source query, range, segment, operator breakdown, drill-through filter, cursor/cache key, and error is merchant-qualified from the live risk-operator context; viewers receive merchant aggregates, analysts receive merchant aggregates plus self workload, and only managers receive named operator breakdown without foreign existence/count/timing leakage.
- Failure sequence: Aggregate query omits merchant, operator filter accepts a foreign UUID, analyst selects another operator, viewer receives ranking, drill-through loses tenant, or cached manager data survives role/tenant change.
- Implementation: tenant-qualified repositories/projections, server-derived actor scope, closed authorization policy, not-found/denied non-disclosure, manager-only operator endpoint, self-only analyst filter, and tenant/operator/role/range/generation-qualified UI cache keys cleared on reconnect.
- Proof: Planned `InsightTenantAndRoleContractTests`, equal-shaped two-merchant fixtures, every-role/operator matrix, direct SQL tenant-plan assertions, timing/count equivalence, and browser role-switch/cache tests.
- Adversarial evidence: Foreign merchant/operator/payment/review; viewer/analyst/manager; own/peer/disabled operator; absent versus foreign; copied drill-through/cursor; role downgrade mid-read; cached data; and same aggregate values.
- Status: PENDING

## C56-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: Insight APIs/UI/logs/metrics/exports/cache expose only bounded aggregate values and manager-authorized safe operator identity; they exclude PAN/CVV/PII, raw/HMAC correlation, credentials, idempotency, note/webhook contents, unrestricted metadata, individual transaction lists outside safe drill-through authorization, exception/database text, and operator ranking or comparative scoring.
- Failure sequence: Small segment/filter becomes an identity oracle, error/log includes query values, analyst sees peer performance, chart tooltip leaks raw source metadata, or an export/clipboard path appears without a frozen file boundary.
- Implementation: closed filters/segments, minimum bounded product partitions without arbitrary dimensions, role-projected DTOs, safe drill-through to existing tenant-authorized search, no export endpoint, sanitized diagnostics, and prohibited-value canaries.
- Proof: Planned `InsightPrivacyTests`, aggregate schema/filter inventory, manager/analyst/viewer response comparisons, small/zero segment tests, API/DOM/log/metric canary scans, and route/export absence scans.
- Adversarial evidence: PAN/CVV/email/phone/IP/device/session/HMAC/key/note/webhook/SQL canaries; one-row aggregate; arbitrary segment/dimension; foreign operator; tooltip/error; URL/clipboard/download; and dependency failure.
- Status: PENDING

## C56-API-001

- Lens: API_PRODUCT
- Invariant: Decision-outcome, review-operation, and manager-only operator endpoints accept UTC-aligned half-open ranges defaulting to the previous seven complete days and capped at 31 days, return typed freshness/coverage/as-of/origin/denominator/currency semantics, and generate only safe typed drill-through filters.
- Failure sequence: Current partial day enters default, inverted/future/unaligned/32-day range broadens, endpoint defaults differ, timezone changes buckets, a drill-through uses raw SQL/identity, or unavailable metric fields disappear without status.
- Implementation: shared aligned-range validator, server-owned clock, closed metric/segment/origin enums, explicit availability/computability DTOs, stable ordering, and allowlisted Chapter 53/55 route/filter builders.
- Proof: Planned `InsightApiContractTests`, table/property range/timezone/DST tests, method/media/auth/role contracts, response schema/golden tests, and drill-through round trips.
- Adversarial evidence: Range absent, 0/1/7/31/32 days, start/end exact and inverted, partial current/future, UTC/non-UTC/DST input, every origin/segment/metric, 400/401/403/404/409/429/503, and hostile filter/link.
- Status: PENDING

## C56-UI-001

- Lens: API_PRODUCT
- Invariant: The existing `/workspace/insights` route visibly separates projection outcome freshness from source review as-of, renders numerator/denominator/unknown/not-computable and currency partitions, enforces role-specific operator visibility, provides safe drill-through, and supports loading/empty/partial/stale/denied/unavailable/retry plus keyboard/focus/accessibility without causal overclaim.
- Failure sequence: One “last updated” label merges authorities, 0% replaces not-computable, partial money renders, currencies blend, viewer sees operator names, chart color is the only status cue, or one slice failure blanks others.
- Implementation: typed independent query slices, semantic tables/cards with raw counts and warnings, no cross-currency total, server role projection, allowlisted routes, accessible headings/status/focus, and text that uses descriptive/proxy language only.
- Proof: Planned `decisionAndReviewInsights.test.tsx`, browser role/freshness/partial/drill-through/accessibility tests, copy/text scans for prohibited causal claims, storage/history inspection, and production build.
- Adversarial evidence: Empty/not-computable/partial/stale/fresh; one/multi-currency; unknown/unlabeled; viewer/analyst/manager and downgrade; source/projection slice failures; keyboard/screen-reader status; narrow viewport; and hostile route.
- Status: PENDING

## C56-OBS-001

- Lens: OBSERVABILITY
- Invariant: Bounded signals expose insight type, outcome, freshness/coverage/computability reason, source query timeout, and query-duration/cardinality bucket without tenant, actor, operator, payment, review, generation ID, range value, segment value, currency amount, cursor, route, credential, message, or exception text as metric dimensions.
- Failure sequence: Merchant/operator/range becomes a tag, partial response counts full success, empty denominator is invisible, query timeout logs SQL, or projection/source mismatch cannot be diagnosed.
- Implementation: closed insight/outcome/freshness/computability enums, bounded timers/buckets, safe request-ID diagnostics, readiness for active projection and source query support, and no identifier/value-bearing tags.
- Proof: Planned `InsightObservabilityTests`, cardinality/canary/partial/timeout tests, readiness combinations, and exact metric outcome reconciliation.
- Adversarial evidence: Success/empty/not-computable/partial/stale/unavailable/timeout/denied; high-volume merchants/operators/ranges; database exception; generation swap; source outage; and client cancellation.
- Status: PENDING

## C56-MIG-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: V91 adds only query-support indexes justified by frozen endpoint plans and measured `EXPLAIN` evidence; fresh V1 and retained V90 PostgreSQL converge without changing source/projection values or ownership, indexes remain tenant/range/selectivity appropriate, and deployment/repeated startup never requires a second aggregate store.
- Failure sequence: Speculative index adds write cost without use, index omits tenant leading column, migration blocks or corrupts retained rows, planner still uses unbounded sequential scan at maximum cardinality, or ClickHouse appears without an SLO trigger/ADR.
- Implementation: measured capability-named concurrent-safe index DDL as supported by Flyway/deployment constraints, exact expected plan families, pre/post row digests, schema readiness, and documented query budgets/timeouts.
- Proof: Planned `InsightQueryPlanPostgreSqlTests` and `InsightMigrationPostgreSqlTests` for fresh V1→V91 and retained V90→V91, maximum cardinality/selectivity, accepted covering indexes, source/projection digests, repeated startup, and rollback expectations.
- Adversarial evidence: Empty/small/max rows; one/many merchants; narrow/wide 31-day range; each filter/order; fresh/retained schema; invalid index state; lock timeout; repeated migration; old/new app; and absence of external analytics store.
- Status: PENDING

## C56-SCP-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Chapter 56 owns descriptive PostgreSQL-backed insights and safe UI drill-through only; it cannot claim causal lift/prevented loss/profit/interchange, approximate p50/p95 without a frozen distribution, operator ranking, arbitrary BI/export, ClickHouse/warehouse scale, mutation, or model performance.
- Failure sequence: Marketing copy turns a proxy into causal savings, average is labelled p95, manager list becomes a leaderboard, CSV/export bypasses privacy, infrastructure install substitutes for product proof, or a chart action mutates source state.
- Implementation: closed metric catalogue/copy, source/dependency/route scans, no percentile fields without exact proof, no export/arbitrary dimension endpoints, explicit Phase 7/8 owners, and read-only public demo.
- Proof: Planned semantics/copy/source scans, prohibited endpoint/component inventory, source non-interference fingerprints, and demo safety tests.
- Adversarial evidence: “prevented,” “saved,” “lift,” “profit,” percentile/rank/leaderboard/export/SQL/warehouse/model claims; direct SQL; action button; and hidden synthetic-only alternate path.
- Status: PENDING

## Frozen Insight Definitions

| Metric | Numerator | Denominator | Empty/partial policy |
|---|---|---|---|
| Label coverage | labeled eligible payments | label-eligible payments | empty is `NOT_COMPUTABLE`; partial dependent rate withheld |
| Confirmed-fraud rate | confirmed-fraud labels | known labels | unknown/unlabeled explicit |
| Review yield | confirmed fraud from resolved reviews | resolved reviews with known label | delayed/unknown explicit |
| Legitimate-review proxy | legitimate labels from resolved reviews | resolved reviews with known label | descriptive proxy only |
| Money outcomes | exact minor-unit amount per outcome/currency | not a rate | currency separated; partial withheld |

## Frozen Hostile-Family Coverage

| Phase 6 family | Chapter 56 obligations |
|---|---|
| Outcome semantics | C56-OUT-001, C56-DEN-001, C56-MNY-001 |
| Review productivity | C56-REV-001 |
| Snapshot/generation consistency | C56-SNP-001 |
| Tenant/role/privacy | C56-TEN-001 and C56-SEC-001 |
| API/browser product | C56-API-001 and C56-UI-001 |
| Observability/query migration | C56-OBS-001 and C56-MIG-001 |
| Causal/scale/scope truth | C56-SCP-001 |
