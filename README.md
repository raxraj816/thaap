# THAAP

Threat Hunting & Adaptive Analytics for Payments.

THAAP is a production-shaped payment processing and fraud intelligence simulator. It exists to build payment and fraud primitives end to end: merchant onboarding, customer and payment method creation, PaymentIntent and PaymentAttempt lifecycle, processor simulation, idempotency, webhooks, ledgering, fraud rules, manual review, synthetic data, ML scoring, graph intelligence, and later GenAI-assisted offline investigation.

The authoritative implementation source of truth is [docs/IMPLEMENTATION_MASTER_PLAN.md](docs/IMPLEMENTATION_MASTER_PLAN.md).

## Current Status

Chapter 10 is complete. Confirm is now the first real-time orchestration bridge,
with processor authorization, durable fraud lineage, and ledger side effects
still intentionally deferred to their owner chapters:

- Monorepo structure is in place.
- Documentation entry points are in place.
- ADR folder and initial architecture decisions are in place.
- Architecture diagram is in place.
- Glossary is in place.
- Docker Compose starts PostgreSQL and Redis.
- `apps/payments-api` is scaffolded as a Java 25 / Spring Boot 4.0.6 service.
- Actuator health, liveness, and readiness endpoints are available.
- The local Spring profile connects to PostgreSQL and Redis.
- Merchant tenant context is resolved from `X-Api-Key`.
- Merchant-facing APIs are tenant-scoped.
- Request IDs and standard API error responses are implemented.
- Flyway owns the `payments-api` schema.
- Hibernate validates entity mappings instead of mutating durable schemas.
- The initial migration creates tenant-scoped merchant, customer, payment
  method, payment intent, idempotency, webhook, fraud decision, rule, and review
  tables.
- Merchant API keys and webhook signing secrets are stored as prefixes and
  hashes, not raw secrets.
- Merchant creation requires an admin bootstrap token and contract tests prove
  one-time API key reveal, stored prefix/hash behavior, validation errors, and
  follow-on API-key authentication.
- Customer and payment method contract tests prove raw PII and raw card input
  are not returned or persisted beyond the simulator boundary.
- Simulated card input is reduced to internal fingerprinting plus public safe
  metadata such as token, BIN, last4, funding type, and country.
- `POST /payment_intents` creates tenant-scoped PaymentIntents for owned
  customers, requires `Idempotency-Key`, normalizes currency to uppercase,
  defaults missing capture method to `AUTOMATIC`, starts in
  `REQUIRES_CONFIRMATION`, and creates `payment_intent.created` webhook
  delivery records for configured endpoints.
- PaymentIntent create now accepts optional `description` and constrained
  string metadata, returns `createdAt`/`updatedAt`, rejects unsupported V1
  currencies, and enforces a simulator transaction amount ceiling.
- PaymentIntent state transitions now reject impossible capture/cancel/refund
  mutations before processor, fraud, or ledger side effects can attach to bad
  lifecycle state.
- Chapter 6 contract tests prove PaymentIntent response shape, persisted state,
  idempotency replay, conflict handling, validation errors, default capture
  method, currency normalization, webhook delivery non-duplication, and
  cross-merchant customer rejection.
- Chapter 7 review updated the architecture learning trail and made remaining
  idempotency, attempt, processor, fraud, and ledger risks explicit before
  deeper payment side effects.
- `IdempotencyService` now reserves an `IN_PROGRESS` row before a mutation,
  binds each key to merchant, operation, and request hash, completes the record
  only after the mutation response is durable, rejects in-progress duplicates,
  and releases reservations after failed mutations so retryable failures are
  not cached.
- Chapter 8 service tests prove completed replay, operation mismatch conflict,
  in-progress duplicate rejection, rollback cleanup after mutation failure,
  merchant-scoped key reuse, and in-progress storage shape.
- `POST /payment_intents/{id}/confirm` now creates a tenant-scoped
  `PaymentAttempt` for a valid PaymentIntent and PaymentMethod owned by the
  same merchant/customer.
- Confirm now calls a deterministic in-memory decision-engine interface that
  returns `ALLOW` with reason code `RULES_NOT_ENABLED`, without persisting thin
  fraud lineage before Chapter 12.
- Successful confirm moves the `PaymentAttempt` from `CREATED` to
  `READY_FOR_PROCESSOR` and the `PaymentIntent` from
  `REQUIRES_CONFIRMATION` to `PROCESSING`.
- `GET /payment_attempts/{id}` fetches attempts through merchant scope.
- Chapter 10 contract tests prove successful orchestration, idempotent confirm
  replay, changed-body conflict, invalid status rejection, cross-merchant
  PaymentIntent rejection, cross-merchant PaymentMethod rejection,
  wrong-customer PaymentMethod rejection, one successful concurrent confirm,
  no duplicate attempts, and no persisted `FraudDecision` or processor result.
- Lifecycle contract tests prove cancel is allowed from `REQUIRES_CONFIRMATION`
  while capture/refund are rejected before authorization/capture eligibility.
- Staff-apprenticeship learning notes, deep dives, rubric, weekly review, and
  repetition trackers are in place under `docs/learning/`.

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

The real-time authorization path must be deterministic, bounded, observable,
and auditable. LLMs are explicitly out of that path. Future fraud decisions
must preserve feature lineage and freshness proof: decision latency, feature
schema version, snapshot compute time, oldest feature timestamp, max feature
age, and stale/missing feature names.

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

Local infrastructure and the first runnable service are available:

```bash
export THAAP_ADMIN_BOOTSTRAP_TOKEN=change-me-local-admin-bootstrap-token
export THAAP_HASH_PEPPER=change-me-local-sensitive-value-pepper
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

If an older local PostgreSQL volume was created before Flyway became the schema
owner, reset it before normal local startup:

```bash
docker compose -f infra/docker/compose.yml down --volumes
scripts/local-infra-up.sh
scripts/payments-api-run.sh
```

Chapter 10 learning verification:

```bash
scripts/learning-day-check.sh 2026-06-19 --deep-dive-required
```
