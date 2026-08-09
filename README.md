# THAAP

### Threat Hunting & Adaptive Analytics for Payments

**Fraud decisions you can explain, test, and roll back.**

THAAP is a production-shaped payments and fraud-risk platform: real-time
decisioning on the authorization path, an analyst workspace for the humans who
work the queue, governed rules with dual control, and complete decision replay.
Every decision it makes can be reconstructed months later — the exact feature
values, the exact rule versions, the exact outcome, and who changed what.

It is built to sit alongside an existing PSP rather than replace its
money-moving, acquiring, and settlement responsibilities.

> **THAAP is a simulator.** It processes no real card data, is not PCI-scoped,
> and moves no real money. Version `1.0.0` means six phases of payment, fraud,
> lineage, tenancy and operational correctness are complete, proven and
> deployable — not that the system is certified or connected to an acquirer.
> See [CHANGELOG.md](CHANGELOG.md) for what a version number does and does not
> claim.

## Run it

```bash
git clone https://github.com/raxraj816/thaap.git && cd thaap
docker compose -f infra/docker/compose.yml up -d --build
```

| Surface           | URL                                     |
| ----------------- | --------------------------------------- |
| Analyst workspace | <http://localhost:8081>                 |
| Merchant API      | <http://localhost:8080>                 |
| Version / commit  | <http://localhost:8080/actuator/info>   |
| Readiness         | <http://localhost:8080/actuator/health/readiness> |

Then drive a full fraud decision through it — merchant, customer, payment
method, confirm, decision, feature lineage, review case:

```bash
export THAAP_ADMIN_BOOTSTRAP_TOKEN=local-admin-bootstrap-token
scripts/demo-fraud-decision-loop.py
```

That token is the compose default; override it in both places to change it. The
demos talk to the running stack over loopback only, and each one prints the
evidence it reconciled rather than a success message.

Also worth running: `scripts/demo-payment-investigation.py`
(reconstruct why one payment was decided the way it was),
`scripts/demo-card-testing-incidents-rate-limits.py` (watch an enumeration
attack get detected and throttled), and
`scripts/demo-analyst-workspace.py` (queue, assignment, resolution).
`scripts/compose-stack-check.sh` proves the whole stack starts from a cold
clone.

## What it does

- **Decides.** Deterministic, bounded, observable rule evaluation before any
  processor call, producing `ALLOW`, `BLOCK`, `REQUEST_3DS`, or `REVIEW` with
  fixed precedence. LLMs are explicitly excluded from this path.
- **Explains.** Every decision carries an immutable typed feature snapshot and
  ordered per-rule results, so any decision can be reconstructed exactly —
  including which features were stale or missing.
- **Runs the payment lifecycle.** PaymentIntents, attempts, authorization,
  automatic and manual capture, cancel/void, refunds with exactly reversing
  balanced ledger entries, chargebacks and dispute operations.
- **Gives analysts a workspace.** Review queues, saved views, bounded bulk
  assignment, case resolution, labels, notes, payment timelines, safe
  same-merchant pivots, and denominator-honest insights.
- **Governs change.** Dual-controlled rule activation, narrow time-boxed safety
  exceptions, historical simulation against real past traffic, and rollback.
- **Defends against abuse.** Velocity counters, fraud lists, merchant risk
  policies, and durable card-testing incident detection with rate limits.
- **Keeps a durable record.** Transactional outbox → Kafka → inbox-idempotent
  consumption → rebuildable PostgreSQL projections, plus point-in-time offline
  replay datasets.
- **Ships a developer product.** Date-versioned generated OpenAPI contract, a
  stable public error catalogue, signed at-least-once webhooks, and a supported
  Python sandbox SDK.

## How it is built

```text
apps/
  payments-api/                 Java 25 + Spring Boot 4 backend, the primary deployable
  analyst-dashboard-ui/         React + Vite analyst console
clients/
  python/thaap_sdk/             Supported Python sandbox SDK
services/                       Placeholders for later phases — no implementation today
  model-serving-service/        Python FastAPI model scoring service
  analytics-worker/             Event analytics and replay worker
  fraud-intelligence-agent-service/
                                Offline GenAI investigation service
infra/
  docker/                       Compose stack for the whole system
  kubernetes/                   Cloud-shaped manifests, later phase
openapi/                        Generated, date-versioned public API contracts
scripts/                        Executable demos and repository gates
docs/
  adr/                          Architecture decision records
  architecture/                 Detailed architecture pack
  diagrams/                     Mermaid architecture diagrams
  reviews/                      Per-chapter invariant matrices and closure dossiers
  learning/                     Daily learning notes
  references/                   Background research and source materials
```

PostgreSQL, Redis, and Kafka back the API.

Correctness is enforced rather than asserted. Chapters close only against a
checked [invariant-proof matrix](docs/INVARIANT_PROOF_REVIEW_PROCESS.md) with
forced PostgreSQL interleavings and a separate hostile review, recorded under
`docs/reviews/`; findings that escaped an earlier review are kept permanently in
the [escaped-review register](docs/ESCAPED_REVIEW_REGISTER.md) rather than
quietly fixed. `scripts/commit-readiness-check.sh` runs the gates, including
`scripts/vision-coverage-check.py`, which fails if a permanent product promise
loses its owner or its evidence.

## Product boundaries

V1 is a card-payment simulator with integrated fraud detection. It does not
move real money, persist raw PAN, persist CVV, or claim PCI certification.

The real-time authorization path must be deterministic, bounded, observable,
and auditable. LLMs are explicitly out of that path. Fraud decisions must
preserve feature lineage and freshness proof: decision latency, feature schema
version, snapshot compute time, oldest feature timestamp, max feature age, and
stale/missing feature names.

## Canonical terms

- Payment container: `PaymentIntent`
- Scoring and audit unit: `PaymentAttempt`
- Fraud actions: `ALLOW`, `BLOCK`, `REQUEST_3DS`, `REVIEW`
- Cancel before settlement: `CANCELED`
- Post-capture customer claim: `DISPUTED` / chargeback

Full [glossary](docs/GLOSSARY.md).

## Documentation

| Document | What it is |
| -------- | ---------- |
| [Implementation status](docs/STATUS.md) | Chapter-by-chapter record of what is built and proven |
| [Master plan](docs/IMPLEMENTATION_MASTER_PLAN.md) | Authoritative cross-phase implementation source |
| [Phase 6.5 plan](docs/PHASE_6_5_DETAILED_IMPLEMENTATION_PLAN.md) | The active phase — release and AWS deployment |
| [Vision](docs/VISION.md) and [coverage matrix](docs/THAAP_VISION_COVERAGE_MATRIX.md) | The 23 permanent product promises and who owns each |
| [Architecture](docs/ARCHITECTURE.md) | System design, with the detailed pack under `docs/architecture/` |
| [System context diagram](docs/diagrams/day-1-system-context.mmd) | Mermaid architecture diagram |
| [ADRs](docs/adr/README.md) | Architecture decision records |
| [Developer sandbox guide](docs/DEVELOPER_SANDBOX_GUIDE.md) | Integrating against the public API |
| [Runtime configuration](docs/references/RUNTIME_CONFIGURATION_REFERENCE.md) | Every setting the application reads |
| [Changelog](CHANGELOG.md) | Released versions and what each is licensed to do |
| [Technical debt register](docs/TECHNICAL_DEBT_REGISTER.md) | Open and resolved debt |
| [Learning notes](docs/learning/README.md) | The staff-engineering apprenticeship trail behind the build |

## Local development

Running the API directly against local infrastructure, rather than in a
container:

```bash
export THAAP_ADMIN_BOOTSTRAP_TOKEN=change-me-local-admin-bootstrap-token
export THAAP_HASH_PEPPER=change-me-local-sensitive-value-pepper
export THAAP_HASH_PEPPER_VERSION=v1
export THAAP_CORRELATION_PEPPER=change-me-local-sensitive-value-pepper
scripts/local-infra-up.sh
mvn -f apps/payments-api/pom.xml -Dmaven.repo.local=.m2/repository test
scripts/payments-api-run.sh
```

Verify it came up:

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/health/liveness
curl http://localhost:8080/actuator/health/readiness
```

If a PostgreSQL volume predates the current schema owner and startup fails,
reset it once with
`docker compose -f infra/docker/compose.yml down --volumes`, then start again.

Shell demos against a running API, for the core money paths:

```bash
scripts/demo-confirm-flow.sh                                  # merchant → confirm → decision
THAAP_DEMO_CARD=4999990000000002 scripts/demo-confirm-flow.sh # issuer decline
THAAP_DEMO_CARD=4999990000000008 scripts/demo-confirm-flow.sh # 3DS required
scripts/demo-payment-cancel-void-flow.sh                      # automatic capture and authorization void
scripts/demo-refund-ledger-flow.sh                            # durable refund with exact ledger reversal
scripts/demo-webhook-delivery-flow.sh                         # signed callback that fails, retries, succeeds
```

Learning-trail verification, which gates the apprenticeship notes:

```bash
scripts/learning-day-check.sh 2026-06-21 --deep-dive-required
```

<details>
<summary><b>Completion record</b> — machine-checked chapter and phase status</summary>

These markers are read by `scripts/chapter-closure-check.py` and
`scripts/phase-closure-check.py`. The narrative behind them is in
[docs/STATUS.md](docs/STATUS.md).

Phase 1 through Phase 4 are complete. Phase 4.5 is complete.

Phase 5 implementation status: COMPLETE

Phase 6 implementation status: COMPLETE

Chapter 19 is complete.
Chapter 20 is complete.
Chapter 21 is complete.
Chapter 22 is complete.
Chapter 23 is complete.
Chapter 24 is complete.
Chapter 25 is complete.
Chapter 26 is complete.
Chapter 27 is complete.
Chapter 28 is complete.
Chapter 29 is complete.
Chapter 30 is complete.
Chapter 31 is complete.
Chapter 32 is complete.
Chapter 33 is complete.
Chapter 34 is complete.
Chapter 35 is complete.
Chapter 36 is complete.
Chapter 37 is complete.
Chapter 38 is complete.
Chapter 39 is complete.
Chapter 40 is complete.
Chapter 41 is complete.
Chapter 42 is complete.
Chapter 43 is complete.
Chapter 43R is complete.
Chapter 44 is complete.
Chapter 45 is complete.
Chapter 46 is complete.
Chapter 47 is complete.
Chapter 48 is complete.
Chapter 49 is complete.
Chapter 50 is complete.
Chapter 51 is complete.
Chapter 52 is complete.
Chapter 53 is complete.
Chapter 54 is complete.
Chapter 55 is complete.
Chapter 56 is complete.
Chapter 57 is complete.
Chapter 58 is complete.
Chapter 59 is complete.
Chapter 60 is complete.
Chapter 61 is complete.
Chapter 62 is complete.

Phase 6.5 implementation status: CHAPTERS 60–62 COMPLETE. THAAP now builds as
digest-pinned container images, starts end to end under `docker compose up`,
states which commit it is running at `/actuator/info`, and refuses to start a
deployed instance whose secret contract is incomplete. `1.0.0` is the first
released version and `CHANGELOG.md` describes it; `1.0.0` denotes a complete,
proven, deployable simulator and not a system licensed to process real payments.
Chapters 63–66 — Terraform topology, first deploy, seeded demo tenant and phase
closure — are the remaining work, and the AWS account, domain and hosted zone
they depend on are external prerequisites.
A 2026-08-08 review of the Phase 6.5 plan against the three chapters that had
shipped raised `ER-0105` and `ER-0106`. The first: the plan's decision to use one
integrated matrix never said when that matrix is drafted, and the matrix checker
could not represent a frozen-but-unproven matrix at all, so a pre-implementation
matrix could not be committed. Both are fixed, and
`docs/reviews/PHASE_6_5_INVARIANT_PROOF_MATRIX.md` is now frozen with ten
`PENDING` rows covering Chapters 63–66. The second: the plan called the
deployment "private and invite-only" and named no mechanism, relying implicitly
on an unpublished hostname that Certificate Transparency publishes within minutes
of certificate issuance. Chapter 63 now owns a three-layer exposure control, and
until it exists the deployment is not private and no document here describes it
as one.

</details>
