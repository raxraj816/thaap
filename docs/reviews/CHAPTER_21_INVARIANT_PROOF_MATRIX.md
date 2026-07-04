# Chapter 21 Invariant Proof Matrix

- Chapter: Chapter 21 — Previous-Phase Debt Retirement And Zero-Debt Entry Gate
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

Scope clarification: before the run-state implementation, the matrix was
re-frozen around an operator-local durable journal. One generator run creates
and verifies multiple isolated merchants, so pretending the orchestration row
belongs to one merchant would be false. Public product objects remain strictly
tenant-scoped; the loopback-only operator journal owns their cross-tenant run
references and is mode `0600`.

The final hostile passes reopened five boundary families after `ER-0023`
through `ER-0028`: credentialed HTTP effective destination, time-bounded
secret use, complete KEK inventory, crash-recoverable endpoint reservations,
and sanitized generator cleanup lineage. The matrix was re-verified only after
the implementations and hostile tests below closed every partition.

## C21-PAY-001

- Lens: PAYMENT_FRAUD
- Invariant: Debt-retirement operations do not rewrite lifecycle, processor, fraud, refund, void, or ledger truth beyond requested public payment operations.
- Failure sequence: Resume or webhook administration replays a payment mutation, changes a decision, or couples delivery state to money state.
- Implementation: Stable public-API idempotency, immutable origin, and separate webhook administration transactions preserve payment boundaries.
- Proof: Full 240-test backend suite; repository 60-test Python suite; Chapter 21 live crash/resume reconciliation; existing capture, cancel, refund, rollback, and webhook suites.
- Adversarial evidence: Crash after remote commit resumed to exactly five intended intents, attempts, authorizations, decisions, and zero capture ledger entries.
- Status: PASS

## C21-PAY-002

- Lens: PAYMENT_FRAUD
- Invariant: A resumed declared scenario step produces at most one intended payment, attempt, decision, authorization, and event.
- Failure sequence: The process dies after API commit but before checkpoint and resumes with a different mutation identity.
- Implementation: Run-scoped stable idempotency keys, durable completed-step responses, and fail-closed ambiguous non-idempotent steps enforce convergence.
- Proof: `test_crash_after_remote_commit_retries_only_a_retry_safe_step`, checkpoint/abandonment tests, and live `chapter21-live-resume` PostgreSQL evidence.
- Adversarial evidence: The live failpoint fired after PaymentIntent commit; resume produced five distinct intended PaymentIntents, five attempts, and five decisions—not six.
- Status: PASS

## C21-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: Origin and all webhook endpoint, secret, creation, delivery, and event records remain owned by exactly one merchant.
- Failure sequence: A caller substitutes another merchant's endpoint, key, generation, object ID, or identical external key/URL.
- Implementation: Merchant authentication, scoped lookups, composite foreign keys, tenant-bound encryption AAD, and merchant-scoped uniqueness enforce ownership.
- Proof: `MerchantTenantIsolationTests`, endpoint replay cross-tenant proof, secret wrong-tenant proof, generator tenant reads, and full suite.
- Adversarial evidence: Identical endpoint idempotency keys and URLs succeed independently across tenants; cross-tenant object reads remain 404 and wrong-AAD decrypt fails.
- Status: PASS

## C21-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: Only the authenticated scenario-runner boundary can assign trusted synthetic origin.
- Failure sequence: Merchant metadata or an invalid/guessed header contaminates risk and future training lineage.
- Implementation: `MerchantContextArgumentResolver` uses a dedicated configured credential and constant-time equality; metadata is never consulted for origin.
- Proof: `trustedScenarioCredentialAssignsOriginAndMerchantMetadataCannotForgeIt` and `trustedSyntheticOriginPropagatesThroughDecisionTimeAndEventLineage`.
- Adversarial evidence: Synthetic-looking metadata remains `MERCHANT_API`; wrong credential is unauthorized and persists no payment or idempotency row.
- Status: PASS

## C21-SEC-002

- Lens: SECURITY_PRIVACY
- Invariant: Creation replay and rotation never persist plaintext signing secrets; expired/superseded generations are unusable at the exact cryptographic-use instant; startup has every KEK needed by still-decryptable state.
- Failure sequence: Retry mints a second secret, replay exposes a rotated-away response, ciphertext crosses tenants, or old material works at expiry.
- Implementation: AES-GCM response/endpoint ciphertext, HMACed idempotency identity, generation binding, `WebhookSecretUseWindow` validation at the exact signature timestamp, complete current/previous/replay KEK inventory, and superseded-response conflict enforce lifecycle.
- Proof: `endpointCreationReplayIsTenantScopedAndDuplicateUrlsAreExplicitlyRejected`, `claimedSecretGenerationRemainsAvailableOnlyDuringRotationGrace`, `previousGenerationIsRejectedWhenDispatchWaitCrossesItsExpiry`, `requiredKeyInventoryIncludesCurrentPreviousAndReplayCiphertext`, startup key-version proof, and cipher tamper/AAD tests.
- Adversarial evidence: Exact before/at/after signature use, a dispatch wait crossing expiry, missing current/previous/replay KEKs, second active overlap, rotation-after-create replay, wrong tenant, and ciphertext tampering all fail safely. `ER-0025` and `ER-0026` record the reopened families.
- Status: PASS

## C21-SEC-003

- Lens: SECURITY_PRIVACY
- Invariant: Dependency and test runtime configuration preserves DNS, migration, instrumentation, and PostgreSQL proof fidelity.
- Failure sequence: Native fallback, untested H2, dynamic agent loading, or Testcontainers teardown noise hides a real failure.
- Implementation: H2 2.3.232, Testcontainers 2.0.5 packages, macOS native DNS runtime, explicit native access, Mockito agent, and a 24-hour test-only scheduler delay remove owned ambiguity.
- Proof: Fresh 240-test suite has zero failure, error, or skip; all PostgreSQL migration and forced-interleaving tests execute.
- Adversarial evidence: PostgreSQL 16 migrations and forced interleavings execute rather than skip; ER-0022 proved the live upgrade path independently.
- Status: PASS

## C21-SEC-004

- Lens: SECURITY_PRIVACY
- Invariant: The loopback-only Python clients neither follow redirects nor delegate API, admin, or scenario-runner credentials to an environment-configured proxy.
- Failure sequence: A validated loopback origin returns any supported redirect, or hostile `HTTP_PROXY` configuration changes the effective connection destination.
- Implementation: Each `ApiClient` builds an opener with an explicit empty `ProxyHandler` and a `RejectRedirectHandler`.
- Proof: Both Python suites use real direct/redirect/proxy servers; redirect tests assert zero target requests for 301/302/303/307/308, and proxy tests force bypass false while proving only the direct origin receives the request.
- Adversarial evidence: Requests carry privileged credential classes and bodies; `ER-0023` and `ER-0024` record why initial-origin and redirect-only proof were insufficient.
- Status: PASS

## C21-CON-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: At most one process advances a generator run; stale recovery cannot repeat a completed non-idempotent step.
- Failure sequence: Two processes own one run, or an ambiguous merchant/customer create is blindly retried.
- Implementation: SQLite `BEGIN IMMEDIATE`, expiring exclusive lease ownership, durable STARTED/COMPLETED steps, a separate sanitized resource ledger, and `NEEDS_CLEANUP` guard transitions enforce ownership and cleanup operability.
- Proof: `test_active_run_lease_blocks_a_concurrent_generator`, completed-step replay, retry-safe restart, ambiguous-step, and abandonment/resource-retention tests use real SQLite files.
- Adversarial evidence: A second owner is rejected; a crashed non-idempotent STARTED step never invokes its operation again; abandonment erases API keys while retaining safe merchant/resource references. `ER-0028` records the original lineage loss.
- Status: PASS

## C21-CON-002

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Endpoint create, rotation, claim, and disable converge to one endpoint/secret/delivery truth.
- Failure sequence: Concurrent create duplicates an endpoint, rotation removes a claimed key, or disable loses its monotonic state.
- Implementation: Fenced expiring endpoint-create reservation leases, database URL uniqueness, endpoint lock order, and claim-bound current/previous secret generations enforce convergence.
- Proof: H2 active/stale reservation tests, V14 historical migration proof, `expiredCreationReservationReclaimSerializesAndReplaysOneEndpoint`, and existing PostgreSQL create/claim, rotation/disable, routing/disable, and lease-recovery interleavings.
- Adversarial evidence: A process-death reservation is reclaimed without stealing live ownership; two contenders wait on the real PostgreSQL row lock and converge to one endpoint/replayed secret; stale replay after rotation conflicts. `ER-0027` records the omitted fail-stop sequence.
- Status: PASS

## C21-CON-003

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Fair claiming preserves exclusive claims, lease recovery, disable, and bounded progress for quiet merchants/endpoints.
- Failure sequence: A hot backlog fills every poll, quota filtering duplicates rows, or cursor state strands work.
- Implementation: A singleton database cursor serializes round-robin merchant/endpoint choice; delivery rows still use `FOR UPDATE SKIP LOCKED`; executor claims one immediately dispatched row at a time.
- Proof: `durableCursorPreventsNoisyMerchantAndEndpointStarvation`, `concurrentWorkersCreateOneClaimAndOneAttemptNumberWinner`, executor budget test, and complete PostgreSQL suite.
- Adversarial evidence: Five noisy events cannot displace one quiet event from a two-claim round; two endpoints each win one slot; concurrent workers create one attempt per delivery.
- Status: PASS

## C21-DATA-001

- Lens: DATA_ML_LINEAGE
- Invariant: Traffic origin is immutable, server-assigned, and propagated point-in-time through payment, fraud, event, and generated evidence.
- Failure sequence: Origin is inferred from mutable metadata, lost at confirm, or omitted from event/dataset-facing state.
- Implementation: V10 non-null columns/defaults, constructors, immutable propagation, projections, feature JSON, and event envelopes carry authoritative origin.
- Proof: Full-chain synthetic lineage contract, metadata spoof contract, clean/upgrade migrations, and live generator summary/database reconciliation.
- Adversarial evidence: Legacy rows default to `MERCHANT_API`; ordinary and synthetic paths remain distinct; all five live scenario payments and their decision lineage are synthetic.
- Status: PASS

## C21-DATA-002

- Lens: DATA_ML_LINEAGE
- Invariant: Generator checkpoints and summary publication cannot claim success before all referenced product truth reconciles.
- Failure sequence: Resume changes plan identity, a partial run publishes success, or checkpoint secrets survive completion.
- Implementation: Immutable seed/base/plan fingerprint, deterministic plan, atomic report write, completion guard, SQLite rollback journaling with secure deletion, checkpoint payload redaction, and separate safe resource references enforce evidence truth.
- Proof: Repeated-seed, identity, atomic-output, completion-redaction, safe-reference retention, crash/restart, live reconciliation, and sensitive-data scan tests.
- Adversarial evidence: Changed identity is rejected, failure leaves no success summary, raw API-key checkpoint data is erased, sanitized cleanup identity survives abandonment, and live output is mode `0600`.
- Status: PASS

## C21-API-001

- Lens: API_PRODUCT
- Invariant: Endpoint creation has an explicit tenant-scoped retry contract and deterministic exact-URL behavior.
- Failure sequence: Lost response creates another active endpoint/secret, changed body rebinds a key, or rotation makes replay misleading.
- Implementation: Required `Idempotency-Key`, HMACed identity, canonical request hash, encrypted 24-hour response replay, V12 URL uniqueness, and generation comparison enforce the contract.
- Proof: Endpoint replay test covers same request, changed request, different key/same URL, cross-tenant reuse, single audit, and rotation supersession.
- Adversarial evidence: Duplicate URL insertion reaches the database uniqueness boundary and becomes a 409; plaintext idempotency and secret values are absent from storage/audit.
- Status: PASS

## C21-API-002

- Lens: API_PRODUCT
- Invariant: Generator commands expose explicit start/resume/complete and fail-closed abandonment semantics without rewriting terminal history.
- Failure sequence: A caller changes run identity, resumes an abandoned/completed run as mutable work, or abandons an active owner.
- Implementation: `--run-id`, `--state`, `--abandon-run`, constrained cleanup dispositions, immutable identity validation, leases, and terminal guards define the command contract.
- Proof: Generator-focused Python tests plus live failpoint/resume invocation.
- Adversarial evidence: Blank/missing disposition, active-owner abandonment, terminal resumption, identity mismatch, and unsafe ambiguous replay are rejected.
- Status: PASS

## C21-OBS-001

- Lens: OBSERVABILITY
- Invariant: Operators can inspect safe run status and bounded fairness pressure without secret, URL, tenant, endpoint, or payment cardinality.
- Failure sequence: A stuck run/starved queue is invisible, or telemetry leaks credentials and attacker-controlled dimensions.
- Implementation: Durable run/step statuses and cleanup disposition provide local inspection; `thaap.webhook.claim.throttled` uses only `merchant|endpoint` scope; queue-age/due signals remain intact.
- Proof: Generator state/redaction tests, metrics budget test, endpoint audit redaction, and live sensitive-output scan.
- Adversarial evidence: Scans include cards, PII, API/runner/idempotency/signing secrets, URLs, hashes, and ciphertext; published evidence remains clean.
- Status: PASS

## C21-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Crash, restart, dependency timeout, scheduler shutdown, and schema upgrade leave work resumable or explicitly terminal, never falsely successful.
- Failure sequence: Crash lands between remote commit/checkpoint, scheduler polls a destroyed database, or a shipped schema cannot start current code.
- Implementation: Generator leases/checkpoints, stable API idempotency, test scheduler lifecycle control, webhook delivery leases, V14 endpoint-reservation leases, and additive V13/V14 repairs enforce recovery.
- Proof: Live failpoint/resume, Python restart tests, full lifecycle suite, `v13RepairsPreviouslyAppliedWebhookAttemptSchemaWithoutLosingSendTruth`, `v14MakesPreviouslyStrandedEndpointCreationReservationsReclaimable`, and deterministic PostgreSQL reservation reclaim.
- Adversarial evidence: `ER-0022` reopened migration proof and `ER-0027` reopened fail-stop reservation recovery; shipped attempt and endpoint-create states now upgrade conservatively.
- Status: PASS

## C21-OPS-002

- Lens: OPERATIONS_RESILIENCE
- Invariant: Chapter 21 cannot close with non-resolved debt, skipped proof, unresolved matrix entries, escaped findings, or contradictory source-of-truth status.
- Failure sequence: Debt is cosmetically hidden, PostgreSQL skips, README claims readiness early, or the live upgrade failure is omitted.
- Implementation: Immutable debt/escaped registers, matrix/dossier checks, mandatory readiness tests, and source reconciliation enforce the gate.
- Proof: `technical-debt-check.py` reports five resolved/zero open; invariant and escaped-review checks pass; full backend reports 240/0/0/0; repository Python reports 60/0; README/master plan agree.
- Adversarial evidence: `ER-0022` through `ER-0028` are recorded and repaired rather than suppressed; every prior-phase debt retains its immutable ID and concrete evidence.
- Status: PASS
