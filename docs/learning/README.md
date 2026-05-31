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
  DAILY_NOTE_TEMPLATE.md
  concepts/
    CONCEPT_NAME.md
  daily/
    YYYY-MM-DD.md
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

## What To Capture

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

If a learning note discovers a decision that changes implementation, update `docs/IMPLEMENTATION_MASTER_PLAN.md` rather than leaving the decision only in learning notes.

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
- [API Error Contracts And Request IDs](concepts/API_ERROR_CONTRACTS_AND_REQUEST_IDS.md)
- [API Validation From First Principles](concepts/API_VALIDATION.md)
- [Docker Compose Local Infrastructure From First Principles](concepts/DOCKER_COMPOSE_LOCAL_INFRASTRUCTURE.md)
- [Health Checks And Actuator From First Principles](concepts/HEALTH_CHECKS_AND_ACTUATOR.md)
- [Idempotency From First Principles](concepts/IDEMPOTENCY.md)
- [Merchant Tenant Isolation From First Principles](concepts/MERCHANT_TENANT_ISOLATION.md)
- [Payment Method Tokenization From First Principles](concepts/PAYMENT_METHOD_TOKENIZATION.md)
- [PaymentIntent From First Principles](concepts/PAYMENT_INTENT.md)
- [PostgreSQL And Redis From First Principles](concepts/POSTGRESQL_AND_REDIS.md)
- [Spring Profiles From First Principles](concepts/SPRING_PROFILES.md)
- [Webhooks From First Principles](concepts/WEBHOOKS.md)
