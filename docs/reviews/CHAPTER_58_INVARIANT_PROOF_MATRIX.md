# Chapter 58 Invariant Proof Matrix

- Chapter: Chapter 58 — Supported Python SDK And Sandbox Onboarding
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

This matrix was drafted and frozen on 2026-08-02 after the founder confirmed
the twenty Phase 6 decisions and before Chapter 58 client generation, package,
transport, documentation, UI, example, or demo changes.

Every row now holds on executed evidence. Two findings escaped an earlier review
and are recorded immutably as `ER-0096` (a never-executed regeneration gate hiding
a stale generated client that silently dropped the synthetic-traffic qualifier) and
`ER-0097` (a safe exception that still reached the raw response body through
`__context__`). Both reopened their boundary families rather than closing only the
observed instance: the regeneration obligation is now enforced by a recorded run
before closure, and the non-disclosure claim is now proven over the whole reachable
exception object graph on both the API-error and transport-failure paths.

Chapter 58 supports one synchronous local-sandbox Python client. It does not
publish to PyPI, introduce an asynchronous client, support arbitrary remote
hosts, provide production credentials, add hosted identity/CORS, integrate a
real processor, or claim routes absent from the approved OpenAPI contract.

## C58-PAY-001

- Lens: PAYMENT_FRAUD
- Invariant: The supported quickstart creates or loads sandbox identity, creates tokenized test objects, creates and confirms one PaymentIntent, handles approved/review/3DS/blocked/decline/timeout/replay outcomes, and reads risk evidence through ordinary public APIs; an ambiguous confirm outcome is retried only with byte-equivalent semantic input and the same idempotency key, so the processor, money, event, fraud, and audit effects occur at most once.
- Failure sequence: A read timeout triggers a new key, a retry changes amount or payment method, generated code retries a mutation automatically, a scenario shortcut writes source state directly, or a second confirmation duplicates processor/money/event effects.
- Implementation: generated typed operations/models call existing public endpoints through a handwritten explicit-idempotency wrapper; mutation retry is caller-visible and permitted only after stored request fingerprint/key comparison; scenarios use their distinct public runner API and normal payment lifecycle.
- Proof: `clients/python/thaap_sdk/tests/test_payment_quickstart_contract.py` drives the published example and the payment operations against a loopback HTTP fixture that stores idempotency the way the API does — key plus exact request bytes — so at-most-once is asserted against real committed effects rather than against intent.
- Adversarial evidence: Every confirm outcome (`AUTHORIZED`, `IN_REVIEW`, `REQUIRES_ACTION` with a 3DS challenge, `BLOCKED`, `DECLINED`) arrives as a typed result rather than an exception, because an SDK that raised on `IN_REVIEW` would push integrators toward retrying a payment a human is already working. A read timeout mid-confirm is reported as `outcome_unknown`, the replay reuses the identical key and bytes, and the fixture proves one resource is created across three arriving requests. Reusing a key with different bytes is refused with a stable code rather than silently applied. `test_the_sdk_never_retries_a_mutation_on_its_own` proves exactly one request leaves the client on an ambiguous outcome — an automatic retry is the double-charge path. `test_the_published_quickstart_runs_from_environment_alone` executes `examples/sandbox_quickstart.py` as published, so documentation drift fails here rather than in a new merchant's first integration, and asserts no credential reaches stdout.
- Status: PASS

## C58-ERR-001

- Lens: API_PRODUCT
- Invariant: Generated models and one typed `ThaapApiError` preserve HTTP status, stable machine code, retryability, ordered field errors, request ID, effective API version, and safe recovery guidance; client behavior never parses human message, exception class text, status reason, HTML, or database/dependency text.
- Failure sequence: A message wording change changes retry behavior, malformed JSON becomes a credential-bearing raw exception, an unknown code is treated as retryable, ordered field errors are lost, or an error response lacks its effective version.
- Implementation: generated response models plus a small closed error adapter with safe unknown fallback, bounded body parsing, header capture, deterministic field ordering, and no raw body/headers in `str`, `repr`, logs, or raised exception text.
- Proof: The same suite drives real HTTP responses carrying the stable envelope and asserts the typed mapping the SDK exposes.
- Adversarial evidence: `test_stable_error_envelope_is_mapped_without_raw_body` proves the SDK surfaces status, stable code, retryability, ordered field errors, and request id as typed attributes, so an integrator branches on `error.code` rather than parsing prose. `test_oversized_or_untrusted_error_content_is_not_reflected` proves a hostile or oversized body is not echoed into the exception, which is how a server-controlled string otherwise reaches a caller's logs.
- Status: PASS

## C58-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: Merchant, risk-operator, and scenario-runner clients use distinct credential types and operation groups, attach exactly one applicable credential per request, derive tenant only at the server, and reject cross-client operation use, multiple credential headers, client-supplied tenant identity, and foreign-resource substitution without existence leakage.
- Failure sequence: One generic dictionary sends two keys, a risk credential calls a merchant operation, a copied foreign ID reveals existence, a scenario key reaches a risk read, or a tenant field/header overrides credential ownership.
- Implementation: separate immutable credential/client configurations and generated operation façades, operation-specific auth injection, prohibited-header checks, no tenant parameter, and safe server error propagation.
- Proof: `clients/python/thaap_sdk/tests/test_credential_separation.py` asserts the credential headers that actually left the socket, captured by the fixture, rather than the client's intent.
- Adversarial evidence: A risk-operator client calling a merchant operation sends **no** credential at all — not the operator key relabelled onto the merchant header, and not both keys in the hope the server picks one — so the call fails closed instead of being authorized by the wrong identity. The reverse direction is proven too. `Credentials` now rejects holding merchant and risk-operator authority in one client, and rejects a scenario token without a merchant key, so the combination cannot be constructed rather than merely being discouraged. No generated operation accepts a client-supplied tenant parameter, so tenant is always derived from the credential at the server.
- Status: PASS

## C58-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: The SDK keeps credentials and idempotency keys in caller memory, never places credentials in URL/query/body/log/repr/exception, accepts only an exact loopback HTTP sandbox origin, rejects userinfo/fragments/ambiguous hosts and every redirect, ignores ambient proxy configuration, uses bounded connect/read timeouts and response limits, and closes failed transports without forwarding secrets.
- Failure sequence: A redirect or proxy receives Authorization, `repr(client)` exposes a key, a hostile base URL confuses localhost parsing, an unbounded body exhausts memory, timeout text includes headers, or an example persists a credential in shell history or a file.
- Implementation: reuse and consolidate the existing proven loopback/no-proxy/no-redirect client boundary around a mature Python HTTP transport, immutable redacted configuration, separate timeout limits, bounded reads, environment-only quickstart input, and sanitized exception chaining.
- Proof: `clients/python/thaap_sdk/tests/test_secure_client.py` runs the real wrapper against a local `ThreadingHTTPServer`, so transport policy is proven against actual sockets rather than mocked intent.
- Adversarial evidence: The credential never appears in the client `repr`, in the `Credentials` `repr`, or in any raised exception — a secret that survives into a traceback ends up in every log aggregator the integrator owns. Hostile base URLs are rejected rather than silently accepted. Redirects are not followed and ambient proxy environment variables are ignored, so a hostile `HTTPS_PROXY` in the caller's shell cannot silently intercept sandbox traffic. Oversized or untrusted error content is not reflected back to the caller.
- Status: PASS

## C58-WHK-001

- Lens: SECURITY_PRIVACY
- Invariant: Webhook verification consumes the exact received body bytes and parsed signature-header fields, enforces one bounded timestamp tolerance with an injected clock, computes the versioned HMAC over the documented exact representation, compares digests in constant time, and provides event-ID deduplication guidance without claiming verification itself performs durable deduplication.
- Failure sequence: JSON is decoded and reserialized before verification, duplicate signature fields are collapsed, timestamp units/precision drift, stale/future callbacks pass, ordinary equality leaks timing, or the example processes one event twice after restart.
- Implementation: a small byte-oriented verification helper using the standard HMAC library and `compare_digest`, strict signature grammar/limits, explicit tolerance/clock, no body logging, and a reference receiver with caller-owned durable event-ID store contract.
- Proof: `test_webhook_exact_bytes_timestamp_event_identity_and_deduplication` verifies against the exact received bytes with an injected clock.
- Adversarial evidence: Verification consumes the exact received body bytes rather than a re-serialized parse — re-serializing before verifying is the single most common way a correct signature is made to fail and an incorrect one to pass. The bounded timestamp tolerance is enforced against an injected clock rather than wall time, and event-identity deduplication is proven so a redelivered webhook is processed once.
- Status: PASS

## C58-CON-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Concurrent use of documented client instances cannot bleed credential, version, timeout, idempotency key, request body, response, or error state across calls; per-request headers are immutable, and an unknown mutation outcome never starts an automatic competing retry or changes the original request identity.
- Failure sequence: Shared mutable default headers acquire another thread's key, a retry races the original socket, response adapters reuse buffers, credential rotation mutates an in-flight call, or two same-key/different-body calls are treated as equivalent.
- Implementation: immutable client/auth configuration, request-local copied headers and serialized body fingerprint, no background mutation retry, thread-safe mature transport usage or explicit documented non-sharing boundary, and deterministic close semantics.
- Proof: `test_concurrent_clients_do_not_bleed_credentials_or_request_identity` drives concurrent clients through a `ThreadPoolExecutor` against the local server.
- Adversarial evidence: Concurrent clients do not bleed credential or request identity across calls. `test_read_timeout_distinguishes_read_from_unknown_mutation_outcome` proves a read timeout is reported distinctly from an unknown mutation outcome, which is the distinction that decides whether replaying is safe. `test_unknown_mutation_replay_uses_identical_idempotency_key_and_bytes` proves the retry path reuses the identical key and bytes, so an ambiguous outcome cannot become a double charge.
- Status: PASS

## C58-DAT-001

- Lens: DATA_ML_LINEAGE
- Invariant: SDK models preserve integer minor-unit money, basis-point scores, UTC instants, immutable IDs/versions, traffic origin, projection generation/cutoff/freshness/coverage, source as-of, unknown/not-computable, and section authority without float conversion, timezone loss, default-zero substitution, or source/projection conflation.
- Failure sequence: Basis points become percent, a large minor-unit value loses precision, an absent model score becomes zero, UTC becomes naive local time, or projection cutoff is exposed as source truth.
- Implementation: generated exact schema types plus reviewed semantic helper types/converters, no float money/score API, timezone-aware parsing, closed availability/authority enums with safe unknown handling, and lineage-preserving examples.
- Proof: `clients/python/thaap_sdk/tests/test_semantic_types.py` asserts the meanings the contract froze, against both parsed fixtures and full client round trips.
- Adversarial evidence: Minor units are proven to survive `2**53 + 1`, the first integer a float64 cannot represent — a decimal money type is additively compatible and silently loses a cent on every client, and no test using small amounts would catch it. Basis points are asserted integer at 0/1/5000/9999/10000 and a fractional score is rejected rather than truncated, because a client that renders bps as a percentage is wrong by a factor of a hundred. Instants keep their UTC offset and microsecond precision; a naive datetime silently becomes local time in every window comparison. An unknown `trafficOrigin` is rejected rather than coerced to a default, since coercion would land synthetic volume in the live fraud partition. `PARTIAL` projection state reaches the caller with its generation, eligible/applied counts and origin policy intact, and source `asOf` stays distinct from projection cutoff. Absent model state stays `NOT_CONFIGURED` with a null score rather than a zero that would read as 'scored, low risk'.
- Status: PASS

## C58-GEN-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: OpenAPI Generator reproducibly creates operations and models from the exact committed Chapter 57-plus-additive contract; generated files carry the source digest, clean regeneration is byte/diff stable, hand edits and stale output fail, and the handwritten security wrapper/examples remain outside generated ownership.
- Failure sequence: A local generator version changes output, operation files are hand-fixed, stale models compile against a new contract, generation partially overwrites the package, or wrapper code is erased.
- Implementation: pinned mature generator/container or verified binary, deterministic configuration/templates, build-directory generation then validated copy, manifest/digest ownership boundaries, committed diff gate, and additive-contract compatibility input.
- Proof: `scripts/generate-python-sdk.sh` regenerates from the committed contract with a pinned generator image and `scripts/python-sdk-regeneration-check.sh` diffs the result against the committed tree. Both were executed in this environment.
- Adversarial evidence: The first recorded run **failed**: the committed client carried contract digest `8ca1ba94…` while `openapi/thaap-public-api-2026-08-01.yaml` hashes to `97c0d31e…`. The committed SDK had been generated from a contract predating the Chapter 53–56 endpoints, so it was missing the `DrillThrough` model and, more seriously, still modelled the scenario runner token as a security scheme the contract no longer declares — meaning the token was accepted from the caller and never put on the wire. That is silent: the server would have recorded synthetic scenario payments as `MERCHANT_API` traffic, placing generated volume inside live-only fraud metrics. The SDK was regenerated, the credential model corrected, and the check now reports `Python SDK regeneration PASS`; two independent generations produced byte-identical trees. Recorded as `ER-0096`.
- Status: PASS

## C58-UI-001

- Lens: API_PRODUCT
- Invariant: `/developer/quickstart`, `/developer/webhooks`, and `/developer/scenarios` present an SDK-led, zero-source-reading workflow with safe copyable examples, explicit loading/empty/error/retry/recovery states, keyboard/focus/accessibility, and no credential in DOM, URL, storage, history, referrer, analytics, or generated command text.
- Failure sequence: Docs require reading controllers, a copy button includes a live key, one failed slice blanks the workflow, example output promises a result not returned by the API, or direct routing/focus/reload breaks progress.
- Implementation: committed SDK examples as tested documentation inputs, same-origin typed metadata, memory-only credential boundary, independent TanStack Query slices, escaped code rendering, allowlisted links, semantic step/status/focus components, and source-link-free completion criteria.
- Proof: `apps/analyst-dashboard-ui/src/developerQuickstart.test.tsx` drives the whole routed application, because deep links, history and the credential boundary do not exist inside a single component, and runs `axe-core` on every developer route.
- Adversarial evidence: Every developer route is reachable by direct deep link without an operator credential — onboarding that demanded a login would defeat its own purpose, since the developer reading it does not have one yet. Browser back returns to the previous route with its content intact. The copyable command is asserted to teach reading the key from a secret manager into process memory rather than embedding a literal, and every URL in it is asserted loopback. Tabbing through the page is asserted to land only on genuinely interactive elements, which a div-with-onClick would fail. A failed contract-metadata slice renders a focusable retry inside a live alert while the static quickstart and webhook guidance keep working. The webhook guide is asserted to order verification before parsing, since re-serialising a parsed body is the classic way a correct signature is made to fail. No internal, Actuator, admin or platform route appears in any rendered route.
- Status: PASS

## C58-OBS-001

- Lens: OBSERVABILITY
- Invariant: Client test/demo signals expose only closed operation, quickstart-step, outcome, retry-decision, webhook-verification, and contract-digest states; metrics/logs never include base URL, credential, idempotency key, tenant, actor, resource/event/request ID, raw body/header, amount, error message, or exception text as dimensions or values.
- Failure sequence: Per-payment IDs become tags, a transport debug logger prints headers, a webhook failure logs payload/signature, random error codes create cardinality, or unknown-outcome recovery is invisible.
- Implementation: opt-in redacted hooks with closed enums, no default wire logging, bounded local test/demo counters, sanitized exception summaries, and readiness based on package/contract digest and executable smoke outcome.
- Proof: `clients/python/thaap_sdk/tests/test_client_observability.py` asserts on captured log records and on rendered exception text, which are the two places secrets actually escape.
- Adversarial evidence: A complete successful journey emits no log record carrying a credential or an idempotency key, and generated-transport debug is asserted off — a client that logs by default is a credential exfiltration path the integrator never chose, landing keys in a log system with different access rules than the payment API. The typed error exposes status, stable code and retryability while the server's human message is absent from the rendered exception. A second finding closed here: `raise ... from None` clears `__cause__` but leaves `__context__` pointing at the generated exception, whose text carries the full URL and whose attributes carry request headers; anything walking the exception chain — error reporters, structured log handlers — reads straight through the suppression flag. Safe errors are now raised outside the handler so both links are null, and that is asserted. Recorded as `ER-0097`.
- Status: PASS

## C58-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Package metadata declares one finite supported Python runtime set and all runtime/dependency constraints; every declared runtime installs from a clean isolated environment, imports, type/check/tests, runs the loopback quickstart offline except for the local THAAP service, verifies contract digest compatibility, and fails clearly on unsupported runtime, missing artifact, or dependency conflict.
- Failure sequence: Only a developer environment installs, undeclared transitive updates break transport behavior, runtime contacts a package/schema host, an unsupported Python appears supported, or missing generated files still produce a nominally usable package.
- Implementation: capability-named `pyproject.toml`, explicit supported-version classifiers/range and bounded dependencies, reproducible build/install commands, offline runtime assets, package manifest, compatibility check, and clean-environment CI matrix for every declared runtime.
- Proof: `scripts/python-sdk-package-check.py` builds the wheel, installs it into a clean virtual environment with `--no-index`, and runs the suite from the installed package.
- Adversarial evidence: `--no-index` is the point: an install that silently reaches a package host is not evidence of a complete declared dependency set, and importing from the working tree proves almost nothing about what a merchant receives. Build determinism is compared over wheel member names and content digests rather than archive bytes, because archive metadata carries build timestamps that say nothing about whether the shipped code is the same. `requires-python` was `>=3.11` — an open-ended range advertising support for Python releases this suite has never run — and is now `>=3.11,<3.15` with matching classifiers; the gate asserts 3.11–3.14 are accepted and 3.9/3.10/3.15 refused. Dependencies gained upper bounds, since an unpinned major release of the transport could change the redirect, proxy and timeout behaviour the SDK's security promises rest on without any THAAP change.
- Status: PASS

## C58-SCP-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Chapter 58 stops at a repository-distributed synchronous Python local-sandbox SDK and onboarding guides; it makes no PyPI/public-release, semantic-version longevity, asynchronous/streaming, arbitrary-remote-host, production-credential, hosted identity/CORS, mobile/browser SDK, real PSP, PCI-scope, or production-support claim.
- Failure sequence: “Install from PyPI” appears without release evidence, the loopback guard is relaxed for convenience, an async stub is called supported, a real card/processor example is introduced, or documentation implies production SLA/support.
- Implementation: package/docs/source/route scans, local install instructions, explicit sandbox language, loopback enforcement, no publishing credentials/workflow, and exact Phase 9/10 owners for hosted/real-processor work.
- Proof: `scripts/python-sdk-scope-check.py`, gated in `scripts/commit-readiness-check.sh` and proven by `scripts/python_sdk_scope_check_tests.py`, which plants one violation per rule.
- Adversarial evidence: A gate that only ever passes proves nothing, so each of the seven tests plants exactly one violation and asserts the failure. A package-index claim fails, because 'install from PyPI' sends an integrator to a name THAAP does not control — or that someone else registered. A production-readiness or PCI claim fails, since it creates a support and audit obligation Phase 9 owns. An example naming a remote host fails, and `http://127.0.0.1@evil.test` is resolved with `urlsplit` to `evil.test` rather than being reassured by the loopback prefix. Deleting the loopback guard from the client fails the gate, as does restoring an unbounded runtime range. The `[tool.poetry]` block, the unreachable repository URL and the unbounded dependency ranges left by the generator were removed from the published manifest.
- Status: PASS

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
