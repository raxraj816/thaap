# THAAP

Threat Hunting & Adaptive Analytics for Payments.

THAAP is a production-shaped payment processing and fraud intelligence simulator. It exists to build payment and fraud primitives end to end: merchant onboarding, customer and payment method creation, PaymentIntent and PaymentAttempt lifecycle, processor simulation, idempotency, webhooks, ledgering, fraud rules, manual review, synthetic data, ML scoring, graph intelligence, and later GenAI-assisted offline investigation.

The authoritative implementation source of truth is [docs/IMPLEMENTATION_MASTER_PLAN.md](docs/IMPLEMENTATION_MASTER_PLAN.md).

## Current Status

Day 2 is complete at local-infrastructure foundation level:

- Monorepo structure is in place.
- Documentation entry points are in place.
- ADR folder and initial architecture decisions are in place.
- Architecture diagram is in place.
- Glossary is in place.
- Docker Compose starts PostgreSQL and Redis.
- `apps/payments-api` is scaffolded as a Java 25 / Spring Boot 4.0.6 service.
- Actuator health, liveness, and readiness endpoints are available.
- The local Spring profile connects to PostgreSQL and Redis.

## Repository Layout

```text
apps/
  payments-api/                 Java 25 + Spring Boot backend, primary V1 deployable
  analyst-dashboard-ui/         React analyst console, added after backend core flows
services/
  model-serving-service/        Python FastAPI model scoring service, later phase
  analytics-worker/             Event analytics and replay worker, later phase
  fraud-intelligence-agent-service/
                                Offline GenAI investigation service, later phase
infra/
  docker/                       Local Docker Compose and container config
  kubernetes/                   Local Kubernetes and cloud-shaped manifests, later
scripts/                        Developer utilities and repeatable commands
docs/
  adr/                          Architecture decision records
  architecture/                 Detailed architecture pack
  diagrams/                     Mermaid architecture diagrams
  learning/                     Daily learning notes
  references/                   Background research and source materials
```

## Product Boundaries

V1 is a card-payment simulator with integrated fraud detection. It does not move real money, persist raw PAN, persist CVV, or claim PCI certification.

The real-time authorization path must be deterministic, bounded, observable, and auditable. LLMs are explicitly out of that path.

## Canonical Terms

- Payment container: `PaymentIntent`
- Scoring and audit unit: `PaymentAttempt`
- Fraud actions: `ALLOW`, `BLOCK`, `REQUEST_3DS`, `REVIEW`
- Cancel before settlement: `CANCELED`
- Post-capture customer claim: `DISPUTED` / chargeback

See [docs/GLOSSARY.md](docs/GLOSSARY.md) for the full glossary.

## Documentation Map

- [Master plan](docs/IMPLEMENTATION_MASTER_PLAN.md)
- [Product vision](docs/VISION.md)
- [Architecture overview](docs/ARCHITECTURE.md)
- [Architecture diagram](docs/diagrams/day-1-system-context.mmd)
- [Glossary](docs/GLOSSARY.md)
- [ADRs](docs/adr/README.md)
- [Daily learning notes](docs/learning/README.md)

## Local Development

Local infrastructure and the first runnable service are now available:

```bash
scripts/local-infra-up.sh
mvn -f apps/payments-api/pom.xml -Dmaven.repo.local=.m2/repository test
scripts/payments-api-run.sh
```

Then verify:

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/health/liveness
curl http://localhost:8080/actuator/health/readiness
```
