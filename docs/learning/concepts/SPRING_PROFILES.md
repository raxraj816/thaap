# Spring Profiles From First Principles

This note explains Spring profiles in THAAP.

## The Core Idea

A Spring profile selects configuration for a runtime environment.

In plain language:

```text
Same application code, different environment settings.
```

Examples:

- local development
- automated tests
- staging
- production

## The Problem It Solves

The app should not use the same dependencies in every context.

Local development should connect to Docker Compose PostgreSQL and Redis.

Fast tests should not require Docker.

Production should eventually use managed infrastructure and stricter settings.

Profiles let us separate those concerns without changing code.

## Why It Matters In Payments And Fraud

Payment systems must be tested quickly, but they also need production-shaped infrastructure.

Profiles let THAAP have both:

- local profile for realistic infrastructure
- test profile for fast isolated test runs

This prevents tests from becoming slow and flaky while preserving local integration realism.

## How THAAP Uses It Today

The default profile is `local`.

`local` connects to PostgreSQL and Redis from Docker Compose.

`test` uses an H2 in-memory database and disables Redis health.

This is why Maven tests can pass without starting Docker.

## Design Tradeoffs

H2 is fast but not identical to PostgreSQL.

That is acceptable for early unit-style and HTTP-layer tests, but it is not enough for migration and database constraint confidence.

After Flyway exists, THAAP should add PostgreSQL integration tests.

## Failure Modes

Profiles can fail if:

- test behavior diverges too far from local/PostgreSQL behavior
- production defaults accidentally use local credentials
- important health checks are disabled outside tests
- schema creation settings differ too much between environments

## Tests And Proof

Current tests prove the `test` profile can start the app with H2.

Local verification proved `payments-api` can start against Docker Compose PostgreSQL and Redis.

## What Remains To Build

- Dedicated integration-test profile for Docker PostgreSQL.
- Production profile defaults.
- Remove local Hibernate schema update after Flyway migrations exist.
