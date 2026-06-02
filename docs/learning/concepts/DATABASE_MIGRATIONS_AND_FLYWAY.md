# Database Migrations And Flyway

## What This Means

A database migration is a versioned, repeatable record of how the schema changes
over time. Flyway applies migrations in order and records which versions have
successfully run in `flyway_schema_history`.

## Problem It Solves

Hibernate can generate or update tables for early scaffolding, but payment
systems need deterministic schema history. Migrations make table definitions,
constraints, indexes, and rollout order explicit.

## Why It Matters For Payments And Fraud

- Payment APIs rely on durable invariants such as idempotency uniqueness.
- Tenant isolation should be enforced by storage constraints as well as API
  checks.
- Fraud and review workflows need stable historical data for investigations.
- Operational teams need to know exactly which schema version is running.

## THAAP Implementation

- `payments-api` includes `spring-boot-starter-flyway`.
- PostgreSQL support is provided by `flyway-database-postgresql`.
- Versioned SQL migrations live in
  `apps/payments-api/src/main/resources/db/migration`.
- Hibernate uses `ddl-auto: validate` so application startup fails if entities
  and migrations drift apart.
- The Day 4 migration creates the initial tenant-scoped schema and hot lookup
  indexes.
- Stable payment/security fields such as API key hashes, webhook retry metadata,
  safe card metadata, and capture method are included in the first schema.

## Design Tradeoffs

- The initial migration starts from the current domain model, not the full
  future architecture. Future payment attempt, ledger, outbox, audit, and ML
  fields should arrive in their own migrations.
- Fraud decision fields that depend on `payment_attempts` are intentionally
  deferred until they can be backed by real foreign keys.
- Composite foreign keys add a little schema verbosity, but they prevent child
  rows from attaching to objects owned by another merchant.
- Existing local databases created before Flyway must be reset because those
  schemas were temporary scaffolding, not a migration baseline.

## Failure Modes

- A migration can fail on startup if SQL is invalid for the target database.
- A migration can pass H2 but fail PostgreSQL if tests rely only on H2-specific
  behavior.
- Entity annotations can drift from migration SQL; Hibernate validation catches
  many of these mismatches.
- Missing indexes can leave correct APIs too slow for merchant-scoped hot paths.

## Tests And Proof

- `DatabaseMigrationTests` checks Flyway schema history and table/index
  presence.
- Constraint tests verify idempotency uniqueness and tenant-safe composite
  foreign keys.
- A PostgreSQL Testcontainers migration test runs when Docker is available and
  skips cleanly otherwise.
- The full Maven test suite proves Flyway migration and Hibernate validation
  run together before the application context is considered healthy.

## What Remains

- Add later migrations for payment attempts, ledger entries, outbox events,
  audit logs, fraud labels, and ML features.
- Harden idempotency reservation so duplicate-key handling is part of the
  runtime flow, not only the schema.
