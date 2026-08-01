# Chapter 58 Invariant Proof Matrix

- Chapter: Chapter 58 — Supported Python SDK And Sandbox Onboarding
- Matrix provenance: PRE_IMPLEMENTATION
- Scope status: FROZEN
- Review status: IN_PROGRESS
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PENDING
- Fresh hostile review: PENDING
- Boundary family review: PENDING
- Custom infrastructure review: NOT_APPLICABLE
- Escaped review assessment: COMPLETE

This matrix was drafted and frozen on 2026-08-02 after the founder confirmed
the twenty Phase 6 decisions and before Chapter 58 client generation, package,
transport, documentation, UI, example, or demo changes. Planned artifacts and
proof are obligations only. Every row remains `PENDING` until Chapter 57 is
merge-ready, implementation exists, and real boundary evidence passes a
traceability review and a temporally later hostile review.

Chapter 58 supports one synchronous local-sandbox Python client. It does not
publish to PyPI, introduce an asynchronous client, support arbitrary remote
hosts, provide production credentials, add hosted identity/CORS, integrate a
real processor, or claim routes absent from the approved OpenAPI contract.

## C58-PAY-001

- Lens: PAYMENT_FRAUD
- Invariant: The supported quickstart creates or loads sandbox identity, creates tokenized test objects, creates and confirms one PaymentIntent, handles approved/review/3DS/blocked/decline/timeout/replay outcomes, and reads risk evidence through ordinary public APIs; an ambiguous confirm outcome is retried only with byte-equivalent semantic input and the same idempotency key, so the processor, money, event, fraud, and audit effects occur at most once.
- Failure sequence: A read timeout triggers a new key, a retry changes amount or payment method, generated code retries a mutation automatically, a scenario shortcut writes source state directly, or a second confirmation duplicates processor/money/event effects.
- Implementation: generated typed operations/models call existing public endpoints through a handwritten explicit-idempotency wrapper; mutation retry is caller-visible and permitted only after stored request fingerprint/key comparison; scenarios use their distinct public runner API and normal payment lifecycle.
- Proof: Planned `PaymentQuickstartContractTests`, hostile local HTTP fixture tests for disconnect-before/after commit, repeated same-key versus changed-body partitions, source table/event/outbox/ledger fingerprints, and a zero-source-reading quickstart.
- Adversarial evidence: Approved/review/3DS/blocked/decline/timeout/replay; connect failure; response lost before/after commit; same/different key/body; duplicate concurrent call; malformed success/error; process restart; and scenario runner unavailable.
- Status: PENDING

## C58-ERR-001

- Lens: API_PRODUCT
- Invariant: Generated models and one typed `ThaapApiError` preserve HTTP status, stable machine code, retryability, ordered field errors, request ID, effective API version, and safe recovery guidance; client behavior never parses human message, exception class text, status reason, HTML, or database/dependency text.
- Failure sequence: A message wording change changes retry behavior, malformed JSON becomes a credential-bearing raw exception, an unknown code is treated as retryable, ordered field errors are lost, or an error response lacks its effective version.
- Implementation: generated response models plus a small closed error adapter with safe unknown fallback, bounded body parsing, header capture, deterministic field ordering, and no raw body/headers in `str`, `repr`, logs, or raised exception text.
- Proof: Planned `SdkErrorContractTests`, catalogue-to-client exhaustive mapping, message mutation tests, malformed/oversized/content-type/HTML bodies, safe exception snapshots, and public API conformance fixtures.
- Adversarial evidence: Every documented 4xx/5xx code; unknown code/status; absent/duplicate fields; invalid JSON/UTF-8; oversized body; hostile message/header; field-order variants; Retry-After; and transport failure without response.
- Status: PENDING

## C58-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: Merchant, risk-operator, and scenario-runner clients use distinct credential types and operation groups, attach exactly one applicable credential per request, derive tenant only at the server, and reject cross-client operation use, multiple credential headers, client-supplied tenant identity, and foreign-resource substitution without existence leakage.
- Failure sequence: One generic dictionary sends two keys, a risk credential calls a merchant operation, a copied foreign ID reveals existence, a scenario key reaches a risk read, or a tenant field/header overrides credential ownership.
- Implementation: separate immutable credential/client configurations and generated operation façades, operation-specific auth injection, prohibited-header checks, no tenant parameter, and safe server error propagation.
- Proof: Planned `SdkCredentialBoundaryTests`, every credential/operation cross-product against real HTTP, two-merchant fixtures, exact outbound-header capture, foreign/absent equivalence, and generated-signature scans.
- Adversarial evidence: Missing/empty/malformed/merchant/risk viewer/risk analyst/risk manager/scenario/platform keys; one/two/many headers; foreign UUID; injected tenant header/query/body; shared client/thread; and credential rotation.
- Status: PENDING

## C58-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: The SDK keeps credentials and idempotency keys in caller memory, never places credentials in URL/query/body/log/repr/exception, accepts only an exact loopback HTTP sandbox origin, rejects userinfo/fragments/ambiguous hosts and every redirect, ignores ambient proxy configuration, uses bounded connect/read timeouts and response limits, and closes failed transports without forwarding secrets.
- Failure sequence: A redirect or proxy receives Authorization, `repr(client)` exposes a key, a hostile base URL confuses localhost parsing, an unbounded body exhausts memory, timeout text includes headers, or an example persists a credential in shell history or a file.
- Implementation: reuse and consolidate the existing proven loopback/no-proxy/no-redirect client boundary around a mature Python HTTP transport, immutable redacted configuration, separate timeout limits, bounded reads, environment-only quickstart input, and sanitized exception chaining.
- Proof: Planned `SdkTransportSecurityTests` with hostile local redirect/proxy/DNS/base-URL/body/timeout servers, outbound capture, log/repr/traceback canary scans, memory/storage/command-example inspection, and connection cleanup assertions.
- Adversarial evidence: `localhost`, `127.0.0.1`, `[::1]`, suffix/userinfo/encoded/dotted/IPv4 variants; 301/302/303/307/308; HTTP(S)_PROXY/NO_PROXY; slow connect/read; oversized/chunked body; nested exceptions; and every secret sink.
- Status: PENDING

## C58-WHK-001

- Lens: SECURITY_PRIVACY
- Invariant: Webhook verification consumes the exact received body bytes and parsed signature-header fields, enforces one bounded timestamp tolerance with an injected clock, computes the versioned HMAC over the documented exact representation, compares digests in constant time, and provides event-ID deduplication guidance without claiming verification itself performs durable deduplication.
- Failure sequence: JSON is decoded and reserialized before verification, duplicate signature fields are collapsed, timestamp units/precision drift, stale/future callbacks pass, ordinary equality leaks timing, or the example processes one event twice after restart.
- Implementation: a small byte-oriented verification helper using the standard HMAC library and `compare_digest`, strict signature grammar/limits, explicit tolerance/clock, no body logging, and a reference receiver with caller-owned durable event-ID store contract.
- Proof: Planned `WebhookVerificationContractTests`, exact-byte golden fixtures shared with the service callback fixture, mutation/property partitions, timestamp boundary tests, parser grammar tests, comparison-function instrumentation, and restart/deduplication reference tests.
- Adversarial evidence: Whitespace/key order/encoding/body-byte changes; empty/duplicate/unknown signature fields; malformed hex; exact before/at/after tolerance; seconds/milliseconds confusion; multiple secrets; replayed event ID; and sensitive payload canaries.
- Status: PENDING

## C58-CON-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Concurrent use of documented client instances cannot bleed credential, version, timeout, idempotency key, request body, response, or error state across calls; per-request headers are immutable, and an unknown mutation outcome never starts an automatic competing retry or changes the original request identity.
- Failure sequence: Shared mutable default headers acquire another thread's key, a retry races the original socket, response adapters reuse buffers, credential rotation mutates an in-flight call, or two same-key/different-body calls are treated as equivalent.
- Implementation: immutable client/auth configuration, request-local copied headers and serialized body fingerprint, no background mutation retry, thread-safe mature transport usage or explicit documented non-sharing boundary, and deterministic close semantics.
- Proof: Planned `SdkConcurrencyContractTests` force interleavings around header construction/send/read/error/close with barriers and capture servers, then assert exact per-call bytes plus server/domain postconditions for same/different keys and bodies.
- Adversarial evidence: One/two/many clients and threads; same/different credentials/keys/bodies; rotate/close during call; response loss; delayed server; exception path; and repeated loop.
- Status: PENDING

## C58-DAT-001

- Lens: DATA_ML_LINEAGE
- Invariant: SDK models preserve integer minor-unit money, basis-point scores, UTC instants, immutable IDs/versions, traffic origin, projection generation/cutoff/freshness/coverage, source as-of, unknown/not-computable, and section authority without float conversion, timezone loss, default-zero substitution, or source/projection conflation.
- Failure sequence: Basis points become percent, a large minor-unit value loses precision, an absent model score becomes zero, UTC becomes naive local time, or projection cutoff is exposed as source truth.
- Implementation: generated exact schema types plus reviewed semantic helper types/converters, no float money/score API, timezone-aware parsing, closed availability/authority enums with safe unknown handling, and lineage-preserving examples.
- Proof: Planned `SdkSemanticTypeTests`, OpenAPI golden schema-to-model checks, numeric/time/UUID boundaries, JSON round trips, unknown enum/absent/null partitions, and analytics/investigation readback assertions.
- Adversarial evidence: 0/1/10000/10001 basis points; min/max/overflow minor units; microsecond/zone timestamps; absent/null/unknown; old/new enum; partial/stale projection; and mismatched source/projection contexts.
- Status: PENDING

## C58-GEN-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: OpenAPI Generator reproducibly creates operations and models from the exact committed Chapter 57-plus-additive contract; generated files carry the source digest, clean regeneration is byte/diff stable, hand edits and stale output fail, and the handwritten security wrapper/examples remain outside generated ownership.
- Failure sequence: A local generator version changes output, operation files are hand-fixed, stale models compile against a new contract, generation partially overwrites the package, or wrapper code is erased.
- Implementation: pinned mature generator/container or verified binary, deterministic configuration/templates, build-directory generation then validated copy, manifest/digest ownership boundaries, committed diff gate, and additive-contract compatibility input.
- Proof: Planned clean/parallel/repeated generation, changed-contract and hand-edit negative fixtures, interruption before copy, manifest/digest checks, package type/test execution, and exact generated-versus-wrapper inventory.
- Adversarial evidence: Empty cache; wrong generator/checksum/config; contract drift; partial/stale output; hand edit; path/locale/timezone variance; concurrent generation; and read-only source tree.
- Status: PENDING

## C58-UI-001

- Lens: API_PRODUCT
- Invariant: `/developer/quickstart`, `/developer/webhooks`, and `/developer/scenarios` present an SDK-led, zero-source-reading workflow with safe copyable examples, explicit loading/empty/error/retry/recovery states, keyboard/focus/accessibility, and no credential in DOM, URL, storage, history, referrer, analytics, or generated command text.
- Failure sequence: Docs require reading controllers, a copy button includes a live key, one failed slice blanks the workflow, example output promises a result not returned by the API, or direct routing/focus/reload breaks progress.
- Implementation: committed SDK examples as tested documentation inputs, same-origin typed metadata, memory-only credential boundary, independent TanStack Query slices, escaped code rendering, allowlisted links, semantic step/status/focus components, and source-link-free completion criteria.
- Proof: Planned `developerQuickstart.test.tsx`, browser deep-link/back/reload/copy/credential/storage/accessibility tests, hostile text/link fixtures, documentation-to-executable snippet tests, and zero-source-reading usability run.
- Adversarial evidence: No/wrong/rotated credential; direct route; partial endpoint failure; stale scenario; unknown outcome; hostile error/example/link; keyboard-only and screen-reader flow; narrow viewport; reload/back/forward; and clipboard inspection.
- Status: PENDING

## C58-OBS-001

- Lens: OBSERVABILITY
- Invariant: Client test/demo signals expose only closed operation, quickstart-step, outcome, retry-decision, webhook-verification, and contract-digest states; metrics/logs never include base URL, credential, idempotency key, tenant, actor, resource/event/request ID, raw body/header, amount, error message, or exception text as dimensions or values.
- Failure sequence: Per-payment IDs become tags, a transport debug logger prints headers, a webhook failure logs payload/signature, random error codes create cardinality, or unknown-outcome recovery is invisible.
- Implementation: opt-in redacted hooks with closed enums, no default wire logging, bounded local test/demo counters, sanitized exception summaries, and readiness based on package/contract digest and executable smoke outcome.
- Proof: Planned `SdkObservabilityTests`, canary scans across logging levels/errors/test reports, cardinality assertions, every step/outcome fixture, and disabled/default instrumentation proof.
- Adversarial evidence: Success/validation/auth/rate/dependency/timeout/unknown; random IDs/messages/URLs; malicious header/body; webhook failure; repeated high-volume calls; and debug environment variables.
- Status: PENDING

## C58-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Package metadata declares one finite supported Python runtime set and all runtime/dependency constraints; every declared runtime installs from a clean isolated environment, imports, type/check/tests, runs the loopback quickstart offline except for the local THAAP service, verifies contract digest compatibility, and fails clearly on unsupported runtime, missing artifact, or dependency conflict.
- Failure sequence: Only a developer environment installs, undeclared transitive updates break transport behavior, runtime contacts a package/schema host, an unsupported Python appears supported, or missing generated files still produce a nominally usable package.
- Implementation: capability-named `pyproject.toml`, explicit supported-version classifiers/range and bounded dependencies, reproducible build/install commands, offline runtime assets, package manifest, compatibility check, and clean-environment CI matrix for every declared runtime.
- Proof: Planned wheel/sdist build and clean install per declared runtime, dependency-resolution/unsupported-version/missing-file negatives, offline import/quickstart, package-content inspection, repeated build hashes, and uninstall/reinstall.
- Adversarial evidence: Oldest/newest declared and adjacent unsupported runtimes; empty cache; offline network; missing/corrupt OpenAPI/generated module; dependency floor/ceiling; read-only directory; locale/timezone; and repeated install.
- Status: PENDING

## C58-SCP-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Chapter 58 stops at a repository-distributed synchronous Python local-sandbox SDK and onboarding guides; it makes no PyPI/public-release, semantic-version longevity, asynchronous/streaming, arbitrary-remote-host, production-credential, hosted identity/CORS, mobile/browser SDK, real PSP, PCI-scope, or production-support claim.
- Failure sequence: “Install from PyPI” appears without release evidence, the loopback guard is relaxed for convenience, an async stub is called supported, a real card/processor example is introduced, or documentation implies production SLA/support.
- Implementation: package/docs/source/route scans, local install instructions, explicit sandbox language, loopback enforcement, no publishing credentials/workflow, and exact Phase 9/10 owners for hosted/real-processor work.
- Proof: Planned scope/copy/package-index/network/source scans and one complete local demo that needs no public registry, remote host, real credential, or hidden configuration.
- Adversarial evidence: PyPI URL/token/workflow; remote hostname; production/live/PCI/SLA claim; async/client variants; browser/mobile language; real PAN/PSP; and external network access.
- Status: PENDING

## Frozen SDK Ownership Boundary

| Surface | Owner |
|---|---|
| Operations, request/response models | OpenAPI Generator output; never hand-edited |
| Version, credential, idempotency, timeout, redirect/proxy policy | reviewed THAAP transport wrapper |
| Stable error adaptation | reviewed THAAP error wrapper over generated models |
| Webhook exact-byte verification | reviewed byte-oriented helper using standard HMAC |
| Quickstart/scenario/webhook examples | handwritten, executable, contract-tested examples |
| HTTP, URL parsing, HMAC primitives | mature Python standard or pinned maintained libraries |

## Frozen Hostile-Family Coverage

| Phase 6 family | Chapter 58 obligations |
|---|---|
| Payment/idempotency/unknown outcome | C58-PAY-001 and C58-CON-001 |
| Error and operation contract | C58-ERR-001 |
| Tenant/credential separation | C58-TEN-001 |
| Transport and secret hygiene | C58-SEC-001 |
| Webhook protocol | C58-WHK-001 |
| Data/ML semantics | C58-DAT-001 |
| Generation/package reproducibility | C58-GEN-001 and C58-OPS-001 |
| Developer route/accessibility | C58-UI-001 |
| Observability | C58-OBS-001 |
| Supported scope | C58-SCP-001 |
