# Chapter 20 Invariant Proof Matrix

- Chapter: Chapter 20 — Deterministic Processor Catalog And Phase 2 Closure
- Matrix provenance: PRE_IMPLEMENTATION
- Scope status: FROZEN
- Review status: VERIFIED
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Fresh hostile review: PASS
- Boundary family review: PASS
- Custom infrastructure review: PASS
- Escaped review assessment: COMPLETE

## C20-PAY-001

- Lens: PAYMENT_FRAUD
- Invariant: Every documented simulator fixture produces exactly its promised processor authorization outcome, and processor truth never masquerades as the fraud engine's decision.
- Failure sequence: A card fixture is remapped, an unknown card stops following the documented default, or a suspected-fraud processor decline is recorded as a THAAP fraud `BLOCK`.
- Implementation: The simulator catalog is the single runtime mapping from safe BIN-plus-last4 metadata to processor outcome; FraudDecision remains independently persisted before processor authorization.
- Proof: SimulatorProcessorCatalogTests.documentedCatalogMatchesEveryProcessorFixtureContract and unknownValidCardMetadataUsesExplicitDefaultApprovalWithoutChangingDocumentedCatalog; PaymentIntentConfirmContractTests mapped decline, 3DS, mapped approval, and unknown approval tests; and the Chapter 20 public-API runner.
- Adversarial evidence: The explicit expected-value table and fresh runner compare processor code, network status, verification fields, 3DS result, authorized amount, lifecycle state, and independent `ALLOW` FraudDecision action across all nine catalog partitions.
- Status: PASS

## C20-PAY-002

- Lens: PAYMENT_FRAUD
- Invariant: Only an approved authorization can create capture eligibility, and every captured payment has one balanced capture ledger group.
- Failure sequence: A declined or 3DS-required attempt reaches capture, an automatic approval commits `CAPTURED` without ledger truth, or a manual approval is captured twice.
- Implementation: PaymentIntent state guards, approved Authorization lookup, capture transaction locking, and ledger source constraints enforce capture eligibility and accounting.
- Proof: PaymentIntentCaptureLedgerContractTests, PaymentIntentLifecycleContractTests, PaymentIntentCancelVoidContractTests, PaymentIntentCaptureLedgerRollbackTests, DatabaseMigrationTests.ledgerEntrySourceRequiresSameMerchantPaymentIntentAndUniqueCaptureAccount, and PaymentIntentCaptureCancelPostgreSqlConcurrencyTests.
- Adversarial evidence: Decline and 3DS capture attempts are rejected with zero ledger entries; injected persistence failures roll back capture; duplicate capture is rejected; automatic approval and the fresh Chapter 17 demo reconcile one balanced pair.
- Status: PASS

## C20-PAY-003

- Lens: PAYMENT_FRAUD
- Invariant: Cancel before authorization creates no processor fiction, cancel after approved manual authorization records durable void evidence, and canceled authorization can never be captured.
- Failure sequence: Cancellation invents a void for a decline, omits a void for an approved authorization, races capture into contradictory terminal truth, or permits later capture.
- Implementation: PaymentIntent cancel state machine, append-only AuthorizationVoid lineage, endpoint transaction boundary, and ordered row locking enforce cancel/void semantics.
- Proof: PaymentIntentCancelVoidContractTests, PaymentIntentCancelVoidRollbackTests, Chapter 17 H2/PostgreSQL migration tests, PaymentIntentCaptureCancelPostgreSqlConcurrencyTests, and the fresh Chapter 17 demo.
- Adversarial evidence: Both dangerous PostgreSQL lock orderings and all processor outcome classes are reconciled against void, capture, ledger, audit, and webhook truth.
- Status: PASS

## C20-PAY-004

- Lens: PAYMENT_FRAUD
- Invariant: A full refund exists only for one captured payment and exactly reverses its capture accounting without rewriting the original authorization or FraudDecision.
- Failure sequence: Refund precedes capture, exceeds or duplicates the captured amount, races another refund, commits without reversal entries, or changes original decision lineage.
- Implementation: Refund state guards, locked PaymentIntent mutation, capture-evidence validation, unique Refund lineage, balanced reversal constraints, and one transaction boundary enforce refund truth.
- Proof: RefundContractTests, RefundRollbackTests, DatabaseMigrationTests and PostgreSqlMigrationTests Chapter 18 cases, RefundPostgreSqlConcurrencyTests.concurrentRefundsCommitExactlyOneCoherentRefundTransaction, and the fresh Chapter 18 demo.
- Adversarial evidence: Invalid processor outcomes, duplicate idempotency keys, distinct concurrent refund requests, and injected ledger/webhook/audit failures leave no false refund state.
- Status: PASS

## C20-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: Merchant identity scopes every Phase 2 payment, attempt, authorization, void, refund, ledger, webhook, audit, and demo-evidence lookup.
- Failure sequence: Merchant A substitutes Merchant B's PaymentIntent, PaymentMethod, PaymentAttempt, Refund, webhook endpoint, delivery, or cursor identifiers.
- Implementation: API-key-derived MerchantContext, merchant-scoped repositories, composite database foreign keys, and tenant-bound webhook secret AAD enforce ownership.
- Proof: Existing MerchantTenantIsolationTests, money-mutation cross-merchant contract tests, webhook tenant tests, and database constraint suites.
- Adversarial evidence: Cross-merchant identifiers fail without revealing object existence, creating side effects, decrypting secrets, or exposing ledger/event history.
- Status: PASS

## C20-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: Processor catalog documentation and executable evidence never persist or emit raw PAN, CVV, buyer PII, API keys, signing secrets, card fingerprints, or idempotency hashes.
- Failure sequence: A demo report records the submitted test-card number or one-time API key, an audit/webhook payload captures raw input, a loopback response redirects a credential-bearing request to another origin, or a fixture encourages real-card use.
- Implementation: The tokenization boundary reduces transient card input to safe metadata; audit/webhook allowlists and the report sanitizer expose fixture labels rather than submitted PAN values; the demo HTTP client rejects every redirect before a follow-up request is created.
- Proof: CustomerAndPaymentMethodContractTests, AuditSanitizerTests, WebhookSecurityAndRetryTests, generate_synthetic_scenarios_tests.py, ProcessorOutcomeCatalogDemoTests.test_sensitive_report_scan_rejects_card_credentials_and_idempotency, and test_api_client_rejects_every_redirect_without_forwarding_credentials.
- Adversarial evidence: Exact submitted fixture values, buyer identifiers, API credential, one-time webhook signing secret, all three per-fixture idempotency keys, and prohibited field classes are scanned across persistence, logs, payloads, and the generated report; real loopback servers prove 301, 302, 303, 307, and 308 responses create no request at the redirect target.
- Status: PASS

## C20-SEC-002

- Lens: SECURITY_PRIVACY
- Invariant: Phase 2 webhook delivery preserves authenticated immutable payload bytes, bounded SSRF-safe transport, secret protection, and replay-safe merchant guidance.
- Failure sequence: Retry changes event bytes, DNS resolves to a prohibited address, an old worker sends after disable, or a duplicate callback applies a second merchant effect.
- Implementation: Chapter 19's immutable event envelope, encrypted signing keys, pinned-address socket transport, delivery leases, endpoint locking, and durable reference consumer remain unchanged.
- Proof: Existing Chapter 19 security, protocol, retry, PostgreSQL concurrency, migration, and callback fixture suites.
- Adversarial evidence: Exact-byte, grammar, size, timeout, DNS-result, endpoint-ordering, duplicate, restart, and payload-conflict partitions remain executable during Chapter 20 readiness.
- Status: PASS

## C20-CON-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Payment lifecycle state, ledger, audit, webhook, refund/void evidence, and idempotency completion commit atomically or roll back together.
- Failure sequence: Persistence fails after a status mutation but before one durable side effect, or a retry observes a completed response for a rolled-back mutation.
- Implementation: IdempotencyService wraps each mutation and response completion in the same transaction; injected repository failures exercise every Phase 2 side-effect boundary.
- Proof: Existing confirm, capture, cancel, refund, ledger, audit, webhook, and fraud-lineage rollback suites.
- Adversarial evidence: Injected failures at lineage, ledger, audit, and webhook boundaries assert both absence of partial rows and retryability of the original idempotency key.
- Status: PASS

## C20-CON-002

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Concurrent capture/cancel, refund/refund, and webhook claim/disable transitions produce one coherent winner under every dangerous production-database ordering.
- Failure sequence: Competing transactions read the same eligible state, wait on different locks, then both commit incompatible terminal or retryable truth.
- Implementation: PaymentIntent row locks, endpoint-before-delivery lock order, unique lineage constraints, claim tokens, and predicate-rechecked bulk updates serialize conflicts.
- Proof: Existing forced PostgreSQL capture/cancel, refund, webhook endpoint, finalization, routing, rotation, and metric interleaving tests.
- Adversarial evidence: Tests hold the production row lock, observe the competing PostgreSQL backend waiting on the intended lock, release each ordering, and assert durable postconditions.
- Status: PASS

## C20-DATA-001

- Lens: DATA_ML_LINEAGE
- Invariant: Every successful confirm attempt retains exactly one immutable FraudDecision and FeatureSnapshot regardless of processor outcome, retry, capture, cancel, refund, or webhook delivery.
- Failure sequence: Processor decline drops lineage, retry duplicates lineage, refund rewrites the original decision, or webhook failure rolls payment lineage backward.
- Implementation: Unique same-merchant decision/snapshot constraints and the confirm transaction bind lineage to PaymentAttempt before processor execution; later lifecycle operations append rather than rewrite.
- Proof: PaymentIntentConfirmContractTests, PaymentIntentConfirmFraudLineageRollbackTests, RefundContractTests, WebhookDeliveryExecutionContractTests.signedFailureRetrySuccessPreservesOneEventAndPaymentTruth, and the fresh Chapter 20 runner.
- Adversarial evidence: All nine catalog partitions, idempotent replay, retryable decline retry, refund, and webhook retry are compared against exact decision/snapshot row counts and stable identifiers.
- Status: PASS

## C20-API-001

- Lens: API_PRODUCT
- Invariant: The documented processor catalog is a stable developer contract whose expected PaymentIntent, PaymentAttempt, Authorization, verification, and 3DS projections match public API responses.
- Failure sequence: Documentation names a fixture outcome that runtime or API projection contradicts, or an internal enum leaks a processor/fraud semantic mismatch.
- Implementation: One immutable catalog feeds SimulatorProcessorAdapter; service documentation and the Chapter 20 runner derive expected behavior from the same reviewed contract.
- Proof: SimulatorProcessorCatalogTests, PaymentIntentConfirmContractTests, the processor-authorization fixture table, and scripts/demo-processor-outcome-catalog.py.
- Adversarial evidence: Every mapped fixture plus an unmapped valid test card is exercised; the runner raises before report publication on any field mismatch, non-loopback origin, any HTTP redirect, missing audit/delivery row, ledger entry, or sensitive output.
- Status: PASS

## C20-API-002

- Lens: API_PRODUCT
- Invariant: A fresh local Phase 2 demo proves approved automatic capture, approved manual capture/void, full refund reversal, processor decline, 3DS-required, and webhook retry without direct mutation of database truth.
- Failure sequence: A demo succeeds from stale rows, skips public mutation boundaries, records only console claims, or tolerates a mismatch between API and durable state.
- Implementation: Existing Chapter 17–19 public-API demos and the loopback-only Chapter 20 runner create collision-resistant fresh objects and fail closed on reconciled postconditions.
- Proof: Clean-PostgreSQL execution of all four demos with sanitized artifacts and explicit durable-state reconciliation.
- Adversarial evidence: Demos start from a fresh schema, use unique run identifiers, verify rejected/terminal branches, and do not treat process exit alone as product proof.
- Status: PASS

## C20-OBS-001

- Lens: OBSERVABILITY
- Invariant: Operators can distinguish processor outcome, money mutation, webhook delivery failure, retry, disable, exhaustion, and backlog without secret or tenant-cardinality leakage.
- Failure sequence: Different failures collapse into one metric/log class, a successful API response lacks audit evidence, or observability emits raw card/credential material.
- Implementation: Safe audit actions, bounded webhook metrics/error codes, request IDs, structured lifecycle logs, and Actuator health/metrics expose Phase 2 behavior.
- Proof: ConfirmAuditEventMapperTests, AuditLogServiceTests, AuditSanitizerTests, webhook executor/scheduler/endpoint metrics tests, HealthEndpointTests, and Chapter 20's exact audit-action reconciliation.
- Adversarial evidence: Failure classes are compared for required bounded dimensions while forbidden high-cardinality and sensitive fields are scanned for absence.
- Status: PASS

## C20-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: A clean or supported prior database reaches V9 with schema validation, all Phase 2 demos are repeatable, and non-trivial readiness always executes backend and repository tests.
- Failure sequence: An upgrade preserves impossible lifecycle/webhook state, a demo depends on unreproducible local residue, or readiness passes after skipping executable suites.
- Implementation: Forward-only V1–V9 migrations, Hibernate validation, fresh local infrastructure scripts, deterministic fixtures, and the mandatory non-trivial readiness gate enforce operability.
- Proof: DatabaseMigrationTests, PostgreSqlMigrationTests, CommitReadinessCheckTests, three fresh V1-to-V9 PostgreSQL demo databases, and the final commit-readiness invocation naming the Chapter 20 matrix and dossier.
- Adversarial evidence: Legacy-invalid migration states fail before false schema truth, fresh schemas validate, demos run after database recreation, and readiness is invoked without relying on an optional test flag.
- Status: PASS

## C20-OPS-002

- Lens: OPERATIONS_RESILIENCE
- Invariant: Existing registered Phase 2 debt remains bounded to its explicit trigger, and no fee, payout, dispute, or chargeback capability is falsely claimed as Phase 2 product truth.
- Failure sequence: Source documents mark Phase 2 complete while retaining contradictory “remaining” claims, silently broaden V1 money movement, or omit an accepted implementation gap from the debt register.
- Implementation: README, master plan, debt register, matrix, dossier, learning trail, architecture, concept, and service/script documentation are reconciled to the canonical Phase 2 boundary.
- Proof: Source-status search, technical-debt checker, closure checker, and fresh diff inspection.
- Adversarial evidence: Every Phase 2 status statement and deferral destination is searched; disputes remain Phase 4, partial accounting retains its trigger, and payout settlement remains outside V1.
- Status: PASS
