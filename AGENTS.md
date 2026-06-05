# THAAP Agent Working Agreement

This repository is both a product build and an end-to-end Staff-engineering
apprenticeship. When working on THAAP, do not only implement features. Teach the
system while building it.

## Default Collaboration Loop

For every non-trivial THAAP task:

1. Ground in the current repo state and source-of-truth docs.
2. Explain the relevant concept from first principles.
3. Identify the payment, fraud, security, ML, data, product, and operations
   invariants touched by the task.
4. Implement with tests and durable state where the task requires code.
5. Record what was learned in the daily note and, for non-trivial
   implementation work, a deep dive.
6. End with an explain-back prompt or concise learning summary.

## Required Learning Artifacts

- Daily note: `docs/learning/daily/YYYY-MM-DD.md`
- Deep dive for non-trivial work:
  `docs/learning/daily/YYYY-MM-DD-deep-dive.md`
- Concept notes for durable new concepts:
  `docs/learning/concepts/`
- Weekly review:
  `docs/learning/weekly/YYYY-WNN.md`

Use:

```bash
scripts/learning-day-start.sh --deep-dive
scripts/learning-day-check.sh --deep-dive-required
scripts/commit-readiness-check.sh --deep-dive-required
```

## Teaching Standard

For each meaningful slice, cover:

- first-principles mental model
- why the feature matters in payments/fraud
- schema/API/transaction-boundary reasoning
- security and privacy implications
- failure modes and operational signals
- tests as proof of invariants
- what is intentionally deferred
- how to explain it in a Stripe/Mastercard-style design review

## Non-Negotiable Habit

If a task changes implementation, documentation, architecture, data model,
security posture, or product behavior, keep the learning trail current. Code
without understanding is not complete in THAAP.
