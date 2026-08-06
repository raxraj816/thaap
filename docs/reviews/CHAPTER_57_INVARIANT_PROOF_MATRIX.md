# Chapter 57 Invariant Proof Matrix

- Chapter: Chapter 57 — Versioned OpenAPI And Stable Error Contract
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
the twenty Phase 6 decisions and before Chapter 57 production code, dependency,
runtime configuration, generated contract, UI, example, or demo changes.
Planned classes, tools, tests, artifacts, and commands are obligations only.
Every row remains `PENDING` until real behavior, traceability, and a temporally
later hostile pass supply the evidence.

Chapter 57 makes the existing public contract executable and version-visible.
It does not release the Chapter 58 SDK, deploy multiple API versions, publish a
package, add hosted identity, expose internal/platform routes, or change
payment/fraud/money semantics.

## C57-PAY-001

- Lens: PAYMENT_FRAUD
- Invariant: API-version resolution, stable error classification, documentation generation, contract serving, linting, and diffing cannot invoke or alter payment, processor, fraud, review, control, dispute, ledger, webhook, event, projection, idempotency, or audit domain state; an invalid/unsupported/retired version fails before mutation.
- Failure sequence: A version error occurs after processor execution, OpenAPI generation boots a destructive fixture, error mapping retries a non-idempotent command, or contract metadata becomes payment/fraud decision input.
- Implementation: an early read-only `ApiVersionFilter`, pure error catalogue, build/test-time OpenAPI generation, static bundled contract controller, and no dependencies from domain orchestration to documentation/version packages.
- Proof: `ApiVersionMutationNonInterferenceTests` drives real payment mutations across every version-header partition and fingerprints the payment domain around each.
- Adversarial evidence: A refused version stops every mutation before any durable state exists, and — the sharper case — does not consume the idempotency key it carried, so a client that fixes its header can still retry with the same key rather than being permanently locked out by a header typo. An absent header and the exact version drive byte-identical payment behaviour. Reading the contract surface changes nothing about the payment domain, and payment orchestration is proven not to depend on documentation or version metadata at all.
- Status: PASS

## C57-VER-001

- Lens: API_PRODUCT
- Invariant: Every intentional public success and error resolves one `Thaap-Version` contract. Absence defaults to `2026-08-01`; one exact trimmed value selects it; duplicate, empty, malformed, unsupported future, and retired values fail deterministically. Version-resolution failures use and return the current/default error-contract version without echoing invalid input as effective truth.
- Failure sequence: Header parsing differs by endpoint, a duplicate value is collapsed, authentication errors omit the response version, unsupported input is reflected as selected, or a mutation begins before rejection.
- Implementation: one highest-precedence `ApiVersionFilter`, closed `ApiVersionCatalogue`, request attribute for downstream contract selection, response-header finalization, and stable typed version exceptions before authentication/resource/controller work.
- Proof: `ApiVersionFilterContractTests` exercises the resolver as a real servlet filter rather than a unit, so filter ordering is part of the proof.
- Adversarial evidence: Every header grammar partition resolves to exactly one deterministic outcome; the effective version is returned on every success and error family, so a client can always discover which contract answered it; an unusable requested version is never echoed back as effective truth, which would otherwise let a caller believe a version exists that does not; a version refusal is decided before authentication and before any handler, so it cannot leak resource existence; repeated header lines are refused rather than silently collapsed to the first or last; and version resolution deliberately does not govern internal, platform, or tooling surfaces.
- Status: PASS

## C57-ERR-001

- Lens: API_PRODUCT
- Invariant: The existing `ApiErrorResponse` remains additively compatible while `error` is a documented stable machine code for every known public failure and new `retryable`/ordered `fieldErrors` semantics are exact; clients never parse `message`, status reason, exception type, or database text to decide recovery.
- Failure sequence: Two domain failures collapse to generic `conflict`, a message change breaks a client, retryable is true for a permanent or ambiguous unsafe mutation, validation order changes nondeterministically, or an unexpected exception leaks internal text.
- Implementation: typed public exceptions/error descriptors, one closed `PublicApiErrorCatalogue`, explicit status/code/retry/idempotency/recovery mappings, deterministic field ordering, and safe fallback descriptors; message-derived classification is removed.
- Proof: `PublicApiErrorCatalogueTests`, `PublicApiErrorDescriptorTests`, and `ApiErrorCompatibilityTests` together prove the catalogue's internal consistency and, crucially, the join between what the API emits and what it publishes.
- Adversarial evidence: A transient classification is never given to a permanent failure and vice versa; every retryable code names its safe replay; client errors are non-retryable unless waiting is the documented remedy; 5xx never blames the caller; every stable code is unique and mirrors its constant; no safe message leaks internal detail; the recovery vocabulary stays small enough to implement; and two distinct operational causes never share one code. On the wire, every failure family returns the same envelope with no undocumented field, a client never has to parse message, status reason, or exception text to recover, validation field errors are deterministically ordered across repeated requests, and a foreign resource is byte-indistinguishable from an absent one. `everyEmittedCodeIsPublishedInTheCatalogueClientsAreToldToBranchOn` and `theCatalogueAgreesWithTheEnvelopeOnStatusAndRetrySemantics` close the join that `ER-0092` was filed for.
- Status: PASS

## C57-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: OpenAPI security declarations and runtime version/error handling preserve the existing merchant, risk-operator, scenario-runner, platform-operator, and unauthenticated metadata boundaries; no operation accepts interchangeable credentials, client-supplied tenant identity, or error detail that reveals a foreign resource.
- Failure sequence: Generated security alternatives mean “any key works,” a global header parameter replaces tenant authentication, version failure reveals which tenant owns an ID, or the error catalogue exposes tenant-specific examples.
- Implementation: operation-specific named security schemes matching live filters, zero tenant parameters where identity is credential-derived, not-found-equivalent error descriptors, synthetic fixed examples, and route/security inventory reconciliation.
- Proof: `OpenApiSecurityContractTests` derives the credential each operation actually enforces from `RequestMappingHandlerMapping` handler signatures and compares it against the published document, so the declaration cannot drift from enforcement.
- Adversarial evidence: Every declared credential is the credential the handler enforces, across all published operations; no operation outside the documentation surface is published as open; an operation published as open is proven to read no tenant-scoped state at runtime; merchant and risk-operator credentials are never interchangeable; every referenced scheme is defined as a header credential and nothing else is defined; and no credential is ever published with an example or default value that a generated client might send. `ER-0093` was filed against exactly this family and its correction is pinned here. A later hostile pass found the scenario-credential boundary published a rule the server does not enforce: all six scenario operations reach `DeveloperScenarioService.requireSynthetic` and reject a merchant key presented alone, while the contract declared `merchantApi` only. The contract now derives the requirement from a `@RequiresScenarioRunner` annotation on the handlers rather than generalising over the path prefix, and refuses to publish the token as a security scheme and a bare header at once. Recorded as `ER-0098`.
- Status: PASS

## C57-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: Generated/served OpenAPI, error catalogue, version metadata, logs, metrics, fixtures, UI pages, and build reports exclude internal/platform/Actuator routes, real credentials, prefixes, PAN/CVV/PII, raw/HMAC correlation identities, idempotency values, webhook secrets/targets/payloads, note plaintext, unrestricted metadata, and exception/database text.
- Failure sequence: Runtime route discovery publishes `/internal/**`, an annotation example contains a live secret, schema reflection exposes an entity field, an exception description includes SQL, or a generated report uploads credentials.
- Implementation: explicit public-route allowlist/denylist, DTO-only schemas, synthetic reserved examples, build-time and runtime redaction canaries, safe error descriptions, generated-artifact review, and no broad entity reflection.
- Proof: `OpenApiExposureTests` and `DeveloperContractPrivacyTests` scan the generated artifact, the served bytes, and every open route for prohibited content, and each class also proves its own scanner works.
- Adversarial evidence: No internal, operator, or tooling surface is published; no internal route is mentioned anywhere in the document including prose and examples; the document carries no credential, cardholder, or personal value; every declared server is an explicit local sandbox rather than a production host; no operation leaks an unreviewed field through a free-form object schema; an unknown contract version is refused without revealing which versions exist on disk; no open route discloses internal topology, storage, or implementation detail; the served contract is never cached by an intermediary and carries its own integrity digest; and every response on the open surface carries a correlation identity for support. Both classes include a self-test — `theSensitiveValueScannerDetectsEachClassOfLeakItClaimsToPrevent` and `theDisclosureScannerDetectsEachClassOfLeakItClaimsToPrevent` — so a scanner that silently stopped matching would fail rather than pass everything.
- Status: PASS

## C57-OAS-001

- Lens: API_PRODUCT
- Invariant: One committed `openapi/thaap-public-api-2026-08-01.yaml` is reproducibly generated from executable public behavior and contains every intentional public operation present at the Chapter 57 merge cutoff exactly once with stable `operationId`, request/response schema, status, credential, version, idempotency, bounds, safe examples, and common error response; no current public route is missing and no internal route is present.
- Failure sequence: A controller route is undocumented, a generated path/status differs from runtime, duplicate operation IDs overwrite clients, idempotency is omitted, optional/required or numeric units drift, or served bytes differ from the committed artifact.
- Implementation: pinned Spring/OpenAPI integration, explicit public grouping/customizers, capability-named operation IDs, committed generated artifact, byte/digest equality endpoint, and route-to-contract inventory gate; each later Phase 6 endpoint-owning chapter inherits additive regeneration/lint/diff/test obligations, with final inventory in Chapter 59.
- Proof: `OpenApiRuntimeContractTests` reconciles the committed artifact against the routes the server actually maps, rather than against a hand-maintained list.
- Adversarial evidence: Every public operation the server routes is published exactly once, so neither an undocumented route nor a duplicate entry survives; every `operationId` is derivable from its method and path so it cannot drift silently; the served contract is byte-identical to the bundled and reviewed artifact; the contract is served from an immutable resource rather than regenerated per request; the contract declares the version it is served under and the catalogue agrees; every operation documents the version header on its request and on every response; and every operation documents the stable error envelope as its default outcome. `OpenApiExposureTests` additionally proves the committed and bundled artifacts are the same bytes, and `VersionedPublicContractTests.committedArtifactDescribesTheRunningApiAndNotAStaleSnapshot` fails if the artifact goes stale against the running application.
- Status: PASS

## C57-CMP-001

- Lens: DATA_ML_LINEAGE
- Invariant: Contract schemas preserve payment/fraud/data lineage meanings—integer minor-unit money, basis-point scores, UTC instants, immutable IDs/versions, traffic origin, projection generation/cutoff/freshness/coverage, label availability, and unknown/not-computable states—and the compatibility gate rejects removal, narrowing, unit/type/requiredness/enum semantic changes under `2026-08-01`.
- Failure sequence: Basis points become percent without version change, `long` becomes `int`, timestamp precision/zone changes, unknown becomes zero, an enum value disappears, or a lineage field is renamed while generated clients still compile.
- Implementation: explicit schema annotations/components for semantic units and formats, golden representative schemas, pinned semantic OpenAPI diff tool with an allowlisted additive-change policy, and version-change workflow for incompatible semantics.
- Proof: `OpenApiSemanticLineageTests` walks every property of every schema in the committed artifact — not a sample — and asserts the meanings this chapter froze. The structural compatibility gate proves a field did not disappear; it says nothing about whether the field still means what an integrated client assumed, and that is the half this row owns.
- Adversarial evidence: Every `*Minor` money field is asserted `integer`/`int64`, because a decimal is additively compatible and silently loses cents on every client. Every basis-point field is asserted integer, because making it fractional invites a client to render it as a percentage and be wrong by a factor of a hundred. Every instant is asserted `date-time`; a bare string would let a client parse it as a local date and shift every window. Every THAAP-issued identifier is asserted `uuid`. `AnalyticsFreshness.state` is asserted to be a closed enum containing `FRESH`, `STALE`, `PARTIAL`, and `UNAVAILABLE`, and freshness is required to publish `generationId`, `generationNumber`, `sourceCutoff`, the eligible and applied counts, and the origin policy — without those a published number cannot be reproduced later. The error vocabulary is asserted to be `x-extensible-enum` and explicitly *not* a closed `enum`, because closing it would make adding any new code a breaking change, and its description must still tell clients never to parse the message.
- Two deliberate exceptions are named rather than blanket-asserted, because a blanket rule would force a lie into the contract: `VersionEntry.introducedAt` and `retirementDate` are calendar dates, since the API version *is* a date and giving it a time would imply precision the contract lacks; and seven identifier fields are unconstrained strings because THAAP does not mint them — a correlation handle, two processor-issued references, two caller-supplied identity values stored only as keyed digests, and two sandbox fixture slugs. Both sets are closed, so a genuine instant cannot arrive as a bare date and a new THAAP-issued identifier cannot arrive unconstrained.
- Status: PASS

## C57-BLD-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Concurrent builds, tests, or application instances cannot serve or commit partially generated OpenAPI bytes; generation writes a complete deterministic artifact through build-owned temporary output and comparison, while runtime serves one immutable bundled resource and never rewrites the workspace.
- Failure sequence: Two generators interleave one file, the server reads during replacement, a failed generation leaves a truncated contract, runtime regenerates with environment-dependent order, or a stale process overwrites the approved baseline.
- Implementation: build-directory generation, deterministic sorting/normalization, atomic build copy only after validation, committed-baseline comparison, read-only classpath serving, and one pinned generation task rather than application startup writes.
- Proof: `scripts/openapi_generation_tests.py` tests the writer directly, because atomicity and determinism are properties of the writer rather than things a green build implies.
- Adversarial evidence: Rendering is asserted deterministic under key reordering and byte-equal to the committed artifact, so two machines generating from the same running API cannot disagree and trip the drift gate for no real reason. A writer that dies between opening its temporary file and publishing is simulated by making `os.replace` throw: the previous artifact is asserted intact and no temporary file is left behind. A reader thread polls the target continuously through eighty alternating rewrites and every observation is asserted to be one whole document — a torn read is exactly what a non-atomic write produces. A contract with no paths is asserted to be refused by the generator's own validation, so a failed generation cannot replace a working artifact with an empty one that every downstream gate would then accept. `OpenApiRuntimeContractTests.theContractIsServedFromAnImmutableResourceRatherThanRegeneratedPerRequest` covers the serving half.
- Status: PASS

## C57-UI-001

- Lens: API_PRODUCT
- Invariant: The existing React product exposes a safe, accessible `/developer/api` route that displays the effective version lifecycle, committed OpenAPI artifact, and stable error catalogue with loading/empty/unavailable/retry states; it never renders internal routes, unsafe examples, credentials, or a generated-client support claim.
- Failure sequence: A raw schema renderer executes or links hostile content, a download URL crosses origin or includes credentials, one failed metadata slice blanks the others, keyboard/focus semantics fail, or the page advertises the Chapter 58 SDK as supported.
- Implementation: typed version/error metadata clients, same-origin allowlisted artifact navigation/download, escaped semantic tables rather than raw executable HTML, independent TanStack Query slices, existing memory-only credential boundary where needed, and semantic landmarks/focus/status handling.
- Proof: `developerApiContract.test.tsx` renders the real `/developer/api` route and drives it the way an integrator without a mouse would, with `axe-core` on the rendered container.
- Adversarial evidence: The page publishes the version lifecycle and every stable error code with its HTTP status, and states retryability in words — "Yes — same idempotency key/body" — so an integrator never has to guess whether a key may be reused. Every metadata fetch is asserted to carry no credential, `credentials: omit`, `referrerPolicy: no-referrer`, and `redirect: error`, because this surface is deliberately open and a credential here would land an operator key in a page any integrator can open. Tabbing through the page is asserted to reach the OpenAPI download and to land only on genuinely interactive elements, which a div-as-button would fail. After an outage the retry control is asserted focusable and operable with `Enter`, so recovering does not require a mouse. Every table is asserted to sit inside its own scroll container, because otherwise a narrow viewport scrolls the whole page sideways and the first column — which carries the error code — is what gets lost. An unavailable catalogue renders an explicit alert carrying the request id, and no internal, Actuator, admin, or platform route appears in the DOM.
- Status: PASS

## C57-OBS-001

- Lens: OBSERVABILITY
- Invariant: Bounded signals expose version-resolution outcome, public error-code family, OpenAPI generation/lint/diff/served-consistency result, and contract readiness without version input, path parameters, tenant, actor, resource, request, credential, idempotency, field value, message, example, schema body, or exception text as metric dimensions.
- Failure sequence: Arbitrary requested versions or error messages create high cardinality, a sensitive validation value is logged, OpenAPI drift is invisible, or a generation failure reports ready.
- Implementation: closed resolution/error/gate outcome enums, safe request-ID logs, build reports containing bounded rule IDs only, and readiness based on bundled-versus-baseline digest plus catalogue/public-route consistency.
- Proof: `DeveloperContractObservabilityTests` drives the real version filter and inspects the meter registry, because version resolution is the one place every public request passes through and the requested value is attacker-controlled free text.
- Adversarial evidence: Each of `ABSENT`, `EXACT`, `MALFORMED`, and `UNSUPPORTED` increments its own counter, keeping an unsupported future date operationally distinct from a malformed one so a retired-version rollout stays visible. Twenty-six further distinct hostile values — including a SQL-injection string — create no new time series at all and land entirely on the single `MALFORMED` counter, so the resolver cannot be turned into a memory attack. The version meter is asserted to be dimensioned by `outcome` and nothing else, and every recorded outcome value is inside the closed vocabulary. `DecisionIntelligenceMetricsTests` independently proves unbounded or unrecognised tags are rejected before registration.
- Status: PASS

## C57-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: A clean environment reproducibly generates, lints, semantically diffs, packages, and serves the exact public contract with pinned mature tools; missing/incompatible tooling or artifact drift fails CI/readiness, while normal payment APIs remain runnable from the committed bundled artifact without network-dependent runtime generation.
- Failure sequence: An unpinned tool update changes output, CI skips the diff, offline runtime fetches a schema/tool, missing generated bytes leave a nominally healthy app, or rollback serves a contract incompatible with the running controllers.
- Implementation: pinned build dependencies/tool versions and checksums where external binaries apply, repository generation/lint/diff scripts, committed baseline, runtime bundled bytes, compatibility runbook, and old/new application artifact checks.
- Proof: `scripts/openapi_generation_tests.py` runs the real generator as a subprocess against the frozen runtime contract, restoring the committed artifact afterwards so the test cannot leave the tree dirty.
- Adversarial evidence: Regenerating twice from the same source is asserted to leave the committed artifact byte-identical both times, which is the reproducibility claim CI depends on. The generator is asserted to write both the committed and the bundled artifacts from one render, since serving bytes that differ from the reviewed file is precisely the drift that pairing prevents. The end-to-end gates are exercised for real in this session: `scripts/openapi-compatibility-check.py` reports 133 operations with zero added since the reviewed baseline, and `scripts/openapi-breaking-change-negative-test.sh` rejects a removed `/customers` path, so the gate is shown to fail rather than merely to run.
- Remaining, and deliberately not claimed: the Python SDK regeneration path (`scripts/generate-python-sdk.sh`) pins `openapitools/openapi-generator-cli:v7.22.0` and requires Docker, which this environment has not executed. That obligation belongs to `C58-GEN-001` and is stated as unproven there rather than borrowed here.
- Status: PASS

## C57-SCP-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Chapter 57 stops at version/error/OpenAPI contracts and developer API/error pages for routes present at its merge cutoff; it establishes the additive gate but does not claim future Chapter 53–56 routes already exist, the Chapter 58 Python SDK, public package release, deployed multi-version compatibility, hosted CORS/SSO, external PSP guide, or semantic support for a version other than `2026-08-01`.
- Failure sequence: A generated but unreviewed client is called supported, two schema files imply deployed coexistence, a local metadata page claims production identity/CORS, or a PSP example invents credential/provenance behavior.
- Implementation: source/package/route/docs scans, one-version catalogue, explicit Phase 58/9/10 links, no `clients/python` supported package claim, and demos limited to contract generation/serving/error handling.
- Proof: `PublicContractScopeBoundaryTests` reads the committed artifact and the repository's own gate wiring, so the scope claim is checked against what ships rather than what the chapter intended.
- Adversarial evidence: The committed contract exists, declares the single date version `2026-08-01`, and is asserted not to advertise any later version — a second date version would mean the multi-version lifecycle arrived early without its compatibility-matrix evidence. The artifact names no package registry or installer (`pypi.org`, `pip install thaap`, npm, Maven Central), because public package release is a named Phase 9 exclusion and a contract that promises an SDK sends integrators to a download that does not exist. The declared servers are parsed as JSON rather than string-matched — the committed file is JSON-encoded despite its `.yaml` extension — and every server URL is required to be loopback and never a hosted or cloud host. The generation, contract, compatibility, and error-catalogue scripts are all present, and the compatibility, error-contract, and naming gates are asserted to be invoked by `scripts/commit-readiness-check.sh`, because a gate nobody runs is not a gate. The breaking-change gate is required to ship its negative fixture.
- Status: PASS

## Frozen Public Contract Partition

| Surface | Chapter 57 disposition |
|---|---|
| Merchant payment/customer/method/webhook APIs | include with merchant credential, version, idempotency, and errors |
| Risk-operator workspace/monitoring/control/dispute APIs | include with exact role credential and bounded schemas |
| Trusted scenario APIs intentionally exposed to sandbox operators | include with distinct runner credential and synthetic truth |
| Developer version/OpenAPI/error metadata | include with exact unauthenticated or credential policy frozen by route |
| `/internal/**` platform/bootstrap/data operations | exclude |
| Actuator, framework error, test/probe, implementation-only routes | exclude |
| Chapter 58 SDK/package | not a Chapter 57 completion claim |

## Frozen Compatibility Boundary Families

| Family | Required negative partitions |
|---|---|
| Path/operation | removal, rename, method change, operation-ID collision |
| Request | requiredness, type/format/unit, enum, bounds, credential, idempotency |
| Response | status removal/change, schema type/format/unit, field removal/requiredness |
| Error | code/status/retryability/idempotency/recovery drift, unsafe message dependency |
| Numeric/time/identity | width/overflow, basis points, minor units, UTC precision, UUID syntax |
| Security/privacy | scheme substitution, internal route, unsafe schema/example/description |
| Generation/serving | nondeterminism, partial file, stale baseline, byte mismatch, missing tool |

## Frozen Hostile-Family Coverage

| Phase 6 family | Chapter 57 obligations |
|---|---|
| API version grammar/order | C57-VER-001 and C57-PAY-001 |
| Error classification/retry | C57-ERR-001 |
| Route/schema contract | C57-OAS-001 and C57-CMP-001 |
| Tenant/credential separation | C57-TEN-001 |
| Security/privacy | C57-SEC-001 |
| Build concurrency/determinism | C57-BLD-001 |
| Developer route/accessibility | C57-UI-001 |
| Observability/readiness | C57-OBS-001 and C57-OPS-001 |
| Scope and later owners | C57-SCP-001 |
