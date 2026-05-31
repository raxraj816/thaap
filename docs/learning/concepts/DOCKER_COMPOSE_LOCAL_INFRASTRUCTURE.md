# Docker Compose Local Infrastructure From First Principles

This note explains why THAAP starts with Docker Compose for local infrastructure.

## The Core Idea

Docker Compose lets us run supporting infrastructure locally with repeatable configuration.

In THAAP, that means:

```text
developer machine
  -> PostgreSQL container
  -> Redis container
  -> payments-api connects to both
```

The goal is to make the local environment feel like a small version of production.

## The Problem It Solves

Without local infrastructure automation, each developer has to install and configure databases manually.

That creates drift:

- different PostgreSQL versions
- different Redis versions
- different ports
- different credentials
- missing volumes
- inconsistent startup behavior

Docker Compose gives us one repeatable command path for the services THAAP needs.

## Why It Matters In Payments And Fraud

Payment systems rely on durable state and fast operational state.

We cannot seriously test payment flows with only in-memory objects. We need:

- PostgreSQL for durable payment, merchant, idempotency, webhook, ledger, and fraud state
- Redis for future velocity counters, rate limits, and idempotency acceleration

Local Compose helps us learn production-shaped boundaries before cloud deployment.

## How THAAP Uses It Today

THAAP uses Docker Compose under `infra/docker/` to run PostgreSQL and Redis locally.

Scripts under `scripts/` start, stop, and inspect local infrastructure.

The `local` Spring profile connects `payments-api` to those services.

## Design Tradeoffs

Docker Compose is simpler than Kubernetes and cloud infrastructure.

That is the right first step because the domain model is still changing. Kubernetes would add operational complexity before payment lifecycle behavior is stable.

The tradeoff is that Compose does not fully model cloud networking, IAM, managed database behavior, autoscaling, or deployment rollout patterns.

## Failure Modes

Local infrastructure can fail if:

- containers are not running
- ports conflict with local services
- volumes contain stale data
- credentials drift from app config
- tests accidentally depend on Compose when they should use test isolation

THAAP mitigates this with scripts, health checks, and a separate test profile.

## Tests And Proof

Current verification includes:

- PostgreSQL and Redis containers reaching healthy status
- `payments-api` starting against local PostgreSQL and Redis
- health endpoints reporting readiness/liveness

## What Remains To Build

- Docker Compose PostgreSQL integration tests after Flyway migrations exist.
- Optional local observability stack.
- Later Kubernetes manifests once Compose flows stabilize.
