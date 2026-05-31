# Health Checks And Actuator From First Principles

This note explains health checks and Spring Actuator in THAAP.

## The Core Idea

A health check is an endpoint that tells operators whether a service is alive and ready to do work.

In Spring Boot, Actuator provides standard operational endpoints such as:

```text
/actuator/health
/actuator/health/liveness
/actuator/health/readiness
```

## The Problem It Solves

Without health checks, orchestration systems and humans have to guess whether an application is usable.

A process can be running but still not be ready:

- database connection failed
- Redis is unavailable
- app is still starting
- dependency credentials are wrong

Health checks turn that uncertainty into a machine-readable signal.

## Why It Matters In Payments And Fraud

Payment APIs should not accept traffic when their durable store is unavailable.

For THAAP:

- PostgreSQL health protects payment state, idempotency records, and auditability
- Redis health matters for future velocity counters and rate limiting
- readiness tells callers whether this instance should receive traffic
- liveness tells the runtime whether this process is stuck or dead

## How THAAP Uses It Today

`payments-api` exposes Actuator health endpoints.

The local profile checks PostgreSQL and Redis.

The test profile uses H2 and disables Redis health so unit-style tests do not require Docker.

## Design Tradeoffs

Health checks should be honest but not noisy.

If every optional dependency makes readiness fail, local development becomes fragile. If critical dependencies are ignored, production traffic can hit broken instances.

THAAP's current split is:

- local profile checks real local infrastructure
- test profile isolates tests from Docker

## Failure Modes

Health checks can fail us if:

- they expose too much internal detail
- they ignore critical dependencies
- they include slow checks
- tests depend on unavailable local services

That is why production health detail should be limited and tests should have a controlled profile.

## Tests And Proof

Current tests verify:

- Spring context loads
- health endpoint reports `UP`
- liveness reports `UP`
- readiness reports `UP`

## What Remains To Build

- Add request/trace IDs to operational logs.
- Add metrics that matter for payment/fraud flows.
- Add alerts once deployment targets exist.
