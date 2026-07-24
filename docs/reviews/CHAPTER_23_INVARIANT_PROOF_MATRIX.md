# Chapter 23 Invariant Proof Matrix

- Chapter: Chapter 23 — Deterministic Rules And Coherent Fraud Actions
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

This matrix was drafted before Chapter 23 production code or migration changes
from the frozen Phase 3 promises and hostile failure inventory. Planned class
and test names are proof obligations, not claims that implementation exists.
The chapter boundary deliberately includes the minimum `REVIEW` write path:
the immutable `rules_v1` catalogue contains a review rule, so shipping the
engine without coherent review payment and case truth would violate the phase
promise that every computed action is applied before processor authorization.

## C23-PAY-001

- Lens: PAYMENT_FRAUD
- Invariant: Every committed confirm evaluates the complete immutable `rules_v1` catalogue and applies exactly one `ALLOW`, `BLOCK`, `REQUEST_3DS`, or `REVIEW` branch before any processor authorization.
- Failure sequence: A rule computes a non-allow action but the attempt remains ready for processor, or the processor is called before aggregation and creates authorization or money truth.
- Implementation: `DeterministicConfirmDecisionEngine`, the locked confirm transaction, and explicit PaymentAttempt/PaymentIntent transition methods enforce decision-before-processor ordering.
- Proof: `DeterministicConfirmDecisionEngineTests`, `PaymentIntentConfirmContractTests`, and `FeatureLineagePostgreSqlConcurrencyTests` must prove all actions and processor exclusion.
- Adversarial evidence: Below/at/above boundaries, every action, and forced confirm orderings must reconcile decision, payment, processor, authorization, capture, ledger, audit, webhook, and review postconditions.
- Status: PASS

## C23-PAY-002

- Lens: PAYMENT_FRAUD
- Invariant: `BLOCK` is explicit terminal fraud truth on both attempt and intent, while fraud `REQUEST_3DS` is action-required truth distinct from processor-requested 3DS.
- Failure sequence: A block is recorded as technical `FAILED`, a fraud step-up creates an Authorization, or processor 3DS evidence is copied into a fraud-only branch.
- Implementation: New `BLOCKED` and existing action-required state transitions plus branch-specific audit/webhook payloads preserve the source of the action.
- Proof: Fraud-action contract, lifecycle, authorization, capture, cancel, audit, and webhook tests must distinguish every source and reject invalid follow-up mutations.
- Adversarial evidence: Block/capture/cancel/reconfirm and fraud-step-up/processor-step-up partitions must preserve zero processor and money rows for fraud non-allow branches.
- Status: PASS

## C23-PAY-003

- Lens: PAYMENT_FRAUD
- Invariant: `REVIEW` atomically creates PaymentAttempt `IN_REVIEW`, PaymentIntent `REQUIRES_REVIEW`, and exactly one tenant-scoped `OPEN` ManualReviewCase without processor or money truth.
- Failure sequence: Review wins aggregation but no case exists, a case commits without matching payment state, replay creates a second case, or processor work begins.
- Implementation: The confirm transaction persists the review case with composite tenant/attempt/decision identity and applies both payment transitions before idempotency completion.
- Proof: Review-intake contract, rollback, tenant, replay, and PostgreSQL concurrency tests must reconcile one immutable decision and one open case.
- Adversarial evidence: Attempt-two/three, replay, distinct-key concurrency, injected case persistence failure, and cross-tenant direct-SQL substitutions must leave all-or-nothing truth.
- Status: PASS

## C23-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: Rule results and review cases cannot cross merchant, attempt, decision, payment, feature, or traffic-origin boundaries through API, repository, or direct database substitution.
- Failure sequence: Merchant A reads B's result or a crafted foreign key attaches B's decision/case to A's payment lineage.
- Implementation: API-key merchant context, merchant-scoped repositories, and composite database foreign keys carry tenant identity through every new table.
- Proof: `MerchantTenantIsolationTests` and `PostgreSqlMigrationTests` must cover missing-equivalent API behavior and direct-SQL rejection.
- Adversarial evidence: Every cross-product substitution of merchant, attempt, decision, case, result, and origin must fail without side effects or disclosure.
- Status: PASS

## C23-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: Rules inspect only the exact Chapter 22 `DecisionFeatureContext`; rule results, reason text, APIs, audits, webhooks, logs, and metrics expose no card secret, fingerprint, BIN, last4, PII, credential, idempotency material, metadata, description, or unbounded identifier.
- Failure sequence: An evaluator reopens entity-derived inputs or copies raw configured/value text into an exception, metric tag, or public explanation.
- Implementation: Typed application-owned evaluators consume the governed context and emit enum/code-based bounded evidence; response mappers expose an allowlisted schema.
- Proof: `DeterministicConfirmDecisionEngineTests`, `PaymentIntentConfirmContractTests`, `FraudDecisionMetricsTests`, audit/webhook contracts, and source scans seed prohibited values and prove absence from outputs and meter identities.
- Adversarial evidence: Extra/missing/wrong-type context, malicious Unicode/config text, and exception paths must fail closed without sensitive output.
- Status: PASS

## C23-CON-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Feature lineage, all three rule results, FraudDecision, payment transitions, optional review case, optional processor/money truth, audit, webhook, and idempotency completion commit atomically or roll back together.
- Failure sequence: Result N commits while N+1 fails, a case/audit/webhook failure leaves payment state changed, or processor work survives local rollback.
- Implementation: One confirm transaction flushes complete local decision evidence before the external processor boundary and retains existing fenced idempotency ownership.
- Proof: Position-by-position rollback tests plus `FeatureLineagePostgreSqlConcurrencyTests.realRuleResultFlushFailureRollsBackBeforeProcessorReleasesReservationAndAllowsOneRetry` prove an actual copied-semantics FK failure at `ruleExecutionResultRepository.flush()`, zero processor/lineage rows, reservation release, and one complete same-key retry.
- Adversarial evidence: Failures at rule-result, decision, state, review, audit, webhook, and idempotency boundaries must produce exact zero/one cardinalities.
- Status: PASS

## C23-CON-002

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Same-key replay and distinct-key concurrent confirm converge to one attempt, decision, three results, one action branch, and at most one review case or authorization.
- Failure sequence: Two callers evaluate different attempt numbers or actions, duplicate a case/authorization, or complete divergent replay responses.
- Implementation: Idempotency-row fencing, PaymentIntent `FOR UPDATE`, attempt numbering, and unique composite constraints serialize decision creation.
- Proof: `FeatureLineagePostgreSqlConcurrencyTests` forces same/distinct-key ALLOW and BLOCK at feature/result SQL, plus REVIEW confirm/cancel conflicts at case and intent locks, and reconciles exact postconditions.
- Adversarial evidence: ALLOW and BLOCK span processor versus local-only confirm boundaries; REVIEW adds unique case state. REQUEST_3DS shares the proven local-only transaction/lock path and has separate exact processor-exclusion contracts.
- Status: PASS

## C23-DATA-001

- Lens: DATA_ML_LINEAGE
- Invariant: Every new `rules_v1` decision has exactly three immutable ordered RuleExecutionResults, including non-matches, linked to the exact immutable rule definition, decision, attempt, merchant, and traffic origin.
- Failure sequence: Short-circuit evaluation omits a non-match, result order changes, or a later rule/config mutation rewrites historical explanation.
- Implementation: Seeded immutable definitions, full-set evaluation, sequence/uniqueness constraints, composite lineage keys, and append-only triggers enforce complete evidence.
- Proof: Engine, database, migration, and decision-detail tests must reconcile all match vectors and reject insert/update/delete corruption.
- Adversarial evidence: Zero through three matches, duplicate/missing sequence, wrong rule/version/action/reason, and mutation attempts must cover the full result grammar.
- Status: PASS

## C23-DATA-002

- Lens: DATA_ML_LINEAGE
- Invariant: Aggregation is deterministic: `BLOCK > REVIEW > REQUEST_3DS > ALLOW`; primary reason uses numeric priority then immutable code; all matched reasons remain in stable order; score/risk mapping is exact and never represented as model probability.
- Failure sequence: Repository iteration order changes the winner, ties reorder reasons, or a deterministic severity score is labeled as model confidence.
- Implementation: Pure aggregation over sorted immutable results and fixed exact-decimal action mapping produces one decision artifact with null model version.
- Proof: Table-driven and property-style tests must cover every match subset, tie ordering, insertion order permutation, and exact decimal serialization.
- Adversarial evidence: All eight three-rule match vectors and permutations must produce identical action, score, risk, primary reason, and ordered reasons.
- Status: PASS

## C23-API-001

- Lens: API_PRODUCT
- Invariant: Confirm remains backward compatible while decision detail exposes safe ordered rule results and action-specific attempt states; historical `rules_stub_v1` decisions remain readable without fabricated results.
- Failure sequence: New fields change replay bytes, historical decisions fail because results are absent, or cross-tenant detail leaks rule evidence.
- Implementation: Additive response fields, merchant-scoped lookup, exact idempotent response persistence, and ruleset-aware historical representation enforce compatibility.
- Proof: New/historical/replay/missing/cross-tenant contract tests must verify status, bytes, schema, ordering, and prohibited-field absence.
- Adversarial evidence: Old and new rulesets, every action, malformed IDs, tenant substitution, and replay partitions must retain contract truth.
- Status: PASS

## C23-OBS-001

- Lens: OBSERVABILITY
- Invariant: Operators can distinguish action, rule evaluation outcome, stage, bounded failure cause, and traffic origin without merchant/resource IDs, feature values, card data, PII, or free-form text in metric dimensions.
- Failure sequence: Failures collapse into generic confirm errors or high-cardinality/sensitive values become tags.
- Implementation: Dedicated decision metrics use closed enums/codes and timers/counters around evaluation, persistence, and action application.
- Proof: `FraudDecisionMetricsTests` asserts exact names, complete bounded tag sets, counts, commit-only success, and rollback failure; the cancellation webhook rollback contract proves the production transaction observer.
- Adversarial evidence: Seeded sensitive values and arbitrary exception messages must never appear in metric identities.
- Status: PASS

## C23-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Fresh databases and realistic V19 upgrades seed exactly one valid immutable `rules_v1` catalogue and validate supported operators, types, thresholds, priorities, actions, and reason codes before serving confirms.
- Failure sequence: Partial/duplicate/malformed seeds start successfully, an unsupported operator fails only during payment traffic, or migration rewrites historical decisions.
- Implementation: Forward-only migrations, append-only database enforcement, Hibernate validation, and startup catalogue validation fail readiness.
- Proof: Fresh and V19 upgrade migration tests plus validator partitions must prove exact catalogue and historical byte preservation.
- Adversarial evidence: Missing/duplicate/extra/wrong-version/type/operator/action/threshold/priority/reason configurations must all fail before traffic.
- Status: PASS

## C23-OPS-002

- Lens: OPERATIONS_RESILIENCE
- Invariant: Chapter 23 cannot close until every action is applied coherently, every matrix row passes at its real boundary, hostile review is complete, no blocking debt/finding remains, and sources do not overclaim review operations or Phase 3 completion.
- Failure sequence: Aggregate tests pass while review intake is partial, concurrency is mocked, or documentation claims the analyst workflow/Phase 3 is complete.
- Implementation: Matrix, dossier, debt/escaped registers, learning trail, migration/demo evidence, and named readiness gates reconcile the closure claim.
- Proof: Structural gates, full suites, public API demo, plan-to-proof traceability, and temporally separate hostile review must all pass.
- Adversarial evidence: Final scans and closure checks must reject pending entries, skipped tests, open debt/findings, status contradictions, and premature product claims.
- Status: PASS
