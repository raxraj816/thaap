# Chapter 43R Invariant Proof Matrix

- Chapter: Chapter 43R — Phase 4.5 Product Closure Recovery
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

The full `ER-0064` through `ER-0073` recovery set is resolved. The original
Phase 4.5 promises stayed frozen while all four inherited production-dialect
families, 759 backend tests, 140 repository tests, 20 UI tests, and the
production UI build passed.

This recovery matrix was drafted and frozen on 2026-07-27 after an independent
completion audit disproved Chapter 43's integrated closure claim and before any
Chapter 43R production, client, test, or recovery-document change. It preserves
the original Phase 4.5 product promises and reopens the complete UI,
authorization, retry/idempotency, browser-proof, and governance families rather
than treating the observed examples as isolated documentation defects.

## C43R-PAY-001

- Lens: PAYMENT_FRAUD
- Invariant: Monitoring, endpoint verification, developer retry, role-aware rendering, and recovery documentation remain operational surfaces only and never mutate PaymentIntent, PaymentAttempt, Authorization, FraudDecision, label, chargeback, refund, ledger, or processor truth.
- Failure sequence: A cockpit refresh, drill-down, endpoint verification retry, or scenario-operation replay re-enters payment/fraud orchestration or reports an operation as successful before its durable response exists.
- Implementation: Added read-only monitoring/cohort and merchant endpoint-verification controls, server-refreshed results, and actor-scoped scenario idempotency without schema or money/fraud state-machine changes.
- Proof: All seven public reconciliation clients passed on isolated V64 PostgreSQL; 759 backend tests without skips, 140 repository tests, 20 UI tests/build, and domain/ledger invariants passed.
- Adversarial evidence: Empty and populated monitoring, exact verification retry, duplicate scenario claim/complete/fail, stale response, failed dependency, and unchanged domain/ledger counts.
- Status: PASS

## C43R-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: The cockpit derives the live operator role from authenticated server context, hides manager-only actions from VIEWER/ANALYST, and cannot use copied monitoring cursors, review IDs, endpoint IDs, delivery IDs, or scenario IDs across merchants.
- Failure sequence: The UI guesses a role, renders a manager mutation to an analyst, or a valid credential plus a copied foreign identifier discloses or mutates another merchant's state.
- Implementation: Added authenticated `/risk/operators/me`, server-role-gated controls, viewer-safe conditional loading, and merchant-qualified API use.
- Proof: `operatorSessionProjectsOnlyServerDerivedIdentityAndRole`, existing two-tenant contracts, UI role partitions, and the live VIEWER/RISK_MANAGER browser pass prove the boundary.
- Adversarial evidence: VIEWER/ANALYST/RISK_MANAGER, disabled credential, foreign cursor/review/endpoint/delivery/run, equal UUID-shaped inputs, and 401/403/404 distinctions without disclosure.
- Status: PASS

## C43R-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: Operator, merchant, and runner credentials, endpoint signing secrets, verification challenge material, note plaintext, payment identities, and idempotency material remain memory-only or server-protected and never enter URLs, browser storage, rendered errors, logs, metrics, build output, or screenshots.
- Failure sequence: Query state persists a credential, an endpoint-verification response renders a secret/token, a failure echoes request content, or an idempotency example exposes a reusable key.
- Implementation: Credentials remain React-memory-only; verification uses a safe lifecycle projection; fetches omit credentials/referrers and reject redirects; errors expose only safe messages/request IDs.
- Proof: UI secret/storage canaries, backend privacy contracts, production build, live reload with every credential field empty, and all public-client redaction checks pass.
- Adversarial evidence: Secret-shaped credentials, raw endpoint secret, challenge token, note canary, request conflict, dependency error, reload, redirect, and build artifact scan.
- Status: PASS

## C43R-CON-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Scenario claim, verified-step completion, and terminal failure require actor-scoped Idempotency-Key reservations in addition to run-row lease/version fencing; exact replay returns one committed response and conflicting replay cannot duplicate progress or events.
- Failure sequence: A network-ambiguous claim or completion is retried, two clients reuse one key with different bodies, a stale worker retries after lease takeover, or generic idempotency completion races the scenario transaction.
- Implementation: Claim, completion, and failure now authorize the scenario runner before `IdempotencyService`, use stable operation identities, and retain run/worker/epoch/resource fencing; UI and Python clients reuse stable keys.
- Proof: `everyScenarioMutationRequiresTransportIdempotencyAndReplayKeepsRunnerAuthority`, the full scenario contract, PostgreSQL forced-lock suite, exact event counts, and the public scenario client pass.
- Adversarial evidence: Same/different key, same/different actor, same/different body, current/stale lease epoch, exact/conflicting resource, pre/post-terminal retry, and rollback.
- Status: PASS

## C43R-DATA-001

- Lens: DATA_ML_LINEAGE
- Invariant: The cockpit displays every monitoring metric with exact numerator, denominator-or-not-applicable, value, coverage, definition version, half-open window, asOf, and live/synthetic policy; drill-down rows reconcile without treating absent or unlabeled evidence as zero or legitimate.
- Failure sequence: Missing coverage renders as zero, synthetic traffic enters live-only results, drill-down loses the summary denominator, a later refresh silently changes asOf, or a chart implies model probability.
- Implementation: Added typed exact monitoring summary/cohort clients and table-first evidence with coverage, origin, definition, `asOf`, window, numerator, denominator, and explicit refresh.
- Proof: `renders partial monitoring truth and drills into the exact cohort window`, live no-evidence/cohort browser proof, existing formula/PostgreSQL contracts, and the monitoring demo pass.
- Adversarial evidence: Zero denominator, insufficient history, no denominator metric, live versus included synthetic, stale asOf, pagination, and late label/chargeback.
- Status: PASS

## C43R-API-001

- Lens: API_PRODUCT
- Invariant: The local cockpit exposes monitoring, analyst workspace, simulations, governed controls, disputes, trusted scenarios, endpoint verification, and dead-letter redelivery through the same public APIs as external clients, with no source-only constants or direct SQL.
- Failure sequence: One promised workflow exists only in a Python demo, endpoint verification requires a hidden request, monitoring has no product surface, or the browser reports optimistic success without server evidence.
- Implementation: Completed monitoring/cohort and merchant webhook-verification panels while retaining workspace, simulation, control, dispute, scenario, and redelivery flows.
- Proof: 13 cockpit contracts, production build, retained-stack browser journey, and all seven public clients pass without source or direct SQL product paths.
- Adversarial evidence: New merchant, empty workspace, partial monitoring, verification success/failure/expiry, redelivery, simulation progress, and no source/database access.
- Status: PASS

## C43R-API-002

- Lens: API_PRODUCT
- Invariant: Loading, empty, partial-coverage, stale/conflict, permission-denied, dependency-unavailable, and retry-safe states are explicit, accessible, role-correct, and never represented as success or zero.
- Failure sequence: Promise.all hides which dependency failed, 403 renders as generic invalid data, 409 loses the stale-state remedy, 503 looks empty, or retry creates a different mutation identity.
- Implementation: Added closed authentication/denied/stale/rate-limit/dependency/invalid classifications, accessible retry, explicit loading/empty/partial states, and stable mutation keys captured outside retry closures.
- Proof: Table-driven 401/403/503 tests, stale 409 exact-key retry, partial/empty/loading UI contracts, live invalid-parameter discovery, and corrected browser reruns pass.
- Adversarial evidence: Slow request, invalid credential, insufficient role, foreign absence, stale version, rate limit, dependency outage, malformed response, and successful retry.
- Status: PASS

## C43R-OBS-001

- Lens: OBSERVABILITY
- Invariant: Recovery adds no high-cardinality or sensitive telemetry; operators and users can distinguish monitoring, verification, and idempotency outcomes using closed server/client states and safe request IDs.
- Failure sequence: Merchant/endpoint/run/key values become tags, rollback emits success, browser errors expose bodies, or verification and dependency failures collapse into a false empty view.
- Implementation: Reused bounded backend metrics and safe request IDs, added only closed client failure kinds, and introduced no analytics/log sink.
- Proof: Failure-classification UI tests, existing metric/privacy/rollback contracts, 759-test regression without skips, and public-client output canaries pass.
- Adversarial evidence: Success/rollback, denied/conflict/dependency/rate-limit, hostile IDs/credentials, endpoint verification failure codes, and request-ID rendering.
- Status: PASS

## C43R-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Chapter 43R is schema-neutral, preserves V1–V64 fresh/retained convergence, keeps jobs/transports bounded, and produces a deterministic UI build and public-demo path under documented local-only configuration.
- Failure sequence: Recovery rewrites an applied migration, adds hidden runtime configuration, breaks retained key readiness, creates unbounded polling, or requires a production-unsafe simulation gate.
- Implementation: Recovery is schema-neutral, uses explicit refresh plus existing bounded schedulers, preserves production defaults, and documents the local-only simulation gate.
- Proof: Fresh/retained V1–V64 regression, two isolated runtime starts, 13-test UI build, and seven public demos pass; the temporary audit database was removed.
- Adversarial evidence: Fresh V1, retained V49/V64, missing dependency/key, worker disabled, repeated refresh, default versus local proof gate, and clean build.
- Status: PASS

## C43R-GOV-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Phase 4.5 may return to COMPLETE only after ER-0059 onward are resolved, Chapters 36–43 and 43R matrices/dossiers pass their actual validators, every source agrees, zero undocumented debt remains, and the complete work is durable in Git-ready state.
- Failure sequence: Chapter 43 alone passes while inherited dossiers fail, combined headings evade the validator, README wording disagrees, test counts are stale, or an escaped UI/API finding remains only in conversation.
- Implementation: Repaired inherited dossier/README contracts, added `phase-closure-check.py`, taught the closure checker the `43R` coordinate, reconciled sources/registers/learning, and created this matrix/dossier pair.
- Proof: 140 repository tests include negative/positive aggregate-gate proof; every Chapter 36–43 plus 43R closure, invariant, escaped-review, debt, naming, learning, and readiness gate passes.
- Adversarial evidence: Missing inherited dossier, malformed heading, stale README/master plan, open ER/TD, wrong matrix argument, untracked recovery artifact, and contradictory COMPLETE claim.
- Status: PASS

## Frozen Recovery Boundary Table

| Boundary family | Valid partitions | Invalid partitions |
|---|---|---|
| Operator role | authenticated VIEWER/ANALYST/RISK_MANAGER with matching visible actions | guessed role, manager action rendered to lower role, disabled/foreign actor |
| Monitoring | exact window/asOf/version/coverage/origin and reconciled cohort | zero-for-absence, synthetic leakage, future evidence, unbound cursor |
| Webhook verification | merchant-authenticated endpoint, stable retry key, lifecycle-only safe result | operator credential, foreign endpoint, secret/token rendering, changed retry identity |
| Scenario mutation | stable actor-scoped key plus run/worker/epoch/resource fencing | missing/oversized/reused-conflicting key, stale worker, duplicate event/progress |
| Client state | loading, empty, partial, denied, stale, dependency, retry, success | generic optimism, false zero, secret-bearing error, unbounded automatic retry |
| Governance | every inherited and recovery gate/source agrees | validate-only-current dossier, combined missing headings, undocumented escaped finding |
