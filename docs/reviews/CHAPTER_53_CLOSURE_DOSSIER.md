# Chapter 53 Closure Dossier

- Chapter: Chapter 53 — Unified Payment Investigation And Decision Explanation
- Chapter number: 53
- Status: READY_TO_MERGE
- Invariant matrix: docs/reviews/CHAPTER_53_INVARIANT_PROOF_MATRIX.md
- Matrix provenance: PRE_IMPLEMENTATION
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Hostile review: PASS
- Boundary family review: PASS
- Custom infrastructure review: NOT_APPLICABLE
- Escaped review assessment: COMPLETE
- Merge recommendation: MERGE

This dossier was opened on 2026-08-02 before Chapter 53 implementation and
closed on 2026-08-03 after every named proof obligation was written, executed,
and reconciled against the frozen promises. The proofs found three production
defects that a green suite had never surfaced; all three are fixed, proven, and
registered.

## Observable promises

An authorized analyst can find and open one payment, see current authoritative
payment/fraud/review/label/money/dispute state, understand every deterministic
decision input and control result, distinguish source truth from projection or
transport evidence, and recover section-specific failures from one accessible
route. Reads do not mutate or authorize later actions.

Every promise above is now proven. Search and detail are exercised end to end
by `PaymentInvestigationContractTests` and `PaymentInvestigationApiContractTests`;
decision understanding by `DecisionExplanationContractTests`; source-versus-
derived honesty by `PaymentInvestigationLineageTests`; independent section
recovery by `PaymentInvestigationReadinessTests` and `paymentInvestigation.test.tsx`;
and read non-mutation by `PaymentInvestigationSourceReconciliationTests` plus the
public `scripts/demo-payment-investigation.py` reconciliation.

## Failure inventory

The frozen matrix covers source rows changing between section reads,
cross-attempt and cross-tenant substitution, incomplete or contradictory
decision evidence, basis-point rendering, source/projection disagreement,
missing model state, hostile filters/cursors/routes, credential/cache leakage,
partial dependency failure, timeout, deployment compatibility, and accidental
later-scope claims.

Three sequences from this inventory turned out to be real rather than
hypothetical, and each is recorded below under escaped-review assessment.

## Boundary family evidence

- Source composition: `PaymentInvestigationContractTests` partitions zero, one,
  and multiple attempts and all four fraud actions;
  `PaymentInvestigationSourceReconciliationTests` hex-fingerprints 22
  authoritative tables around every route.
- Historical decision reconstruction: `DecisionExplanationContractTests` runs a
  table partition over every action and proves losing and unmatched controls
  survive into the response, qualified against the exact rendered decision id.
- Tenant-qualified joins and cache identity:
  `PaymentInvestigationTenantIsolationTests` substitutes the seed and every
  child identifier across all four payment-scoped routes;
  `PaymentInvestigationScopeBoundaryTests` proves two merchants transacting one
  identical card produce no relation edge.
- Privacy sinks and safe navigation: `PaymentInvestigationPrivacyTests` sources
  canaries from real card tokens, card fingerprints, and relation-identity
  HMACs and asserts they exist before asserting their absence;
  `paymentInvestigation.test.tsx` proves no credential reaches storage, cookies,
  the URL, or the DOM and that no server value becomes a link.
- PostgreSQL snapshot orderings: `PaymentInvestigationSnapshotPostgreSqlTests`
  forces a writer to commit mid-read behind a latch, probes `pg_locks` for
  ungranted locks while a real `UPDATE` completes, and proves
  `pg_stat_activity` retains no idle-in-transaction session.
- Declared-cutoff family: reopened during this review. Detail holds a
  repeatable-read snapshot, related now holds one because its per-row
  projection lookups have no value ceiling, and timeline pins its stream
  ceiling by value and holds it across every page. All three members are
  proven; no member is closed by analogy to another.
- Typed filter/cursor grammar: `PaymentInvestigationApiContractTests` rejects
  ten invalid grammars and four invalid page sizes, proves cursors are bound to
  their query shape, and proves filter canonicalization.
- Section-result cross products: `PaymentInvestigationReadinessTests` runs the
  projection-outage matrix; `PaymentInvestigationObservabilityTests` proves the
  recorded section states equal the response body's own `sectionStatuses`.
- Browser route and accessibility state: `paymentInvestigation.test.tsx` proves
  a slow abandoned response cannot render under the new payment's heading, that
  two dead dependencies leave source detail and per-slice retries intact, and
  that the rendered tree carries no axe violation.

## Enforcement boundaries

Each claim is proven where it is enforced. Tenant isolation, grammar, and
non-disclosure are proven over real HTTP through MockMvc with the request-id
and API-version filters installed. Snapshot, lock, and idle-transaction claims
are proven against PostgreSQL 16 in Testcontainers, because H2 cannot certify
them. Rule-catalogue immutability is proven by executing UPDATE, DELETE, and
INSERT against the live database and observing the `trg_fraud_rules_immutable`
and `trg_rule_execution_results_immutable` triggers refuse them. Browser claims
are proven in the rendered DOM rather than against a component mock. Demo
safety is proven by executing the demo's own SELECT-only guard.

## Exact proof

Eleven named artifacts plus the scope scans, all green:

| Row | Artifact | Tests |
| --- | --- | --- |
| C53-PAY-001 | `PaymentInvestigationContractTests` | 3 |
| C53-PAY-001 | `PaymentInvestigationSourceReconciliationTests` | 3 |
| C53-EXP-001 | `DecisionExplanationContractTests` | 6 |
| C53-TEN-001 | `PaymentInvestigationTenantIsolationTests` | 8 |
| C53-SEC-001 | `PaymentInvestigationPrivacyTests` | 4 |
| C53-TXN-001 | `PaymentInvestigationSnapshotPostgreSqlTests` | 5 |
| C53-DAT-001 | `PaymentInvestigationLineageTests` | 6 |
| C53-API-001 | `PaymentInvestigationApiContractTests` | 18 |
| C53-UI-001 | `paymentInvestigation.test.tsx` | 6 |
| C53-OBS-001 | `PaymentInvestigationObservabilityTests` | 6 |
| C53-OPS-001 | `PaymentInvestigationReadinessTests` | 5 |
| C53-SCP-001 | `PaymentInvestigationScopeBoundaryTests` | 6 |
| C53-SCP-001 | `scripts/payment_investigation_demo_tests.py` | 5 |

Backend suite: 939 tests, `BUILD SUCCESS`. Analyst dashboard: 34 tests across
six files. Demo safety: 5 tests.

## Migration evidence

Chapter 53 adds no migration and expects none beyond V85, which
`PaymentInvestigationScopeBoundaryTests` reinforces by proving the read surface
declares no write capability. `PaymentInvestigationLineageTests` and
`PaymentInvestigationSourceReconciliationTests` prove the read slice neither
mutates existing history nor depends on a table a later chapter introduces.
`PaymentInvestigationReadinessTests` proves the health indicator reports DOWN
with the closed cause `SOURCE_DATABASE_UNAVAILABLE` when a required source
relation is absent, which is the schema-drift signal an operator would see.

## Demo evidence

`scripts/demo-payment-investigation.py` creates an ordinary merchant, operator,
customer, and card through public APIs; seeds allowed, review-triggering, and
blocked payments; pages a bounded investigation search; proves two equivalent
filters canonicalize to one meaning; opens the blocked payment and proves the
explanation names one winning control while retaining the losing ones and
declares `modelState` `NOT_CONFIGURED`; proves the timeline is canonically
ordered beneath its declared ceiling; proves related payments declare a
same-merchant scope; proves webhook activity is labelled transport evidence
rather than money or fraud truth; proves a foreign payment is indistinguishable
from an absent one for a neighbouring merchant's operator; and proves an
eleven-part source fingerprint is unchanged by the whole investigation.

`scripts/payment_investigation_demo_tests.py` proves the demo accepts only
loopback origins, that its reconciliation path executes SELECT with read-only
argv and rejects mutating SQL before execution, that the fingerprint covers
every authoritative table the read touches, and that the demo declares no route
outside ordinary merchant setup plus the risk-operator investigation surface.

## Operational signals

`DecisionIntelligenceMetrics` records `thaap.decision_intelligence.requests`,
`.investigation.sections`, and `.query_results`.
`PaymentInvestigationObservabilityTests` proves many distinct payments collapse
to one time series, that every tag key is drawn from a closed allowlist and no
tag value is UUID-shaped, that 404, 401, and 400 record `NOT_FOUND`,
`UNAUTHENTICATED`, and `INVALID` rather than `SUCCEEDED`, that result size is a
bucket rather than a count, and that the facade rejects any out-of-allowlist
value. `DecisionIntelligenceHealthIndicator` is proven idempotent across
repeated polls and proven to report a closed cause when source is unavailable.

## Known debt

No debt is accepted for Chapter 53. Timeline and related pivots, saved views,
bulk work, insights, model explanation, network intelligence, and hosted
identity remain named exclusions with owner slices rather than deferred debt.
`docs/TECHNICAL_DEBT_REGISTER.md` records no open Chapter 53 entry.

## Custom infrastructure decision

`NOT_APPLICABLE`. Chapter 53 uses existing Spring transaction and data APIs,
PostgreSQL isolation, and the routed React application. No proprietary
database, cache, protocol, routing, or concurrency substrate was introduced.

## Escaped-review assessment

Three defects escaped earlier review and were found by writing the frozen
proofs. All are fixed and proven.

- `ER-0089` (P1, `WEAK_PROOF`): `PaymentTimelineService.webhookActivity`
  selected `last_error`, a column dropped by V9. Every call to the
  webhook-activity route returned HTTP 500. No test had ever invoked the route.
- `ER-0090` (P1, `WEAK_PROOF`): `ProjectionDefinitionStore` is a `@Repository`,
  so persistence-exception translation rewrote the `IllegalStateException` it
  threw, leaving both graceful-degradation handlers unreachable. A disabled
  projection took down authoritative source detail, which is exactly the
  C53-OPS-001 failure sequence.
- `RelatedPaymentService.related` published a single `asOf` cutoff while its
  per-row projection lookups ran outside any transaction, so one page could mix
  two database states. This was found during Chapter 53's own boundary-family
  review before any completion claim, so it is an in-chapter finding rather
  than an escape; it is recorded here and in the 2026-08-03 deep dive.

The process correction shared by all three: a route or handler that no test
executes is treated as unimplemented, regardless of whether it compiles.

## Source-of-truth reconciliation

The Phase 6 detailed plan, implementation master plan, vision matrix,
architecture, READMEs, and the 2026-08-03 learning trail agree that Chapter 53
is implemented and proven. The invariant matrix reports `VERIFIED` with all
eleven rows `PASS`, `scripts/invariant-proof-check.py` reports no Chapter 53
finding, and `scripts/escaped-review-check.py` passes with 90 immutable
findings.

## Merge recommendation

`MERGE`. Every frozen promise has proof at its real enforcement boundary, every
matrix row is `PASS`, the declared-cutoff boundary family was reopened and
closed across all three of its members, three defects are fixed with both
product evidence and a process correction, and no Chapter 53 debt is deferred.
