# Chapter 41 Invariant Proof Matrix

- Chapter: Chapter 41 — Developer Console And Trusted Scenario Operations
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

Post-closure recovery completed on 2026-07-27 for `ER-0070`. Terminal report
origin counts now derive only from payment attempts referenced by that run's
verified step resources; unrelated same-tenant runs created before or after
completion cannot change the report body or digest.

This matrix was drafted and frozen on 2026-07-27 after Chapter 40 closure and
before Chapter 41 schema, API, runner, console, test-card, report, test, or demo
changes. It derives from the Chapter 21 trusted/resumable scenario substrate and
the approved Phase 4.5 developer-console promises.

## C41-ORIGIN-001

- Lens: PAYMENT_FRAUD
- Invariant: Only a separately authenticated scenario runner can create `SYNTHETIC_SCENARIO` payment traffic; merchant bodies/metadata/cards cannot forge origin, and synthetic decisions/labels remain visibly separated from live monitoring and model evidence.
- Failure sequence: A merchant sets an origin field, copies a test card, replays a scenario request without runner authority, or synthetic rows enter LIVE_ONLY metrics.
- Implementation: `MerchantContextArgumentResolver` assigns `SYNTHETIC_SCENARIO` only after both merchant authentication and the separately configured runner token; scenario DTOs have no origin field, and every payment instruction is server-generated with exact synthetic metadata.
- Proof: `trustedCredentialCreatesDeterministicTenantRunAndOrdinaryMerchantCannotCreateOne` rejects an ordinary merchant; `publicApiExecutionAdvancesOnlyVerifiedResourcesAndProducesImmutableSanitizedReport` proves attempt, decision, and feature-snapshot origin plus zero ledger entries; the live demo reconciles one merchant and one synthetic attempt.
- Adversarial evidence: Missing runner token, copied foreign resource, server-generated metadata, synthetic lineage joins, report origin breakdown, and existing LIVE_ONLY/INCLUDE_SYNTHETIC monitoring regressions.
- Status: PASS

## C41-RUN-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: A scenario run has one immutable seed/specification, one resumable owner lease, monotonic checkpoint, and one terminal report; retries/restarts/concurrent workers never duplicate completed steps or payment truth.
- Failure sequence: Two workers own one run, a stale worker advances a checkpoint, retry creates duplicate intents, or restart changes the seed/spec.
- Implementation: V61 stores the immutable run/specification, ordered steps, lease owner/epoch/expiry, monotonic checkpoint, append-only events, and one terminal report; step keys derive from version, tenant, spec, index, and operation. Every completion locks the run and verifies the referenced public-API resource before advancing.
- Proof: `forcedConcurrentClaimsWaitOnRunRowAndCommitOneLiveOwner` observes a real PostgreSQL waiter and one owner; `expiredLeaseAdvancesEpochAndRejectsStaleWorkerWhileHistoryTriggersHold` proves epoch fencing; contract proof covers exact completed-step replay, conflicting resource replay, ordering, terminal state, and deterministic create/resume.
- Adversarial evidence: Live foreign owner, expired owner, stale epoch, out-of-order step, exact/conflicting replay, failure replay, immutable events, and run DELETE.
- Status: PASS

## C41-SEED-001

- Lens: DATA_ML_LINEAGE
- Invariant: The same governed scenario type/version/seed/input produces the same ordered safe step plan and idempotency identities; report digest covers exact specification and durable results.
- Failure sequence: Random/time iteration changes cards or amounts, resumed execution reorders steps, report omits excluded/failed steps, or same-tenant attempts from another run change origin counts or digest.
- Implementation: The closed ASCII seed grammar is SHA-256 digested, never stored raw, and canonicalized with scenario type and `developer_scenarios_v1`; ordered plans and child keys depend only on stable inputs. Terminal aggregation joins attempts through the exact run's verified step resources, then writes canonical JSON plus stored SHA-256.
- Proof: `trustedCredentialCreatesDeterministicTenantRunAndOrdinaryMerchantCannotCreateOne` locks the golden seed/spec digests and same/different-seed identity; `terminalReportAndDigestAreBoundOnlyToAttemptsOwnedByTheExactRun` creates unrelated runs before and after the target and proves its one-attempt origin breakdown, report bytes, and digest remain stable; card-testing proves the full ten-step order.
- Adversarial evidence: Same/different seed, unknown/Unicode/oversized seed through strict validation, stable golden bytes, partial versus terminal report, unrelated same-tenant attempts before/after completion, exact report-byte recomputation, and immutable specification columns.
- Status: PASS

## C41-CARD-001

- Lens: SECURITY_PRIVACY
- Invariant: The public test-card catalogue exposes only documented simulator tokens/PAN-like test values and exact expected outcomes; test cards are impossible to use as live credentials and no secret/PAN-like merchant data is copied into logs, metrics, reports, or URLs.
- Failure sequence: A catalogue card is presented as a live credential, catalogue response leaks a secret, examples log credentials, or an unknown fixture identifier silently enters a governed scenario plan.
- Implementation: The nine-card catalogue is derived from `SimulatorProcessorCatalog`, labels every entry `SIMULATOR_ONLY`, and the three closed scenario types refer only to internal fixture IDs. Reports and metrics contain outcomes, not card data.
- Proof: `catalogueIsPublicBoundedAndExplicitlySimulatorOnly` checks all nine bounded values/outcomes and secret vocabulary; execution verifies BIN/last4 against the governed fixture; the report canary excludes card, key, runner token, seed, and customer data; the public demo executes the documented catalogue.
- Adversarial evidence: Public unauthenticated catalogue, exact nine-card cross-product, foreign/mismatched resources, strict scenario enum/seed grammar, report canaries, URL query rejection, and redirect rejection.
- Status: PASS

## C41-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: Runs, steps, created resources, progress, reports, and console reads are merchant-qualified from authenticated context; copied run/resource IDs and seeds reveal no foreign existence.
- Failure sequence: A runner attaches a foreign merchant, a console reads another tenant report, or resume joins a payment by bare UUID.
- Implementation: V61 uses merchant-qualified unique/composite keys and foreign keys for runs, steps, events, and resources; every query derives merchant identity from authentication and joins by tenant plus ID.
- Proof: The happy-path contract rejects a foreign customer before checkpoint advancement and returns 404 when the second merchant copies the run ID; PostgreSQL migration assertions validate the composite constraints.
- Adversarial evidence: Foreign customer/run IDs, bare UUID lookup, authenticated tenant derivation, same-shaped identifiers, and append-only tenant identity.
- Status: PASS

## C41-API-001

- Lens: API_PRODUCT
- Invariant: Scenario create/status/report and test-card APIs are typed, bounded, strictly parsed, idempotent, cursor-safe, and return stable errors; the developer console stores credentials in memory only and accurately renders partial/failed/resumed state.
- Failure sequence: Unknown fields widen a scenario, unbounded steps exhaust resources, refresh invents progress, credential persists, or copy example uses unsafe retry semantics.
- Implementation: Typed create/get/claim/complete/fail/report endpoints expose a three-type fixed plan with 4 or 10 steps; strict Jackson, bean validation, standard errors, idempotency, and cooperative leases bound the API. React keeps both credentials only in component state and renders durable server progress/report state.
- Proof: Contract tests cover unknown fields, out-of-order completion, terminal failure, exact replay, report availability, and all three plan outcome families; `App.test.tsx` proves memory-only credentials, storage absence, safe fetch options, durable progress, catalogue rendering, and entry-screen accessibility; the Vite build passes.
- Adversarial evidence: Unknown field, invalid enum/seed/step index, incomplete report, exact/conflicting replay, concurrent claim, UI credential DOM/storage canaries, and fetch `credentials: omit`/`redirect: error`.
- Status: PASS

## C41-NET-001

- Lens: SECURITY_PRIVACY
- Invariant: Public examples and runner clients accept only configured loopback/API origins, ignore ambient proxies, reject redirects/userinfo/query secrets, use bounded timeouts, and never perform arbitrary outbound calls from scenario data.
- Failure sequence: Environment proxy captures a key, redirect crosses origin, scenario URL triggers SSRF, or hung API blocks a worker indefinitely.
- Implementation: The executable example accepts explicit-port loopback HTTP only, installs an empty proxy handler, rejects redirects, validates every server-supplied step against a closed relative-path grammar, and caps requests at 30 seconds. The browser uses same-origin relative paths with redirects rejected.
- Proof: `developer_scenario_demo_tests.py` proves origin partitions, step-path/UUID grammar, no ambient proxy handler, an actual cross-origin 302 rejection, an actual bounded slow-response failure, and missing-credential fail-before-network behavior.
- Adversarial evidence: HTTP/HTTPS, loopback/public/lookalike hosts, absent/bad port, userinfo, query/fragment, absolute/network paths, unknown path, malformed UUID, proxy environment, 302, and slow response.
- Status: PASS

## C41-OBS-001

- Lens: OBSERVABILITY
- Invariant: Closed scenario type/lifecycle/step/outcome tags expose queued/running/resumed/succeeded/failed progress while excluding tenant, run, seed, payment, customer, credential, card, idempotency, and request content.
- Failure sequence: Seed/card/key becomes a tag, stale worker emits success, report contains raw error/credential, or failed steps disappear.
- Implementation: `DeveloperScenarioMetrics` emits after transaction completion with only operation, scenario type, outcome, and committed/rolled-back tags; events use closed types/codes; `DeveloperScenarioHealthIndicator` checks schema/catalogue compatibility.
- Proof: `readinessAndMetricsExposeOnlyClosedNonIdentifyingDimensions` validates readiness and the complete tag-key/value privacy inventory; stale-owner/conflict and exact retry tests prove progress is not falsely advanced; live startup reported readiness `UP`.
- Adversarial evidence: Seed/API key/merchant UUID canaries, closed tag vocabulary, rollback status, stale owner, duplicate step, terminal failure/success, and incompatible schema/catalogue readiness behavior.
- Status: PASS

## C41-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Fresh/retained upgrades add empty scenario operational state without changing existing payment/fraud rows; creation enablement, lease, step/run limits, retry, and client shutdown are bounded and readiness fails on incompatible catalogue/schema.
- Failure sequence: Migration invents runs, startup executes old fixtures, disabled creation still accepts runs, an unbounded run starves service, or retained generator rows change meaning.
- Implementation: Forward-only V61/V62 add empty state, bounded 4/10-step plans, 5-second-to-5-minute leases, due indexes, creation enablement, catalogue readiness, and PostgreSQL projection/history triggers. Expired leases are reclaimed with a higher epoch.
- Proof: Fresh PostgreSQL migration tests cover V61/V62 objects/indexes/triggers; the retained local database upgraded V60→V62 and the public demo passed; `DeveloperScenarioConfigurationTests` proves disabled creation and lease limits; PostgreSQL tests prove expiry recovery and history protection.
- Adversarial evidence: Fresh and retained schema, creation disabled, 4s/6m invalid leases, live/expired lease, stale worker, incompatible catalogue, immutable history, repeated terminal requests, and full regressions.
- Status: PASS

## C41-DEMO-001

- Lens: API_PRODUCT
- Invariant: A merchant can follow public console/examples—without reading source—to create a trusted scenario, observe durable progress, inspect a sanitized report, and reconcile synthetic results separately from live truth.
- Failure sequence: Hidden manual DB step, example requires source knowledge, report overclaims network truth, or reconciliation mutates state.
- Implementation: `scripts/README.md`, the service README, React console, and `demo-developer-scenario-console.py` provide a self-contained environment-credential quickstart that creates one merchant-origin reference, executes/resumes a trusted scenario exclusively through public APIs, and renders a sanitized immutable reconciliation report.
- Proof: The live run completed `SUCCEEDED` with `4/4`, report digest `e5f605a0b190be305678a22658e39ddad385c89926f67f5bf598989334c21380`, and `MERCHANT_API:1|SYNTHETIC_SCENARIO:1`; five client safety tests and six UI tests prove the copy path and memory/network boundaries.
- Adversarial evidence: Fresh merchant, environment-only credentials, wrong/missing runner authority, partial durable state, retry/resume, seed/key/token report canaries, redirect, proxy, timeout, and fixed-origin execution.
- Status: PASS

## Frozen Boundary Table

| Boundary | Valid | Rejected |
|---|---|---|
| Origin | server-assigned with separate runner authority | merchant/body/metadata/card forgery |
| Ownership | one live lease epoch and monotonic checkpoint | concurrent/stale worker commit |
| Determinism | same version+seed+spec gives same plan/keys | time/random/locale-dependent plan |
| Catalogue | documented simulator-only outcomes | live use, unknown/coerced card |
| Tenant | authenticated merchant-qualified run graph | copied foreign run/resource IDs |
| Client | loopback/configured fixed API, no proxy/redirect | arbitrary URL, proxy, redirect, userinfo/query secret |
| UI/report | durable partial/terminal state, exact run-owned attempt lineage, sanitized evidence | merchant-wide/unrelated attempts, invented progress, credential persistence, raw errors |

## Post-closure recovery: `ER-0070`

The original report query was tenant-qualified but not run-qualified. Tenant
isolation prevented cross-merchant leakage, yet same-merchant scenario traffic
could still rewrite a terminal run's `originBreakdown` and digest. The
remediation derives attempt IDs only from the target run's completed
`CONFIRM_PAYMENT_INTENT` step resources before aggregating origins.

`terminalReportAndDigestAreBoundOnlyToAttemptsOwnedByTheExactRun` executes one
unrelated run, then the target run, and proves the target reports exactly one
`SYNTHETIC_SCENARIO` attempt even though two tenant attempts exist. After a
third unrelated run, the target's canonical report and digest remain
byte-equivalent. This closes both pre-existing contamination and
post-completion stability partitions at the real PostgreSQL/API boundary.
