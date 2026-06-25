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
