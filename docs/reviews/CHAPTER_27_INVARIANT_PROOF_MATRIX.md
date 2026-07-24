# Chapter 27 Invariant Proof Matrix

- Chapter: Chapter 27 — Pre-Authorization Review Approval
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

This matrix was drafted on 2026-07-13 before Chapter 27 production or migration
changes. It inherits the founder-confirmed Phase 4 promise that APPROVE records
an immutable analyst override, preserves the original REVIEW decision, and
continues the same PaymentAttempt to one idempotent simulator authorization.
The implementation and proof fields retain the frozen design intent and are now
reconciled to the verification ledger at the end of this matrix.

## C27-PAY-001

- Lens: PAYMENT_FRAUD
- Invariant: APPROVE preserves the original REVIEW FraudDecision and continues the same reviewed PaymentAttempt to exactly one processor authorization identity.
- Failure sequence: Approval rewrites REVIEW to ALLOW, creates another attempt/decision, or authorizes twice.
- Implementation: The locked approval path flushes immutable resolution/override evidence, transitions the existing attempt to processor-ready, and uses attempt-bound simulator idempotency.
- Proof: Public approval contracts and durable reconciliation for attempt, decision, resolution, authorization, and processor identity.
- Adversarial evidence: Same/distinct keys, terminal replay, changed body, rollback/retry, and direct duplicate authorization insertion.
- Status: PASS

## C27-PAY-002

- Lens: PAYMENT_FRAUD
- Invariant: Processor APPROVED, DECLINED, and REQUIRES_ACTION results produce the same truthful payment/attempt semantics as confirm, including AUTOMATIC versus MANUAL capture.
- Failure sequence: Approval fabricates captured funds, loses a decline, completes a challenge, or skips ledger capture.
- Implementation: The continuation reuses the existing processor catalogue and payment/capture invariants without implementing challenge completion.
- Proof: Every documented processor fixture, both capture methods, exact authorization fields, ledger rows, and webhook/audit results.
- Adversarial evidence: Approved, issuer decline, insufficient funds, suspected fraud, CVC/AVS failures, processor 3DS, and default fixture partitions.
- Status: PASS

## C27-PAY-003

- Lens: PAYMENT_FRAUD
- Invariant: Merchant cancellation and analyst approval have one PaymentIntent-lock-serialized winner; the loser creates no processor, ledger, resolution, label, audit, or webhook duplicate.
- Failure sequence: Cancellation and approval both commit or the loser overwrites terminal payment truth.
- Implementation: Approval follows the Chapter 26 PaymentIntent-first lock order and revalidates review/attempt state after waiting.
- Proof: Forced PostgreSQL analyst-first and merchant-first waits with exact terminal reconciliation.
- Adversarial evidence: Both capture methods, approved/declined fixtures, same/distinct keys, and retry after winner commit.
- Status: PASS

## C27-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: Approval, override, optional label, payment method, processor request, authorization, ledger, audit, and webhook lineage remain within one merchant.
- Failure sequence: A foreign operator, method, review, attempt, or resolution creates processor/money truth for another tenant.
- Implementation: Composite tenant keys, tenant-scoped locks/repositories, and copied-lineage checks bind every edge.
- Proof: API missing-equivalent substitutions and direct PostgreSQL pairwise lineage substitutions.
- Adversarial evidence: Foreign actor/review/method/attempt/intent/decision/resolution/authorization/label values.
- Status: PASS

## C27-AUTH-001

- Lens: SECURITY_PRIVACY
- Invariant: Only the assigned ACTIVE ANALYST or a RISK_MANAGER may APPROVE, and authorization is revalidated under lock before override or processor work.
- Failure sequence: VIEWER, disabled, stale, unassigned, released, reassigned, or foreign operator invokes processor work.
- Implementation: The path reuses locked operator and assignment checks before any flush or adapter call.
- Proof: Role/assignment table and forced disable-after-precheck interleaving with a processor spy proving zero call.
- Adversarial evidence: Every credential state, role, assignment state, and manager/analyst target partition.
- Status: PASS

## C27-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: Approval adds no PAN, PII, raw credential, processor secret, arbitrary reason, or high-cardinality metric/log surface.
- Failure sequence: Existing payment method data or analyst input leaks through approval response, audit, webhook, log, metric, or error.
- Implementation: Closed reason/classification DTOs and safe state mappers expose references and enums only.
- Proof: Prohibited-value runtime/persistence scans and exact response/audit/webhook schemas.
- Adversarial evidence: Unknown fields, Unicode/nested/free-text attempts, adapter exception details, and failing-row messages.
- Status: PASS

## C27-CON-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Override/resolution, optional label, payment/attempt, authorization, capture ledger, audit, webhook, and idempotency completion commit atomically or roll back together.
- Failure sequence: Processor/money truth survives without override, or a rolled-back operation is cached as complete.
- Implementation: The operation transaction flushes override before adapter invocation and registers final-outcome metrics before fallible writes.
- Proof: Position-by-position production-constraint failures before processor and after authorization, ledger, audit, webhook, and idempotency completion, followed by clean same-key retry.
- Adversarial evidence: Exact zero/one row counts, deterministic simulator identity, and committed/rolled-back metric deltas.
- Status: PASS

## C27-CON-002

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Competing approvals converge to one resolution, one optional label, one processor authorization identity, and one capture at most.
- Failure sequence: Distinct keys call processor with divergent identities or create duplicate money/audit/webhook truth.
- Implementation: PaymentIntent/PaymentAttempt/case locks, unique source identities, terminal replay, and deterministic simulator operation identity serialize approval.
- Proof: Forced approve/approve PostgreSQL wait plus adapter invocation/identity reconciliation.
- Adversarial evidence: Same/different actor, same/distinct keys, confirmed-legitimate versus inconclusive bodies, and retry after rollback.
- Status: PASS

## C27-DATA-001

- Lens: DATA_ML_LINEAGE
- Invariant: APPROVE plus CONFIRMED_LEGITIMATE creates one immutable LEGITIMATE label with null fraud type; APPROVE plus INCONCLUSIVE creates none.
- Failure sequence: Every approval manufactures a legitimate label, a legitimate label carries fraud type, or duplicate replay labels twice.
- Implementation: Label-value semantics and database source-lineage checks separate analyst action from classification.
- Proof: Both classification contracts, direct invalid-label substitutions, timestamp boundaries, and replay.
- Adversarial evidence: CONFIRMED_FRAUD on APPROVE, LEGITIMATE plus fraud type, missing/mismatched reason, future evidence, and duplicate source.
- Status: PASS

## C27-DATA-002

- Lens: DATA_ML_LINEAGE
- Invariant: Approval evidence, authorization, optional label, and capture all bind to the original same-attempt REVIEW decision without changing historical feature/rule bytes.
- Failure sequence: Continuation attaches to another attempt or rewrites the decision/snapshot/rule result.
- Implementation: Copied lineage and PostgreSQL triggers bind resolution/label/authorization to the reviewed attempt.
- Proof: Historical-byte snapshots, direct copied-field substitutions, and live reconciliation.
- Adversarial evidence: Wrong intent/attempt/decision/origin/method and attempted historical updates/deletes.
- Status: PASS

## C27-API-001

- Lens: API_PRODUCT
- Invariant: APPROVE uses the existing idempotent resolve endpoint with closed classification/reason grammar and returns terminal processor/payment truth without breaking Chapter 26 outcomes.
- Failure sequence: Unknown fields succeed, replay bytes drift, APPROVE becomes a separate inconsistent endpoint, or prior CANCEL contracts regress.
- Implementation: Additive enums/response fields retain the dedicated risk credential and standard errors.
- Proof: Full method/media/validation/idempotency matrix plus all Chapter 26 regressions.
- Adversarial evidence: Missing/overlong key, malformed enum/time, same/changed body, unsupported method/media, cross-tenant, and terminal replay.
- Status: PASS

## C27-OBS-001

- Lens: OBSERVABILITY
- Invariant: Operators can distinguish approval, processor result, capture, commit, rollback, and denial with closed dimensions and no actor/resource/card values.
- Failure sequence: Processor continuation is invisible, rollback looks committed, or sensitive/high-cardinality tags appear.
- Implementation: Risk-operation outcomes use closed processor/capture/transaction values and bounded exception logging.
- Proof: Exact meter names/tag sets/cardinality and secret/card/reference absence across all fixtures and failures.
- Adversarial evidence: Adapter exception, constraint failure, each processor result, automatic/manual capture, and authorization denial.
- Status: PASS

## C27-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Fresh and retained V28 upgrades install approval/legitimate-label semantics without rewriting Chapter 26 history or weakening its triggers.
- Failure sequence: V29 changes old resolution/label bytes, invalidates V28 checks, or makes an existing endpoint subscribe to new behavior.
- Implementation: The forward-only migration changes closed constraints and protection functions without row updates.
- Proof: Fresh V1→current, retained V28→current with Chapter 26 CANCEL/FRAUD rows, Hibernate validation, and trigger checks.
- Adversarial evidence: Old rows, malformed new seeds, edited checksum, and rollback/downgrade attempts.
- Status: PASS

## C27-OPS-002

- Lens: OPERATIONS_RESILIENCE
- Invariant: Chapter 27 closes only after public approve-to-processor demos cover approved automatic/manual capture, decline, and pending processor 3DS with exact durable reconciliation.
- Failure sequence: Unit tests substitute for processor/payment/ledger proof or Chapter 28 completion is falsely claimed.
- Implementation: The loopback public demo and closure artifacts distinguish pending challenge creation from challenge completion.
- Proof: Full suites, matrix/dossier gates, traceability, later hostile pass, and live PostgreSQL demo cohorts.
- Adversarial evidence: Closure fails on pending rows, skipped fixture/capture branch, open debt/finding, or Phase 4 overclaim.
- Status: PASS

## Verification ledger

- `ReviewApprovalContractTests`: 11 cases covering every processor
  fixture, automatic/manual capture, same-key and distinct-key terminal replay,
  unchanged REVIEW lineage, and both classification partitions.
- `ReviewResolutionPostgreSqlConcurrencyTests`: Chapter 27 additions force both
  approval/cancellation orders, competing approvals, disable-before-approval,
  and failures at override flush, authorization, ledger, audit, webhook, and
  idempotency completion. Each failure reconciles zero partial Chapter 27 rows
  before a clean same-key retry.
- `SimulatorProcessorCatalogTests`: same PaymentAttempt produces the exact same
  simulator processor reference; a distinct attempt produces a distinct one.
- `PostgreSqlMigrationTests`: retained V25 rows remain byte/value equal through
  V26–V29 and PostgreSQL installs the closed approval/legitimate semantics.
- `scripts/demo-review-approval.py`: current binary plus retained
  PostgreSQL V28→V29 proved `CAPTURED|AUTHORIZED|RESOLVED|REVIEW`, one
  resolution, zero labels, three authorizations including the two setup
  declines, two balanced capture entries, deterministic processor identity,
  and exact same/distinct-key terminal replay.
- Chapter 26 actor, tenant, credential privacy, audit/webhook, immutable-history,
  and response-boundary proof remains applicable because Chapter 27 uses the
  same locked resolve endpoint and only adds closed enums and identifiers.

The traceability pass mapped every frozen promise to the implementation and
the evidence above. The later hostile pass specifically revisited processor
decline versus terminal automatic capture, disabled actors waiting on the
operator lock, approval/cancel arrival order, competing classifications after
winner commit, and failures before and after processor invocation. No blocking
finding or undocumented deferral remained.
