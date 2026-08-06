# Chapter 58 Closure Dossier

- Chapter: Chapter 58 — Supported Python SDK And Sandbox Onboarding
- Chapter number: 58
- Status: READY_TO_MERGE
- Invariant matrix: docs/reviews/CHAPTER_58_INVARIANT_PROOF_MATRIX.md
- Matrix provenance: PRE_IMPLEMENTATION
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Hostile review: PASS
- Boundary family review: PASS
- Custom infrastructure review: NOT_APPLICABLE
- Escaped review assessment: COMPLETE
- Merge recommendation: MERGE

This dossier was opened on 2026-08-02 before Chapter 58 implementation. All
twelve matrix rows now hold on executed evidence, and two findings escaped an
earlier review claim.

## Observable promises

A new merchant developer can install one repository-distributed synchronous
Python sandbox SDK and, without reading THAAP source, create/load sandbox
identity, create tokenized test objects, confirm a payment with safe
idempotency recovery, interpret stable typed outcomes, verify exact webhook
bytes, run/resume a trusted scenario, and inspect freshness-qualified risk
evidence. Credentials remain memory-only and never cross the exact operation
boundary.

That promise is now executed rather than described:
`tests/test_payment_quickstart_contract.py::test_the_published_quickstart_runs_from_environment_alone`
runs `examples/sandbox_quickstart.py` as published — by path, through its
documented environment variables — against a loopback fixture, and asserts the
printed journey reaches an `IN_REVIEW` attempt, a `REVIEW` decision, `PARTIAL`
analytics freshness and a claimed scenario run, with no credential in stdout.

## Failure inventory

The matrix freezes payment/idempotency ambiguity, error and semantic typing,
credential/tenant separation, hostile URL/redirect/proxy/timeout/body behavior,
exact webhook bytes/timestamps/deduplication, concurrent call isolation,
generator ownership/drift, browser documentation and secret sinks, bounded
signals, clean package installation, and local-sandbox scope.

## Boundary family evidence

| Family | Where it is proven | The failure it forecloses |
|---|---|---|
| Payment/idempotency ambiguity | `tests/test_payment_quickstart_contract.py` | A timeout mid-confirm becomes a second charge because the caller minted a fresh key |
| Semantic lineage | `tests/test_semantic_types.py` | Money loses a cent past `2**53`, or basis points render as percent |
| Credential separation | `tests/test_credential_separation.py` | An operator key authorizes merchant money movement |
| Transport and secret hygiene | `tests/test_secure_client.py`, `tests/test_client_observability.py` | A credential or response body reaches the integrator's logs |
| Webhook protocol | `tests/test_secure_client.py` | A re-serialised body makes a correct signature fail and a forged one pass |
| Generation reproducibility | `scripts/python-sdk-regeneration-check.sh` | A client disagrees with the server about what exists |
| Packaging | `scripts/python-sdk-package-check.py` | The package works only on the machine that built it |
| Scope | `scripts/python-sdk-scope-check.py` + `scripts/python_sdk_scope_check_tests.py` | Documentation promises support THAAP has not built |
| Developer routes | `apps/analyst-dashboard-ui/src/developerQuickstart.test.tsx` | Onboarding requires a credential, a mouse, or reading controllers |

## Enforcement boundaries

Enforcement is Chapter 57's committed OpenAPI contract; pinned OpenAPI Generator
`v7.22.0` output for operations and models, never hand-edited; an immutable THAAP
transport wrapper carrying the loopback/no-proxy/no-redirect/bounded-timeout
boundary; standard-library constant-time HMAC primitives; executable examples run
as tests; bounded package metadata; and the existing memory-only routed React
developer surface.

The credential model is enforced in `Credentials.__post_init__` rather than by
documentation: merchant and risk-operator authority cannot coexist in one client,
and the scenario runner token cannot exist without a merchant key. A client
holding the runner token attaches the synthetic-traffic qualifier to every request
it makes, because a per-call qualifier that is forgotten fails silently — it
records a synthetic payment as live merchant traffic.

## Exact proof

- 54 SDK tests (`clients/python/thaap_sdk/tests`), all against a real loopback
  `ThreadingHTTPServer` rather than mocks, because redirect, proxy, timeout and
  disconnect behaviour are properties of sockets and cannot be certified by a mock.
- 83 UI tests including 10 new developer-route tests with `axe-core` on every route.
- `scripts/python-sdk-regeneration-check.sh` → `Python SDK regeneration PASS`, with
  two independent generations producing byte-identical trees.
- `scripts/python-sdk-package-check.py` → `PASS`: wheel built, installed into a
  clean virtual environment with `--no-index`, suite executed from the installed
  package, repeated builds compared by member content digest, and the declared
  runtime range asserted to accept 3.11–3.14 and refuse 3.9/3.10/3.15.
- `scripts/python-sdk-scope-check.py` → `PASS` over 17 files, with 7 planted-violation
  tests proving the gate fails when it should.
- Backend suite green at 1256 tests.

## Migration evidence

Chapter 58 plans no migration and adds none. The SDK reaches state only through
documented public operations; no test, example, or script writes source tables.

## Demo evidence

The published quickstart is itself the demo and is executed as a test, so the demo
cannot drift from the documentation. It exercises the full local merchant journey —
customer, tokenized payment method, intent, confirm, risk operator provisioning,
scenario run and claim, investigation readback and analytics readback — and forces
an ambiguous response with a same-key replay. It requires no remote host, real
credential, public registry, or hidden configuration.

## Operational signals

The SDK is deliberately silent by default: generated-transport debug is off, and a
complete successful journey is asserted to emit no log record carrying a credential
or an idempotency key. Failures surface as closed typed state — status, stable
machine code, retryability, ordered field errors, request id, effective version —
so an integrator branches on `error.code` rather than parsing prose, and so no
server-controlled string reaches their logs.

## Known debt

No debt is accepted. Public package distribution, asynchronous and
additional-language clients, hosted identity/CORS, production credentials, and real
processor onboarding retain their Phase 9 or Phase 10 owners; they are excluded
product scope rather than incomplete Chapter 58 obligations, and
`scripts/python-sdk-scope-check.py` fails the build if the SDK begins claiming any
of them.

## Custom infrastructure decision

`NOT_APPLICABLE`. OpenAPI Generator owns typed operations and models; a mature
Python HTTP transport and standard URL/HMAC primitives own protocol mechanics.
THAAP adds only the narrow product-specific credential, version, idempotency,
safe-error, loopback transport, webhook, and onboarding policy the frozen contract
requires.

## Escaped-review assessment

Two findings escaped and are recorded immutably.

`ER-0096` (P1, `WEAK_PROOF`) — the regeneration gate had never been executed. Its
first run failed: the committed client had been generated from a contract predating
the Chapter 53–56 endpoints, and still modelled the scenario runner token as a
security scheme that the contract, at that moment, attached to no operation at all. The token was
accepted from the caller and never sent, so sandbox payments would have been
recorded as `MERCHANT_API` traffic and entered live-only fraud metrics — with
nothing failing. The process correction is that a gate which has never run is not
evidence, and a matrix row may not reach `PASS` on a script's assertion alone.

`ER-0097` (P1, `IMPLEMENTATION_DEFECT`) — `raise … from None` cleared `__cause__`
and suppressed the default traceback, but `__context__` still referenced the
generated exception carrying the raw response body, request headers and full URL.
Every existing assertion passed because they all read the rendered message. The
correction reopened the whole secret-hygiene family: a non-disclosure claim about
an exception is now proven over its entire reachable object graph, on both the
API-error and transport-failure paths.

## Source-of-truth reconciliation

The detailed and master plans, vision matrix, architecture notes, READMEs,
learning trail, matrix and this dossier agree that Chapter 58 is implemented and
proven. `docs/DEVELOPER_SANDBOX_GUIDE.md` is the published onboarding entry point
and is covered by the scope gate.

## Merge recommendation

`MERGE`. Every matrix row holds on executed evidence at its real enforcement
boundary, both escaped findings are resolved with product evidence and a process
correction, no debt is accepted, and the zero-source-reading merchant journey runs
as a test rather than as a claim.
