# Chapter 36 Invariant Proof Matrix

- Chapter: Chapter 36 — Fraud Taxonomy And Merchant-Risk Monitoring
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

Post-closure recovery completed on 2026-07-27 for `ER-0064`. Enumeration
evidence is visible only when its immutable link satisfies `linked_at <= asOf`;
both summary and drill-down cohort replay preserve the pre-link result, while
the exact link instant makes the evidence visible. The inherited PostgreSQL
suite passed 7/7 on PostgreSQL 16.14 and the full backend suite passed 759/759
without skips.

This matrix was drafted on 2026-07-25 before Chapter 36 production code,
migration, or test changes. It derives from the proposed promises and hostile
families in `docs/PHASE_4_5_DETAILED_IMPLEMENTATION_PLAN.md`. Planned production
boundaries and test names below are proof obligations, not implementation or
completion claims. Founder implementation authorization confirmed the Phase
4.5 product decisions on 2026-07-25 before production code or migration
changes. The matrix is `FROZEN`; implementation, traceability, hostile review,
and production-boundary proof are verified.

## C36-PAY-001

- Lens: PAYMENT_FRAUD
- Invariant: Merchant-risk monitoring reports authorization, decline, chargeback, fraud, review, enumeration, and volume facts without changing PaymentIntent, PaymentAttempt, Authorization, FraudDecision, label, refund, chargeback, ledger, or active merchant-profile truth.
- Failure sequence: A monitoring request writes a domain row, treats a late label as authorization-time truth, changes an earlier decision/action, or automatically activates a risk tier while building a metric.
- Implementation: Planned read-only monitoring queries over immutable PostgreSQL payment/fraud facts, explicit `asOf`, label `available_at`, optional append-only request/result snapshots, and append-only profile recommendations isolated from the existing dual-controlled profile lifecycle.
- Proof: Planned `MerchantRiskMonitoringContractTests`, `MerchantRiskMonitoringPostgreSqlTests`, source-level mutation prohibition, before/after row digests, and a public monitoring reconciliation demo.
- Adversarial evidence: Late fraud label, late chargeback, refunded chargeback cohort, contradictory labels, open review, risky-merchant recommendation, and a forced persistence failure while an optional snapshot is recorded.
- Status: PASS

## C36-PAY-002

- Lens: PAYMENT_FRAUD
- Invariant: Every published metric has one versioned formula with exact numerator, denominator, half-open time window, `asOf`, coverage, and traffic-origin policy; absence or insufficient history never renders as zero.
- Failure sequence: Approval and decline use different denominators, a boundary event is double-counted, a zero denominator produces zero percent, or an incomplete volume bucket creates a false spike.
- Implementation: Planned application-owned metric catalogue, typed coverage status, UTC half-open intervals, database-consistent `asOf`, and formula-specific denominator queries.
- Proof: Planned table-driven `MerchantRiskMetricDefinitionTests` and PostgreSQL fixtures for before/at/after boundaries, zero/one/many denominator, incomplete bucket, minimum-history, and exact decimal rounding.
- Adversarial evidence: No authorizations, only pending outcomes, all synthetic traffic, denominator overflow, repeated equal timestamps, and a spike window exactly at `asOf`.
- Status: PASS

## C36-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: Taxonomy resolution, monitoring cohorts, drill-down rows, optional snapshots, and cursors are derived from the authenticated merchant and cannot include or disclose another merchant's facts.
- Failure sequence: A caller substitutes another merchant's snapshot/cursor/resource ID, or a join omits `merchant_id` and contributes a foreign payment, label, chargeback, review, or incident.
- Implementation: Planned operator-derived MerchantContext, merchant-qualified repository predicates and composite keys, opaque tenant-bound cursor identity, and same-tenant database constraints.
- Proof: Planned `MerchantRiskMonitoringPostgreSqlTests`, direct PostgreSQL composite-key tests, and two-merchant equal-ID/equal-time cohort reconciliation.
- Adversarial evidence: Foreign UUIDs, same external chargeback identity, shared simulator card/IP HMACs, copied cursor, shared taxonomy code, and disabled foreign operator credential.
- Status: PASS

## C36-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: Monitoring and taxonomy surfaces expose only safe closed aggregate dimensions and resource references; raw/HMACed identities, PAN/CVV, PII, credentials, note content, list values, exception text, and unbounded labels never enter responses, persistence, logs, metrics, audit, or UI state.
- Failure sequence: Drill-down serializes a broad entity, a grouping uses an HMAC identity, a SQL/validation failure logs a prohibited value, or a taxonomy display value becomes an unbounded metric tag.
- Implementation: Planned allowlisted response DTOs, closed taxonomy/metric catalogues, aggregate-only dimensions, bounded error mapping, safe query projections, and existing secret/logging policy.
- Proof: Planned prohibited-value canary scans across API, optional snapshots, audit, logs, metrics, and frontend state plus source scans for broad entity serialization.
- Adversarial evidence: Seeded PAN/CVV/email/IP/device/session/API keys, hostile Unicode taxonomy/filter input, database constraint failure, and exact HMAC aliases from current/retained versions.
- Status: PASS

## C36-CON-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: A monitoring result observes one coherent `asOf` cohort and optional snapshot creation is idempotent without locking or blocking concurrent payment, review, label, chargeback, or policy mutations.
- Failure sequence: Numerator and denominator observe different commits, two identical requests create divergent snapshot truth, or a long aggregate read holds payment locks while a confirm/refund completes.
- Implementation: Planned short read transaction with a captured database time and documented isolation, canonical request digest, unique tenant/request identity for optional snapshots, and no `FOR UPDATE` on domain facts.
- Proof: Planned deterministic PostgreSQL tests that pause between numerator/denominator boundaries while a label or chargeback commits, plus duplicate-snapshot and payment-progress lock probes.
- Adversarial evidence: Label-first/read-first orderings, chargeback at exact boundary, same/different idempotency key, snapshot insert failure, and concurrent live/synthetic requests.
- Status: PASS

## C36-DATA-001

- Lens: DATA_ML_LINEAGE
- Invariant: Fraud and dispute metrics use only evidence available by the declared `asOf`; supersession and contradictory-label policy is explicit, and unlabeled attempts remain coverage rather than being inferred legitimate.
- Failure sequence: A chargeback or analyst label is backdated into an earlier cohort, event time substitutes for `available_at`, superseded labels double-count, or unlabeled rows improve a legitimate rate.
- Implementation: Point-in-time label and incident-evidence resolvers require label/chargeback `available_at <= asOf` and enumeration-link `linked_at <= asOf`; immutable taxonomy mapping, explicit label-policy/version and exclusion reasons, and traffic-origin lineage remain visible on every result.
- Proof: `MerchantRiskMonitoringContractTests.lateChargebackAndFraudLabelEnterOnlyReportsWhoseAsOfCanObserveThem` and `lateIncidentEvidenceCannotRewriteEnumerationSummaryOrCohortBeforeLinkedAt` exercise event/evidence/availability/link times, exact boundary inclusion, and report replay across multiple `asOf` values.
- Adversarial evidence: Future label, out-of-order chargeback, incident evidence linked after a frozen `asOf`, exact `available_at == asOf`, exact `linked_at == asOf`, cross-payment supersession, contradictory FRAUD/LEGITIMATE evidence, and legacy labels resolved through V1 without rewriting.
- Status: PASS

## C36-DATA-002

- Lens: DATA_ML_LINEAGE
- Invariant: Fraud taxonomy codes are immutable, versioned, and consistently map labels, chargeback reasons, incident triggers, synthetic scenarios, filters, and reporting without fabricating historical classifications.
- Failure sequence: A display rename changes a stored label's meaning, a migration assigns an unsupported type, the same code maps to two families, or an unknown legacy value is guessed into confirmed fraud.
- Implementation: Planned application-owned immutable taxonomy catalogue, unique version/code identities, semantic migration constraints, explicit `UNCLASSIFIED` reporting bucket, and new-label version binding.
- Proof: Planned catalogue property tests, fresh/retained PostgreSQL migration tests, mapping cross-product tests, and byte-stability comparison of existing label rows.
- Adversarial evidence: Duplicate code, unknown fraud type, unsupported chargeback reason, inactive taxonomy version, scenario/filter mismatch, and retained V49 rows with no fabricated new label.
- Status: PASS

## C36-API-001

- Lens: API_PRODUCT
- Invariant: Monitoring APIs are bounded, role-aware, deterministic, strict in grammar, stable at cursor/time ties, explicit about partial coverage, and cannot be mistaken for model probability or network truth.
- Failure sequence: Oversized intervals exhaust the service, malformed cursors skip rows, unknown dimensions succeed, VIEWER sees manager-only drill-down, or response language calls a deterministic ratio risk probability.
- Implementation: Planned typed DTOs, closed metric/filter enums, maximum interval/page/cardinality, keyset cursors, standard error contract, and reviewed product vocabulary.
- Proof: Planned API table tests for role, interval, set, cursor, time-zone, JSON/media grammar, equal-time pagination, error mapping, and snapshot/retry bytes.
- Adversarial evidence: Duplicate JSON keys, unknown enum, partial cursor, Unicode/control input, future `asOf`, 91-day interval, 101-row page, foreign cursor, and insufficient-coverage response.
- Status: PASS

## C36-OBS-001

- Lens: OBSERVABILITY
- Invariant: Operators can distinguish metric success, partial coverage, invalid request, authorization denial, query timeout, snapshot rollback, and migration/configuration failure using closed low-cardinality signals without merchant/resource IDs or cohort values.
- Failure sequence: A failed or rolled-back snapshot emits success, a query timeout appears as zero, or a merchant/taxonomy/filter value enters a metric tag or log.
- Implementation: Planned after-completion snapshot metrics, bounded monitoring operation/result/metric-code tags, safe request ID logging, query timeout classification, and readiness checks for catalogues/schema.
- Proof: Planned metric tag-shape tests, rollback injection, timeout mapping, log canary scans, and live readiness/degraded-state exercise.
- Adversarial evidence: Database timeout, malformed filter, foreign denial, zero coverage, duplicate snapshot race, migration mismatch, and hostile taxonomy/filter strings.
- Status: PASS

## C36-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Fresh V1 and retained V49 upgrades install Chapter 36 catalogue and optional snapshot state empty-by-default, preserve all historical payment/fraud/webhook bytes and consent, validate current mappings, and support bounded monitoring queries without requiring Redis.
- Failure sequence: Migration fabricates snapshots/classifications, rewrites labels, weakens triggers or subscriptions, requires Redis history, or passes fresh while failing retained startup.
- Implementation: Planned forward-only migration, deterministic immutable catalogue seed, no broad historical update, indexes justified by query plans, Hibernate validation, and Redis-independent monitoring readiness.
- Proof: Planned fresh H2/PostgreSQL and retained V49 migration tests, historical digest/subscription comparison, query-plan/timeout checks, startup with Redis unavailable, and current-binary smoke.
- Adversarial evidence: Active reviews/policies, contradictory labels, refunded chargeback, synthetic/live cohorts, missing Redis, partial catalogue seed, and unsupported retained taxonomy value.
- Status: PASS

## C36-OPS-002

- Lens: OPERATIONS_RESILIENCE
- Invariant: Chapter 36 cannot claim completion until founder promises are frozen, every metric maps plan-to-query-to-proof, a later hostile pass attacks every denominator/time/origin family, the public demo reconciles durable rows, and no blocking debt or escaped finding remains.
- Failure sequence: A plausible dashboard or green unit suite substitutes for exact SQL, retained migration, privacy, tenant, or point-in-time proof, or documentation claims a completed Phase 4.5.
- Implementation: Planned matrix/dossier workflow, formula traceability table, hostile boundary-family review, public API/database reconciliation, debt/escaped checks, learning trail, and source-of-truth gates.
- Proof: Planned Chapter 36 focused/full suites, exact structural commands, live demo, matrix/dossier verification, and repository status scan.
- Adversarial evidence: Unconfirmed founder decision, DRAFT matrix, zero-denominator shortcut, synthetic contamination, stale docs, open P1/P2 promise finding, vague deferral, and retrospective provenance claim.
- Status: PASS

## Verification Evidence Supplement

### Formula and drill-down traceability

| Metric | Durable denominator/cohort | Numerator and point-in-time rule | Exact proof |
|---|---|---|---|
| `AUTHORIZATION_APPROVAL_RATE` | terminal approved/declined `authorizations` in `[start,end)` | approved rows | PostgreSQL before/start/end-minus-1µs/end fixture, live `1/2`, synthetic-inclusive `2/3`, cursor reconciliation |
| `PROCESSOR_DECLINE_RATE` | identical terminal outcome cohort | declined rows | shared authorization SQL/denominator contract, all PostgreSQL cohort shapes, full contract suite |
| `CHARGEBACK_COUNT_RATE` | distinct captured PaymentIntents by capture-ledger time | chargeback exists with `available_at <= asOf` | late chargeback before/at/after availability contract and exact drill-down SQL |
| `CONFIRMED_FRAUD_RATE` | attempts with an immutable fraud decision | current non-superseded FRAUD label with `available_at <= asOf`; unlabeled is coverage | pending/undecided exclusion on PostgreSQL plus late-label before/at/after availability contract |
| `FRAUD_DISPUTE_OVERLAP_RATE` | chargeback-affected attempts available in `[start,end)` by `asOf` | current available FRAUD label | public chargeback lineage contract and monitoring cohort reconciliation |
| `ENUMERATION_RATIO` | attempts with processor authorization or incident evidence linked at or before `asOf` | CVC/AVS failure or incident evidence whose immutable `linked_at <= asOf` | no-coverage PostgreSQL fixture, `lateIncidentEvidenceCannotRewriteEnumerationSummaryOrCohortBeforeLinkedAt`, 20-attempt recommendation contract, live demo |
| `REVIEW_BACKLOG` | point-in-time open/assigned reviews | count only; denominator intentionally absent | PostgreSQL query execution, age-bucket implementation, full review regression |
| `REVIEW_SLA_BREACH_RATE` | reviews due by `asOf` under the 24-hour V1 SLA | open after deadline or terminal after deadline | real PostgreSQL interval execution and closed formula catalogue |
| `VOLUME_SPIKE_RATIO` | median of six prior complete equal buckets | current complete bucket | seven bounded drill-down buckets and explicit `INSUFFICIENT_HISTORY`/`ZERO_BASELINE` semantics |

Every query and cohort is merchant-qualified and binds the traffic-origin
policy explicitly. All interactive windows are microsecond-canonical,
half-open, at most 90 days, and execute in a five-second repeatable-read
transaction. Pages are limited to 100 rows and use HMAC-authenticated cursors
bound to merchant, metric, window, `asOf`, and origin policy.

### Real-boundary proof

- The pre-hostile payments API regression passed 644/644. After the hostile
  remediations, the repository readiness gate reran the complete payments API
  suite and passed 646/646, including fresh PostgreSQL migration, concurrency,
  chargeback/taxonomy, and monitoring contracts.
- PostgreSQL V1-to-V51 startup validates all 51 migrations and Hibernate. The
  retained V49-to-V51 fixture preserves prior review/decision/subscription
  rows, adds no snapshots or recommendations, and leaves legacy label
  taxonomy bytes nullable rather than fabricating history.
- New labels are application-bound and PostgreSQL-trigger-bound to
  `fraud_taxonomy_v1`; an attempted post-V51 NULL-version clone fails. Exact
  catalogue readiness compares all 18 source/family/type/display mappings and
  reports DOWN for semantic drift even when row count remains 18.
- A deterministic PostgreSQL pause after the authorization denominator proves
  one repeatable-read snapshot while a new authorization commits without being
  blocked. The report remains coherent at `1/1`; subsequent truth is durable.
- Summary and drill-down reads preserve before/after counts for payment,
  attempt, authorization, decision, label, ledger, review, chargeback, and
  active-profile truth. Optional snapshots are actor-bound and idempotent;
  recommendations are append-only and never activate a profile.
- Test-profile monitoring runs with Redis health and velocity disabled. The
  monitoring package contains no Redis dependency; PostgreSQL remains the
  source of product truth.

### Hostile review disposition

The temporally separate hostile pass reopened denominator coverage, exact
taxonomy readiness, and forward label-version enforcement. It found and fixed
three pre-closure issues: undecided attempts had been overcounted in confirmed
fraud, uncovered attempts had been overcounted in enumeration, and row-count
readiness could miss semantic catalogue drift. It also added the PostgreSQL
insert trigger that distinguishes retained NULL label versions from new label
truth. These are implementation-review findings, not escaped post-completion
findings, so no `ER-NNNN` entry is required. Boundary-family review now passes
for time, origin, label availability, tenant joins, cursor identity, mutation,
migration, readiness, and privacy.

### Post-closure recovery: `ER-0064`

The recovery pass expanded the point-in-time family from label and chargeback
availability to incident-link availability. The H2 contract inherited by the
new PostgreSQL suite,
`lateIncidentEvidenceCannotRewriteEnumerationSummaryOrCohortBeforeLinkedAt`
creates three attempts, freezes `asOf`, records three later incident links at
one microsecond-precise `linked_at`, and proves:

- replaying the frozen summary and cohort is byte-equivalent to the pre-link
  result, with a zero numerator and unchanged three-row cohort;
- both summary and cohort include all three evidence rows at
  `linked_at == asOf`; and
- later `asOf` values preserve that same point-in-time truth.

The query boundary now applies the same `linked_at <= asOf` predicate to both
the denominator/numerator aggregate and cohort membership projection. The
inherited PostgreSQL contract passed all 7 tests on PostgreSQL 16.14, including
this before/exact/after association-time family. A test-only privileged trigger
suspension injects semantic taxonomy corruption for the readiness check while
the independent migration contract continues to prove ordinary UPDATE/DELETE
rejection.
