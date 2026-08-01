# Chapter 49 Invariant Proof Matrix

- Chapter: Chapter 49 — Payment-Risk And Merchant-Risk Analytics
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

Verification update (2026-08-01): V70–V71 and V77–V78 implement generation-
qualified payment and merchant projections, opaque pagination, lineage,
freshness, coverage, origin, and range indexes. Analytics contracts, real
PostgreSQL plan proof, 22 UI tests, production build, and in-app browser proof
passed; PARTIAL money totals are withheld.

Reopened verification update (2026-08-01): live API execution found `ER-0074`
and `ER-0075`. The production-dialect family now executes summary, time series,
freshness scalar mapping, and opaque-cursor continuation on PostgreSQL; V82 and
H2/PostgreSQL rebuild proof align the canonical score domain at `0..10000`
basis points. The fresh public demo requires both five-event streams and passed.

Second reopened hostile review (2026-08-01): `ER-0077`, `ER-0080`, and
`ER-0081` closed the remaining database-to-human boundary. Basis points render
as an explicit percentage; payment, summary, time-series, and projection-history
collections all have lossless signed keyset continuation; the UI discards no
loaded points; and hour/day ranges are aligned half-open UTC intervals with
separate explicit-future and safe server-default policies. V83 also persists
and exposes exact fraud-decision and feature-snapshot identifiers.

This matrix was drafted and frozen on 2026-07-27 after the Phase 5 founder
authorization and before Chapter 49 production code, migration, API, runtime
configuration, UI, test, demo, or runbook changes. It assumes the Chapter 48
consumer substrate has proven inbox-idempotent, ordered generation-local effects.
No analytics implementation or completion claim is made by this artifact.

## Frozen analytics contract

| Boundary | Frozen value |
|---|---|
| Payment projection/generation identifier | `payment-risk-v1` |
| Merchant projection/generation identifier | `merchant-risk-v1` |
| Payment search | `GET /risk/analytics/payments` |
| Payment detail | `GET /risk/analytics/payments/{id}` |
| Merchant summary | `GET /risk/analytics/merchant/summary` |
| Merchant time series | `GET /risk/analytics/merchant/timeseries` |
| Projection status/history | `GET /risk/analytics/projections` |
| Pagination | Opaque cursor; maximum page size `100` |
| Collection continuation | Payment, summary, time series, and projection-generation history all use opaque lossless keyset cursors |
| Score presentation | API/storage use basis points; analyst UI renders `basisPoints / 100` with `%` |
| Time-series range | UTC-aligned half-open hour/day intervals; explicit future bounds rejected; omitted `to` uses the next server-owned boundary |
| Complete-data freshness | `FRESH` when age is at most `60 seconds`; `LAGGING` when greater than `60 seconds` and at most `5 minutes`; `STALE` when greater than `5 minutes` |
| Non-age states | `EMPTY`, `PARTIAL`, and `UNAVAILABLE` |
| Freshness evidence | Source cutoff, generation, last applied event time, last processing time, status, coverage, and traffic-origin policy on every response |
| Time/aggregation zone | UTC |

Freshness age is measured from the last successfully applied processing
checkpoint, not from a mutable domain read. `EMPTY` means the declared source
cutoff contains no eligible events. `PARTIAL` means known coverage is incomplete,
including held/quarantined streams or incomplete bootstrap. `UNAVAILABLE` means
the service cannot establish projection/checkpoint truth. Those three states are
never rendered as a numeric zero or silently collapsed into an age bucket.

## C49-PAY-001

- Lens: PAYMENT_FRAUD
- Invariant: `payment-risk-v1` explains each tenant-qualified payment lifecycle and risk outcome while captured, refunded, and disputed amounts remain summaries of ledger-authoritative facts rather than new money truth.
- Failure sequence: A duplicate event doubles an amount, refund and chargeback overlap incorrectly, review/label updates erase prior evidence, or the projection is treated as authority for a payment action.
- Implementation: The planned projection will store safe payment/attempt/processor/decision/control/feature/review/label lineage, generation, and last source position while deriving monetary summaries only from canonical committed facts.
- Proof: contract `paymentRiskProjectionReconcilesLifecycleAndMoneyWithoutBecomingSourceTruth` will compare projection results with payment, authorization, balanced ledger, refund, chargeback, review, and label source evidence.
- Adversarial evidence: Duplicate capture/refund/chargeback, partial lifecycle, terminal event followed by illegal fact, refund-chargeback overlap, later review/label, and attempted source mutation through analytics.
- Status: PASS

## C49-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: Every analytics row, cursor, filter, projection status, generation, coverage result, and endpoint lookup derives merchant identity from the authenticated context and discloses no foreign existence.
- Failure sequence: A caller copies a foreign payment ID or cursor, substitutes a merchant filter, or projection history returns another tenant's checkpoint or generation metadata.
- Implementation: generation-qualified tables and queries will use tenant-composite keys; endpoint DTOs will expose no authoritative merchant input and opaque cursors will bind tenant, query shape, ordering, and generation.
- Proof: two-merchant API/PostgreSQL suite `analyticsEndpointsRejectForeignIdsCursorsAndProjectionMetadata` will exercise every frozen route and same-shaped identifiers.
- Adversarial evidence: Foreign payment ID, cursor reuse across tenants/routes/filters/generations, omitted/forged merchant query parameter, viewer/analyst/manager roles, and pagination boundary.
- Status: PASS

## C49-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: Analytics persist and return only safe identifiers, governed enums, amounts, bounded reason/control references, origin, coverage, and freshness; raw RiskContext, notes, credentials, PII, raw device/session identifiers, unrestricted metadata, payloads, and exception text are absent.
- Failure sequence: Projection handler copies event payload wholesale, API returns protected note or raw identifier, browser persists a response/credential, or a query failure exposes SQL/exception text.
- Implementation: schemas and DTOs will be explicit allowlists; logs/metrics will use bounded route/projection/status dimensions; the cockpit will retain credentials and payloads in memory only.
- Proof: canary suite `analyticsProjectionApiUiAndTelemetryExcludeProhibitedData` will scan database, API, logs, metrics, audit, fixtures, rendered UI, browser storage, and generated reports.
- Adversarial evidence: Protected note, email/phone/address/IP, raw device/session, API key, idempotency material, event payload, SQL/exception content, foreign UUID, and browser refresh/storage partitions.
- Status: PASS

## C49-TX-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Concurrent duplicate, delayed, and different-stream events cannot double payment facts or merchant aggregates, regress a checkpoint, or expose rows from a partially applied transaction.
- Failure sequence: Two handlers update the same UTC bucket, a duplicate increments twice, a delayed predecessor arrives after a successor, or a reader observes payment detail without its aggregate/checkpoint effect.
- Implementation: handlers will use the Chapter 48 inbox transaction, generation-qualified keys, stream positions, deterministic upsert/update rules, and database constraints for one canonical effect per event.
- Proof: forced PostgreSQL tests `concurrentDuplicateAndBucketUpdatesApplyOneDeterministicAnalyticsEffect` and `readerNeverObservesPartialProjectionTransaction` will place transactions at row locks and assert durable postconditions.
- Adversarial evidence: Same event concurrently, two events in one UTC bucket, out-of-order same stream, unrelated streams, transaction rollback, read during write, and exact checkpoint boundary.
- Status: PASS

## C49-DATA-001

- Lens: DATA_ML_LINEAGE
- Invariant: Every row reports projection generation, event cutoff, last source event/time, processing time, origin policy, coverage, evaluator version, and point-in-time label availability without future-label or current-context leakage.
- Failure sequence: Rebuild applies a later label to an earlier decision, synthetic traffic enters a LIVE_ONLY view, an aggregate denominator omits unknown/unlabelled rows, or current Redis/policy state changes historical output.
- Implementation: projection schemas will retain immutable decision/feature/control/label identities and availability times; merchant aggregates will make numerator, denominator, unknown/unlabelled coverage, origin policy, and UTC interval explicit.
- Proof: table-driven test `analyticsRespectLabelAvailabilityOriginPolicyAndCoverageAtCutoff` will cover before/exact/after availability, contradictory/superseded labels, synthetic inclusion, and missing feature/source partitions.
- Adversarial evidence: Later label/dispute, mixed controls, LIVE_ONLY versus INCLUDE_SYNTHETIC, unknown/unlabelled denominator, missing feature, unsupported event, and changed Redis/current RiskContext.
- Status: PASS

## C49-API-001

- Lens: API_PRODUCT
- Invariant: The five frozen `GET` endpoints are tenant-scoped, role-filtered, cursor-paginated, capped at 100 rows, and return a freshness block; invalid/foreign/stale cursors and page sizes above 100 fail with stable safe errors.
- Failure sequence: An unbounded request exhausts the database, a cursor crosses route/filter/generation, detail omits freshness, or missing/stale data is returned as zero/success.
- Implementation: controllers will use typed filters, opaque bound cursors, explicit page-size validation, safe tenant-scoped not-found behavior, and one shared freshness/coverage response contract.
- Proof: route cross-product `analyticsRoutesEnforcePageCursorRoleAndFreshnessContract` will cover all five paths, page sizes 1/100/101, cursor substitution, roles, filter combinations, and every freshness state.
- Adversarial evidence: Unknown fields/filters, malformed/foreign cursor, negative/zero/101 page sizes, copied detail ID, unsupported origin policy, permission denial, stale/partial/unavailable dependency, and retry.
- Status: PASS

## C49-FRESH-001

- Lens: API_PRODUCT
- Invariant: Freshness is truthful and deterministic: `FRESH` at age `<=60s`, `LAGGING` at `>60s` and `<=5m`, `STALE` at `>5m`, with distinct `EMPTY`, `PARTIAL`, and `UNAVAILABLE` states that are never rendered as zero.
- Failure sequence: Exact 60-second or 5-minute boundaries flip incorrectly, occurrence time is mistaken for processing lag, a quarantined stream is called fresh, or dependency failure displays an empty successful chart.
- Implementation: freshness evaluation will use database-precision checkpoint processing time and explicit precedence for unavailable, partial, empty, then complete-data age states.
- Proof: clock-controlled API/UI test `freshnessPartitionsUseExactBoundariesAndNeverRenderMissingAsZero` will exercise before/exact/after 60 seconds and 5 minutes plus all non-age states.
- Adversarial evidence: 59.999999s/60s/60.000001s, 4:59.999999/5:00/5:00.000001, no eligible events, known missing coverage, unavailable checkpoint/dependency, and old event occurrence processed recently.
- Status: PASS

## C49-OBS-001

- Lens: OBSERVABILITY
- Invariant: Projection applied count, handler latency, source-to-visible latency, checkpoint age, freshness state, coverage, query latency, and failure outcome are observable through bounded projection/route/status dimensions.
- Failure sequence: Global healthy state hides a stale tenant, a partial result emits success, query latency lacks route context, or merchant/payment/event IDs become metric labels.
- Implementation: transaction-aware metrics, safe structured logs, and readiness will use closed projection names `payment-risk-v1`/`merchant-risk-v1`, route templates, freshness states, and bounded outcomes only.
- Proof: telemetry test `analyticsSignalsExposeFreshnessCoverageAndQueryFailureWithSafeDimensions` will enumerate all states and scan labels/log fields for sensitive or high-cardinality values.
- Adversarial evidence: Fresh/lagging/stale/empty/partial/unavailable, slow query, database outage, no active generation, incompatible handler, foreign denial, malformed cursor, and identifier canaries.
- Status: PASS

## C49-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Fresh and retained migrations install generation-qualified projection tables and indexes without inventing historical domain events; query/readiness behavior remains bounded under empty state, lag, dependency failure, and incompatible generation.
- Failure sequence: Upgrade backfills fake event lineage, missing indexes allow unbounded scans, no active generation reports healthy, or rollback exposes a schema incompatible with the running handler.
- Implementation: forward-only migrations will create empty/generation-qualified structures or explicit source-snapshot provenance, tenant/filter/order indexes, legal coverage/status bounds, and active-generation compatibility checks.
- Proof: fresh/retained PostgreSQL migration and query-plan tests `analyticsMigrationPreservesSourceTruthAndQueriesRemainIndexBounded` plus readiness/rollback configuration tests will inspect rows, constraints, indexes, and plans.
- Adversarial evidence: Fresh install, retained V64/Phase-5 state, empty projection, realistic volume, invalid enum/status, missing active generation, incompatible version, database outage, and rollback to retained compatible generation.
- Status: PASS

## C49-DEMO-001

- Lens: API_PRODUCT
- Invariant: A merchant or analyst can use documented APIs and the thin cockpit to find and explain a risky payment and merchant trend with explicit freshness, coverage, origin, and generation without reading source code.
- Failure sequence: Demo queries source tables, hides synthetic/unknown coverage, requires a manual database edit, stores credentials, or presents stale/partial data as complete.
- Implementation: The planned public demo and cockpit will call only the five frozen analytics endpoints, keep credentials in memory, render all seven freshness states, and reconcile returned facts to durable projection/event evidence separately from source truth.
- Proof: executable demo `demo-payment-risk-analytics` and browser contracts will prove risky-payment search/detail, merchant summary/time series, projection status, cursor pagination, memory-only credentials, and durable reconciliation.
- Adversarial evidence: Fresh tenant, two tenants, synthetic and merchant traffic, delayed label, duplicate delivery, stale/partial/unavailable states, refresh, denied role, retry, and no hidden configuration.
- Status: PASS

## Frozen boundary table

| Boundary | Valid | Rejected |
|---|---|---|
| Projection | `payment-risk-v1`, `merchant-risk-v1`, tenant/generation qualified | Ungenerated/global mutable analytics truth |
| Routes | Five frozen tenant-scoped `GET` endpoints | Raw SQL/event download, caller-selected tenant |
| Pagination | Opaque bound cursor, page size at most 100 | Unbounded page, cursor crossing tenant/route/filter/generation |
| Freshness | `FRESH <=60s`, `LAGGING >60s <=5m`, `STALE >5m` | Approximate/undocumented thresholds, occurrence-time lag |
| Missing truth | Explicit `EMPTY`, `PARTIAL`, `UNAVAILABLE` | Zero-filled or successful-complete fiction |
| Money | Event-derived summaries reconciled to ledger authority | Analytics as settlement/accounting authority |
| Outcome lineage | Availability-time labels, explicit origin/coverage/cutoff | Future labels, current Redis/context, hidden synthetic inclusion |
| UI/telemetry | Memory-only credentials and bounded dimensions | Browser persistence, payload/PII/identifier labels |
