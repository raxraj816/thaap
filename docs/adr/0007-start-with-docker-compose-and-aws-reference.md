# ADR-0007: Start With Docker Compose And Use AWS As Reference Cloud

Date: 2026-05-29
Status: Accepted

## Context

The platform needs local-first development and a production-shaped cloud mapping. Starting with cloud deployment too early would slow the payment/fraud learning loop.

## Decision

Use local Docker Compose for all V1 development until core flows are stable. Add local Kubernetes later. Use AWS as the production-shaped reference cloud, with EKS as the primary mapping and ECS/Fargate as a simpler alternative.

## Consequences

- Day 2 starts with PostgreSQL and Redis in Docker Compose.
- Cloud-specific infrastructure waits until local behavior is proven.
- Architecture docs should map PostgreSQL to RDS/Aurora, Redis to ElastiCache, Kafka to MSK, object storage to S3, and containers to EKS.
