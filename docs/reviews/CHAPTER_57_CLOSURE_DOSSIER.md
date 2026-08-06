# Chapter 57 Closure Dossier

- Chapter: Chapter 57 — Versioned OpenAPI And Stable Error Contract
- Chapter number: 57
- Status: READY_TO_MERGE
- Invariant matrix: docs/reviews/CHAPTER_57_INVARIANT_PROOF_MATRIX.md
- Matrix provenance: PRE_IMPLEMENTATION
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Hostile review: PASS
- Boundary family review: PASS
- Custom infrastructure review: NOT_APPLICABLE
- Escaped review assessment: COMPLETE
- Merge recommendation: MERGE

This dossier was opened on 2026-08-02 before Chapter 57 implementation. All
twelve matrix rows now hold on executed evidence.

The committed `openapi/thaap-public-api-2026-08-01.yaml` describes 133 public
operations across 125 paths at sha256 `8b5b0b2e…`, is generated from the running
application rather than hand-maintained, and is reconciled against the routes the
server actually maps.

## Observable promises

Every intentional public endpoint present at the Chapter 57 merge cutoff has one
visible `2026-08-01` API contract, one stable machine-readable error/retry
contract, and one generated/committed/served OpenAPI definition with exact
credentials, idempotency, bounds, units, statuses, and safe examples. Invalid
version handling occurs before mutation; internal routes and sensitive values are
absent. Later Phase 6 endpoint owners inherit additive regeneration proof, and
Chapter 59 owns final phase-wide completeness.

## Failure inventory

The frozen matrix covers absent/exact/malformed/duplicate/future/unsupported/
retired headers, every public error family, authentication and tenant
substitution, internal-route/schema/example leakage, numeric/time/UUID/enum/
requiredness compatibility, partial or concurrent generation, served-artifact
drift, missing tools, offline runtime, rollback, and accidental SDK/hosted/PSP
claims.

## Boundary family evidence

| Family | Where it is proven | The failure it forecloses |
|---|---|---|
| Version header grammar and filter order | `ApiVersionFilterContractTests`, `ApiVersionMutationNonInterferenceTests` | An unsupported version is rejected after a mutation already ran |
| Public error classification | `PublicApiErrorCatalogueTests`, `PublicApiErrorDescriptorTests`, `ApiErrorCompatibilityTests` | A client branches on prose and breaks when wording changes |
| Route and credential inventory | `OpenApiExposureTests`, `OpenApiSecurityContractTests`, `PublicContractScopeBoundaryTests` | An internal route ships, or a published credential rule differs from the enforced one |
| Semantic lineage | `OpenApiSemanticLineageTests` | A field survives structurally while its meaning silently changes |
| Generation atomicity and determinism | `scripts/openapi_generation_tests.py`, `OpenApiRuntimeContractTests` | A torn or partial artifact is served or committed |
| Served-versus-committed bytes | `VersionedPublicContractTests` | The document clients read is not the document the server implements |
| Developer contract surface | `developerApiContract.test.tsx` | Version and error metadata are unusable without a mouse or leak a credential |

## Enforcement boundaries

One early version filter and catalogue; typed public error descriptors with a
published closed catalogue; operation-specific OpenAPI security derived from the
handlers themselves; pinned mature lint (`@redocly/cli` 2.40.0), semantic diff
(`oasdiff`) and generation tooling; deterministic build output written atomically;
committed-baseline comparison; and read-only serving of bundled exact bytes.

The credential boundary is now derived from enforcement rather than from a path
prefix. `@RequiresScenarioRunner` marks the handlers that reject a merchant key
presented alone, and `PublicOpenApiConfiguration` reads it from the running handler
mapping. All six scenario operations carry it, because every one of them reaches
`DeveloperScenarioService.requireSynthetic`; they declare `merchantApi` AND
`scenarioRunner`. The other 36 merchant operations publish the runner token as the
optional traffic-origin qualifier the resolver actually treats it as.

## Exact proof

- Backend suite green at 1256 tests.
- `scripts/openapi-contract-check.sh` → `PASS` (125 paths, 133 operations) plus a
  clean Redocly lint against `redocly.yaml`.
- `scripts/openapi-compatibility-check.py` → `PASS`, 0 operations added since the
  reviewed baseline.
- `scripts/openapi-breaking-change-check.sh` → no breaking changes;
  `scripts/openapi-breaking-change-negative-test.sh` → `PASS`, proving the gate
  rejects a removed `/customers`.
- `developerApiContract.test.tsx` drives the real `/developer/api` route with
  `axe-core`, asserting keyboard traversal, `credentials: omit`, `redirect: error`,
  `referrerPolicy: no-referrer`, and per-table scroll containment.

## Migration evidence

Chapter 57 adds no database migration. Version, OpenAPI and error work creates and
mutates no domain row; `ApiVersionMutationNonInterferenceTests` proves a rejected
version resolves before any mutation runs.

## Demo evidence

The contract chain is itself the demo and runs as scripts: generate from the
running application, compare against the committed artifact, lint, semantically
diff against the reviewed baseline, and prove a removal is rejected. It installs
and advertises no SDK; Chapter 58 owns that surface.

## Operational signals

Version-resolution outcome and public error-code family are emitted as closed
enums. `DeveloperContractObservabilityTests` and `DeveloperContractPrivacyTests`
assert no request identifier, credential prefix, version input or exception text
becomes a metric dimension, so a hostile version header cannot inflate metric
cardinality.

## Known debt

No debt is accepted. SDK packaging, deployed multi-version support, hosted
identity/CORS, public distribution, and a real PSP guide retain their Chapter 58,
Phase 9, or Phase 10 owners rather than becoming Chapter 57 debt.

## Custom infrastructure decision

`NOT_APPLICABLE`. Chapter 57 uses mature Spring/OpenAPI integration, OpenAPI
linting, semantic diff, and OpenAPI Generator tooling. The small THAAP surface is
configuration, public-route and error catalogues, deterministic build
orchestration, and runtime static serving — not a proprietary schema language or
client generator.

## Escaped-review assessment

Three findings were filed against this chapter's families and all are closed.

`ER-0092` (emitted codes absent from the published catalogue) and `ER-0093`
(published credential requirement disagreeing with the enforced one) were closed
earlier, pinned by tests that derive truth from the runtime rather than from the
generated document.

`ER-0098` (P1, `WEAK_PROOF`) was found by this chapter's later hostile pass and is
the same family as `ER-0093` reopening. `scripts/openapi-contract-check.sh` had
never been wired into `scripts/commit-readiness-check.sh`, so nobody learned it was
failing. It asserted that every scenario operation requires both credentials; the
contract asserted that none do. The check was right: all six operations reach
`DeveloperScenarioService.requireSynthetic` and reject a merchant key presented
alone. The contract understated the requirement, so a generated client would omit
the token and meet an avoidable `SCENARIO_RUNNER_CREDENTIAL_REQUIRED` on a
documented sandbox workflow.

The first attempt at the remedy was itself wrong and is recorded rather than
quietly corrected. Reading only `DeveloperScenarioController` shows
`requireScenarioRunner` in three of six handlers, which suggested a three/three
split; the service-level guard covers all six. That is the third independent time
this boundary was inferred from a convenient surface instead of the enforcement
point — which is why the fix is `@RequiresScenarioRunner` on the handlers, read by
both the contract generator and `OpenApiSecurityContractTests`, rather than a more
carefully written list. The check is wired into the readiness gate with a fixture
test that fails if it is removed.

## Source-of-truth reconciliation

The Phase 6 detailed plan, implementation master plan, vision matrix,
architecture notes, READMEs and learning trail agree that Chapter 57 is
implemented and proven, and that later Phase 6 public routes carry an additive
regeneration obligation enforced by the contract and SDK regeneration gates.

## Merge recommendation

`MERGE`. Every matrix row holds on executed evidence at its real enforcement
boundary, the escaped finding is resolved with product evidence and a process
correction that makes the gate run, and no debt is accepted.
