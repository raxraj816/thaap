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

The 2026-07-11 hostile review reopened merchant lifecycle epochs, webhook
cryptographic-use ordering, stale-generation recovery, KEK readiness across
disabled execution mode, and endpoint-creation fencing under `ER-0036` through
`ER-0040`. Lifecycle-epoch derivation, atomic endpoint-lock-held signing,
retryable stale-generation convergence, mode-independent persisted KEK
inventory, and pre-mutation reservation locking now close those families.
Closure was reopened again because the five protocols were not independently
reviewed, their transition tables were absent before the already-present fixes,
and the claimed hostile pass lacked a separate checkpoint. Five honest
`RETROACTIVE_RECOVERY` packets now record complete transitions and mandatory
closure questions. Each names an implementation commit and a later hostile
proof commit; `scripts/slice-review-check.py --require-verified` validates the
commit ancestry and packet state.

The recovery transition tables immediately found `ER-0041` and `ER-0042`:
signature time was captured before an endpoint-lock wait, and stale-generation
recovery could exhaust at the retry ceiling without any current-generation send
opportunity. `ER-0041` and `ER-0042` are resolved by post-lock time capture and
final-attempt current-generation rebinding, with exact deterministic proof in
Slices B and C.

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

The final pre-merge review subsequently found and resolved `ER-0029` and
`ER-0030`. Generator resume now treats persisted retry classification as
immutable protocol state. V15 fenced server reservations, deterministic
secret-safe merchant API-key replay, tenant-scoped customer idempotency, and
an earlier failpoint prove convergence across the exact remote
commit-to-local-checkpoint window.

The post-closure Senior Staff review then reopened transaction ordering,
secret-derived identity rotation, and workflow lease coverage under `ER-0031`
through `ER-0033`. The operation transaction now owns the idempotency row before
the supplier runs; V16 gives merchant creation versioned cross-key identity
aliases and fail-closed key inventory; every generator HTTP request heartbeats
run ownership. The matrix returned to `VERIFIED` only after forced expiry,
pepper rotation, V15-to-V16 upgrade, and cumulative slow-network proof passed.

The next end-to-end review reopened the matrix under `ER-0034` and `ER-0035`.
`ER-0032` repaired only merchant creation even though the rotatable HMAC service
also backed generic request replay, webhook endpoint idempotency identity, card
fingerprints, and customer pseudonyms. V17 now versions generic request hashes,
adds tenant-scoped webhook identity aliases, and inventories live historical
versions; a separate stable correlation key preserves card and customer
lineage. The generator now persists immutable explicit resource types and
backfills completed/interrupted legacy journals, including the verifier
merchant. `ER-0034` and `ER-0035` returned the matrix to `VERIFIED` only after
clean/upgrade migration, rolling PostgreSQL, live/expired key, correlation, and
complete cleanup-ledger partitions passed.

## C21-PAY-001

- Lens: PAYMENT_FRAUD
- Invariant: Debt-retirement operations do not rewrite lifecycle, processor, fraud, refund, void, or ledger truth beyond requested public payment operations.
- Failure sequence: Resume or webhook administration replays a payment mutation, changes a decision, or couples delivery state to money state.
- Implementation: Stable public-API idempotency locks and validates ownership before processor/mutation execution; original-version request verification survives replay-key rotation; immutable origin and separate webhook administration transactions preserve payment boundaries.
- Proof: `expiredMerchantCreationStartsNewCredentialEpochWithoutPepperRotation`, mixed-version PostgreSQL replay, exact-window crash/resume reconciliation, forced in-flight lease expiry, and existing payment rollback/lifecycle contracts. The full suite is regression context only.
- Adversarial evidence: An expired lease cannot enter a second supplier while the first owns the row lock. A live request replays across rotation, an expired request can restart after old-key retirement, and crash after remote commit resumes to exactly five intended intents, attempts, authorizations, decisions, and zero capture ledger entries.
- Status: PASS

## C21-PAY-002

- Lens: PAYMENT_FRAUD
- Invariant: A resumed declared scenario step produces at most one intended payment, attempt, decision, authorization, and event.
- Failure sequence: The process dies after API commit but before checkpoint and resumes with a different mutation identity.
- Implementation: Stable domain-separated merchant/customer keys, V15 fenced server reservations, V16/V17 versioned replay identities, record-UUID lifecycle epochs, atomic customer mutation/response completion, immutable typed generator resources, and original-version merchant API-key replay make every generator creation step retry-safe without plaintext credential storage.
- Proof: Real-SQLite exact-window merchant/customer crash-resume and legacy typed-journal proof, H2 replay/conflict/privacy/lease/pepper-rotation/same-pepper-expiry contracts, deterministic PostgreSQL reservation waits, V15-to-V17 clean and upgrade migration proof, and full suites.
- Adversarial evidence: Failure is injected after the server response but before every local write; resume restores complete typed resource lineage while the fake remote server contains exactly one merchant/customer. Changed payload, blank key, active lease, expired lease, same-pepper expired reuse, cross-tenant reuse, concurrent replay, pepper rotation, unavailable historical key, resource-type drift, and verifier-merchant cleanup partitions fail or converge as specified.
- Status: PASS

## C21-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: Origin and all webhook endpoint, secret, creation, delivery, and event records remain owned by exactly one merchant.
- Failure sequence: A caller substitutes another merchant's endpoint, key, generation, object ID, or identical external key/URL.
- Implementation: Merchant authentication, scoped lookups, composite foreign keys, tenant-bound encryption AAD, tenant-scoped versioned webhook identities, explicit generator merchant lineage, and merchant-scoped uniqueness enforce ownership.
- Proof: `MerchantTenantIsolationTests`, endpoint replay/rotation cross-tenant proof, secret wrong-tenant proof, generator resource-ledger tenant reconciliation, and full suite.
- Adversarial evidence: Identical endpoint idempotency keys and URLs succeed independently across tenants; current/previous aliases cannot cross merchants; every child generator resource records its merchant; cross-tenant object reads remain 404 and wrong-AAD decrypt fails.
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
- Implementation: AES-GCM response/endpoint ciphertext, HMACed idempotency identity, generation binding, post-lock exact-time signing, current-generation fallback at the final stale pre-send attempt, complete current/previous/replay KEK inventory in both execution modes, and superseded-response conflict enforce lifecycle.
- Proof: `rotationWaitsUntilCurrentGenerationSignatureIsCryptographicallyUsed`, `previousGenerationWaitingForEndpointLockIsRejectedAtActualUseExpiry`, `expiredClaimGenerationRetriesAndNextClaimBindsCurrentSecret`, `finalAttemptStaleGenerationUsesCurrentSecretBeforeFirstHttpSend`, `requiredKeyInventoryIncludesCurrentPreviousAndReplayCiphertext`, `disabledExecutionStillFailsClosedWhenAdministrationNeedsUnavailableKey`, and cipher tamper/AAD tests.
- Adversarial evidence: Both signer/rotation lock orderings, a lock wait crossing exact expiry, first/intermediate/final stale attempts, missing KEKs with workers enabled or disabled, second active overlap, wrong tenant, and ciphertext tampering fail or converge as specified. `ER-0025`, `ER-0026`, `ER-0037` through `ER-0039`, `ER-0041`, and `ER-0042` preserve the review history.
- Status: PASS

## C21-SEC-003

- Lens: SECURITY_PRIVACY
- Invariant: Dependency and test runtime configuration preserves DNS, migration, instrumentation, and PostgreSQL proof fidelity.
- Failure sequence: Native fallback, untested H2, dynamic agent loading, or Testcontainers teardown noise hides a real failure.
- Implementation: H2 2.3.232, Testcontainers 2.0.5 packages, macOS native DNS runtime, explicit native access, Mockito agent, and a 24-hour test-only scheduler delay remove owned ambiguity.
- Proof: PostgreSQL migration and forced-interleaving classes execute without skips; dependency-lifecycle warning scans remain clean. Aggregate totals are supporting regression context only.
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
- Invariant: At most one process advances a generator run; persisted retry safety is immutable; in-flight or stale recovery cannot duplicate merchant, customer, payment, processor, or fraud truth.
- Failure sequence: Two processes own one run across cumulative network waits, a caller reclassifies a durable unsafe step, a mutation lease expires while its supplier is active, pepper rotation changes identity, or the process dies after merchant/customer API commit before local checkpoint.
- Implementation: SQLite validates immutable retry and resource-type classification and heartbeats ownership around every HTTP request. V15 owner tokens plus a pre-supplier row lock fence general mutations; V16/V17 unique current/previous HMAC aliases and original-version derivation fence merchant and endpoint creation across rotation.
- Proof: SQLite classification/resource-type drift tests; real delayed-server heartbeat proof; active/stale H2 leases; live/expired pepper-rotation and missing-key contracts; V15-to-V17 upgrades; PostgreSQL NOWAIT/`pg_stat_activity` forced waits for mixed pepper versions and expiry while protected work is blocked.
- Adversarial evidence: Cumulative network latency exceeds the original lease without ownership loss; classification drift never invokes the operation; an expired mutation lease cannot run a second supplier; pepper rotation replays one merchant/API key and one endpoint; unavailable live versions fail startup while expired versions can retire; stranded ownership remains reclaimable.
- Status: PASS

## C21-CON-002

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Endpoint create, rotation, claim, and disable converge to one endpoint/secret/delivery truth.
- Failure sequence: Concurrent create duplicates an endpoint, rotation removes a claimed key, or disable loses its monotonic state.
- Implementation: Fenced expiring endpoint-create reservation leases locked before mutation, tenant-scoped versioned identity aliases, original-version request verification, database URL uniqueness, endpoint-lock-held exact-use signing, and claim-bound current/previous secret generations enforce convergence.
- Proof: H2 active/stale/rotation/key-retirement reservation tests, V14 and V16-to-V17 historical migration proof, `expiredCreationReservationReclaimSerializesAndReplaysOneEndpoint`, `liveEndpointCreationLockSurvivesLeaseExpiryBeforeFirstMutationCompletes`, `rotationWaitsUntilCurrentGenerationSignatureIsCryptographicallyUsed`, the forced rolling-version endpoint-create test, and existing PostgreSQL claim/disable/routing/lease-recovery interleavings.
- Adversarial evidence: A process-death reservation is reclaimed without stealing live ownership; a live owner retains fencing after lease expiry while blocked before its first mutation; old/new workers wait on the real PostgreSQL row lock and converge to one endpoint/replayed secret with two aliases; rotation waits for an in-progress signature; changed-request reuse still conflicts and expired state no longer retains a dead key. `ER-0027`, `ER-0034`, `ER-0037`, and `ER-0040` record the escaped sequences.
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
- Implementation: Immutable seed/base/plan fingerprint, deterministic plan, atomic report write, completion guard, SQLite rollback journaling with secure deletion, checkpoint payload redaction, immutable explicit resource types, and separate safe resource references enforce evidence truth.
- Proof: Repeated-seed, identity, atomic-output, completion-redaction, typed legacy-journal backfill, resource-type drift, complete verifier-merchant ledger, safe-reference retention, crash/restart, live reconciliation, and sensitive-data scan tests.
- Adversarial evidence: Changed identity or resource type is rejected before execution, failure leaves no success summary, raw API-key checkpoint data is erased, every committed resource including the verifier merchant remains in sanitized cleanup lineage, and live output is mode `0600`.
- Status: PASS

## C21-API-001

- Lens: API_PRODUCT
- Invariant: Endpoint creation has an explicit tenant-scoped retry contract and deterministic exact-URL behavior.
- Failure sequence: Lost response creates another active endpoint/secret, changed body rebinds a key, or rotation makes replay misleading.
- Implementation: Required `Idempotency-Key`, tenant-scoped current/previous HMAC aliases, original-version canonical request hash, encrypted 24-hour response replay, V12 URL uniqueness, and generation comparison enforce the contract.
- Proof: Endpoint replay tests cover same/changed request, live/expired rotation, same-pepper expired reuse, missing historical key, different key/same URL, cross-tenant reuse, single audit, and rotation supersession; PostgreSQL proves mixed-version serialization.
- Adversarial evidence: Duplicate URL insertion reaches the database uniqueness boundary and becomes a 409; rotation cannot make a bound key appear new; expired bindings can be reused after old-key retirement; plaintext idempotency and secret values are absent from storage/audit.
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
- Implementation: Existing recovery boundaries remain; V15 fail-stop leases recover stranded reservations, V16/V17 preserve replay identity across pepper rotation, explicit resource types preserve cleanup lineage, and per-request heartbeats keep the generator owner live across bounded network waits.
- Proof: V15-to-V17 clean and upgrade migrations, forced PostgreSQL in-flight expiry and rolling-version interleavings, H2 active/stale/live/expired rotation contracts, legacy SQLite journal upgrades, delayed real-HTTP heartbeat proof, and exact remote-response-before-local-write crash/replay tests.
- Adversarial evidence: Historical `IN_PROGRESS` rows become reclaimable; active mutation and generator owners cannot be stolen; missing live historical versions fail startup while expired versions retire; malformed keys fail before mutation; legacy interrupted journals preserve type; client death before local persistence converges rather than duplicating or losing server truth.
- Status: PASS

## C21-OPS-002

- Lens: OPERATIONS_RESILIENCE
- Invariant: Chapter 21 cannot close with non-resolved debt, skipped proof, unresolved matrix entries, escaped findings, or contradictory source-of-truth status.
- Failure sequence: Debt is cosmetically hidden, PostgreSQL skips, README claims readiness early, or the live upgrade failure is omitted.
- Implementation: TD-0001 through TD-0005 and ER-0001 through ER-0042 are resolved; five independently verified slice packets, matrix, dossier, README, master plan, learning trail, and registers carry one completion state.
- Proof: Slice-review ancestry validation, named deterministic interleavings, plan-to-code-to-proof traceability, complete persisted-HMAC inventory, deterministic keyring-order and duplicate-material rejection, and separate hostile lifecycle-epoch, exact-use, retry-ceiling, mode-readiness, fencing, typed-journal, and cumulative-network-lease reviews. Full suites are regression context only.
- Adversarial evidence: The recovery review rejected aggregate structural green, wrote complete transition tables, reused expired identity under unchanged key material, forced both endpoint-lock orderings across expiry, crossed stale recovery with the retry ceiling, disabled the worker while retaining admin decrypt paths, expired live ownership during mutation, changed durable resource type, and held network reads beyond the original run lease before re-closing.
- Status: PASS
