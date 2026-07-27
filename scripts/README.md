# Scripts

Developer utilities for repeatable local workflows.

## Trusted Developer Scenario Quickstart

After creating a local merchant and starting `payments-api` with a scenario
runner token, export the two reveal-once values into the current shell and run:

```bash
export THAAP_MERCHANT_API_KEY='the local merchant API key'
export THAAP_SCENARIO_RUNNER_TOKEN='the separately configured runner token'
python3 scripts/demo-developer-scenario-console.py
```

No source inspection or database mutation is required. The client first creates
one ordinary live reference payment, then creates or resumes an
`APPROVED_PAYMENT` scenario, follows the server-issued public API steps, and
submits each resource ID for tenant/outcome/origin verification. It finishes by
checking the immutable report reconciles at least one `MERCHANT_API` and one
`SYNTHETIC_SCENARIO` attempt. `--scenario ISSUER_DECLINE` and
`--scenario CARD_TESTING_BURST` select the other governed plans.

Create, claim, step completion, and terminal failure are all transport-idempotent.
The client derives a stable per-operation retry key while the server separately
enforces worker ownership and lease epoch; neither boundary substitutes for the
other.

The client accepts only an explicit loopback HTTP origin, installs no ambient
proxy, rejects redirects, accepts only the closed customer/payment step paths,
uses bounded request timeouts, reads credentials only from environment
variables, and never prints them, the raw seed, customer contact, or test-card
values. Run its safety contract with:

```bash
python3 scripts/developer_scenario_demo_tests.py
```

## Phase 4.5 Risk Operations Demos

Start the retained local stack with the normal production-safe defaults, then
run the monitoring, workspace, historical-simulation, and dispute workflows:

```bash
export THAAP_ADMIN_BOOTSTRAP_TOKEN=change-me-local-admin-bootstrap-token
export THAAP_SCENARIO_RUNNER_TOKEN=change-me-local-scenario-runner-token
python3 scripts/demo-merchant-risk-monitoring.py
python3 scripts/demo-analyst-workspace.py
python3 scripts/demo-historical-risk-simulation.py
python3 scripts/demo-dispute-operations.py
```

The governed-controls demo intentionally builds a one-row cohort so it remains
fast and deterministic. The real runtime default rejects activation until a
simulation has at least 100 eligible rows and 50% label coverage. For this
local proof only, restart `payments-api` with the explicit reduced gate:

```bash
export THAAP_SIMULATION_GATE_MINIMUM_ELIGIBLE_POPULATION=1
export THAAP_SIMULATION_GATE_MINIMUM_LABEL_COVERAGE_PERCENT=0
python3 scripts/demo-governed-controls.py
```

These variables change only the evidence threshold; they do not bypass the
successful-run, exact-candidate, freshness, tenant, creator, or dual-control
checks. Do not use the reduced threshold in a shared or production-like
environment. The default remains `100`/`50` when the variables are absent.

All five clients accept only loopback HTTP, ignore ambient proxies, reject
redirects, provision fresh tenant-owned resources through public APIs, and use
only read-only PostgreSQL queries for final reconciliation. Credentials remain
in environment memory and are never printed.

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
scripts/demo-payment-cancel-void-flow.sh
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
scripts/demo-refund-ledger-flow.sh
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
scripts/demo-webhook-delivery-flow.sh
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

Run the Chapter 42 signed verification, dead-letter, and exact-redelivery proof:

```bash
export THAAP_ADMIN_BOOTSTRAP_TOKEN=change-me-local-admin-bootstrap-token
python3 scripts/demo-webhook-operations.py
```

Start `payments-api` first with local webhook execution enabled and loopback in
`THAAP_WEBHOOK_ALLOWED_HOSTS`. The client accepts only an explicit loopback API
origin, ignores environment proxies, rejects redirects, and provisions a fresh
merchant plus risk manager without printing either credential. Its loopback
receiver echoes the exact signed verification challenge in the final 2xx
response field, returns one permanent 400 for the business event, and then
returns 204 after the operator requests `RECEIVER_REPAIRED` redelivery. The
script proves `VERIFIED -> EXHAUSTED -> generation 2 -> SUCCEEDED`, validates
both HMAC signatures, and reports only event ID, payload digest, statuses, and
generation evidence. Supplying both `THAAP_MERCHANT_API_KEY` and
`THAAP_RISK_OPERATOR_KEY` skips provisioning; supplying only one fails closed.

Run the Chapter 20 complete deterministic processor-catalog demo:

```bash
export THAAP_ADMIN_BOOTSTRAP_TOKEN=change-me-local-admin-bootstrap-token
scripts/demo-processor-outcome-catalog.py
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
scripts/demo-feature-lineage.py
```

Chapter 26 public analyst review-to-label reconciliation:

```bash
THAAP_ADMIN_BOOTSTRAP_TOKEN=... scripts/demo-review-label-loop.py
```

The Chapter 26 demo accepts only a loopback HTTP API, uses public merchant and
risk-operator APIs for the workflow, and uses `docker exec ... psql` only for
read-only reconciliation of payment, review, label, webhook, privacy, tenant,
and unchanged processor/money truth.

Chapter 27 public approval-to-processor/capture reconciliation:

```bash
THAAP_ADMIN_BOOTSTRAP_TOKEN=... scripts/demo-review-approval.py
```

The Chapter 27 demo uses two safe simulator declines to open review, confirms
the third attempt with an approval fixture without calling the processor,
assigns an analyst, then approves that same attempt. Read-only PostgreSQL
reconciliation proves the original REVIEW decision, deterministic attempt-bound
processor identity, one resolution, no fabricated label, automatic capture's
two balanced ledger entries, and exact same/distinct-key terminal replay.
The runner accepts only a loopback API origin, ignores environment proxies,
and rejects redirects. It creates and confirms a manual-capture payment through
public APIs, checks exact idempotent replay and the safe lexical decision
snapshot, then performs a read-only query against the local `thaap-postgres`
container to reconcile all seven typed values and their point-in-time
predicate. It prints only non-secret resource IDs and bounded outcomes.

Run the Chapter 28 fraud- and processor-source authentication proof with:

```bash
export THAAP_ADMIN_BOOTSTRAP_TOKEN=change-me-local-admin-bootstrap-token
python3 scripts/demo-authentication-challenge.py
```

The runner requires the loopback API and local `thaap-postgres`, rejects
redirects and proxy routing, and performs read-only reconciliation. It proves
zero approved funds before completion, source-bound reveal-once next actions,
exact replay, one decision/attempt per cohort, automatic capture for the fraud
source, and manual `REQUIRES_CAPTURE` for the processor source.

Run the Chapter 29 safe RiskContext and all-action parity proof with:

```bash
export THAAP_ADMIN_BOOTSTRAP_TOKEN=change-me-local-admin-bootstrap-token
python3 scripts/demo-risk-context.py
```

The runner accepts only a loopback API origin, rejects redirects and proxy
routing, and uses read-only local PostgreSQL reconciliation. It compares
context-absent and context-present ALLOW, BLOCK, REQUEST_3DS, and REVIEW cohorts,
proves byte-exact confirm replay, complete context/feature/decision lineage,
and zero raw device-canary occurrences across context, snapshot, audit,
webhook, and idempotency rows.

Run the Chapter 30 exact Redis velocity, typed fraud-list, and outage-recovery
proof with:

```bash
export THAAP_ADMIN_BOOTSTRAP_TOKEN=change-me-local-admin-bootstrap-token
python3 scripts/demo-velocity-fraud-lists.py
```

The runner accepts only a loopback API origin and requires local
`thaap-postgres` and `thaap-redis` containers. It proves list BLOCK and
append-only deactivation history, fourth-attempt velocity REVIEW, byte-exact
replay, complete durable lineage, Redis pause→unavailable REVIEW, readiness
degradation and recovery after unpause, and zero raw card/email canaries across
Redis and relevant durable surfaces.

Run the Chapter 31 merchant risk profile, policy, and decision-integration proof
with:

```bash
export THAAP_ADMIN_BOOTSTRAP_TOKEN=change-me-local-admin-bootstrap-token
python3 scripts/demo-merchant-risk-policy.py
```

The runner accepts only a loopback API origin and requires local
`thaap-postgres`. It proves creator/approver dual control, approval without
decision effect, activation changing only future decisions to REQUEST_3DS,
REVIEW, and BLOCK, byte-exact replay, fixture-only preview, immutable historical
lineage, three processor exclusions, and readiness `UP`.

Run the Chapter 32 captured/refunded chargeback and structured-evidence proof
with:

```bash
export THAAP_ADMIN_BOOTSTRAP_TOKEN=change-me-local-admin-bootstrap-token
python3 scripts/demo-chargeback-lifecycle.py
```

The runner accepts only a loopback API origin and requires local
`thaap-postgres`. It proves captured fraud allocation with a balanced dispute
ledger and immutable label, refund-first zero offset without a second debit or
false label, byte-exact idempotency replay, independent external-event
deduplication, explicit simulator source attestation, pre-deadline bounded DRAFT
evidence, and read-only durable reconciliation.

Run the Chapter 33 signal-to-review-to-refund proof with:

```bash
export THAAP_ADMIN_BOOTSTRAP_TOKEN=change-me-local-admin-bootstrap-token
python3 scripts/demo-post-authorization.py
```

Run the Chapter 34 durable suspected-incident and exact 429 rate-limit proof:

```bash
export THAAP_ADMIN_BOOTSTRAP_TOKEN=change-me-local-admin-bootstrap-token
python3 scripts/demo-card-testing-incidents-rate-limits.py
```

Both runners accept only a loopback API origin and use read-only container
reconciliation. Chapter 34 additionally verifies Redis contains only bounded
HMAC-shaped keys and distinguishes ephemeral rate limiting from durable
incident truth.

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

## Product Vision Coverage

Validate every permanent `VP-NNN` challenger promise, its current evidence,
remaining gap, owner phase/slice, DRI, trigger, product demo, done evidence,
review freshness, and master-plan roadmap traceability:

```bash
scripts/vision-coverage-check.py
python3 -m unittest scripts/vision_coverage_check_tests.py
```

The commit-readiness gate runs this automatically. It rejects vague ownership,
missing demos, stale reviews, dangling promise IDs, and loss of the rebaselined
Phase 6–10 product contracts or merchant-facing product destination.

## Executable Artifact Naming

Reject roadmap chapter identifiers in production code, test identities,
migrations, demo scripts, executable fixtures, and runtime messages:

```bash
scripts/executable-artifact-naming-check.py
python3 -m unittest scripts/executable_artifact_naming_check_tests.py
```

Capability names are durable because they describe what the payment-risk system
does. Chapter identifiers remain valid in plans, learning notes, invariant
matrices, closure dossiers, and the chapter-closure validator. The checker also
preserves exact allowlisted references to immutable applied migration history.

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
scripts/executable-artifact-naming-check.py
scripts/vision-coverage-check.py
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
