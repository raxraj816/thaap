# THAAP Learning Notes

This folder is for implementation-time learning notes.

Learning docs are not implementation source of truth. They capture what we learned while building, questions that came up, tradeoffs we considered, and links back to the authoritative docs.

Authoritative implementation decisions belong in:

- `docs/IMPLEMENTATION_MASTER_PLAN.md`

Architecture decisions and structure belong in:

- `docs/ARCHITECTURE.md`
- `docs/architecture/`

## Structure

```text
docs/learning/
  README.md
  DAILY_APPRENTICESHIP_CHECKLIST.md
  DAILY_NOTE_TEMPLATE.md
  DEEP_DIVE_TEMPLATE.md
  EXPLAIN_BACK_TEMPLATE.md
  SENIOR_STAFF_ML_ARCHITECT_RUBRIC.md
  SPACED_REPETITION_TRACKER.md
  WEEKLY_ARCHITECTURE_REVIEW_TEMPLATE.md
  concepts/
    CONCEPT_NAME.md
  daily/
    YYYY-MM-DD.md
    YYYY-MM-DD-deep-dive.md
  weekly/
    YYYY-WNN.md
```

## Daily Note Naming

Use:

```text
docs/learning/daily/YYYY-MM-DD.md
```

Example:

```text
docs/learning/daily/2026-05-29.md
```

For non-trivial implementation chapters, also create:

```text
docs/learning/daily/YYYY-MM-DD-deep-dive.md
```

Use `docs/learning/DEEP_DIVE_TEMPLATE.md` for this artifact.

## What To Capture

Use [DAILY_APPRENTICESHIP_CHECKLIST.md](DAILY_APPRENTICESHIP_CHECKLIST.md)
at the start and end of each implementation chapter or work session.

Start a light learning day:

```bash
scripts/learning-day-start.sh
```

Start a non-trivial implementation chapter:

```bash
scripts/learning-day-start.sh --deep-dive
```

The quick local check is:

```bash
scripts/learning-day-check.sh
```

For non-trivial implementation chapters:

```bash
scripts/learning-day-check.sh --deep-dive-required
```

Each daily note should include:

- what we built
- payment concepts learned
- fraud concepts learned
- security concepts learned
- architecture decisions touched
- tests or verification done
- bugs or surprises
- open questions
- next steps

Each deep dive should make Staff-level code understandable end to end. It should
include:

- a plain-English design brief
- file-by-file code map
- request/data flow
- database invariants
- security/privacy invariants
- fraud/ML relevance
- tests as proof
- Staff engineer review notes
- explain-back questions
- code-reading drills
- a tiny extension task
- an interview narrative

If a learning note discovers a decision that changes implementation, update `docs/IMPLEMENTATION_MASTER_PLAN.md` rather than leaving the decision only in learning notes.

## Apprenticeship Loop

Use this loop for non-trivial work:

```text
Build -> Explain -> Trace -> Explain Back -> Modify -> Review -> Record -> Revisit
```

- **Explain back:** use `EXPLAIN_BACK_TEMPLATE.md` after reading a deep dive.
- **Modify:** complete one tiny ownership task from the deep dive or daily
  checklist.
- **Review:** update the weekly architecture review.
- **Revisit:** add dense concepts to `SPACED_REPETITION_TRACKER.md`.

## Weekly Review

Once per week, create:

```text
docs/learning/weekly/YYYY-WNN.md
```

Use `WEEKLY_ARCHITECTURE_REVIEW_TEMPLATE.md`.

Run:

```bash
scripts/learning-week-check.sh YYYY-WNN
```

Update `SENIOR_STAFF_ML_ARCHITECT_RUBRIC.md` during the weekly review. This is
how we measure whether the project is increasing independent Staff-level
judgment, not only producing code and notes.

## Concept Notes

Use `docs/learning/concepts/` for first-principles explanations of concepts we are learning while building THAAP.

Every non-trivial feature, platform primitive, architectural pattern, or external tool we build or depend on should get a concept note. Code proves that something works; concept notes explain why it exists and how to reason about it.

Concept notes should explain:

- what the concept means
- what problem it solves
- why it matters in payment and fraud systems
- how THAAP implements or plans to implement it
- design tradeoffs
- failure modes
- tests and proof
- what remains to build

Current concept notes:

- [Concept Note Template](concepts/CONCEPT_NOTE_TEMPLATE.md)
- [Agentic AI Guardrails For Fraud Operations From First Principles](concepts/AGENTIC_AI_GUARDRAILS_FOR_FRAUD_OPERATIONS.md)
- [API Error Contracts And Request IDs](concepts/API_ERROR_CONTRACTS_AND_REQUEST_IDS.md)
- [API Credential Lifecycle From First Principles](concepts/API_CREDENTIAL_LIFECYCLE.md)
- [API Validation From First Principles](concepts/API_VALIDATION.md)
- [Database Migrations And Flyway](concepts/DATABASE_MIGRATIONS_AND_FLYWAY.md)
- [Docker Compose Local Infrastructure From First Principles](concepts/DOCKER_COMPOSE_LOCAL_INFRASTRUCTURE.md)
- [Health Checks And Actuator From First Principles](concepts/HEALTH_CHECKS_AND_ACTUATOR.md)
- [Idempotency From First Principles](concepts/IDEMPOTENCY.md)
- [Merchant Tenant Isolation From First Principles](concepts/MERCHANT_TENANT_ISOLATION.md)
- [Payment Method Tokenization From First Principles](concepts/PAYMENT_METHOD_TOKENIZATION.md)
- [PaymentIntent From First Principles](concepts/PAYMENT_INTENT.md)
- [PostgreSQL And Redis From First Principles](concepts/POSTGRESQL_AND_REDIS.md)
- [Spring Profiles From First Principles](concepts/SPRING_PROFILES.md)
- [Webhooks From First Principles](concepts/WEBHOOKS.md)
