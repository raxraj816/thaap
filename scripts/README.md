# Scripts

Developer utilities for repeatable local workflows.

## Local Infrastructure

Start PostgreSQL and Redis:

```bash
scripts/local-infra-up.sh
```

Check container status:

```bash
scripts/local-infra-ps.sh
```

Stop local infrastructure:

```bash
scripts/local-infra-down.sh
```

Reset the local PostgreSQL password to the documented default:

```bash
scripts/local-postgres-reset-password.sh
```

## payments-api

Run the Spring Boot API from the repository root:

```bash
scripts/payments-api-run.sh
```

Run the Chapter 13 create-to-confirm demo against a running local API:

```bash
scripts/demo-confirm-flow.sh
THAAP_DEMO_CARD=4999990000000002 scripts/demo-confirm-flow.sh
THAAP_DEMO_CARD=4999990000000008 scripts/demo-confirm-flow.sh
```

Run the Chapter 17 automatic-capture and manual-authorization-void demo:

```bash
scripts/demo-chapter-17-flow.sh
```

The script fails unless approved `AUTOMATIC` confirm reaches `CAPTURED` with
exactly two balanced ledger entries owned by that PaymentIntent, using the
reviewed account/direction pair, captured amount, currency, and one transaction
group. Approved `MANUAL` confirm must wait in `REQUIRES_CAPTURE`, and cancel
must expose `RECORDED` / `MERCHANT_CANCELED` void evidence with zero ledger
rows for the manual payment. It accepts only loopback API origins so demo
traffic cannot enter a shared environment without the trusted traffic-origin
boundary tracked by `TD-0001`.

Run the Chapter 18 durable full-refund and ledger-reversal demo:

```bash
scripts/demo-chapter-18-refund-flow.sh
```

The script accepts only a loopback API origin. It proves automatic capture,
durable Refund retrieval and idempotent replay, `RECORDED` simulator-local
status, exactly reversed account directions, balanced capture/refund
transaction groups, PaymentIntent/Refund ledger lineage, and exactly one
`refund.created` delivery whose resource is the Refund.

Run the Chapter 19 signed webhook failure/retry/success demo:

```bash
export THAAP_WEBHOOK_SECRET_ENCRYPTION_KEY="$(openssl rand -base64 32)"
export THAAP_WEBHOOK_EXECUTION_ENABLED=true
scripts/payments-api-run.sh
# In another terminal:
scripts/demo-chapter-19-webhook-flow.sh
```

The demo accepts only a loopback API origin and starts a loopback callback
fixture. The endpoint secret is written to a mode-restricted temporary file and
removed on exit. The fixture strictly verifies timestamped HMAC signatures,
derives the deduplication ID from the signed payload, rejects a disagreeing
`THAAP-Event-Id` header, and atomically applies one representative merchant
effect through a SQLite event-ID/payload-hash uniqueness row. That state
survives restart, serializes concurrent duplicates, acknowledges identical
redelivery without a second effect, and rejects conflicting bytes under one
event ID. The fixture returns `503` once and then `204`, and writes only
sanitized event/delivery IDs, effect/duplicate booleans, payload hashes,
response statuses, and verification results under
`build/reports/webhook-delivery/`. The script waits for the deterministic
one-minute retry and proves two attempts share one immutable event and apply
exactly one durable callback effect.

Run the Chapter 20 complete deterministic processor-catalog demo:

```bash
export THAAP_ADMIN_BOOTSTRAP_TOKEN=change-me-local-admin-bootstrap-token
scripts/demo-chapter-20-processor-catalog.py
```

The runner accepts only a loopback API origin, ignores environment proxy
configuration, and rejects every HTTP redirect before a follow-up request can
forward API or bootstrap credentials. It creates
fresh merchant-owned objects through public APIs for mapped approval, six
decline/verification outcomes, 3DS-required, and unknown-card default approval.
It fails closed unless PaymentIntent, PaymentAttempt, Authorization,
FraudDecision, FeatureSnapshot, zero-ledger, webhook-delivery, and read-only
local PostgreSQL audit evidence agree. Its atomic owner-only report under
`build/reports/processor-catalog/` identifies fixtures by safe labels and
excludes submitted card values, buyer PII, API keys, signing secrets,
idempotency material, and card fingerprints.

Run the Chapter 22 typed point-in-time lineage demo after starting local
PostgreSQL and the API:

```bash
export THAAP_ADMIN_BOOTSTRAP_TOKEN=change-me-local-admin-bootstrap-token
scripts/demo-chapter-22-feature-lineage.py
```

The runner accepts only a loopback API origin, ignores environment proxies,
and rejects redirects. It creates and confirms a manual-capture payment through
public APIs, checks exact idempotent replay and the safe lexical decision
snapshot, then performs a read-only query against the local `thaap-postgres`
container to reconcile all seven typed values and their point-in-time
predicate. It prints only non-secret resource IDs and bounded outcomes.

Generate Chapter 16 synthetic payment-risk scenarios through the public API:

```bash
export THAAP_ADMIN_BOOTSTRAP_TOKEN=change-me-local-admin-bootstrap-token
export THAAP_SCENARIO_RUNNER_TOKEN=change-me-local-scenario-runner-token
scripts/generate-synthetic-scenarios.py --seed chapter-16-demo
```

Useful options:

```bash
scripts/generate-synthetic-scenarios.py \
  --seed chapter-16-demo \
  --base-url http://localhost:8080 \
  --run-id chapter-16-demo-run-1 \
  --state build/reports/synthetic-scenarios/generator-runs.sqlite3 \
  --output build/reports/synthetic-scenarios/chapter-16-demo-summary.json
```

The generator creates approved, retryable-decline, and basic card-testing
scenario families using merchant-facing APIs only. It reads the bootstrap token
and dedicated scenario-runner credential directly from the environment,
accepts only loopback API targets, and refuses to run against a shared or
production endpoint. It explicitly ignores environment proxy configuration
and rejects every HTTP redirect before a follow-up request can forward API,
bootstrap, or scenario-runner credentials. The runner
credential assigns server-authoritative
`SYNTHETIC_SCENARIO` lineage; synthetic-looking merchant metadata has no trust
authority.

Each run is journaled in an owner-only SQLite file. Reuse `--run-id` to resume
after interruption. Completed steps replay from checkpoints, mutation steps use
stable run-scoped idempotency keys, persisted retry-safety classifications
cannot be rewritten by a later binary, and two processes cannot own one live
run. Merchant and customer creation now reconcile through server-side
idempotency even if the process dies immediately after the API commits and
before any local resource/checkpoint write. Every HTTP request heartbeats the
run lease before and after its bounded network wait, and startup rejects a
lease that does not exceed one request timeout, so slow verification reads
cannot silently hand ownership to another process. A future or explicitly
unsafe step with an ambiguous outcome still enters `NEEDS_CLEANUP` rather than
being repeated. Record the operator disposition explicitly:

```bash
scripts/generate-synthetic-scenarios.py \
  --state build/reports/synthetic-scenarios/generator-runs.sqlite3 \
  --abandon-run chapter-16-demo-run-1 \
  --cleanup-disposition retain-generated-resources
```

Abandonment erases secret-bearing checkpoint responses but retains a separate
sanitized inventory of created merchant/resource IDs so the operator can
reconcile or clean up durable product state without recovering API keys.
Every resource-producing step persists an explicit immutable `resource_type`;
resource lineage no longer depends on step-name suffixes. Older journals are
additively backfilled, including interrupted steps and the tenant-isolation
verifier merchant, before resume is allowed.

Its JSON summary
includes safe IDs, final statuses, fraud decision IDs, actual processor response
codes, tenant-isolation checks, and a sensitive-data scan. It fails if an
uncaptured scenario creates ledger truth, if authorization/fraud lineage drifts,
or if sensitive output is detected. It intentionally omits raw card numbers,
CVV, buyer email, buyer phone, API keys, idempotency keys, hashes, and card
fingerprints.

## Learning Workflow

Create today's learning note, and optionally the deep dive:

```bash
scripts/learning-day-start.sh
scripts/learning-day-start.sh --deep-dive
```

Check that today's Staff apprenticeship learning artifacts exist:

```bash
scripts/learning-day-check.sh
```

For non-trivial implementation chapters:

```bash
scripts/learning-day-check.sh --deep-dive-required
```

Check that the weekly architecture review and growth-tracking artifacts exist:

```bash
scripts/learning-week-check.sh
```

## Technical And Implementation Debt

Validate the canonical debt register and its implementation-roadmap references:

```bash
scripts/technical-debt-check.py
python3 -m unittest scripts/technical_debt_check_tests.py
```

Every accepted or deferred technical, implementation, security, data/ML, or
operational debt must receive an immutable `TD-NNNN` ID in
`docs/TECHNICAL_DEBT_REGISTER.md`. The commit-readiness gate runs this check
automatically and rejects open entries that have not been reviewed within 35
days.

## Technical Blog Workflow

Create a weekly technical blog draft from the post template:

```bash
scripts/blog-week-start.sh YYYY-WNN short-slug
```

Example:

```bash
scripts/blog-week-start.sh 2026-W25 why-fraud-systems-need-explainable-intelligence
```

Drafts live in `docs/blog/drafts/` and should be checked against
`docs/blog/WEEKLY_BLOG_CHECKLIST.md` before moving to `docs/blog/published/`.

Check public drafts for private product names, repo paths, branches, commits,
and build-status language:

```bash
scripts/blog-public-check.sh
scripts/blog-public-check.sh docs/blog/drafts/YYYY-WNN-short-slug.md
```

## Commit Readiness

Run the commit-readiness gate before saying a change is ready to commit:

```bash
scripts/commit-readiness-check.sh YYYY-MM-DD --deep-dive-required \
  --invariant-matrix=docs/reviews/CHAPTER_N_INVARIANT_PROOF_MATRIX.md \
  --closure-dossier=docs/reviews/CHAPTER_N_CLOSURE_DOSSIER.md
```

The readiness gate prints the Senior Staff adversarial review lenses. Use that
output to classify reviewer-style findings as fixed now, accepted risk,
deferred, or rejected as not applicable before handoff.

The non-trivial gate also runs:

```bash
scripts/invariant-proof-check.py docs/reviews/CHAPTER_N_INVARIANT_PROOF_MATRIX.md
scripts/escaped-review-check.py
scripts/slice-review-check.py
scripts/slice-review-check.py --require-verified
scripts/chapter-closure-check.py \
  docs/reviews/CHAPTER_N_INVARIANT_PROOF_MATRIX.md \
  docs/reviews/CHAPTER_N_CLOSURE_DOSSIER.md
```

These scripts enforce honest matrix provenance, a completed boundary-family
review with explicit dossier evidence, immutable `ER-NNNN` findings, blocking
severity, custom-infrastructure ADR evidence, and agreement between the closure
dossier, root README, and implementation master plan. They validate the shape
and consistency of the evidence; the traceability, boundary-family, and hostile
passes remain semantic engineering reviews.

`--deep-dive-required` also makes backend and repository Python test execution
mandatory. `--run-tests` remains available to request the same execution for a
trivial readiness invocation.
