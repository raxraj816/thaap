# PostgreSQL And Redis From First Principles

This note explains why THAAP uses both PostgreSQL and Redis.

## The Core Idea

PostgreSQL and Redis solve different storage problems.

PostgreSQL is the durable source of truth.

Redis is a fast operational store for temporary or derived state.

In THAAP:

```text
PostgreSQL remembers what happened.
Redis helps answer fast operational questions.
```

## The Problem They Solve

Payment systems need durable records:

- merchants
- customers
- payment methods
- payment intents
- idempotency records
- webhook deliveries
- fraud decisions
- ledger entries

Those belong in PostgreSQL.

Fraud systems also need fast counters:

- attempts from this customer in 5 minutes
- attempts from this card fingerprint in 1 hour
- request counts by API key
- rate-limit windows

Those fit Redis.

## Why It Matters In Payments And Fraud

If durable payment state lives only in Redis, it can be lost.

If every velocity counter is computed from PostgreSQL on every request, real-time fraud checks become slow.

The split is:

```text
Durable truth -> PostgreSQL
Fast temporary counters -> Redis
```

## How THAAP Uses It Today

PostgreSQL is configured for local durable application state.

Redis is configured for future idempotency acceleration, velocity counters, and rate limits.

Current idempotency truth is still PostgreSQL. Redis must not be the only copy of important payment state.

## Design Tradeoffs

Using both systems adds operational complexity.

The benefit is that each system is used for its strengths:

- PostgreSQL for transactions, constraints, and durable queries
- Redis for low-latency counters and cache-like data

This is a production-shaped pattern worth learning early.

## Failure Modes

Storage design can fail if:

- Redis becomes the only durable copy of important state
- PostgreSQL queries become too slow for real-time fraud decisions
- cache values outlive their correctness window
- test profiles accidentally depend on local Redis
- local and production configs drift

## Tests And Proof

Current verification includes:

- local PostgreSQL and Redis health through Docker Compose
- `payments-api` local profile connects to both
- test profile uses H2 and disables Redis health
- backend tests pass without requiring Docker

## What Remains To Build

- Flyway migrations for PostgreSQL schema.
- Redis-backed velocity counters.
- Redis-backed rate limits.
- Optional Redis idempotency acceleration with PostgreSQL as truth.
- PostgreSQL integration tests against Docker Compose.
