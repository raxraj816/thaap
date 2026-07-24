# Chapter 26 Invariant Proof Matrix

- Chapter: Chapter 26 — Analyst Identity, Review Claim, And Label Truth
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

This matrix was drafted and founder-confirmed on 2026-07-13 before Chapter 26
production code or migration changes. The traceability pass reconciled each
frozen promise to V26–V28, the implementation, exact public contracts, and the
real enforcement boundary. A later hostile pass reopened request grammar,
migration upgrade, taxonomy, log-privacy, stale-state, rollback-metric, cursor,
and label-supersession families; all discovered defects were fixed and the
affected partitions re-proved before this matrix moved to `VERIFIED`.

## Verified evidence baseline

- `mvn -q test`: 351 backend tests pass.
- `ReviewResolutionAndLabelContractTests`: 9 public API/credential/privacy/cursor cases.
- `ReviewResolutionPostgreSqlConcurrencyTests`: 15 PostgreSQL cases, including
  five forced lock orderings, six rollback positions, direct lineage/taxonomy
  guards, and label-supersession rejection.
- `PostgreSqlMigrationTests`: fresh V1→V28 plus a retained V25→V28 cohort that
  preserves historical OPEN/CANCELED review bytes and webhook consent.
- `python3 -m unittest discover -s scripts -p '*_tests.py'`: 80 tests pass.
- `scripts/demo-review-label-loop.py`: latest-code live run passed
  against retained local PostgreSQL V28 with public APIs and read-only SQL
  reconciliation.

## C26-PAY-001

- Lens: PAYMENT_FRAUD
- Invariant: `CANCEL` and `CANCEL_AND_REPORT_FRAUD` atomically resolve the pre-authorization review and cancel its PaymentIntent/PaymentAttempt without Authorization, Capture, Refund, ledger, or FraudDecision mutation.
- Failure sequence: A partial resolution, processor/money side effect, or rewritten historical REVIEW decision survives.
- Implementation: `ReviewOperationsService.resolve` locks PaymentIntent, PaymentAttempt, then ManualReviewCase; writes resolution/optional label, payment state, audit, webhook, and idempotency completion in one operation transaction.
- Proof: Chapter 26 API contracts cover both outcomes; six forced PostgreSQL constraint failures reconcile zero partial rows and a clean same-key retry; the live demo reconciles unchanged processor/money counts and decision action.
- Adversarial evidence: Failures at resolution, label, payment state, audit, webhook, and idempotency completion all roll back; CANCEL creates zero labels and changed-body replay conflicts.
- Status: PASS

## C26-PAY-002

- Lens: PAYMENT_FRAUD
- Invariant: Merchant cancellation and analyst resolution serialize to one terminal winner; competing analyst resolutions return the committed terminal truth without duplicate side effects.
- Failure sequence: Both terminal actions commit or a loser overwrites the winner and duplicates resolution/label truth.
- Implementation: Both paths use the PaymentIntent-first lock order, terminal state guards, unique resolution/source-label keys, and fenced idempotency.
- Proof: Forced analyst-first and merchant-first PaymentIntent lock waits prove opposite arrival orders; forced resolve/resolve proves the follower returns the winner's resolution and label.
- Adversarial evidence: Exact PostgreSQL lock-wait observation and terminal row reconciliation prove one review status, one resolution, at most one label, and no second transition.
- Status: PASS

## C26-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: Operator, credential, assignment, annotation, resolution, label, case, decision, attempt, intent, and origin lineage cannot cross merchants through API or direct database substitution.
- Failure sequence: A foreign operator observes or mutates another tenant's review, or cross-tenant lineage becomes durable.
- Implementation: Dedicated operator context, merchant-scoped repositories, composite tenant foreign keys, and PostgreSQL lineage triggers enforce the boundary.
- Proof: API contracts and the live demo return missing-equivalent 404 for foreign detail/action; direct PostgreSQL foreign-actor and lineage substitutions fail.
- Adversarial evidence: Foreign manager, assignment target, annotation actor, review, payment, resolution, label, and supersession partitions produce no cross-tenant rows or disclosure.
- Status: PASS

## C26-AUTH-001

- Lens: SECURITY_PRIVACY
- Invariant: Every mutation revalidates one ACTIVE locked operator; VIEWER is read-only, ANALYST acts only on its assignment, and RISK_MANAGER alone may reassign another operator.
- Failure sequence: A stale, disabled, wrong-role, unassigned, or reassigned actor mutates review truth.
- Implementation: Candidate authentication is followed by in-transaction locked operator revalidation and role/assignment checks in `ReviewOperationsService`.
- Proof: Role and assignment API partitions plus forced disable/action and manager-reassign/stale-release PostgreSQL interleavings prove the locked authorization decision.
- Adversarial evidence: Missing/malformed credential, VIEWER mutation, disabled credential, foreign actor, assignment theft, stale owner release, and target substitution are rejected without side effects.
- Status: PASS

## C26-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: Operator secrets are revealed only at bounded creation/replay, stored as prefix/hash plus authenticated encrypted replay, accepted only on risk APIs, and absent from logs, audit, metrics, errors, and public reads.
- Failure sequence: A raw secret persists, crosses credential surfaces, leaks through failure output, or stops working during an authorized HMAC rotation.
- Implementation: Versioned domain-separated HMAC identity, SHA-256 verification material, authenticated encryption, exact credential type, disable lifecycle, and bounded replay TTL protect the secret.
- Proof: Persistence/audit/runtime scans, merchant-versus-risk header substitution, exact replay/change/expiry grammar, prior-key rotation cohorts, missing-key readiness contracts, and live-demo credential scans pass.
- Adversarial evidence: Whitespace, Unicode suffix, changed-body replay, expired replay, disabled credential, current/previous HMAC keys, and exception paths expose no raw secret.
- Status: PASS

## C26-SEC-002

- Lens: SECURITY_PRIVACY
- Invariant: Analyst annotations are append-only closed codes with no free-form text, PII, card data, credentials, metadata, or arbitrary JSON channel.
- Failure sequence: An unknown field, nested value, invalid enum, or direct SQL insert becomes uncontrolled analyst prose.
- Implementation: Enum-only DTO/entity/schema, strict HTTP unknown-property rejection, no text column, and PostgreSQL code constraint close the grammar.
- Proof: Unknown-field, nested, unknown-code, wrong-media-type, and direct-SQL invalid-code partitions fail; persistence and metric surfaces contain only closed values.
- Adversarial evidence: Malformed JSON representations and database bypass attempts create zero annotations.
- Status: PASS

## C26-CON-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Assignment/release/annotation/resolution/label/payment/audit/webhook/idempotency truth commits atomically or rolls back together, and metrics report the final transaction outcome.
- Failure sequence: Any durable partial side effect or cached success survives a failure.
- Implementation: Fenced idempotency owns the transaction; risk metrics register an after-completion callback before fallible writes.
- Proof: Six real PostgreSQL constraints inject failures at resolution, label, payment state, audit, webhook, and idempotency completion; every case proves exact rollback, one rolled-back metric, zero committed metric, and successful same-key retry.
- Adversarial evidence: Logs suppress PostgreSQL failing-row values and the retry produces exactly one coherent terminal graph.
- Status: PASS

## C26-CON-002

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Competing claim, release, reassignment, disable, cancellation, and resolution operations converge to one current owner and one terminal resolution/label.
- Failure sequence: Dual ownership, stale release, stale authorization, or divergent terminal outcomes commit.
- Implementation: Explicit row-lock order, assignment version compare, append-only events, deferred projection constraint, and unique terminal identities serialize writers.
- Proof: Forced claim/claim, manager-reassign/stale-release, disable/action, analyst/merchant in both orders, and resolve/resolve waits assert lock ownership and final PostgreSQL rows.
- Adversarial evidence: Losers observe missing-equivalent, conflict, or the committed terminal result; no stale writer clears or replaces newer truth.
- Status: PASS

## C26-DATA-001

- Lens: DATA_ML_LINEAGE
- Invariant: Confirmed fraud creates exactly one immutable FRAUD label with matching tenant/payment/attempt/decision/origin/resolution/type/reason and distinct evidence/availability time; CANCEL creates no label.
- Failure sequence: Inconclusive cancellation poisons training data, taxonomy mismatches persist, duplicate replay labels twice, or time is backdated.
- Implementation: Typed label/resolution records, V28 exact fraud-type/reason binding, unique source identity, time checks, and PostgreSQL source-lineage trigger enforce semantics.
- Proof: Both outcome contracts, replay, future-evidence rejection, API taxonomy partitions, direct PostgreSQL mismatched taxonomy, migration validation, and live read reconciliation pass.
- Adversarial evidence: Null/unknown/mismatched fraud semantics, wrong source lineage, future evidence, duplicate source, and cross-payment inputs fail.
- Status: PASS

## C26-DATA-002

- Lens: DATA_ML_LINEAGE
- Invariant: Labels and their historical payment, decision, feature, rule, review, and resolution lineage are append-only; any future supersession must remain on the exact payment lineage.
- Failure sequence: Update/delete, self-cycle, cross-payment chain, or historical decision rewrite changes prior evidence.
- Implementation: V27 immutability and source/supersession triggers plus composite keys reject mutation and invalid chains; Chapter 26 exposes no correction mutation API.
- Proof: Direct PostgreSQL update/delete, decision mutation, self-supersession, and cross-payment supersession all fail while original rows remain byte-stable.
- Adversarial evidence: The retained migration cohort also proves V24 historical review protection remains active after V26–V28.
- Status: PASS

## C26-API-001

- Lens: API_PRODUCT
- Invariant: Risk reads/actions are bounded, tenant-scoped, role-aware, idempotent, strict in request grammar, deterministic at cursor ties, and backward compatible with merchant surfaces.
- Failure sequence: Cursor ties skip/duplicate work, malformed or unknown input succeeds, cross-tenant existence leaks, or replay changes truth.
- Implementation: Dedicated risk controllers/DTOs, `(created_at,id)` cursor ordering, strict Spring HTTP JSON mapper, standard error mappings, and scoped idempotency implement the contract.
- Proof: Limits, malformed/missing cursors, equal-timestamp two-page traversal, unknown/nested fields, unsupported media, credential separation, tenant 404, exact replay, and changed-body conflicts pass.
- Adversarial evidence: The full 351-test suite proves the stricter unknown-field grammar and 415 mapping preserve all existing API contracts.
- Status: PASS

## C26-OBS-001

- Lens: OBSERVABILITY
- Invariant: Risk operations expose closed operation/outcome/transaction dimensions without IDs, credentials, evidence, annotations, exception text, or unbounded values.
- Failure sequence: Signals leak sensitive/high-cardinality data or report a rolled-back action as committed.
- Implementation: `RiskOperationsMetrics` emits only `operation`, `outcome`, and `transaction` tags after transaction completion; bounded exception logging emits request ID, path, and exception type only.
- Proof: Meter tag-shape/secret scans and all six forced rollback cases assert exact committed-versus-rolled-back deltas; API error contracts prove detail suppression.
- Adversarial evidence: Database constraint messages and failing-row contents are absent from captured output.
- Status: PASS

## C26-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Fresh and supported V25 upgrades install V28 without rewriting historical review/decision bytes or webhook consent, retain immutability, validate ORM mappings, and require credential key material.
- Failure sequence: Upgrade triggers block migration, history changes, legacy endpoints gain subscriptions, constraints weaken, or unreadable credentials reach readiness.
- Implementation: V26 adds default-backed non-rewriting columns, V27 expands PostgreSQL protection, and V28 binds resolution taxonomy without touching rows.
- Proof: Fresh H2/PostgreSQL V1→V28 and retained PostgreSQL V25→V28 tests pass; the retained cohort snapshots OPEN/CANCELED history and subscriptions, checks triggers, Flyway history, and Hibernate validation.
- Adversarial evidence: The first live retained upgrade exposed the V26 trigger conflict; the migration was repaired and re-proved on automated and retained local cohorts.
- Status: PASS

## C26-OPS-002

- Lens: OPERATIONS_RESILIENCE
- Invariant: Chapter closure requires real-boundary proof, a public reconciliation demo, learning/source reconciliation, later hostile review, and no blocking debt or escaped finding.
- Failure sequence: A green unit suite or documentation claim substitutes for PostgreSQL, migration, privacy, or live product proof.
- Implementation: Matrix, dossier, learning trail, demo, debt/escaped registers, and structural gates form the closure boundary.
- Proof: Traceability and temporally later hostile reviews pass; 351 backend and 80 Python tests pass; the latest-code public demo reconciles durable truth on retained PostgreSQL V28.
- Adversarial evidence: All hostile findings were fixed before closure; no Chapter 26 debt or escaped finding remains, and source-of-truth documents identify only Chapter 26—not Phase 4—as complete.
- Status: PASS
