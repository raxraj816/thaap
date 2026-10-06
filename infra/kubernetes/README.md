# Kubernetes Infrastructure

**Empty on purpose. The current deployment target is ECS Fargate, defined in
[`../terraform/`](../terraform/).**

This directory is retained rather than deleted, by explicit instruction, because EKS remains a
real path rather than a rejected one — and a deleted directory records neither the decision nor
the trigger that would reverse it.

## Why ECS Fargate is the current target

`ADR-0007` originally mapped the reference cloud target to EKS with ECS as a simpler alternative.
`ADR-0012` supersedes that mapping for Phase 6.5. The argument is cost against value at this
scale: an EKS control plane is roughly $75/month before a single workload runs, plus upgrade,
addon and IRSA toil, against a total infrastructure estimate of about $134/month. None of it buys
anything the product needs today. THAAP runs two stateless API tasks, one broker, a database and
a cache — a shape Fargate expresses directly.

## What would move it to EKS

Not a preference, and not a headcount. A measured requirement Fargate cannot meet:

- a throughput or latency target that Fargate task startup or scaling behaviour demonstrably
  misses, or
- a multi-service topology whose scheduling, affinity or sidecar needs Fargate cannot express, or
- an availability requirement from a real design partner that needs orchestration Fargate does
  not offer.

Until one of those is measured rather than anticipated, migrating would be work that produces no
product truth — which is the same test every other deferral in this repository is held to.

## If that trigger fires

The Terraform topology is the specification to port, not a thing to translate mechanically. Three
parts of it are load-bearing and would need equivalents rather than copies:

- the three-layer exposure control in Section 5.1 — an ingress controller changes where the
  second layer lives, and it must not simply disappear;
- readiness bound to `/actuator/health/readiness` rather than to liveness, with the idle-state
  behaviour `ER-0104` corrected;
- the seven-prefix routing contract, which would gain a fifth router and must stay in
  `scripts/routing-contract-check.py`.
