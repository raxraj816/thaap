# Chapter 24 Invariant Proof Matrix

- Chapter: Chapter 24 — Review Reads And Merchant Cancellation
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

This matrix was drafted before Chapter 24 review API or cancellation production
changes. Chapter 23 owns immutable review intake; Chapter 24 exposes that truth
and provides the only Phase 3 exit, without claiming analyst resolution.

## C24-PAY-001

- Lens: PAYMENT_FRAUD
- Invariant: Merchant cancellation of an open review atomically moves PaymentIntent `REQUIRES_REVIEW`, PaymentAttempt `IN_REVIEW`, and ManualReviewCase `OPEN` to canceled truth without rewriting the original FraudDecision.
- Failure sequence: Intent cancels while attempt/case remains open, case cancels while payment remains reviewable, or the decision is mutated to hide the original action.
- Implementation: Existing idempotent cancel locks PaymentIntent first, then the tenant-scoped open case, updates attempt and case, and records audit/webhook truth in one transaction.
- Proof: Review cancellation contract, rollback, replay, and PostgreSQL forced-order tests must reconcile all entities and immutable decision bytes.
- Adversarial evidence: Confirm/cancel, replay/cancel, repeated cancel, injected case/audit/webhook failure, and stale case partitions must prove one all-or-nothing result.
- Status: PASS

## C24-PAY-002

- Lens: PAYMENT_FRAUD
- Invariant: Review cancellation creates no processor, Authorization, void, capture, refund, or ledger truth.
- Failure sequence: Generic cancellation treats review like an approved authorization and emits a void or money side effect.
- Implementation: The `REQUIRES_REVIEW` branch bypasses authorization lookup/void and only applies review cancellation state.
- Proof: Contract and rollback tests assert zero money/processor artifacts before and after cancellation.
- Adversarial evidence: Review cancellation across automatic/manual capture methods and repeated calls must retain zero money truth.
- Status: PASS

## C24-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: Review list, detail, cursor, and cancellation resolve tenant exclusively from the API key; cross-tenant IDs/cursors are missing-equivalent and create no side effect.
- Failure sequence: Merchant A reads B's case or uses B's cursor/payment ID to infer or mutate review state.
- Implementation: Merchant-scoped repositories, composite keys, and owned PaymentIntent locking enforce every boundary.
- Proof: API and direct-SQL tenant substitution tests cover case, cursor, intent, attempt, and decision identities.
- Adversarial evidence: Valid/missing/cross-tenant/malformed identifiers must have indistinguishable disclosure and zero mutation.
- Status: PASS

## C24-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: Review APIs, audits, webhooks, logs, and metrics expose only allowlisted lineage/status/reason/timestamps and never card data, PII, credentials, feature values, metadata, description, or idempotency material.
- Failure sequence: A convenient entity serializer leaks payment method/customer or feature snapshot contents into review output.
- Implementation: Dedicated response records and bounded enum-based signals expose no entity graph or free-form text.
- Proof: Seeded prohibited-value tests and source scans verify every output surface.
- Adversarial evidence: List/detail/cancel/success/failure/cross-tenant paths must all exclude prohibited material.
- Status: PASS

## C24-CON-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Lock order is idempotency ownership, PaymentIntent, then ManualReviewCase; concurrent confirm/replay/cancel permits one coherent terminal state without deadlock or split truth.
- Failure sequence: Cancel locks case before intent while confirm locks intent first, or stale replay returns pre-cancel truth after mutation.
- Implementation: Pessimistic repositories and one cancel transaction preserve the global order and exact idempotent response semantics.
- Proof: Deterministic PostgreSQL interleavings must observe lock waits for confirm-first and cancel-first sequences and assert final cardinalities.
- Adversarial evidence: Same/different cancel keys, replay during cancel, and cancel during review creation must be forced at real locks.
- Status: PASS

## C24-DATA-001

- Lens: DATA_ML_LINEAGE
- Invariant: Cancellation changes only mutable review/payment lifecycle columns; immutable features, rule results, FraudDecision action/reasons, and original review reasons remain unchanged for future labels and replay.
- Failure sequence: Cancellation overwrites the review reason or fraud action, destroying why the payment entered review.
- Implementation: Narrow entity transition methods and append-only decision/rule/feature protections separate decision truth from operational case status.
- Proof: Before/after byte comparisons and database mutation rejection tests prove historical lineage preservation.
- Adversarial evidence: Cancel/replay/repeated cancel and attempted direct mutations must not change decision-time evidence.
- Status: PASS

## C24-API-001

- Lens: API_PRODUCT
- Invariant: Review list is status-filtered, limit-bounded, stable, cursor-based, and tenant-scoped; detail returns the exact case; no assignment or analyst-resolution mutation exists.
- Failure sequence: Unbounded list exhausts service, unstable ordering skips/duplicates cases, cross-tenant cursor leaks position, or an accidental generic update endpoint expands scope.
- Implementation: `limit` 1–100, `(created_at,id)` ordering, owned cursor lookup, dedicated GET endpoints, and no review mutation controller enforce the contract.
- Proof: `PaymentIntentConfirmContractTests.reviewListIsBoundedStableAndTenantScoped` proves empty and status-filtered reads, 0/1/100/101 limits, repeated-page equality, malformed/missing/foreign cursors, and unsupported POST as 405; `FeatureLineagePostgreSqlConcurrencyTests.reviewCursorPaginationUsesStablePostgreSqlOrderingForEqualTimestamps` proves 2+1 pages without skips/duplicates for three equal-timestamp rows.
- Adversarial evidence: A deleted cursor is unreachable because V24 rejects case deletion; missing and cross-tenant cursor identities return missing-equivalent 404, while equal-timestamp and repeated pages retain stable `(created_at,id)` order.
- Status: PASS

## C24-OBS-001

- Lens: OBSERVABILITY
- Invariant: Operators can distinguish review opened/canceled and cancellation failures with bounded status/origin/cause labels and no IDs or sensitive data in metrics.
- Failure sequence: Review work is invisible or merchant/case identifiers become unbounded labels.
- Implementation: Closed-enum counters and existing request/audit correlation provide safe operational visibility.
- Proof: `FraudDecisionMetricsTests` asserts exact commit/rollback names, tags, and counts; `PaymentIntentConfirmContractTests.cancelReviewWebhookFailureRollsBackAllStateAndEmitsBoundedFailureMetrics` proves rollback state plus bounded cancellation failure telemetry at the production boundary.
- Adversarial evidence: Success and every failure partition retain bounded dimensions.
- Status: PASS

## C24-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Chapter 24 closes only with public API, rollback, migration, forced concurrency, privacy, and source-of-truth proof and no claim of analyst assignment/resolution.
- Failure sequence: Happy-path GET/cancel tests pass while concurrency, tenant cursors, or original-decision preservation is unproven.
- Implementation: Matrix/dossier/readiness gates and later hostile review reconcile every promise.
- Proof: Full suites, exact PostgreSQL tests, public demo, structural gates, and hostile pass are required.
- Adversarial evidence: Pending/skipped/contradictory/open-debt states must block closure.
- Status: PASS
