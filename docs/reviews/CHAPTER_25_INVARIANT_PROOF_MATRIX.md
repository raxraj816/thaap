# Chapter 25 Invariant Proof Matrix

- Chapter: Chapter 25 — Integrated Fraud Decision MVP Closure
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

This matrix freezes Chapter 25 before its integrated closure work and Phase 3
completion claim. Chapters 23 and 24 already own the production implementation;
Chapter 25 owns cross-branch reconciliation, the public demo, source-of-truth
alignment, and the later traceability and hostile reviews. The earlier demo run
was exploratory evidence for those implementation chapters, not a Chapter 25
closure disposition.

## C25-PAY-001

- Lens: PAYMENT_FRAUD
- Invariant: Public confirms produce coherent ALLOW, BLOCK, REQUEST_3DS, and REVIEW truth, and only ALLOW can reach the processor or create Authorization and money-side state.
- Failure sequence: A non-allow decision reaches the processor, a winning action is not applied to payment state, or review cancellation creates void or ledger truth.
- Implementation: The locked confirm transaction applies the aggregated decision before its branch-specific processor boundary; review cancellation has a separate zero-money transition.
- Proof: `PaymentIntentConfirmContractTests`, `PaymentIntentConfirmProcessorAdapterCallTests`, PostgreSQL forced interleavings, and `demo-chapter-25-fraud-decision-mvp.py` reconcile all four branches.
- Adversarial evidence: Amount/attempt precedence, replay, distinct keys, and review cancel partitions prove exact state and zero forbidden rows.
- Status: PASS

## C25-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: Every feature, result, decision, case, payment, cursor, audit, webhook, and idempotency path remains bound to one merchant and traffic origin at API and database boundaries.
- Failure sequence: A substituted tenant, attempt, decision, cursor, or origin reveals or attaches another merchant's fraud truth.
- Implementation: API-key merchant context, scoped repositories, composite V18/V22/V23 foreign keys, and V24 review-history enforcement carry tenant and origin identity end to end.
- Proof: `MerchantTenantIsolationTests`, PostgreSQL migration substitution cases, and review cursor contracts prove missing-equivalent access and SQL rejection.
- Adversarial evidence: Cross-merchant API IDs plus mismatched rule/review semantic lineage are rejected without mutation or disclosure.
- Status: PASS

## C25-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: Decision and review outputs, logs, audits, webhooks, metrics, and the demo expose only allowlisted bounded evidence and never secrets, card data, PII, merchant-controlled prose, or idempotency material.
- Failure sequence: Entity serialization or an unbounded metric/log dimension leaks sensitive values while explaining a decision.
- Implementation: Dedicated response records, governed feature projection, enum/code evidence, audit sanitization, bounded metrics, and a loopback-only demo constrain every surface.
- Proof: Feature/privacy boundary tests, tenant contracts, exact metric tests, source scans, and the loopback/no-proxy/redirect-rejecting demo cover success, rollback, and error paths.
- Adversarial evidence: Seeded prohibited values, malformed catalogue state, non-allow actions, historical reads, and redirects/proxy settings remain safe.
- Status: PASS

## C25-CON-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Confirm and review-cancel commit one all-or-nothing lineage under the global order idempotency ownership, PaymentIntent, decision evidence, then ManualReviewCase.
- Failure sequence: A partial result/case survives rollback, same-key calls diverge, distinct keys duplicate work, or confirm/cancel deadlock into split state.
- Implementation: Transactional orchestration, explicit pre-processor flush, pessimistic locks, idempotency fencing, and uniqueness constraints enforce convergence.
- Proof: Position-by-position rollback tests and `Chapter22FeatureLineagePostgreSqlConcurrencyTests` force waits at feature/result/case/idempotency/intent SQL; its real rule-result FK failure occurs through the production flush and proves zero processor/partial truth plus one same-key retry.
- Adversarial evidence: Same and distinct keys, result positions, review persistence failure, block confirms, and review cancellation are forced on PostgreSQL rather than inferred from thread starts.
- Status: PASS

## C25-DATA-001

- Lens: DATA_ML_LINEAGE
- Invariant: Each new decision retains seven typed feature values, one exact snapshot, and three immutable ordered rule results whose copied semantics are bound to immutable definitions; historical stub bytes remain unchanged.
- Failure sequence: A non-match is omitted, copied rule meaning is mixed, review reasons attach to a non-review decision, or an upgrade fabricates or rewrites history.
- Implementation: Complete evaluation plus V18–V25 typed, immutable, composite lineage and conservative subscription-migration constraints and startup catalogue validators enforce historical truth.
- Proof: Engine vector tests, database contracts, fresh/upgrade PostgreSQL migrations, historical detail contracts, and demo SQL reconciliation verify exact cardinalities and identity.
- Adversarial evidence: All eight match vectors, type/rule semantic cross-products, ALLOW-to-review substitution, mutation attempts, and retained V17/V19 data are covered.
- Status: PASS

## C25-API-001

- Lens: API_PRODUCT
- Invariant: Confirm remains replay-stable and backward compatible while rule evidence and bounded review list/detail/cancel APIs form a demoable merchant-to-review loop without claiming analyst resolution.
- Failure sequence: Additive fields break historical reads or replay bytes, pagination skips/leaks, or an unsupported review mutation expands product scope.
- Implementation: Additive DTOs, stored idempotent responses, ruleset-aware empty historical results, stable owned cursors, and a deliberately narrow review controller preserve the contract.
- Proof: Confirm/review contract and tenant tests plus the public demo cover new, historical, exact replay, all pagination/cursor/limit/method partitions, missing-equivalent access, and cancellation; equal-timestamp pagination runs on PostgreSQL.
- Adversarial evidence: Limits, owned and foreign cursors, every decision action, exact replay, and unsupported lifecycle states are enumerated.
- Status: PASS

## C25-OBS-001

- Lens: OBSERVABILITY
- Invariant: Operators can distinguish rule outcomes, fraud actions, review open/cancel, failure stages, and traffic origin using only closed bounded dimensions.
- Failure sequence: Decision failures are invisible or merchant/resource/value/error text creates sensitive high-cardinality telemetry.
- Implementation: `FraudDecisionMetrics`, existing feature metrics, audit events, and action-specific webhooks emit bounded operational truth after transaction outcome.
- Proof: `FraudDecisionMetricsTests`, feature metric tests, the cancellation webhook rollback contract, and source scans verify exact names/tags/counts, transaction-outcome timing, and absence of forbidden dimensions.
- Adversarial evidence: All actions and arbitrary exception/value inputs cannot alter the finite metric vocabulary.
- Status: PASS

## C25-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Phase 3 closes only when fresh and supported upgrades, full suites, public demo, structural gates, traceability, hostile review, registers, learning trail, and every source of truth agree with no blocking debt or finding.
- Failure sequence: A green focused suite masks migration drift, retained data failure, stale documentation, an open finding, or a premature Phase 3 completion claim.
- Implementation: Forward migrations, readiness validators, matrices/dossiers, executable repository gates, and explicit plan/README/learning reconciliation form the release boundary.
- Proof: Full Maven and Python suites, migration tests, public demo, invariant/escaped/debt/learning/commit-readiness checks, and final source scans must all pass.
- Adversarial evidence: Retained applied migration checksums, partial and exact-full legacy subscription sets, post-Phase-3 nine-event sets, malformed catalogues, unresolved statuses, and contradictory completion text are rejected or conservatively preserved as promised.
- Status: PASS
