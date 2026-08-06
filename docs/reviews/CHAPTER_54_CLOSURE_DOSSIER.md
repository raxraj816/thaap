# Chapter 54 Closure Dossier

- Chapter: Chapter 54 — Canonical Timeline And Safe Related-Payment Pivots
- Chapter number: 54
- Status: READY_TO_MERGE
- Invariant matrix: docs/reviews/CHAPTER_54_INVARIANT_PROOF_MATRIX.md
- Matrix provenance: PRE_IMPLEMENTATION
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Hostile review: PASS
- Boundary family review: PASS
- Custom infrastructure review: NOT_APPLICABLE
- Escaped review assessment: COMPLETE
- Merge recommendation: MERGE

This dossier was opened on 2026-08-02 before Chapter 54 implementation and
completed on 2026-08-03 after every frozen row reached `PASS`. Chapter 53 is
merge-ready, so the dependency that blocked this chapter is discharged.

## Observable promises

An authorized analyst can traverse the exact canonical payment history in
stream-version order, inspect separately labelled webhook transport activity,
and pivot to bounded same-merchant related payments through seven safe reason
codes without raw/HMAC disclosure, cross-tenant inference, or source mutation.

Each promise now has a named owner artifact:

- Exact history in stream order: `PaymentTimelineContractTests`, reconciled
  against `domain_events` rather than against a fixture constant.
- Safe presentation of every event type: `PaymentTimelinePrivacyTests`, proven
  exhaustive over `DomainStreamType.PAYMENT`.
- A page that does not shift under concurrent appends:
  `PaymentTimelineCursorTests` and `PaymentTimelineAppendPostgreSqlTests`.
- Meaningful, bounded, honestly-covered pivots: `RelatedPaymentContractTests`.
- No cross-tenant inference: `PaymentRelationshipTenantIsolationTests`.
- No identity disclosure: `RelatedPaymentPrivacyTests`.
- Anchors that trace to their real source: `PaymentRelationshipLineageTests`.
- A durable boundary nothing can bypass:
  `PaymentRelationshipMigrationPostgreSqlTests`.
- A contract an integrator can hold: `PaymentInvestigationNavigationApiTests`.
- A browser that preserves order and hides identity:
  `paymentTimelineAndRelations.test.tsx`.
- Signals on-call can alert on:
  `PaymentInvestigationNavigationObservabilityTests`.
- No mutation authority and no borrowed capability:
  `PaymentInvestigationNavigationScopeBoundaryTests`.

## Failure inventory

The matrix freezes event mapper/type/version, timestamp/stream-order,
append-pagination, cursor substitution, raw-identity, HMAC domain/key rotation,
coverage, tenant/timing/count, source lineage, migration/backfill, browser,
query saturation, and scope failure families. Each was executed rather than
merely enumerated.

Hostile sequences proven:

- A payment event appended between two page reads of an already-issued cursor.
- A real signed event record replayed under a fresh database primary key.
- An operator attempting to repair, edit, or erase recorded history.
- Two connections racing for the same stream version.
- A cursor minted for another payment, another tenant, or another view domain.
- Every single-character edit of a valid cursor.
- Two merchants holding byte-identical correlation digests.
- A well-formed relationship anchor carrying a foreign digest.
- An anchor claiming a source kind that cannot produce its relation.
- A digest padded with whitespace so it would compare unequal to itself.
- A repeated observation for a single payment attempt.
- A server regression echoing a correlation digest to the browser.
- One degraded dependency attempting to blank the whole workspace.

## Boundary family evidence

- Timeline grammar: proven exhaustively by reflecting over the presentation
  mapping and asserting every `DomainStreamType.PAYMENT` type is covered, so a
  future event type cannot silently blank the timeline. Unknown fields, an
  unsupported `eventVersion`, and structured payload values each fail closed.
- Cursor grammar: proven generatively. Every single-character edit of a valid
  cursor, stepping the index by seven, is refused; so are oversized and
  non-Base64 cursors, while a blank cursor is a first page.
- Relation vocabulary: all seven reasons are asserted against the frozen list,
  and coverage is proven to partition the vocabulary with no overlap and no
  omission.
- Digest domain separation: the boundary is proven unshiftable, which
  establishes the NUL delimiter rather than merely observing one example.
- Numeric and range boundaries: `limit` is proven at `0`, `-1`, `50`, `51`, and
  a non-numeric value on all three routes; the window at inverted, oversized,
  future-facing, and empty.
- Migration constraints: each named CHECK, unique, and trigger boundary is
  exercised individually on real PostgreSQL rather than inferred from DDL text.

## Enforcement boundaries

Every claim is proven where it is actually enforced.

- Concurrency, immutability, constraint, trigger, and index claims are proven on
  PostgreSQL 16 via Testcontainers using two real `DataSource` connections. No
  mock certifies a database guarantee.
- Cursor binding is proven through the HTTP surface, not through the codec in
  isolation, so a route that forgot to bind would fail.
- Privacy canaries are sourced from real customer, payment-method, and anchor
  rows and asserted non-empty before their absence is asserted, so no canary
  proves absence vacuously.
- Tenant isolation is proven by byte-comparison of scrubbed response bodies,
  not by status code alone.
- Browser claims are proven against the rendered DOM.

## Exact proof

| Boundary | Artifact | Tests |
|---|---|---|
| C54-TIM-001 | `PaymentTimelineContractTests` | 6 |
| C54-MAP-001 | `PaymentTimelinePrivacyTests` | 7 |
| C54-CUR-001 | `PaymentTimelineCursorTests` | 7 |
| C54-CUR-001 | `PaymentTimelineAppendPostgreSqlTests` | 7 |
| C54-REL-001 | `RelatedPaymentContractTests` | 7 |
| C54-TEN-001 | `PaymentRelationshipTenantIsolationTests` | 5 |
| C54-HMC-001 | `RelatedPaymentPrivacyTests` | 6 |
| C54-LIN-001 | `PaymentRelationshipLineageTests` | 7 |
| C54-MIG-001 | `PaymentRelationshipMigrationPostgreSqlTests` | 9 |
| C54-API-001 | `PaymentInvestigationNavigationApiTests` | 9 |
| C54-OBS-001 | `PaymentInvestigationNavigationObservabilityTests` | 6 |
| C54-SCP-001 | `PaymentInvestigationNavigationScopeBoundaryTests` | 5 |
| C54-UI-001 | `paymentTimelineAndRelations.test.tsx` | 7 |

Suite state at closure: `mvn -o test` reports 1016 backend tests passing with
`BUILD SUCCESS`; `npm test -- --run` reports 41 browser tests passing across 7
files.

## Migration evidence

`PaymentRelationshipMigrationPostgreSqlTests` runs the full Flyway history to
V87 on PostgreSQL 16 and proves the durable boundary directly:

- `trg_payment_investigation_relation_append_only` refuses both UPDATE and
  DELETE with `Investigation relation anchors are append-only`.
- The deferred constraint trigger refuses an anchor whose digest disagrees with
  its named source, which is the most damaging possible forgery because it
  fabricates a relationship that would survive analyst review.
- `ck_payment_investigation_relation_type` and
  `ck_payment_investigation_relation_source` together close the relation
  vocabulary and the relation-to-source pairing.
- `ck_payment_investigation_relation_digest` refuses both a short digest and a
  whitespace-padded one, the latter because a padded digest would compare
  unequal to the same identity written without padding and silently split one
  person into two.
- `ck_payment_investigation_relation_key` refuses a blank key version.
- `uk_payment_investigation_relation_alias` refuses a repeated observation for
  one attempt.
- Both protection triggers and both lookup indexes are asserted present by name.
- Every surviving anchor is reconciled against `customers.email_hash`,
  `customers.phone_hash`, or `payment_methods.card_fingerprint`.

Separately, `PaymentTimelineAppendPostgreSqlTests` proves `domain_events`
history cannot be edited or erased even to repair it, and that a row whose
database identity disagrees with its signed canonical record is refused with
`503 timeline_integrity_unavailable` rather than rendered.

## Demo evidence

`scripts/demo-payment-investigation.py` creates several same-merchant payments
through public APIs, opens one investigation, traverses exact history and
related pivots, and shows coverage and transport separation.
`PaymentInvestigationNavigationScopeBoundaryTests` proves the demo mutates only
over HTTP and that its single database channel is a guarded read-only
reconciliation which rejects any non-`SELECT` statement. The demo therefore
verifies the product rather than manufacturing the state it claims to prove.
`scripts/payment_investigation_demo_tests.py` remains green.

## Operational signals

Each navigation route records latency under its own operation tag
(`TIMELINE`, `RELATED_PAYMENTS`, `WEBHOOK_ACTIVITY`) on
`thaap.decision_intelligence.requests`, so a regression on one route cannot hide
behind healthy traffic on another. Caller error and server error are separable:
a missing payment records `NOT_FOUND`, an invalid cursor records `INVALID`, and
neither records `SUCCEEDED` or `FAILED`. Result size is published as a bounded
bucket on `thaap.decision_intelligence.query_results`, which makes the
difference between a payment with no siblings and a payment that suddenly
relates to hundreds visible without reading rows. No meter under
`thaap.decision_intelligence` carries a tenant, payment, credential, or identity
digest as a tag value, which is both a cardinality and a privacy guarantee,
because metrics are routinely shipped to backends with weaker access control
than the database.

## Known debt

`TD-0006` (`OPEN`, `P2`) — the timeline and webhook-activity routes name their
row collection `entries` while the related-payment route names it `data`. This
is a published-contract wart, not a correctness defect: every route is proven to
have uniform cursor, bound, refusal, and finality behavior. Unifying the name is
a breaking change that must be decided once for the whole navigation surface,
so it is owned by the Chapter 59 public contract freeze with a named DRI,
trigger, and done criteria.

No other debt is accepted. Cross-merchant network and graph intelligence remains
an explicit Phase 8 exclusion; saved views, bulk work, and insights retain
Chapters 55–56 ownership.

## Custom infrastructure decision

`NOT_APPLICABLE`. Chapter 54 reuses the existing canonical event store, signed
cursor primitive, PostgreSQL constraints/indexes, typed Spring APIs, and React
client rather than inventing event, graph, cursor, or identity infrastructure.

## Escaped-review assessment

No Chapter 54 escaped finding exists, and no `ER-NNNN` is opened by this
chapter.

Two findings were made during the proof build, and both are in-chapter proof
strengthenings rather than escapes, because neither was a defect and neither
survived a prior completion claim:

1. `PaymentTimelineService.map` decodes the signed `event_record_bytes` and
   cross-checks the database row's `id`, `event_version`, and `stream_id`
   against the values embedded in the signed record. A synthetic append built by
   replaying a real record under a fresh primary key was correctly refused. The
   guard was promoted to its own test rather than worked around, and the
   snapshot-stability claim was rebuilt on a genuine cancel append.
2. `domain_events` UPDATE and DELETE raise `domain event history is immutable`,
   so the forged row could not be cleaned up. This was promoted to an explicit
   assertion and its own test, and the affected stream is quarantined to that
   test's own merchant.

Two of this chapter's own claims were also corrected against real system
behavior rather than being forced onto the code: an assertion that anchors are
unique per identity, when they are correctly unique per attempt observation and
the deduplication promise lives in the read path, which is now separately
proven; and a scope scan that would have banned the demo's guarded read-only
reconciliation. Both corrections made the proof stronger and more honest.

## Source-of-truth reconciliation

`docs/IMPLEMENTATION_MASTER_PLAN.md` records Chapter 54 as complete with its
`TD-0006` reference in both the originating chapter and the Chapter 59
destination slice. `docs/TECHNICAL_DEBT_REGISTER.md` carries `TD-0006` and
passes `scripts/technical-debt-check.py`. The matrix is `VERIFIED` with all
twelve rows `PASS` and passes `scripts/invariant-proof-check.py`. `README.md`
records that Chapter 54 is complete. The learning trail carries the daily note
and deep dive for 2026-08-03. No completion marker anywhere claims evidence this
dossier does not name.

## Merge recommendation

`MERGE`. Every frozen promise has a named enforcement boundary and an executed
proof at that boundary; every hostile sequence in the failure inventory was run;
every boundary family was proven by partition rather than by example; the
durable guarantees were proven on the production engine; the single accepted
deferral is registered as `TD-0006` with a DRI, trigger, and done criteria; and
the full backend and browser suites are green.
