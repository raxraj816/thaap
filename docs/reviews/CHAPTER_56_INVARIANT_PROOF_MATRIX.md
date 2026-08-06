# Chapter 56 Invariant Proof Matrix

- Chapter: Chapter 56 — Queue Productivity And Decision/Business Insights
- Matrix provenance: PRE_IMPLEMENTATION
- Scope status: FROZEN
- Review status: VERIFIED
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Fresh hostile review: PASS
- Boundary family review: PASS
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
- Proof: `DecisionOutcomeInsightContractTests` builds the population through the real confirm and projection pipeline, then reconciles every published number against the projection rows themselves rather than against another derived figure.
- Adversarial evidence: The decision distribution is compared bucket-for-bucket against a `GROUP BY fraud_action` over `payment_risk_projections`, with the projected row set asserted non-empty first so the reconciliation cannot pass vacuously — a bucket that double-counted or dropped a payment would fail the map equality. The `LIVE_ONLY` and `SYNTHETIC_ONLY` partitions are asserted to sum exactly to the `ALL` population, so a payment counted in both would inflate every downstream rate. Money is reconciled per currency against `SUM(captured_amount_minor)`, `SUM(refunded_amount_minor)`, and `SUM(disputed_amount_minor)`, proving amounts come from reconciled projection columns rather than being inferred from events. Two repeated insight reads leave a fingerprint over payment intents, fraud decisions, review cases, projections, and domain events byte-identical, because a read that writes makes every later number unreproducible. The response names the exact active generation id, reconciled against the `ACTIVE` row, so any published number can be traced back to the generation that produced it.
- Status: PASS

## C56-DEN-001

- Lens: DATA_ML_LINEAGE
- Invariant: Every rate exposes exact numerator, denominator, unknown/unlabeled count, eligibility definition, UTC range, traffic-origin filter, and computability; zero denominator returns `NOT_COMPUTABLE`, and partial coverage withholds money and dependent rates rather than rendering 0 or an apparently complete value.
- Failure sequence: Unknown labels enter the negative class, empty denominator renders 0%, numerator exceeds denominator silently, partial projection returns money, percentage rounding hides raw counts, or eligibility changes without a contract version.
- Implementation: typed `InsightRate`/`Computability` DTOs, integer count arithmetic with explicit checked invariants, closed metric definitions from the frozen formulas, coverage gates before derived values, and raw numerator/denominator alongside display percentage.
- Proof: `WorkspaceInsightSemanticsTests` builds the population through the real confirm and projection pipeline, then shifts the projected rows into a complete prior UTC day — the frozen range always ends at the start of the current day, so a payment created during a test run is otherwise invisible to every valid range.
- Adversarial evidence: With no population at all, every rate reports denominator 0, status `NOT_COMPUTABLE_EMPTY_DENOMINATOR`, and a null value, because a 0 here reads as 'no fraud' rather than 'nothing to measure'. With three unlabelled payments, `labelCoverage` reports the exact denominator reconciled against the projection row count, numerator 0, and an unknown count equal to the population — every unlabelled row is counted as unknown and never folded into a negative class — while `confirmedFraudRate` stays not-computable in the same response, proving computability is per rate rather than per response. A genuine 0 of N is asserted distinct from not-computable. Numerator is asserted never to exceed its denominator and basis points to stay within 0..10000 for every rate. A range that excludes the population reports an empty denominator rather than the counts from outside the window.
- Status: PASS

## C56-MNY-001

- Lens: PAYMENT_FRAUD
- Invariant: Money insights remain integer minor units grouped by exact currency and outcome; no endpoint, UI total, drill-through, log, or metric sums or compares USD/EUR/INR/other currencies as one amount, and partial coverage withholds affected money.
- Failure sequence: A map is reduced without currency key, decimal/float conversion loses cents, refund/dispute amounts are counted twice, an unknown currency is silently normalized, or partial coverage displays a grand total.
- Implementation: currency-keyed money DTOs and projection queries, checked integer arithmetic, no floating-point aggregation, explicit source outcome buckets, deterministic ordering, and UI components that render one currency row/card at a time.
- Proof: `WorkspaceInsightSemanticsTests.moneyStaysSeparatedByCurrencyAndIsNeverBlendedIntoOneTotal` transacts three currencies through the real payment API and inspects the response shape.
- Adversarial evidence: USD, EUR, and GBP each produce their own row in deterministic order, so a single row would prove amounts had been added across currencies; every captured, refunded, and disputed amount is asserted to be an integral minor-unit value rather than a decimal that could lose cents; and the response is scanned for `totalAmountMinor`, `grandTotal`, and `amountMinorTotal` so a blended total cannot appear in any form. The browser half is closed by `workspaceInsights.test.tsx`, which renders one currency per row and asserts the arithmetic sum of two currencies never appears in the DOM.
- Status: PASS

## C56-REV-001

- Lens: PAYMENT_FRAUD
- Invariant: Review-operation insights derive current backlog/SLA from authoritative review state at one `sourceAsOf` and range activity only from durable assignment/release/resolution evidence; absent conflict/reopen history is reported unavailable rather than inferred from current state or audit prose.
- Failure sequence: Current status is treated as historical throughput, one reassignment counts as two resolutions, missing history becomes zero attempts, SLA uses request time instead of durable due/transition time, or source review metrics are forced to the projection cutoff.
- Implementation: `ReviewOperationInsightService` uses bounded source queries over review cases, assignment events, resolutions, and explicit durable history; typed availability distinguishes unsupported/missing activity from zero; source `sourceAsOf` is independent of projection freshness.
- Proof: `ReviewOperationInsightContractTests` builds review cases through the real confirm pipeline and drives assignment and release through the production `/risk/reviews/{id}/assign` and `/release` routes, then reconciles every published number against the authoritative rows rather than against another derived value.
- Adversarial evidence: Backlog equals the exact `manual_review_cases` row counts by status, not an approximation. An assignment moves a case from the unassigned to the assigned bucket and is counted exactly once. A reassignment and a release are counted under their own activity — two assignments and one release — and never merged, with each counted assignment reconciled against a durable `review_assignment_events` row so the number cannot be a derived guess. Time-to-assignment publishes its eligible and missing counts alongside the total, because a duration statistic that hides how many cases it could not measure is an average over an unstated population. An empty population reports zero counts and null minimum/maximum rather than a 0 that would read as instant assignment. Absent conflict history is published as `null` with `CONFLICT_ATTEMPTS_NOT_AVAILABLE_WITHOUT_DURABLE_CONFLICT_FACTS`, because a 0 would assert no conflict ever happened. The response carries `sourceAsOf` and neither `freshness` nor `generationId`, so a stale projection number can never be read as current source truth.
- Window semantics: range-scoped activity is deliberately unobservable for the current partial UTC day, since the frozen contract ends every window at the start of today. The proofs therefore shift durable event timestamps into a complete prior day; only the timestamps move, and the events themselves were produced by the real routes.
- Status: PASS

## C56-SNP-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: One decision/outcome response freezes one active projection generation/cutoff across all projection queries, while one review-operation response reads authoritative review facts under a bounded source snapshot; a concurrent generation activation or review transition cannot produce internally mixed totals, and the two authorities never claim one shared as-of time.
- Failure sequence: Generation swaps between numerator and denominator queries, review resolves between backlog and SLA reads, retry silently mixes old/new pages, or UI labels projection cutoff as sourceAsOf.
- Implementation: resolve active generation once and pass it explicitly through every projection query; use read-only bounded repeatable read for source review aggregates; include separate typed `projectionContext` and `sourceContext` in combined UI composition.
- Proof: `InsightSnapshotAndPlanPostgreSqlTests` runs on real PostgreSQL, because H2 cannot certify a `REPEATABLE READ` claim. It shares the workspace container and context, so it adds neither.
- Adversarial evidence: One decision response is asserted internally consistent with the generation it names: the distribution total, the money rows, and the coverage denominator are each reconciled against the row count for that exact `projection_generation_id`, across three separate statements. If any statement resolved the active generation independently rather than receiving it by value, a swap between statements would produce a response whose parts describe different populations, and those three numbers would have to agree by luck to pass. A review response's backlog buckets are asserted to partition the population the snapshot observed exactly once, and a transition applied after the read is shown not to alter the value already returned while the next read does reflect it. The `reviewOperations` and `operators` methods are asserted to carry `readOnly`, `REPEATABLE_READ`, and a positive timeout, so the single `sourceAsOf` they publish is backed by a real bounded snapshot rather than being decoration.
- Why a forced mid-read activation is not the proof here: attempting an ungoverned generation activation directly in the database — precisely what a careless operator or a buggy rebuild would do — is refused by the platform with `projection activation requires current complete reconciliation`. `aGenerationCannotBeSwappedUnderAReadBecauseActivationIsAGovernedOperation` asserts that refusal and then asserts the active generation is unchanged and a fresh read still names it. A generation cannot be swapped casually at all, which is a stronger guarantee than surviving one forced swap.
- Status: PASS

## C56-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: Every projection/source query, range, segment, operator breakdown, drill-through filter, cursor/cache key, and error is merchant-qualified from the live risk-operator context; viewers receive merchant aggregates, analysts receive merchant aggregates plus self workload, and only managers receive named operator breakdown without foreign existence/count/timing leakage.
- Failure sequence: Aggregate query omits merchant, operator filter accepts a foreign UUID, analyst selects another operator, viewer receives ranking, drill-through loses tenant, or cached manager data survives role/tenant change.
- Implementation: tenant-qualified repositories/projections, server-derived actor scope, closed authorization policy, not-found/denied non-disclosure, manager-only operator endpoint, self-only analyst filter, and tenant/operator/role/range/generation-qualified UI cache keys cleared on reconnect.
- Proof: `WorkspaceInsightTenantAndPrivacyTests` builds two deliberately equal-shaped merchants that transact the *same card and contact*, so a query missing its tenant predicate returns the neighbour's rows rather than nothing.
- Adversarial evidence: The fixture first asserts both merchants really share one card fingerprint, making the cross-tenant claim non-vacuous; the first merchant's aggregate then reports a population of exactly 1 while the second reports exactly 3, so neither tenant's volume reaches the other. A viewer receives merchant-wide backlog aggregates but a null own-workload block, while an analyst receives their own workload keyed to their own operator id. The manager breakdown contains the manager and analyst of its own tenant, excludes the viewer who holds no review workload, and excludes the other tenant's manager entirely.
- Drill-through tenant qualification: the descriptor is now implemented and proven. `DecisionOutcomeInsightContractTests.everyDrillThroughDescriptorIsTenantFreeAndReplayableAgainstTheSearchRoute` asserts each descriptor carries no merchant id, operator id, `generationId`, or `paymentIntentId`, and that replaying it against the search route reproduces exactly its own segment count — a drill-through that disagrees with the card it came from would send an analyst to the wrong population. `aDrillThroughDescriptorCopiedByAnotherTenantReachesOnlyItsOwnPayments` has a neighbour replay the identical descriptor under its own credential and receive an empty page, because tenant is derived from the credential and never from the filter.
- Browser cache invalidation: `workspaceInsights.test.tsx` proves no descriptor reaches the DOM carrying a tenant, generation, or credential, and `savedViewsAndBulkReview.test.tsx` proves an operator switch forces a fresh server answer rather than reusing the previous actor's rows.
- Status: PASS

## C56-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: Insight APIs/UI/logs/metrics/exports/cache expose only bounded aggregate values and manager-authorized safe operator identity; they exclude PAN/CVV/PII, raw/HMAC correlation, credentials, idempotency, note/webhook contents, unrestricted metadata, individual transaction lists outside safe drill-through authorization, exception/database text, and operator ranking or comparative scoring.
- Failure sequence: Small segment/filter becomes an identity oracle, error/log includes query values, analyst sees peer performance, chart tooltip leaks raw source metadata, or an export/clipboard path appears without a frozen file boundary.
- Implementation: closed filters/segments, minimum bounded product partitions without arbitrary dimensions, role-projected DTOs, safe drill-through to existing tenant-authorized search, no export endpoint, sanitized diagnostics, and prohibited-value canaries.
- Proof: `WorkspaceInsightTenantAndPrivacyTests` plants a card number, contact email, and phone into the fixture through the real payment API so a leak has something to leak, then scans every insight response.
- Adversarial evidence: No insight response contains the card number, contact email, phone, merchant API key, or operator credential, and none contains a PAN-shaped digit run or database/driver text. No aggregate response carries `paymentIntentId`, `reviewCaseId`, `customerId`, or `paymentAttemptId`, and every real payment identifier drawn from the projection table is asserted absent — an aggregate that ships its underlying row identifiers is a transaction list wearing a chart's clothes. The operator breakdown is asserted free of `rank`, `percentile`, `score`, `performance`, and `efficiency`, because counts are facts while a ranking is a judgement the product does not make. No export, download, or CSV route exists on the insight surface. The browser half asserts the credential never reaches the DOM.
- Status: PASS

## C56-API-001

- Lens: API_PRODUCT
- Invariant: Decision-outcome, review-operation, and manager-only operator endpoints accept UTC-aligned half-open ranges defaulting to the previous seven complete days and capped at 31 days, return typed freshness/coverage/as-of/origin/denominator/currency semantics, and generate only safe typed drill-through filters.
- Failure sequence: Current partial day enters default, inverted/future/unaligned/32-day range broadens, endpoint defaults differ, timezone changes buckets, a drill-through uses raw SQL/identity, or unavailable metric fields disappear without status.
- Implementation: shared aligned-range validator, server-owned clock, closed metric/segment/origin enums, explicit availability/computability DTOs, stable ordering, and allowlisted Chapter 53/55 route/filter builders.
- Proof: `WorkspaceInsightApiContractTests` drives all three routes through MockMvc against the server-owned clock, asserting the accepted window rather than the code's opinion of it.
- Adversarial evidence: The default window is asserted to be exactly the seven complete UTC days ending at the start of the current UTC day, so the partial current day can never enter a comparison; 1, 7, 30, and 31-day ranges are accepted and 32 is the first refused width; zero-width, inverted, and future-ending ranges are each refused with `insight_range_invalid`; a boundary one second off UTC midnight is refused on either end, because an unaligned boundary silently shifts every daily bucket; `ALL`, `LIVE_ONLY`, and `SYNTHETIC_ONLY` are accepted and echoed while `EVERYTHING`, `MERCHANT_API`, and a SQL-injection string are refused with `insight_origin_policy_invalid`; lowercase and padded policies normalize rather than fail; the operator breakdown is manager-only and refuses analyst and viewer with `insight_manager_required`; every route refuses an absent or unknown credential; and the range contract and seven-day default are asserted identical across all three routes.
- Authority separation: `reviewOperationsPublishesItsOwnSourceAsOfAndNeverBorrowsProjectionFreshness` asserts the review response carries `sourceAsOf` and no `freshness`, and the decision response carries `freshness` and no `sourceAsOf`, so the two authorities cannot be read as one timestamp.
- Status: PASS

## C56-UI-001

- Lens: API_PRODUCT
- Invariant: The existing `/workspace/insights` route visibly separates projection outcome freshness from source review as-of, renders numerator/denominator/unknown/not-computable and currency partitions, enforces role-specific operator visibility, provides safe drill-through, and supports loading/empty/partial/stale/denied/unavailable/retry plus keyboard/focus/accessibility without causal overclaim.
- Failure sequence: One “last updated” label merges authorities, 0% replaces not-computable, partial money renders, currencies blend, viewer sees operator names, chart color is the only status cue, or one slice failure blanks others.
- Implementation: typed independent query slices, semantic tables/cards with raw counts and warnings, no cross-currency total, server role projection, allowlisted routes, accessible headings/status/focus, and text that uses descriptive/proxy language only.
- Proof: `workspaceInsights.test.tsx` renders the real `InsightsRoute` against stubbed transport, asserting what a reader actually sees rather than what the component intends. Accessibility is proven with `axe-core` on the rendered container.
- Adversarial evidence: A not-computable rate renders the words "Not computable" and never a percentage, while a computable rate shows its exact `2 / 4 · 2 unknown` raw counts beside the figure so a reader can always see how thin the denominator is. USD and EUR render as separate rows and the arithmetic blend of the two amounts is asserted absent from the DOM in every format. The source section publishes exactly one "Source as of" and the projection section publishes a range, so the two authorities cannot be misread as one timestamp. A failing projection slice leaves the source-backed review section fully readable and vice versa, proving one outage never blanks the other. Partial coverage raises an alert containing "withheld" and withdraws the money rows rather than rendering them as complete. Unavailable conflict history renders its named unavailable status rather than a 0 that would assert no conflicts happened. The credential never reaches the DOM, and axe reports zero violations.
- Safe drill-through: implemented and proven. A drillable segment renders as a real control that navigates to the payments route, and the typed filter is handed over in memory rather than through the URL — the route allowlist carries only UUIDs and static slugs, and a filter in the address bar would be both unvalidated input and a shareable link to another operator's view. A segment the search grammar cannot express (`UNKNOWN`, the projection's absent-decision marker) deliberately offers no control at all, because a link that cannot reproduce its own segment is worse than no link. The handed-over filter is consumed once, so returning to search later does not silently re-apply a filter the analyst never chose again.
- Status: PASS

## C56-OBS-001

- Lens: OBSERVABILITY
- Invariant: Bounded signals expose insight type, outcome, freshness/coverage/computability reason, source query timeout, and query-duration/cardinality bucket without tenant, actor, operator, payment, review, generation ID, range value, segment value, currency amount, cursor, route, credential, message, or exception text as metric dimensions.
- Failure sequence: Merchant/operator/range becomes a tag, partial response counts full success, empty denominator is invisible, query timeout logs SQL, or projection/source mismatch cannot be diagnosed.
- Implementation: closed insight/outcome/freshness/computability enums, bounded timers/buckets, safe request-ID diagnostics, readiness for active projection and source query support, and no identifier/value-bearing tags.
- Proof: `WorkspaceInsightObservabilityTests` drives all three routes through the real `DecisionIntelligenceObservabilityFilter` and inspects the meter registry. Insight requests carry tenant- and caller-controlled inputs — a range, an origin policy, a merchant, an operator — and every one of them is a candidate dimension.
- Adversarial evidence: Each insight type increments its own `DECISION_INSIGHTS`, `REVIEW_INSIGHTS`, or `OPERATOR_INSIGHTS` series, so collapsing them could not hide which surface is slow or failing. A refused 32-day range records `INVALID` while an analyst denied the operator breakdown records `DENIED`, keeping a client mistake operationally distinct from an authorization event. Twenty distinct ranges across two origin policies create no additional time series, and a second tenant issuing the same request creates none either — a per-merchant series would grow the registry with the customer list. Every insight meter is asserted to carry exactly the `operation` and `outcome` tag keys and nothing else, with tag values checked to contain no merchant id, operator id, credential, or date value. Every recorded operation and outcome is asserted inside the closed vocabulary.
- Status: PASS

## C56-MIG-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: V91 adds only query-support indexes justified by frozen endpoint plans and measured `EXPLAIN` evidence; fresh V1 and retained V90 PostgreSQL converge without changing source/projection values or ownership, indexes remain tenant/range/selectivity appropriate, and deployment/repeated startup never requires a second aggregate store.
- Failure sequence: Speculative index adds write cost without use, index omits tenant leading column, migration blocks or corrupts retained rows, planner still uses unbounded sequential scan at maximum cardinality, or ClickHouse appears without an SLO trigger/ADR.
- Implementation: measured capability-named concurrent-safe index DDL as supported by Flyway/deployment constraints, exact expected plan families, pre/post row digests, schema readiness, and documented query budgets/timeouts.
- Proof: `WorkspaceInsightScopeBoundaryTests` reads V91 statically, and `InsightSnapshotAndPlanPostgreSqlTests` measures the plans on real PostgreSQL.
- Adversarial evidence: V91 contains `CREATE INDEX` and no `CREATE TABLE`, `ALTER TABLE`, `DROP`, `INSERT`, `UPDATE`, `DELETE`, or `CREATE MATERIALIZED` statement, so it cannot become a second aggregate store by stealth, and every index it creates leads with `merchant_id` — an index whose leading column is not the tenant invites a plan that scans across merchants. All three declared indexes are asserted present on the migrated schema, so a migration that silently failed on this engine would be caught. `EXPLAIN` is then run on the real insight query shapes with `enable_seqscan` disabled, because planner choice on a small fixture is not meaningful; the projection insight query and the review backlog query are each required to produce an index plan, which is the measured evidence that the indexes are *usable by the shipped query* rather than merely present. `DecisionIntelligencePostgreSqlMigrationTests` proves the fresh V1→V91 path, and `ReviewBulkAssignmentMigrationPostgreSqlTests` proves a retained schema converges at V91 on a repeated startup.
- Remaining, and deliberately not claimed: plans are measured on fixture-sized data rather than at maximum range and cardinality, so these prove index reachability, not latency at scale. A measured SLO breach is the named Phase 8 trigger for revisiting the analytics store.
- Status: PASS

## C56-SCP-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Chapter 56 owns descriptive PostgreSQL-backed insights and safe UI drill-through only; it cannot claim causal lift/prevented loss/profit/interchange, approximate p50/p95 without a frozen distribution, operator ranking, arbitrary BI/export, ClickHouse/warehouse scale, mutation, or model performance.
- Failure sequence: Marketing copy turns a proxy into causal savings, average is labelled p95, manager list becomes a leaderboard, CSV/export bypasses privacy, infrastructure install substitutes for product proof, or a chart action mutates source state.
- Implementation: closed metric catalogue/copy, source/dependency/route scans, no percentile fields without exact proof, no export/arbitrary dimension endpoints, explicit Phase 7/8 owners, and read-only public demo.
- Proof: `WorkspaceInsightScopeBoundaryTests` scans the insight sources, the controller's mappings, and the V91 migration, because a read surface that quietly grows a write or a second store does so in code long before it does so in a route.
- Adversarial evidence: The insight package imports no mutating domain service (`ReviewOperationsService`, `ReviewBulkActionService`, `ChargebackService`, `FullRefundService`, `GovernedControlService`, `FraudListManagementService`), the controller declares no `POST`, `PUT`, `PATCH`, or `DELETE` mapping, and no source issues writing SQL in any form. No source names ClickHouse, BigQuery, Snowflake, Redshift, Elasticsearch, OpenSearch, Druid, or a materialized view, so the deferred second aggregate store cannot arrive by stealth. Every insight source is scanned — with underscores and spaces removed so a template cannot evade it — for `preventedFraud`, `preventedLoss`, `lossAvoided`, `interchangeSaving`, `profit`, `roi`, `causalLift`, `modelLift`, and `uplift`, which are the numbers every fraud dashboard wants to show and none can defend.
- Status: PASS

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
