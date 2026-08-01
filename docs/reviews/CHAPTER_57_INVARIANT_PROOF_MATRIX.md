# Chapter 57 Invariant Proof Matrix

- Chapter: Chapter 57 — Versioned OpenAPI And Stable Error Contract
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
- Proof: Planned `ApiVersionMutationNonInterferenceTests`, payment/fraud/ledger source fingerprints around every version partition, source call-graph checks, and public lifecycle regression under absent/exact/invalid headers.
- Adversarial evidence: Create/confirm/authenticate/capture/cancel/refund/chargeback/review/control/webhook mutations; absent/exact/malformed/duplicate/future/unsupported/retired versions; validation/auth/domain/dependency/unexpected errors; and generator startup/shutdown.
- Status: PENDING

## C57-VER-001

- Lens: API_PRODUCT
- Invariant: Every intentional public success and error resolves one `Thaap-Version` contract. Absence defaults to `2026-08-01`; one exact trimmed value selects it; duplicate, empty, malformed, unsupported future, and retired values fail deterministically. Version-resolution failures use and return the current/default error-contract version without echoing invalid input as effective truth.
- Failure sequence: Header parsing differs by endpoint, a duplicate value is collapsed, authentication errors omit the response version, unsupported input is reflected as selected, or a mutation begins before rejection.
- Implementation: one highest-precedence `ApiVersionFilter`, closed `ApiVersionCatalogue`, request attribute for downstream contract selection, response-header finalization, and stable typed version exceptions before authentication/resource/controller work.
- Proof: Planned `ApiVersionFilterContractTests`, real HTTP filter-order tests, table/property grammar partitions, every success/error family response-header tests, and mutation source-fingerprint proof.
- Adversarial evidence: Header absent; exact; leading/trailing whitespace; empty; comma-combined; repeated lines; valid past/future date; invalid leap day; Unicode digit; control/oversized value; method/media/path/auth/validation/rate/dependency/internal error; redirect/error dispatch; and committed response.
- Status: PENDING

## C57-ERR-001

- Lens: API_PRODUCT
- Invariant: The existing `ApiErrorResponse` remains additively compatible while `error` is a documented stable machine code for every known public failure and new `retryable`/ordered `fieldErrors` semantics are exact; clients never parse `message`, status reason, exception type, or database text to decide recovery.
- Failure sequence: Two domain failures collapse to generic `conflict`, a message change breaks a client, retryable is true for a permanent or ambiguous unsafe mutation, validation order changes nondeterministically, or an unexpected exception leaks internal text.
- Implementation: typed public exceptions/error descriptors, one closed `PublicApiErrorCatalogue`, explicit status/code/retry/idempotency/recovery mappings, deterministic field ordering, and safe fallback descriptors; message-derived classification is removed.
- Proof: Planned `PublicApiErrorCatalogueTests`, `ApiErrorCompatibilityTests`, source inventory of every public exception/result branch, table-driven response tests, retry policy tests, and sensitive canary scans.
- Adversarial evidence: Every 4xx/5xx family; validation body/header/path/query; not found versus foreign resource; stale/idempotency conflict; rate limit and Retry-After; dependency unavailable/timeout/unknown outcome; message mutation/localization; nested database exception; and unknown route/method/media.
- Status: PENDING

## C57-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: OpenAPI security declarations and runtime version/error handling preserve the existing merchant, risk-operator, scenario-runner, platform-operator, and unauthenticated metadata boundaries; no operation accepts interchangeable credentials, client-supplied tenant identity, or error detail that reveals a foreign resource.
- Failure sequence: Generated security alternatives mean “any key works,” a global header parameter replaces tenant authentication, version failure reveals which tenant owns an ID, or the error catalogue exposes tenant-specific examples.
- Implementation: operation-specific named security schemes matching live filters, zero tenant parameters where identity is credential-derived, not-found-equivalent error descriptors, synthetic fixed examples, and route/security inventory reconciliation.
- Proof: Planned `OpenApiSecurityContractTests`, existing two-tenant API regressions, cross-credential matrix tests, generated-document assertions, and foreign-resource response equivalence/timing checks.
- Adversarial evidence: Merchant/risk-viewer/risk-analyst/risk-manager/scenario/data/admin/bootstrap credentials; missing/multiple/wrong headers; copied foreign IDs/idempotency keys; version error before auth; and public metadata with no credential.
- Status: PENDING

## C57-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: Generated/served OpenAPI, error catalogue, version metadata, logs, metrics, fixtures, UI pages, and build reports exclude internal/platform/Actuator routes, real credentials, prefixes, PAN/CVV/PII, raw/HMAC correlation identities, idempotency values, webhook secrets/targets/payloads, note plaintext, unrestricted metadata, and exception/database text.
- Failure sequence: Runtime route discovery publishes `/internal/**`, an annotation example contains a live secret, schema reflection exposes an entity field, an exception description includes SQL, or a generated report uploads credentials.
- Implementation: explicit public-route allowlist/denylist, DTO-only schemas, synthetic reserved examples, build-time and runtime redaction canaries, safe error descriptions, generated-artifact review, and no broad entity reflection.
- Proof: Planned `OpenApiExposureTests`, `DeveloperContractPrivacyTests`, prohibited-value canary scans across YAML/JSON/HTML/logs/metrics/reports, source scans, and exact negative assertions for every internal route family.
- Adversarial evidence: Internal/platform/Actuator/test/error routes; API/operator/scenario/bootstrap/webhook secrets; PAN/CVV/email/phone/IP/device/session/HMAC/idempotency/note/SQL canaries; schema/example/description/vendor extension; and generation failure.
- Status: PENDING

## C57-OAS-001

- Lens: API_PRODUCT
- Invariant: One committed `openapi/thaap-public-api-2026-08-01.yaml` is reproducibly generated from executable public behavior and contains every intentional public operation present at the Chapter 57 merge cutoff exactly once with stable `operationId`, request/response schema, status, credential, version, idempotency, bounds, safe examples, and common error response; no current public route is missing and no internal route is present.
- Failure sequence: A controller route is undocumented, a generated path/status differs from runtime, duplicate operation IDs overwrite clients, idempotency is omitted, optional/required or numeric units drift, or served bytes differ from the committed artifact.
- Implementation: pinned Spring/OpenAPI integration, explicit public grouping/customizers, capability-named operation IDs, committed generated artifact, byte/digest equality endpoint, and route-to-contract inventory gate; each later Phase 6 endpoint-owning chapter inherits additive regeneration/lint/diff/test obligations, with final inventory in Chapter 59.
- Proof: Planned `OpenApiRuntimeContractTests`, route inventory reflection tests, generated/served byte equality, clean regeneration diff, operation/schema/status/security/idempotency assertions, and representative real HTTP conformance tests.
- Adversarial evidence: Every controller and HTTP method; ambiguous path variables; 200/201/202/204; error responses; required/optional/null; int32/int64/basis-point/minor-unit/timestamp/UUID; enum change; pagination header/query/body; duplicated ID; and internal leakage.
- Status: PENDING

## C57-CMP-001

- Lens: DATA_ML_LINEAGE
- Invariant: Contract schemas preserve payment/fraud/data lineage meanings—integer minor-unit money, basis-point scores, UTC instants, immutable IDs/versions, traffic origin, projection generation/cutoff/freshness/coverage, label availability, and unknown/not-computable states—and the compatibility gate rejects removal, narrowing, unit/type/requiredness/enum semantic changes under `2026-08-01`.
- Failure sequence: Basis points become percent without version change, `long` becomes `int`, timestamp precision/zone changes, unknown becomes zero, an enum value disappears, or a lineage field is renamed while generated clients still compile.
- Implementation: explicit schema annotations/components for semantic units and formats, golden representative schemas, pinned semantic OpenAPI diff tool with an allowlisted additive-change policy, and version-change workflow for incompatible semantics.
- Proof: Planned `OpenApiSemanticLineageTests`, negative compatibility fixtures for every boundary family, positive additive fixtures, exact number/time/UUID serialization tests, and retained baseline diff.
- Adversarial evidence: 0/1/10000/10001 basis points; min/max/overflow minor units; pre/exact/post-microsecond timestamp; malformed/uppercase UUID; absent/null/unknown; enum add/remove/rename; optional-to-required; integer-width/format/unit change; and projection status/coverage drift.
- Status: PENDING

## C57-BLD-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Concurrent builds, tests, or application instances cannot serve or commit partially generated OpenAPI bytes; generation writes a complete deterministic artifact through build-owned temporary output and comparison, while runtime serves one immutable bundled resource and never rewrites the workspace.
- Failure sequence: Two generators interleave one file, the server reads during replacement, a failed generation leaves a truncated contract, runtime regenerates with environment-dependent order, or a stale process overwrites the approved baseline.
- Implementation: build-directory generation, deterministic sorting/normalization, atomic build copy only after validation, committed-baseline comparison, read-only classpath serving, and one pinned generation task rather than application startup writes.
- Proof: Planned `OpenApiGenerationConcurrencyTests`, parallel generation/process tests, injected failure before validation/copy, artifact hash comparison, read-only workspace runtime test, and repeated clean-build determinism.
- Adversarial evidence: One/two/many parallel generators; interruption at discovery/render/lint/diff/copy; empty/partial/stale target; locale/timezone/path/JVM-order differences; read-only source tree; repeated startup; and old/new process overlap.
- Status: PENDING

## C57-UI-001

- Lens: API_PRODUCT
- Invariant: The existing React product exposes a safe, accessible `/developer/api` route that displays the effective version lifecycle, committed OpenAPI artifact, and stable error catalogue with loading/empty/unavailable/retry states; it never renders internal routes, unsafe examples, credentials, or a generated-client support claim.
- Failure sequence: A raw schema renderer executes or links hostile content, a download URL crosses origin or includes credentials, one failed metadata slice blanks the others, keyboard/focus semantics fail, or the page advertises the Chapter 58 SDK as supported.
- Implementation: typed version/error metadata clients, same-origin allowlisted artifact navigation/download, escaped semantic tables rather than raw executable HTML, independent TanStack Query slices, existing memory-only credential boundary where needed, and semantic landmarks/focus/status handling.
- Proof: Planned `developerApiContract.test.tsx`, browser route/accessibility tests, hostile metadata/link fixtures, same-origin download tests, partial-failure tests, storage/history inspection, and production build.
- Adversarial evidence: Direct route, back/forward/reload, missing/corrupt artifact, version/error slice failure, hostile text/URL/HTML, keyboard-only flow, focus after retry/download, viewer/merchant/no credential as applicable, reduced viewport, and false SDK/internal-route content.
- Status: PENDING

## C57-OBS-001

- Lens: OBSERVABILITY
- Invariant: Bounded signals expose version-resolution outcome, public error-code family, OpenAPI generation/lint/diff/served-consistency result, and contract readiness without version input, path parameters, tenant, actor, resource, request, credential, idempotency, field value, message, example, schema body, or exception text as metric dimensions.
- Failure sequence: Arbitrary requested versions or error messages create high cardinality, a sensitive validation value is logged, OpenAPI drift is invisible, or a generation failure reports ready.
- Implementation: closed resolution/error/gate outcome enums, safe request-ID logs, build reports containing bounded rule IDs only, and readiness based on bundled-versus-baseline digest plus catalogue/public-route consistency.
- Proof: Planned `DeveloperContractObservabilityTests`, cardinality and sensitive-canary assertions, every gate outcome test, readiness corruption/drift tests, and repeated hostile input volume.
- Adversarial evidence: Absent/supported/malformed/unsupported/retired version; all stable error families; random paths/IDs/messages/fields; artifact missing/corrupt/drifted; lint/diff tool failure; and database/HTTP exception.
- Status: PENDING

## C57-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: A clean environment reproducibly generates, lints, semantically diffs, packages, and serves the exact public contract with pinned mature tools; missing/incompatible tooling or artifact drift fails CI/readiness, while normal payment APIs remain runnable from the committed bundled artifact without network-dependent runtime generation.
- Failure sequence: An unpinned tool update changes output, CI skips the diff, offline runtime fetches a schema/tool, missing generated bytes leave a nominally healthy app, or rollback serves a contract incompatible with the running controllers.
- Implementation: pinned build dependencies/tool versions and checksums where external binaries apply, repository generation/lint/diff scripts, committed baseline, runtime bundled bytes, compatibility runbook, and old/new application artifact checks.
- Proof: Planned clean-environment generation tests, offline runtime startup, fresh/retained app contract conformance, missing/corrupt/tool-version negative tests, repeated build, and rollback/mixed-artifact tests.
- Adversarial evidence: Empty dependency cache, unavailable network, changed tool version/checksum, stale baseline, missing resource, old app/new artifact and new app/old artifact, multiple startup, read-only filesystem, and CI task omission.
- Status: PENDING

## C57-SCP-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Chapter 57 stops at version/error/OpenAPI contracts and developer API/error pages for routes present at its merge cutoff; it establishes the additive gate but does not claim future Chapter 53–56 routes already exist, the Chapter 58 Python SDK, public package release, deployed multi-version compatibility, hosted CORS/SSO, external PSP guide, or semantic support for a version other than `2026-08-01`.
- Failure sequence: A generated but unreviewed client is called supported, two schema files imply deployed coexistence, a local metadata page claims production identity/CORS, or a PSP example invents credential/provenance behavior.
- Implementation: source/package/route/docs scans, one-version catalogue, explicit Phase 58/9/10 links, no `clients/python` supported package claim, and demos limited to contract generation/serving/error handling.
- Proof: Planned scope scans, generated artifact inventory, docs assertions, route tests, and a public contract/error demo that does not install or advertise an SDK.
- Adversarial evidence: Generated scratch client, package metadata, second API date, hosted URL/CORS/SSO claim, PSP/network name, internal route, and documentation suggesting production support.
- Status: PENDING

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
