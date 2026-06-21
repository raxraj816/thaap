# Daily Staff Apprenticeship Checklist

Use this checklist for every implementation chapter. The goal is to make Staff-level
engineering understandable, repeatable, and explainable.

## Start Of Work

- Run `scripts/learning-day-start.sh --deep-dive` for non-trivial work, or
  `scripts/learning-day-start.sh` for a light documentation/review day.
- Read the relevant section of `docs/IMPLEMENTATION_MASTER_PLAN.md`.
- Create or update `docs/learning/daily/YYYY-MM-DD.md`.
- Write a readiness review before coding:
  - what the master plan says
  - what already exists
  - what to build now
  - what to defer
  - what tests will prove the slice

## During Implementation

- Keep code aligned with existing repo patterns.
- Protect tenant, security, payment, fraud/ML lineage, migration, and API
  contract invariants.
- Add tests for the riskiest behavior, not only the happy path.
- Record surprising tradeoffs or deferrals in the daily note while they are
  still fresh.

## End Of Chapter Or Work Session

- Run relevant tests or clearly record why they could not be run.
- Run the Senior Staff adversarial review gate from
  `docs/COMMIT_READINESS_CHECKLIST.md`.
- Update the daily note with what changed, what was learned, tests, tradeoffs,
  open questions, and next steps.
- For any non-trivial implementation slice, create or update
  `docs/learning/daily/YYYY-MM-DD-deep-dive.md`.
- Add or update concept notes under `docs/learning/concepts/` when a new
  durable concept appears.
- Confirm source-of-truth docs are updated if the implementation changed a
  decision.

## Brain Injection

Before considering the chapter complete, the learner should answer these from
memory. For important slices, use `docs/learning/EXPLAIN_BACK_TEMPLATE.md`.

1. What problem did we solve?
2. Why did we solve it this way?
3. What production risk did this reduce?
4. What did we defer and why?
5. What tests prove the important invariants?
6. How would this be explained in a Stripe/Mastercard-style design review?

## Ownership Task

For non-trivial implementation chapters, choose one tiny task the learner can own:

- add or improve one test
- trace one request flow in writing
- explain one migration or invariant
- add one validation rule
- improve one concept note
- review one design tradeoff

## Repetition

Add important concepts to `docs/learning/SPACED_REPETITION_TRACKER.md`.
Review weak concepts after 1 day, 1 week, and 1 month.

## Daily Command

Start the learning note for the day:

```bash
scripts/learning-day-start.sh
```

For non-trivial implementation chapters, start with:

```bash
scripts/learning-day-start.sh --deep-dive
```

Run:

```bash
scripts/learning-day-check.sh
```

For non-trivial implementation chapters, run:

```bash
scripts/learning-day-check.sh --deep-dive-required
```
