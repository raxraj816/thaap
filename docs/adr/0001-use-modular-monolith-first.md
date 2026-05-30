# ADR-0001: Use A Modular Monolith First

Date: 2026-05-29
Status: Accepted

## Context

The platform needs clear payment, fraud, ledger, webhook, review, and audit boundaries, but the first milestone is a fast local learning loop. Splitting services too early would add deployment, networking, observability, and schema-ownership overhead before the core lifecycle is proven.

## Decision

Start with `payments-api` as a Java 25 and Spring Boot 4.0.6 modular monolith. Keep module boundaries explicit in code so payment, processor, fraud, rules, review, ledger, webhook, and audit responsibilities can split later if latency, scale, ownership, or operational risk justifies it.

## Consequences

- Local development stays simple while the domain model is still changing.
- Transaction boundaries are easier to reason about in early phases.
- The code must avoid accidental cross-module coupling so later extraction remains possible.
- Later services such as `model-serving-service`, `analytics-worker`, and `fraud-intelligence-agent-service` remain planned, not Day 1 deployables.
