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

Generate Chapter 16 synthetic payment-risk scenarios through the public API:

```bash
export THAAP_ADMIN_BOOTSTRAP_TOKEN=change-me-local-admin-bootstrap-token
scripts/generate-synthetic-scenarios.py --seed chapter-16-demo
```

Useful options:

```bash
scripts/generate-synthetic-scenarios.py \
  --seed chapter-16-demo \
  --base-url http://localhost:8080 \
  --output build/reports/synthetic-scenarios/chapter-16-demo-summary.json
```

The generator creates approved, retryable-decline, and basic card-testing
scenario families using merchant-facing APIs only. It reads the bootstrap token
directly from `THAAP_ADMIN_BOOTSTRAP_TOKEN`, accepts only loopback API targets,
and refuses to run against a shared or production endpoint. Its JSON summary
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
scripts/commit-readiness-check.sh YYYY-MM-DD --deep-dive-required --run-tests
```

The readiness gate prints the Senior Staff adversarial review lenses. Use that
output to classify reviewer-style findings as fixed now, accepted risk,
deferred, or rejected as not applicable before handoff.
