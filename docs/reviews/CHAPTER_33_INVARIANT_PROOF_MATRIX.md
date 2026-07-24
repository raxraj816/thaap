# Chapter 33 Invariant Proof Matrix

- Chapter: Chapter 33 — Post-Authorization Signals And Review Money Actions
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

This matrix was frozen after Chapter 32 closure and before Chapter 33 production
code or migrations. Planned proof is intentionally stronger than a green unit
suite: financial and concurrency claims must pass against PostgreSQL with forced
orderings and exact durable postconditions.

## C33-PAY-001

- Lens: PAYMENT_FRAUD
- Invariant: A post-authorization signal is eligible only for an existing tenant-qualified captured or already-refunded automatic-capture payment lineage; it never fabricates authorization, capture, refund, or chargeback truth.
- Failure sequence: A pre-authorization, declined, requires-action, manual-capture, foreign, or malformed lineage opens actionable review or moves money.
- Implementation: Planned locked PaymentIntent plus immutable attempt/authorization/decision lineage and closed eligibility checks.
- Proof: Planned state/capture-method/amount/currency/tenant matrix and direct-SQL lineage substitutions.
- Adversarial evidence: Stale state, wrong authorization, disputed payment, partial lineage, and foreign IDs.
- Status: PASS

## C33-FRD-001

- Lens: PAYMENT_FRAUD
- Invariant: A signal recommendation is evidence, not an action: `NO_ACTION` and `REFUND` ingest no review or money side effect; only `REVIEW` opens one POST_AUTHORIZATION case, and only a later valid resolution may refund or label.
- Failure sequence: Ingestion refunds, labels, rewrites a decision, or creates a case for a non-REVIEW recommendation.
- Implementation: Planned append-only signal and explicit review creation branch with no ingestion-time money service call.
- Proof: Planned recommendation table with exact row counts across signal/review/refund/ledger/label/audit/webhook.
- Adversarial evidence: Recommendation spoofing, retry, stale event, and `REFUND` shortcut attempts.
- Status: PASS

## C33-FRD-002

- Lens: PAYMENT_FRAUD
- Invariant: Original FraudDecision, features, rules, policy, authorization, and earlier labels remain immutable; confirmed post-auth labels point to signal, resolution, original decision, evidence time, and server availability time.
- Failure sequence: New evidence rewrites original score/action/reasons, backdates training availability, or loses source lineage.
- Implementation: Planned signal-qualified case/label columns, append-only triggers, and existing decision immutability.
- Proof: Planned before/after decision bytes, SQL mutation rejection, source/time assertions, and point-in-time label queries.
- Adversarial evidence: Out-of-order evidence, future timestamp, cross-signal label, source substitution, and direct update/delete.
- Status: PASS

## C33-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: Signal ingestion, deduplication, review reads/actions, refund reuse, label, ledger, audit, and webhook lineage stay within one merchant; foreign and missing resources are response-equivalent.
- Failure sequence: Tenant substitution observes or attaches another merchant's payment, signal, case, operator, refund, chargeback, decision, or label.
- Implementation: Planned composite foreign keys, merchant-qualified repositories, credential context, and server-derived lineage.
- Proof: Planned two-tenant route and direct-SQL substitution matrix with identical not-found surfaces.
- Adversarial evidence: Mixed IDs, foreign external key, credential swap, operator swap, and same UUID-shaped payloads.
- Status: PASS

## C33-AUTH-001

- Lens: SECURITY_PRIVACY
- Invariant: Merchant credentials may ingest signals but cannot resolve risk reviews; ANALYST may resolve only assigned `NO_ACTION`; `REFUND` and `REFUND_AND_REPORT_FRAUD` require an active RISK_MANAGER revalidated under the mutation transaction.
- Failure sequence: Viewer, analyst, disabled, foreign, stale-prechecked, or merchant credential moves money or reports fraud.
- Implementation: Planned stage/outcome role gate after locked operator reload and existing missing-equivalent denial.
- Proof: Planned credential/status/role/assignment/stage/outcome matrix plus disable-versus-resolution forced ordering.
- Adversarial evidence: Self-claimed role, reassignment, disabled-in-flight actor, and terminal replay.
- Status: PASS

## C33-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: Signals and refund reasons use bounded closed codes and safe references; no free text, raw PAN/CVV, credential, IP, email, address, HMAC, idempotency key, or hostile external key enters durable state, audit, webhook, metrics, logs, or replay bytes.
- Failure sequence: Signal ingestion becomes an unclassified PII store or rejection leaks a canary.
- Implementation: Planned strict DTO/enums, machine-key grammar, no generic JSON/text fields, and low-cardinality telemetry.
- Proof: Planned unknown/nested/free-text/oversize/Unicode/control payload and canary scans across all sinks.
- Adversarial evidence: Duplicate JSON keys, object/array smuggling, long keys, and exception messages.
- Status: PASS

## C33-API-001

- Lens: API_PRODUCT
- Invariant: Signal mutation and review resolution require fenced idempotency, exact replay, strict JSON, and stable terminal responses; source/external-event dedup works independently of request keys.
- Failure sequence: Changed-body replay, duplicate external event, stale reservation, malformed media/method, or terminal retry duplicates effects or changes bytes.
- Implementation: Planned existing IdempotencyService plus stored canonical signal fingerprint and terminal resolution reconstruction.
- Proof: Planned same/different key and body table, concurrent duplicate proof, method/media/JSON partitions, and byte assertions.
- Adversarial evidence: Field reordering, timestamp-only conflicts, empty/oversize keys, failed winner, and retry after clock advance.
- Status: PASS

## C33-DATA-001

- Lens: DATA_ML_LINEAGE
- Invariant: Signals are append-only and uniquely identified by tenant/source/external-event; occurrence, receipt, and server availability times remain distinct and ordered without treating stale arrival as historical availability.
- Failure sequence: Retry updates a prior signal, older arrival erases newer evidence, or client time controls training eligibility.
- Implementation: Planned additive tables, immutable trigger, stored request fingerprint, bounded occurrence time, and server-owned receipt/availability.
- Proof: Planned update/delete rejection, older/equal/newer tables, precision/timezone boundaries, and retained history reads.
- Adversarial evidence: Far past/future, same identity conflicting bytes, forged availability, and cross-tenant duplicate.
- Status: PASS

## C33-DATA-002

- Lens: DATA_ML_LINEAGE
- Invariant: Refund rows carry immutable catalogue version and one closed reason; historical rows become `LEGACY_UNSPECIFIED`, merchant API rows are `MERCHANT_REQUESTED`, and review rows use only compatible remediation reasons.
- Failure sequence: Free-form/unversioned reasons, retroactive historical inference, or reason/outcome/classification mismatch poisons operations and training truth.
- Implementation: Planned forward migration, enum/version columns, semantic constraints, and server-owned constructors.
- Proof: Planned all reason/outcome/classification/source combinations, retained-row migration, direct SQL substitution, and API shape compatibility.
- Adversarial evidence: Caller-supplied reason, unknown version/code, fraud reason on plain refund, and legacy rewrite.
- Status: PASS

## C33-CON-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Resolution, merchant refund, and chargeback serialize by locking PaymentIntent first; exactly one financial boundary owns the full captured balance and no lock-order cycle exists.
- Failure sequence: Two operations both observe CAPTURED and commit duplicate refunds/dispute liability, or deadlock from opposite lock order.
- Implementation: Planned shared refund component and global lock order PaymentIntent→Attempt→Case→Refund/Chargeback→ledger.
- Proof: Planned deterministic resolution-first, merchant-refund-first, and chargeback-first PostgreSQL interleavings with lock-wait evidence and exact balances.
- Adversarial evidence: Different idempotency keys, rollback winner, retry loser, and preexisting refund reuse.
- Status: PASS

## C33-CON-002

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Concurrent identical signal events have one committed winner and converge to one signal and at most one POST_AUTHORIZATION review; conflicting bytes never create a second truth.
- Failure sequence: Check-then-insert races duplicate cases/audit/webhook or silently reuse an external identity for different evidence.
- Implementation: Planned merchant lock plus database unique identity/fingerprint and stage-qualified review uniqueness.
- Proof: Planned deterministic identical/conflicting PostgreSQL requests with one-winner counts and exact replay/conflict.
- Adversarial evidence: Same event across tenants, failed winner, later retry, and reordered JSON.
- Status: PASS

## C33-LED-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: A new review refund reuses the existing balanced refund ledger boundary exactly once; an already-final exact refund is reused without new refund or ledger rows; no-action creates none.
- Failure sequence: Duplicate/unbalanced entries, wrong source/currency/amount, or replay posts a second reversal.
- Implementation: Planned extracted shared full-refund service using LedgerService and immutable resolution-to-refund link.
- Proof: Planned new/reused/no-action exact row/account/direction/group assertions and injected row/flush failures.
- Adversarial evidence: Currency mismatch, amount overflow, partial inserts, source substitution, and retry.
- Status: PASS

## C33-TXN-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Resolution, optional refund, ledger, optional label, case projection, audit, webhook, and idempotent response commit atomically or leave no new Chapter 33 truth.
- Failure sequence: A later boundary throws after an earlier write and retry sees partial money, label, review, notification, or replay state.
- Implementation: Planned single Spring transaction after fenced reservation, explicit flushes, local audit/webhook, and after-completion metrics.
- Proof: Planned injected failures at resolution/refund/ledger/label/case/audit/webhook/idempotency boundaries with zero partial rows.
- Adversarial evidence: Constraint failure, rollback-only, serializer failure, duplicate event, and same-key retry.
- Status: PASS

## C33-OBS-001

- Lens: OBSERVABILITY
- Invariant: Operators can distinguish signal source/recommendation/dedup, review stage/outcome/role, refund new/reused, label result, commit/rollback, and denial using closed low-cardinality dimensions only.
- Failure sequence: Rolled-back refund appears committed, recommendation is confused with action, or identifiers/evidence/credentials enter tags.
- Implementation: Planned after-completion metrics and safe fixed tag sets.
- Proof: Planned exact meter/tag/cardinality tests, rollback fixtures, log capture, and sensitive-canary scans.
- Adversarial evidence: Foreign denial, duplicate, stale signal, rollback, and hostile key.
- Status: PASS

## C33-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Fresh V1 and retained V44 upgrades add empty signal truth, preserve prior bytes, classify historical refunds explicitly, and let the current binary read every retained cohort without fabricated reviews/labels/subscriptions.
- Failure sequence: Migration mutates prior payment/fraud/ledger semantics, fabricates signals, weakens immutability, or changes webhook consent.
- Implementation: Planned forward-only V45/V46 migrations and opt-in webhook types.
- Proof: Planned fresh and retained migrations, Hibernate validation, byte comparisons, empty tables, historical reason assertion, full suites, and retained startup.
- Adversarial evidence: Captured/refunded/disputed/review/chargeback cohorts, malformed seed, and legacy subscriptions.
- Status: PASS

## C33-PROD-001

- Lens: API_PRODUCT
- Invariant: Public language distinguishes merchant-ingested simulator signal, recommendation, analyst/manager decision, financial refund, and fraud label; it never claims network verification or automated action.
- Failure sequence: Response, audit, webhook, docs, or demo presents a recommendation as executed fraud truth or hides refund reuse.
- Implementation: Planned explicit source attestation/recommendation/action fields and safe signal-to-review-to-refund response lineage.
- Proof: Planned vocabulary contracts and live REVIEW→manager refund, REFUND recommendation-only, no-action, reused-refund demos.
- Adversarial evidence: Confirmed fraud without refund, refund without label, stale signal, and fully refunded payment.
- Status: PASS

## Verification evidence supplement

### Payment, fraud, authorization, and product boundaries

- `PostAuthorizationContractTests` proves recommendation/action
  separation, captured automatic-payment eligibility, manager-only money
  action, analyst assigned no-action, exact existing-refund reuse, balanced
  ledger rows, confirmed-fraud label lineage, original-decision immutability,
  and the closed outcome/classification/reason taxonomy on PostgreSQL.
- Signal identity, strict grammar, tenant substitution, changed-body replay,
  out-of-order arrival, invalid time order, and direct append-only mutation
  partitions pass. Concurrent identical external events serialize on the
  merchant boundary and converge to one signal and one POST_AUTHORIZATION case.
- The public live demo passed on the retained V48 database: REVIEW opened a
  case; RISK_MANAGER `REFUND_AND_REPORT_FRAUD` created exactly one full refund,
  one review-money provenance row, one label, and balanced ledger truth; a
  separate REFUND recommendation left a captured payment unchanged.

### Money concurrency, rollback, and migration boundaries

- Deterministic PostgreSQL resolution-first and merchant-refund-first forced
  interleavings visibly wait on PaymentIntent and converge without duplicate
  refund or ledger state. Chapter 32's chargeback-first forced ordering applies
  to the same extracted `FullRefundService` and PaymentIntent-first lock.
- Injected ledger, audit, and webhook failures roll back resolution, refund,
  refund-action, label, case projection, audit, webhook, payment transition,
  and idempotency truth; retry begins from the unchanged captured/open cohort.
- V45/V46 fresh and retained migration proof passes with Hibernate validation,
  explicit `LEGACY_UNSPECIFIED` historical refund reasons, empty signal/action
  tables, unchanged historical review/decision rows, and unchanged webhook
  consent. The current binary advanced the retained local V44 database through
  V48 before the Chapter 33 live demo.

### Review disposition

- Plan-to-implementation traceability: PASS.
- Temporally separate hostile sequence review: PASS. It re-opened signal time
  order, response money provenance, external-event concurrency, PostgreSQL
  timestamp binding, refund lock ordering, and rollback families.
- Custom infrastructure: none; existing transactions, PostgreSQL, ledger,
  idempotency, audit, webhook, and strict JSON boundaries are reused.
- Debt and escaped findings: none. Findings were fixed before a Chapter 33
  completion claim and have executable regression proof.
