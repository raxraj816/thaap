# ADR-0003: Keep LLMs Out Of Real-Time Authorization

Date: 2026-05-29
Status: Accepted

## Context

Payment confirmation requires bounded latency, deterministic behavior, auditability, and predictable fallback. LLM calls are probabilistic, may be slow, and are harder to make safe in synchronous authorization.

## Decision

No LLM or GenAI agent may run inside the real-time payment authorization path. Real-time fraud decisions use deterministic rules and, later, bounded model scoring with timeout fallback.

GenAI services are limited to offline investigation, explanation, simulation, and recommendation workflows with human approval for operational changes.

## Consequences

- Authorization latency and behavior stay controllable.
- GenAI can still assist analysts and fraud engineers outside the synchronous path.
- Agent tool calls must be audited and must not directly mutate live payment, rule, or model state.
