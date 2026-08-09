# Phase 6.5 Detailed Implementation Plan

- Phase: Phase 6.5 — Production Release And AWS Deployment
- Status: IN PROGRESS — Chapters 60–62 complete; Chapter 63 gated on Section 17
  decisions 1, 2, 3 and 5 and on the external prerequisites in Section 13.1
- Entry gate: SATISFIED by Phase 6 / Chapter 59 closure and merge
- Promise freeze: CONFIRMED for Section 3. The build, release and runtime
  promises have been implemented and proven; the deployment promises are frozen
  and unbuilt. Section 17 remains a decision list, not a freeze gate, and its
  open items are named below rather than blocking the whole document
- Merchant-facing product destination: FOUNDER CONFIRMED 2026-07-27
- Market destination: FOUNDER CONFIRMED 2026-08-08 — global, multi-rail, with
  India and its UPI rail a first-class market rather than the sole target
- Primary deployables: `apps/payments-api`, `apps/analyst-dashboard-ui`
- Technical DRIs: Payments Core and Security/Platform
- Operations DRI: Founding Staff Engineering
- Vision coverage: the deployment-and-reach portion of `VP-016`; no new product
  promise is claimed by this phase
- Planned chapters: Chapters 60–66
- Planned forward migrations: none — Phase 6.5 adds no schema
- Next dependency-ready chapter: Chapter 63, once decisions 1, 2, 3 and 5 are
  confirmed and the external prerequisites exist
- Frozen proof artifacts: `docs/reviews/CHAPTER_60_INVARIANT_PROOF_MATRIX.md`
  (closed) and `docs/reviews/PHASE_6_5_INVARIANT_PROOF_MATRIX.md` (frozen
  2026-08-08, all rows `PENDING`, covering Chapters 63–66). **One matrix for the
  phase, per Section 13.1 — not one per chapter.** This line said the opposite
  until 2026-08-08; it was written before Section 13.1 replaced per-chapter
  matrices and was never updated, which is the drift `ER-0105` records
- Deployment region: `ap-south-1` (Mumbai) first, region-parameterised

Phase 6.5 implementation status: CHAPTERS 60–62 COMPLETE

Section 17 gates Chapter 61 on the nine decisions being confirmed as one set.
Chapters 61 and 62 were authorised by direct founder instruction on 2026-08-08,
which confirms the decisions those two chapters actually depend on — **4** (release
as `1.0.0` with the Section 13 boundary stated wherever the version is published)
and the deployment-target half of **1** (an `aws` profile, since the runtime
contract has to name the target it enforces for). Decisions **2**, **3**, **5**,
**6**, **7**, **8** and **9** are not consumed by these two chapters and remain
open; **1**'s infrastructure half, **2**, **3** and **5** are consumed by
Chapter 63 and must be confirmed before it starts.

THAAP is a global, multi-rail product. India and its UPI rail are a first-class
market within it rather than the whole of it, and that distinction has two
consequences here. The first deployment lands in `ap-south-1`, but every region
reference is a Terraform variable, so standing up a second region is
configuration rather than a rewrite — an infrastructure that hardcodes one
region is a global product's first rewrite. And region and data residency are
treated as future tenant-level properties: RBI localisation and its equivalents
elsewhere will eventually constrain where a given tenant's data may live, so
nothing in this phase may assume a single global store.

This plan does not add product capability. It converts six phases of proven
correctness into a running system that someone other than its author can reach.
It is deliberately the smallest phase in the plan and the one with the highest
ratio of reach to effort.

## 1. Outcome

The operator mental model is one reproducible path from commit to running
system:

```text
commit
  -> reproducible image built from a clean checkout
  -> versioned, tagged release with recorded provenance
  -> runtime configuration that refuses to start when incomplete
  -> terraform-defined AWS topology
  -> migrated database and sixteen-indicator readiness
  -> seeded demo tenant
  -> a URL a person who is not the author can open
```

The claim Phase 6.5 must be able to answer without caveat:

1. Can a stranger with only Docker start THAAP end to end?
2. Does a running instance state exactly which commit it is?
3. Does the system refuse to start rather than run degraded when a secret is
   missing?
4. Can the entire AWS topology be destroyed and rebuilt from version control?
5. Do the Phase 1–6 demos pass against the deployed system, not just localhost?
6. Can the deployment be rolled back, and the database restored, by following a
   written runbook rather than by memory?

## 2. Entry Truth

Phase 6.5 starts from the merged V91 product. The following were executed and
recorded on 2026-08-08 rather than read from documentation:

- `mvn -f apps/payments-api/pom.xml test` → **1387 tests, 0 failures, 0 errors,
  0 skipped**, `BUILD SUCCESS`, 6:08 elapsed
- `npm run build` → clean `tsc --noEmit` and production bundle
  (304.70 kB raw, 87.67 kB gzip)
- `npm test` → **83 tests across 11 files, all passing**
- `scripts/local-infra-up.sh` → PostgreSQL, Redis and Kafka all healthy
- `docs/TECHNICAL_DEBT_REGISTER.md` → `TD-0001`–`TD-0008` all `RESOLVED`, zero
  open entries

Toolchain present: Java 25.0.3, Maven 3.9.15, Node 24.12.0, Docker 29.4.3.

The system is correct. It has never run anywhere but a laptop:

| Claim in the record | Verified state on 2026-08-08 |
|---|---|
| Deployed | No `Dockerfile` exists in the repository |
| Deployed | `infra/kubernetes/` is a five-line README deferring manifests |
| Deployed | `infra/docker/compose.yml` starts PostgreSQL, Redis and Kafka — never `payments-api` or the UI |
| Released | Zero git tags; `pom.xml` is `0.0.1-SNAPSHOT`; no `CHANGELOG.md` |
| Demo-able | All demo scripts require a local clone, Java 25, Maven, Docker and Python |
| Reproducible | `apps/analyst-dashboard-ui/package.json` pins every dependency to `"latest"` |

The last row is undeclared debt. `package-lock.json` currently protects the
build, but the manifest permits react, react-dom, typescript, vite and every dev
dependency to float across majors. A deploy-time `npm install` is a coin flip.
It is repaired in Chapter 60 rather than registered as `TD-NNNN`, because the
fix is one commit and the phase's entire purpose is build integrity.

## 3. Frozen Phase Promises

### 3.1 Build promises

- A clean checkout with no host-cached state produces a runnable image.
- The same commit resolves to the same dependency set on every machine.
- No image layer contains a secret, a credential, a tenant identity, or the
  build toolchain.
- `docker compose up` starts THAAP end to end, not merely its databases.

### 3.2 Release promises

- Every deployed instance reports the exact version and commit it was built
  from, readable over HTTP without shell access.
- Every released version has a tag and a changelog entry.
- An image is addressable by both semantic version and commit SHA.

### 3.3 Runtime promises

- A missing secret fails startup. It never degrades silently.
- Every environment variable the system reads is documented in one place.
- Configuration is supplied at runtime; nothing sensitive is baked into an
  artifact.

### 3.4 Deployment promises

- The entire topology is defined in version control and rebuildable from zero.
- The deployed system passes the same Phase 1–6 demos that pass locally.
- Deployment, rollback, secret rotation and database restore are written
  procedures with recorded rehearsals — not tribal knowledge.
- Production routing and local development routing derive from one list, so
  they cannot drift.
- An unauthenticated request from outside the operator allowlist is refused
  before it reaches any THAAP surface, and no origin hostname is a way around
  that. Added 2026-08-08 under `ER-0106`: this was the property Section 5 claimed
  and Section 3 never promised, which is how it came to have no owner and no
  proof.

## 4. Scope And Named Exclusions

### 4.1 Included

- Container images for `payments-api` and `analyst-dashboard-ui`
- Dependency pinning and reproducible installs
- Release versioning, tagging, changelog, and build provenance
- A production runtime configuration contract with fail-fast validation
- Terraform-defined AWS topology in `ap-south-1`
- First deploy, Flyway migration, and smoke proof against the deployed URL
- A seeded demo tenant with scheduled reset, spanning more than one currency and
  merchant geography
- Operations runbook and `ADR-0012`

### 4.2 Excluded with exact owner and unblock evidence

| Excluded | Owner | Unblock evidence |
|---|---|---|
| Public unauthenticated access | Phase 6.5 follow-on | Per-key quotas, abuse rate limiting, scheduled tenant reset, and cost alarms proven before the URL is offered to anyone outside the operator allowlist. **The WAF is no longer on this list — it moved into Chapter 63, because it is what makes the deployment private in the first place. See Section 5.1.** |
| Multi-AZ / HA PostgreSQL | Phase 9 | A measured availability requirement from a real design partner |
| Managed Kafka (MSK) | Phase 11 | A real merchant pilot boundary, per Section 5 |
| Kubernetes manifests | Phase 9 (scale) | `infra/kubernetes/` is **retained** by explicit instruction (2026-08-08). ADR-0012 records ECS Fargate as the near-term target without closing the EKS path; the directory's README is updated to name ECS as current and EKS as the scale-trigger option, so it documents a real position rather than an unowned promise. Unblock evidence: a measured throughput, availability or multi-service requirement that Fargate cannot meet. |
| Real card traffic / PCI scope | Phase 11 | ADR-0002 boundary; security and privacy review plus an approved design partner |
| UPI rail | Phase 7 (post-renumbering) | Phase 6.6 rail abstraction merged |
| Governed ML | Phase 8 (post-renumbering) | Phase 7 UPI closure |

## 5. Deployment Architecture Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Compute | ECS Fargate | ADR-0007 already names it the sanctioned alternative. EKS adds ~$75/mo control plane plus upgrade, addon and IRSA toil for zero product value at this scale. |
| Region | `ap-south-1` first, parameterised | Proximity to the first design partners, and alignment with the direction of RBI data-localisation obligations a later phase must satisfy anyway. The product is global, so the region is a variable rather than a constant everywhere it appears; a second region must cost a `terraform apply`, not a refactor. |
| Kafka | Single KRaft broker on Fargate + EFS | ~$20/mo against MSK Serverless's ~$550/mo floor. Disabling events is not an option: `thaap.event-publication.enabled` defaults true and Phase 5/6 analytics, projections and workspace insights all depend on the stream. |
| Database | RDS PostgreSQL 16, `db.t4g.micro` | Matches the `postgres:16-alpine` the suites already prove against. Single-AZ with seven-day automated backups. |
| Cache | ElastiCache `cache.t4g.micro` | Redis is ephemeral risk coordination per ADR-0010; losing it degrades rather than corrupts. |
| Static UI | S3 + CloudFront | The UI is a pure SPA. Serving it from the API container would put a static asset path inside a payment service's blast radius. |
| IaC | Terraform | Reviewable in pull requests, and the state of the system is diffable against the repository. |
| Egress | No NAT Gateway | Tasks run in public subnets with security groups admitting only the ALB. Saves ~$32/mo. Recorded as a deliberate tradeoff, not an oversight. |
| Exposure | Private / invite-only, **enforced at the edge** | A WAF WebACL on the CloudFront distribution, default `BLOCK`, admitting an operator IP set; the ALB accepts only requests carrying a CloudFront-injected shared secret. Authentication — a human pasting `X-Risk-Operator-Key` or `X-Api-Key` — sits behind that, not instead of it. See Section 5.1. |

Single-broker Kafka is not highly available. This is recorded honestly rather
than described as "production-grade", with a named replacement trigger in the
style ADR-0009 requires: **replace with MSK when a real merchant pilot begins
(Phase 11), or earlier if a broker restart is observed to lose an event that the
outbox cannot replay.**

## 5.1 Exposure Is A Mechanism, Not An Intention

This section exists because the first draft of this plan did not have it. It
called exposure "private / invite-only" and "a deliberate control", and named no
mechanism whatsoever. The only thing described was authentication — a human
pasting an operator key — and the implicit control was that nobody would know the
hostname.

**The hostname is public the moment the certificate is issued.** ACM issues
publicly-trusted certificates, and publicly-trusted certificates are submitted to
Certificate Transparency logs. That is not a setting worth turning off: a
certificate without CT proof is untrusted in Chrome and Safari, so opting out
trades a private hostname for a browser warning on every visit. CT logs are
streamed by public services, and newly issued hostnames are probed within
minutes. "We have not published the URL" is therefore not a control, and a plan
that relies on it is relying on nothing.

Chapter 63 delivers the actual control, in three layers:

| Layer | Control | What it stops |
|---|---|---|
| Edge | WAF WebACL on the CloudFront distribution, default `BLOCK`, admitting an operator IP set | A CT-log scanner, or anyone else, reaching the workspace, the actuator endpoints or the OpenAPI document |
| Origin | ALB accepts only requests carrying a shared secret header injected by CloudFront, held in Secrets Manager | The `*.elb.amazonaws.com` name being a way around the edge entirely |
| Network | Task security groups admit the ALB security group by reference, not by CIDR | A task in a public subnet being addressable directly, since there is no NAT Gateway |

The origin layer is the one a WAF-only implementation silently omits, and it is
the one that matters most: the WebACL is attached to CloudFront, so an ALB
reachable by its own hostname is protected by nothing at all. It is also what
makes `server.forward-headers-strategy: framework`, set in Chapter 62, safe — a
caller who can reach the ALB directly can choose its own `X-Forwarded-For` and
defeat both the per-IP rate limiter and the per-IP fraud velocity signal.

`C63-SEC-001` in `docs/reviews/PHASE_6_5_INVARIANT_PROOF_MATRIX.md` owns the
proof, and requires it to be executed from an address genuinely outside the
allowlist rather than from a shell inside the VPC.

Until all three layers exist and have been proven, the deployment is **not**
private, and no document in this repository may describe it as such.

## 6. AWS Topology

```text
Route53
  -> CloudFront
       [WAF WebACL: default BLOCK, operator IP set — Section 5.1]
       -> S3 origin            (default behavior: the SPA)
       -> ALB origin           (seven API path patterns, Section 9)
            [accepts only the CloudFront-injected shared secret header]
            -> ECS service: payments-api  (2 tasks, 1 vCPU / 2 GB)
                 -> RDS PostgreSQL 16
                 -> ElastiCache Redis
                 -> Kafka (Cloud Map: kafka.thaap.local:9092)
                      -> ECS service: kafka (1 task, KRaft, EFS-backed)
```

Supporting resources: ECR repository, Secrets Manager entries, CloudWatch log
groups and alarms, S3 + DynamoDB for Terraform remote state and locking, and the
WAF WebACL and IP set.

The two bracketed lines are the exposure control. They are drawn inside the
topology rather than listed beside it because a diagram that shows Route53 →
CloudFront → ALB and nothing else is a diagram of a public system.

Two API tasks are safe without leader election. All eight `@Scheduled` worker
classes coordinate through database leases with fencing epochs, already proven
by the Phase 5 restart suites and by `ApplicationRestartHarness`, which starts
and stops real application instances against one database.

## 7. Cost Model

Monthly estimate, `ap-south-1`, at two API tasks:

| Resource | Estimate |
|---|---|
| ALB | ~$18 |
| Fargate — payments-api (2 × 1 vCPU / 2 GB) | ~$55 |
| Fargate — kafka (0.5 vCPU / 1 GB) | ~$14 |
| EFS | ~$3 |
| RDS `db.t4g.micro` + 20 GB gp3 | ~$13 |
| ElastiCache `cache.t4g.micro` | ~$11 |
| S3 + CloudFront | ~$2 |
| Secrets Manager (9 secrets) | ~$4 |
| WAF WebACL + IP-set rule (Section 5.1) | ~$7 |
| CloudWatch Logs ingestion and storage | ~$5 |
| Data transfer out | ~$2 |
| Route53 | ~$1 |
| **Total** | **~$134** |

At one API task the total is approximately $99. A billing alarm is part of
Chapter 65 rather than an afterthought.

Three of these lines were missing from the first draft and are recorded here
rather than discovered on an invoice: the WAF that Section 5.1 makes mandatory,
CloudWatch Logs — which this system feeds by design, since nothing is written to
a container filesystem — and egress. The ninth secret is the CloudFront-to-ALB
shared header. None is large; the point is that a cost model precise to the
dollar everywhere else should not omit whole categories.

## 8. Runtime Configuration And Secret Contract

`apps/payments-api/src/main/resources/application.yml` reads eight values that
default to the empty string. Every one of them is security-relevant, and every
one currently permits the application to start without it:

| Variable | Purpose |
|---|---|
| `THAAP_ADMIN_BOOTSTRAP_TOKEN` | Merchant creation authority |
| `THAAP_PLATFORM_BOOTSTRAP_TOKEN` | Platform operator authority |
| `THAAP_HASH_PEPPER` | Merchant API key and credential hashing |
| `THAAP_CORRELATION_PEPPER` | Stable fraud/PII correlation keys |
| `THAAP_AUTHENTICATION_REPLAY_ENCRYPTION_KEY` | Authentication replay KEK |
| `THAAP_WEBHOOK_SECRET_ENCRYPTION_KEY` | Webhook signing secret KEK |
| `THAAP_REVIEW_NOTE_ENCRYPTION_KEY` | Analyst note KEK |
| `THAAP_SCENARIO_RUNNER_TOKEN` | Trusted synthetic scenario origin |

Chapter 62 adds an `aws` profile whose startup validator **refuses to start**
when any is absent, empty, or below a minimum entropy length. This mirrors
`DomainEventProducerGate`, which already refuses to start on a catalogue version
it cannot serve rather than dropping events — the repository's established
position that a component which cannot do its job must fail loudly rather than
serve a weaker promise.

**The threat model above was incomplete, and implementation found it.** Absent,
empty and low-entropy are the failures that are easy to name; the highest-
probability one is none of them. It is a **known-published** value. This
repository deliberately publishes working secrets — in `infra/docker/compose.yml`,
in both READMEs and in `application-test.yml` — so that a stranger with only
Docker can start THAAP, which is a Chapter 60 promise worth keeping. The
consequence is that the likeliest route to a deployed system holding a known key
is somebody copying a quickstart that was written to be copied. The three
published KEKs are genuinely random 32-byte keys and pass every entropy rule that
could be written about them; only a list can reject them.

Chapter 62 therefore shipped three rules this section did not ask for, and they
are recorded here so the Chapter 66 dossier reconciles against what was built:

1. **No published value.** Every secret this repository commits is refused, with
   a test that scrapes those four files and fails if a newly committed default is
   missing from the list.
2. **No reuse across two secrets.** Each must be independently rotatable and
   independently compromisable. Sharing `THAAP_HASH_PEPPER` with
   `THAAP_CORRELATION_PEPPER` would tie credential rotation to fraud identity, so
   a routine key roll would silently split every customer's history in two.
3. **No placeholder markers**, scoped honestly to opaque tokens: every marker
   contains a hyphen or a letter outside the hexadecimal alphabet, so neither
   generator can trip it and it cannot reach a Base64 KEK at all.

`THAAP_POSTGRES_PASSWORD` is deliberately outside this contract. It is
security-relevant, but a wrong database password fails loudly on its own — it
cannot produce the silently-degraded system the eight above can. That reasoning
is a judgement call and is written down in
`docs/references/RUNTIME_CONFIGURATION_REFERENCE.md` so the next reader can
disagree with it.

Rotation variables (`THAAP_PREVIOUS_HASH_PEPPERS`,
`THAAP_WEBHOOK_SECRET_DECRYPTION_KEYS`, `THAAP_REVIEW_NOTE_DECRYPTION_KEYS`,
`THAAP_AUTHENTICATION_REPLAY_DECRYPTION_KEYS`) remain optional by design: an
empty previous-key set is a valid state, and the existing rotation suites prove
overlap behaviour.

## 9. Routing Contract

`apps/analyst-dashboard-ui/vite.config.ts` exports `paymentsApiProxy`, and
`vite-config.test.ts` already asserts against it. Seven prefixes route to the
API:

```text
/risk  /developer  /customers  /payment_methods
/payment_intents  /webhook_endpoints  /webhook_deliveries
```

Production CloudFront behaviors must be generated from, or checked against,
that same list. A prefix added to the dev proxy and forgotten in CloudFront is a
feature that works locally and 404s in production — the precise class of drift
this repository's gates exist to prevent. Chapter 63 adds a check that fails
when the two diverge.

## 10. Release And Versioning Contract

- `pom.xml` moves from `0.0.1-SNAPSHOT` to `1.0.0`.
- `spring-boot-maven-plugin`'s `build-info` goal writes version and commit into
  the artifact; `management.info.env.enabled` is already `true`, so
  `/actuator/info` exposes it without new code.
- `CHANGELOG.md` is created and `v1.0.0` is tagged.
- CI pushes each image to ECR under both the semantic version and the commit
  SHA. Deployments reference the SHA; humans reference the version.
- `1.0.0` denotes a complete, proven, deployed simulator. It does not denote a
  system licensed to process real payments; Section 13 governs that claim.

## 11. Pre-Implementation Failure Inventory

Built before implementation, per the Quality Closure Operating System:

1. **Build ambiguity** — the image builds only because `target/` was already
   populated on the developer's machine.
2. **Dependency drift** — a deploy-time install resolves a different tree than
   the one reviewed and tested.
3. **Secret exposure** — a build-time secret persists in an intermediate layer
   and survives its own deletion in a later one.
4. **Silent degradation** — the application starts with an empty KEK and
   encrypts nothing, or authenticates nobody, while reporting healthy.
5. **Migration race** — two tasks start simultaneously and both attempt Flyway.
6. **Lease duplication** — two replicas process one webhook or projection chunk
   because fencing is not honoured across processes.
7. **Routing drift** — CloudFront behaviors and the dev proxy diverge.
8. **Readiness dishonesty** — liveness passes while readiness fails, so an
   unhealthy task is never replaced.
9. **Broker loss** — the single Kafka task restarts and the outbox cannot
   replay what was in flight.
10. **Restore fiction** — automated backups exist and have never been restored,
    so the recovery procedure is untested at the moment it is needed.
11. **Cost runaway** — a misconfigured resource or an abusive caller produces a
    bill nobody notices until it arrives.
12. **Credential leakage into an artifact** — a developer `.env` with a working
    admin bootstrap token is copied into an image by a broad `COPY . .`.

Each maps to at least one Chapter 60–66 matrix row.

## 12. Observability And Operations Contract

- Container health checks bind to `/actuator/health/readiness`, whose existing
  group aggregates sixteen indicators: `readinessState`, `db`, `velocityRedis`,
  `merchantRiskPolicy`, `merchantRiskMonitoring`, `analystWorkspace`,
  `riskSimulation`, `governedControl`, `disputeOperations`,
  `developerScenarios`, `webhookOperations`, `eventPublication`,
  `paymentRiskProjection`, `projectionRebuild`, `offlineReplayDataset`,
  `decisionIntelligence`.
- `show-details: never` is retained. Readiness detail stays internal.
- Logs go to stdout and CloudWatch only. Nothing is written to a container
  filesystem that a restart would lose.
- Alarms: ALB 5xx rate, ECS task health and restart count, RDS CPU / storage /
  connection saturation, Kafka task restarts, projection consumer lag, and an
  AWS Budgets alarm on total spend.
- The existing request-ID log pattern is preserved end to end so a CloudWatch
  trace can be tied to a merchant-visible error response.

## 13. Security Boundary And Honest Limits

These statements are part of the phase contract, not a disclaimer appended to
it:

- **This is not a PCI-scoped system and processes no real card data.** ADR-0002
  and `docs/VISION.md` are explicit that V1 moves no real money. Deploying it
  does not change that. "Production-ready" here means hosted, TLS-terminated,
  backed up, monitored and rebuildable — not licensed, certified or connected to
  a real acquirer.
- Single-broker Kafka is a single point of failure with a named replacement
  trigger.
- Single-AZ RDS means an availability-zone failure is downtime plus a restore,
  not a failover.
- `THAAP_WEBHOOK_EXECUTION_ENABLED` defaults `false`. Enabling it makes
  `THAAP_WEBHOOK_ALLOWED_HOSTS` a live SSRF boundary against the VPC and the
  instance metadata endpoint. The existing `SocketWebhookHttpTransport` address
  pinning is the control that makes this safe, and it must be verified against
  the deployed network before execution is enabled.
- Private exposure is a deliberate control, not an unfinished feature — but only
  once Section 5.1's three layers exist. Until then the hostname is reachable by
  anyone who reads a Certificate Transparency log, and this document said
  otherwise in its first draft. Opening the URL further is a separate decision
  with its own promises.

## 13.1 Schedule — Seven Working Days, Externally Gated

Target set 2026-08-08.

| Day | Chapters | Output | State |
|---|---|---|---|
| 1 | 60 | Images build; `docker compose up` starts THAAP end to end | **Done 2026-08-08** |
| 1 | 61, 62 | `1.0.0` tagged with provenance; fail-fast secret contract proven | **Done 2026-08-08** — pulled forward a day |
| — | — | AWS account, domain, hosted zone | **Gating.** Not started; nothing below can begin |
| G+1, G+2 | 63 | Terraform topology, exposure control, applied to a scratch stack | Blocked |
| G+3 | 64 | First real deploy, migration, sixteen indicators `UP`, demos green against the URL | Blocked |
| G+4 | 65 | Seeded multi-currency tenant, runbook, ADR-0012, rehearsed rollback and restore | Blocked |
| G+5 | 66 | Integrated matrix closed, dossier, gates, roadmap updates | Blocked |

Days are numbered from the gate rather than from the start, because they are not
the same clock and pretending otherwise produces a plan that reports itself
"behind" for a reason nobody on it can fix.

**The external dependency is on the critical path and cannot be compressed.**
An AWS account with billing enabled, a registered domain, and a Route53 hosted
zone are prerequisites for Chapter 63 onward. ACM certificate validation and DNS
propagation are wall-clock delays no amount of parallelism removes — nameserver
repointing at a third-party registrar can take up to 48 hours on its own — and
none of them can be done on the operator's behalf.

**Status on 2026-08-08: Chapters 60–62 are complete and none of the three
prerequisites exists.** The phase is not late; it is gated, and those are
different states. The original plan asked for the prerequisites "by end of Day
2", which has arrived unmet, so the schedule is rebased on the gate rather than
quietly carried forward. Terraform authoring for Chapter 63 does not require an
account — only `apply` and the second-region `plan` do — so that work may proceed
against the gate, provided the chapter stays open until both have run.

**Process ceremony is the compressible cost, and proof is not.** Seven chapters
under the full Quality Closure Operating System means seven eight-lens matrices
and seven dossiers reconciling thirteen dimensions each. For a phase that adds
no migration, no schema, no API and no payment or fraud behaviour change, that
is document duplication rather than evidence.

Phase 6.5 therefore uses **one integrated invariant matrix and one closure
dossier for the phase**, not one per chapter. Every invariant in Section 3 is
still proven at its real enforcement boundary with a recorded run, and
`C60-PAY-001` — that behaviour is unchanged and the whole backend suite still
passes at an exactly stated count — remains the anchor that makes the compression
safe. The already-closed `CHAPTER_60_INVARIANT_PROOF_MATRIX.md` is folded into
the phase matrix at closure.

**The phase matrix is drafted before the work it covers, not at closure.** As
first written this section said only *what* the compression was, never *when* the
one matrix gets written, and the omission was not cosmetic: a matrix authored at
Chapter 66 covering work finished in Chapters 63–65 could not honestly carry
`Matrix provenance: PRE_IMPLEMENTATION`, and the only other value the checker
accepts is reserved for the Chapter 19 legacy recovery. Followed literally, this
section required Chapter 66 to mislabel a retroactive matrix to pass its own
gate — which is the exact substitution the Quality Closure Operating System
forbids. `docs/reviews/PHASE_6_5_INVARIANT_PROOF_MATRIX.md` was therefore drafted
and committed on 2026-08-08, before any Terraform existed, with every row
`PENDING`. Recorded as `ER-0105`.

Chapters 61 and 62 shipped before that correction and have **no**
pre-implementation matrix. Their proof is real and executed, and it is recorded
in Section 14 and in `docs/learning/daily/2026-08-08.md` §12–§20 — but it was
written after the fact and the Chapter 66 dossier must present it that way rather
than fold it in as though it had been planned. That is the cost of finding this
late, and hiding it would cost more.

This is a deliberate, recorded scope decision about documentation structure. It
does not weaken any proof obligation, and it does not apply to Phase 6.6 or
Phase 7, which change payment truth and revert to per-chapter treatment.

## 14. Chapter Execution Plan

### Chapter 60 — Reproducible Build Artifacts — COMPLETE 2026-08-08

- **Delivers:** `apps/payments-api/Dockerfile` (multi-stage, digest-pinned
  `eclipse-temurin:25-jdk` → `25-jre`, layered jar, non-root, JRE-only final
  stage), `.dockerignore`, a UI image, `package.json` pins resolved from
  `package-lock.json`, and `infra/docker/compose.yml` extended so
  `docker compose up` starts the whole system.
- **Retains:** the existing `--enable-native-access=ALL-UNNAMED` JVM argument
  declared in `pom.xml`.
- **Invariants:** `C60-OPS-001`, `C60-OPS-002`, `C60-SEC-001`, `C60-API-001`,
  `C60-PAY-001`, `C60-TEN-001`, `C60-CON-001`, `C60-OBS-001`.
- **Matrix:** `docs/reviews/CHAPTER_60_INVARIANT_PROOF_MATRIX.md` — frozen
  2026-08-08 before any Dockerfile existed, and closed the same day with all
  eight rows `PASS` on artifacts that exist and have recorded runs. Two escaped
  findings were raised by the work and resolved: `ER-0103` and `ER-0104`.

### Chapter 61 — Release Identity — COMPLETE 2026-08-08

- **Delivers:** `1.0.0` in `pom.xml`, the `build-info` goal, `CHANGELOG.md`,
  tag `v1.0.0`, and CI push to ECR under version and SHA.
- **Proves:** a running instance reports the commit it was built from.
- **Delivered as:** `pom.xml` at `1.0.0` with a `build-info` execution;
  `THAAP_BUILD_COMMIT` and `THAAP_BUILD_VERSION` Docker arguments carrying the
  identity into both images as OCI labels; the hardcoded `info.app.version` in
  `application.yml` removed so the version has one source;
  `scripts/release-identity-check.sh`; `.github/workflows/release-image.yml`;
  `ReleaseIdentityContractTests`; an identity assertion added to
  `scripts/compose-stack-check.sh`.
- **Two decisions worth review.** `build.time` is excluded from build-info:
  it is the only recorded property that is not a function of the source, and
  keeping it would have meant one commit producing a different artifact on every
  rebuild, retiring the reproducibility Chapter 60 had just established. And the
  commit is *passed in* rather than read, because `.dockerignore` excludes
  `.git/` on purpose — a git-reading plugin would report nothing in exactly the
  build that matters.
- **Honestly unproven:** the ECR push itself. The registry is created by
  Terraform in Chapter 63 and the AWS account is an external prerequisite, so the
  workflow's publish steps are conditional on the account being configured and
  say so in the run summary. Everything before the push — build, identity proof,
  image content proof, tag-versus-pom agreement — runs unconditionally and gates.

### Chapter 62 — Production Runtime Configuration Contract — COMPLETE 2026-08-08

- **Delivers:** `application-aws.yml`, a fail-fast secret validator for the
  eight variables in Section 8, and the complete environment-variable reference.
- **Proves:** the application refuses to start on a missing or empty secret,
  proven per variable rather than for one representative case.
- **Delivered as:** `RequiredRuntimeSecret` and `RuntimeSecretContractGate` in
  `com.thaap.payments.config`; `application-aws.yml`;
  `docs/references/RUNTIME_CONFIGURATION_REFERENCE.md` covering all 87 variables;
  `scripts/runtime-configuration-reference-check.py` wired into
  `scripts/commit-readiness-check.sh`.
- **Enforced earlier than the plan required.** The gate is an
  `EnvironmentPostProcessor` rather than a bean, so the refusal lands before the
  first bean is created, before Hikari opens a connection, and before Flyway
  takes the migration lock. When the answer is "this instance must not run", the
  cheapest moment to say so is before it has touched the production database.
  `RuntimeSecretContractStartupTests.refusesBeforeItOpensADatabaseConnection`
  proves it by pointing the datasource at an unreachable address and still
  requiring the failure to be the secret contract.
- **Three rules beyond the plan's letter,** each of which refused a plausible
  deployment during the proof: no value may be reused across two secrets; no
  value published anywhere in this repository is accepted; and no value carrying
  a placeholder marker is accepted. The second is the one that matters most —
  this repository publishes working secrets deliberately so a stranger can start
  the stack, which makes "somebody copied the quickstart" the single most likely
  route to a deployed system holding a known key.

### Chapter 63 — AWS Infrastructure As Code

- **Delivers:** `infra/terraform/` covering the Section 6 topology, remote state
  with locking, the Section 9 routing-drift check, and the Section 5.1 exposure
  control.
- **Exposure:** all three Section 5.1 layers — the CloudFront WAF WebACL with an
  operator IP set, the CloudFront-to-ALB shared secret header, and task security
  groups admitting the ALB security group by reference. This moved into this
  chapter from a "Phase 6.5 follow-on" line in Section 4.2, because it is not an
  enhancement to a private deployment; it is the thing that makes the deployment
  private. `C63-SEC-001` owns the proof and requires it to run from outside the
  allowlist.
- **Retains:** `infra/kubernetes/`, with its README updated to state that ECS
  Fargate is the current target and EKS remains the scale-trigger path.
- **Region:** every region, availability-zone and ARN reference is a variable or
  derived from one. No module hardcodes `ap-south-1`, and a gate rejects a
  literal region string outside the variable definition. THAAP is a global
  product; infrastructure that names one region in fifty places is the first
  thing a second region forces someone to rewrite, and by then it is load-bearing.
- **Proves:** `terraform apply` from zero produces a working stack, the following
  `plan` is clean, `terraform plan` against a second region variable resolves
  without a code change — proving the parameterisation is real rather than
  cosmetic, without paying to run a second stack — and a request from a
  non-allowlisted address is refused at the edge while the ALB's own hostname
  refuses a request that did not come through CloudFront.

### Chapter 64 — First Deploy, Migration, And Smoke Proof

- **Delivers:** Flyway `V1`→`V91` against a fresh RDS instance; a base-URL
  parameter for the demo scripts, which currently hardcode loopback.
- **Proves:** all sixteen readiness indicators `UP`, and every Phase 1–6 demo
  passing against the deployed HTTPS endpoint rather than localhost.

### Chapter 65 — Seeded Demo Tenant And Operations

- **Delivers:** a seed built by reusing the existing demo scripts rather than new
  fixtures, spanning more than one currency and merchant geography — including
  INR and an Indian merchant, so the rail and residency work ahead has a
  believable subject, without implying the product is single-market; an
  EventBridge-scheduled reset; the operations runbook; `ADR-0012`.
- **Proves:** a rehearsed rollback and a rehearsed restore-from-backup, both
  recorded. An unrehearsed procedure is not evidence.

### Chapter 66 — Phase 6.5 Closure

- **Closes:** `docs/reviews/PHASE_6_5_INVARIANT_PROOF_MATRIX.md`, which was
  drafted and frozen on 2026-08-08 before any Terraform existed and holds ten
  `PENDING` rows across Chapters 63–65. Chapter 66 moves each to `PASS` on
  executed evidence, sets `Review status: VERIFIED`, and completes the
  traceability, hostile and boundary-family passes the matrix currently records
  as `PENDING`. It introduces no product invariant of its own.
- **Folds in:** `CHAPTER_60_INVARIANT_PROOF_MATRIX.md`, already closed with all
  eight rows `PASS`.
- **Must state plainly, not fold in silently:** Chapters 61 and 62 have no
  pre-implementation matrix. They shipped under Section 13.1 as originally
  written, before `ER-0105` established when the phase matrix is drafted. Their
  evidence is executed and recorded in Section 14 and in
  `docs/learning/daily/2026-08-08.md` §12–§20 with its deep dive §15, and the
  dossier must present it as evidence gathered after the fact.
- **Delivers:** the closed matrix and the phase dossier, infra gates added to
  `scripts/commit-readiness-check.sh`, and updates to
  `docs/THAAP_VISION_COVERAGE_MATRIX.md` and
  `docs/IMPLEMENTATION_MASTER_PLAN.md`.
- **Closure mechanics to expect:** the phase matrix carries `Chapter: Chapter 66`
  so it resolves to one coordinate, and `scripts/chapter-closure-check.py`
  requires the README and the master plan to mark Chapter 66 complete and refuses
  a dossier whose matrix is still `IN_PROGRESS` or still holds a `PENDING` row.
- **Proves:** the phase closes against executed evidence, with no row citing a
  gate that has never been run.

## 15. Verification

Items marked ✅ have been executed and their result recorded. Counts are stated
exactly, because a reduced count that still reports success is the condition the
CI Docker check already exists to prevent.

1. ✅ `docker compose up` starts THAAP end to end locally —
   `scripts/compose-stack-check.sh`, readiness `UP`, seven prefixes routed, 91
   migrations on a fresh database.
2. ✅ `mvn -f apps/payments-api/pom.xml test` → **1457 pass, 0 failures, 0
   skipped** (1387 at phase entry, 1392 after Chapter 60, 1457 after Chapters
   61–62); `npm test` → **86 pass** (83 at entry).
3. `terraform apply` from an empty account yields a reachable HTTPS URL.
4. `/actuator/health/readiness` → all sixteen indicators `UP`, including after an
   idle window longer than the previous staleness threshold.
5. ✅ `/actuator/info` → reports `1.0.0` and the deployed commit SHA. Proven at
   the image boundary by `scripts/release-identity-check.sh` and over HTTP by
   `scripts/compose-stack-check.sh`; re-asserted against the deployed URL in
   Chapter 64.
6. Every `scripts/demo-*.py` passes against the deployed URL, with the count of
   demos executed asserted rather than described.
7. The workspace loads over CloudFront and reaches all seven API prefixes, with
   each response proven to come from the API rather than from the SPA fallback.
8. Two tasks start simultaneously; Flyway migrates exactly once.
9. A task is killed mid-work; lease fencing recovers it without duplicate
   effect.
10. RDS is restored from an automated backup into a scratch instance, the Flyway
    state is confirmed, and a decision made before the backup is re-explained
    end to end through an application instance served by it.
11. ✅ Each of the eight secrets is removed in turn; startup fails each time —
    `RuntimeSecretContractGateTests`, driven from the enum so a ninth secret
    cannot be added without its own case. Also proven against the released
    container under `SPRING_PROFILES_ACTIVE=aws`.
12. A request from outside the operator allowlist is refused at the edge, and the
    ALB's own hostname refuses a request that did not come through CloudFront
    (Section 5.1).
13. `scripts/commit-readiness-check.sh` passes with the new infra gates.

## 16. What Follows

**Phase 6.6 — Payment Rail Abstraction (~1 week).** `PaymentMethod` carries
`cardFingerprint`, `cardBin`, `cardLast4`, `cardFunding` and `cardCountry`
directly on the entity. ADR-0004 promised the model would "avoid assumptions
that make later payment methods impossible"; it did not hold. A pure refactor
behind a rail type, zero behaviour change, the whole backend suite still green at
whatever exact count Phase 6.5 closes on — 1457 as of 2026-08-08. This is the
gate for all UPI work.

The seam must be genuinely multi-rail rather than a card/UPI branch. THAAP is a
global product, so SEPA, ACH, PIX and local wallets are all eventual rails, and
a two-valued abstraction would have to be rebuilt for the third. Anything that
reads like `if (rail == UPI)` in this refactor is the abstraction failing.

**Phase 7 — UPI Rail (~6–8 weeks).** Replaces governed ML at this slot; ML moves
to Phase 8 and 8/9/10 cascade to 9/10/11. Simulated NPCI/PSP boundary, collect
and intent/QR flows, VPA identity, UPI-shaped fraud features replacing
BIN/last4 ones, and the UPI dispute model alongside card chargebacks.

UPI is first because it is the rail least like cards, not because India outranks
other markets. It has no authorization/capture split, it is payer-initiated
rather than merchant-initiated, it offers no BIN, last4 or fingerprint to build
features on, and its disputes run through NPCI rather than as chargebacks. Every
one of those breaks an assumption the current model makes. A rail seam proven
against UPI will absorb SEPA or ACH comfortably; one proven against a second
card-like rail would teach almost nothing and would still be a card model
wearing a different name. Hardest rail first is what makes the abstraction real.

**Fraud service topology — decided 2026-08-08: one rail-aware service, not one
per rail.** The rail-specific surface is narrow: feature extraction, rule
catalogue, fraud taxonomy, and the available action set (card supports
`REQUEST_3DS`; UPI has no merchant-controllable step-up, since the PIN sits
between the payer and their own PSP). Everything expensive is rail-agnostic:
precedence, immutable decision records, reason codes, feature-snapshot lineage,
rule governance, simulation, rollback, review cases, labels, tenancy,
idempotency, audit, outbox, projections and the workspace.

The deciding argument is product, not code organisation. Fraud rings respect
neither rails nor borders: the same actor will probe a merchant by card and by
UPI, and the same technique appears against merchants in different countries
weeks apart. Two services means two decision stores and no practical way to
correlate either pattern; one service keeps them in the same decision history
and the same velocity counters. For a global product that cross-rail and
cross-geography view is the advantage over single-rail incumbents, and splitting
the service would discard it to solve a filing problem. Consistent with ADR-0001
and ADR-0009.

The seam goes inside the service: a `RailFraudFeatureProvider` per rail behind
one interface, rail-tagged rules reusing `controls/` and `rules/` governance
unchanged, a rail-scoped action set, and `rail` on the decision record so
analytics, review queues, labels and later per-rail champion/challenger can all
segment on it.

*Named replacement trigger:* split only when a rail's latency budget measurably
diverges from the others' **and** they contend for the same resources, or when a
regulatory boundary forces a tenant's data into separate storage.

Data residency is the trigger most likely to fire, and it is worth being precise
about what it does and does not force. RBI localisation, and its equivalents in
other jurisdictions a global product will meet, constrain **where a tenant's data
resides** — not how many services process it. That is a storage and placement
boundary, satisfied by regional data stores under one service topology, and it is
why region and residency are treated here as future tenant-level properties
rather than global constants. A regional store still permits one decision model
and one set of rail-aware controls; what it forbids is a single global table that
assumes every tenant's rows may sit together. Nothing in this phase may assume
that, which is the practical reason the region is a variable from the first
commit rather than the first migration.

**In parallel from Chapter 64.** Publishing and design-partner outreach need
only the URL. `docs/blog/` has a workflow, an editorial rubric, a topic backlog
and a checklist, and has produced zero published posts since 2026-06-26. The
declared wedge is transparency; transparency that is never published is not a
wedge.

## 17. Decisions Requiring Founder Confirmation

Phase 6.5 may not begin Chapter 61 until these are confirmed as one set:

1. Deploy to AWS `ap-south-1` first on ECS Fargate, superseding ADR-0007's
   EKS-primary mapping via ADR-0012, with every region reference parameterised
   so a second region is configuration rather than a rewrite.
2. Run Kafka as a single self-managed KRaft broker, superseding ADR-0007's
   "Kafka to MSK" mapping, with MSK as the named Phase 11 replacement trigger.
3. Accept single-AZ RDS and no NAT Gateway as deliberate cost tradeoffs.
4. Release as `1.0.0` with the Section 13 boundary stated wherever the version
   is published.
5. Keep the deployment private and invite-only, **enforced by the three Section
   5.1 layers rather than by an unpublished hostname**, and accept the ~$7/month
   the WAF costs. Opening it further is a separate decision with its own frozen
   promises. This decision was amended on 2026-08-08: as originally written it
   asked only for a policy, and a policy with no mechanism is what the amendment
   corrects.
6. Retain `infra/kubernetes/` and update its README to name ECS Fargate as the
   current target and EKS as the scale-trigger path (instructed 2026-08-08).
7. Renumber the roadmap: UPI becomes Phase 7, ML becomes Phase 8, and the
   current Phases 8/9/10 cascade to 9/10/11.
8. Build one rail-aware fraud service rather than one service per rail, with the
   rail seam designed as genuinely multi-rail rather than a card/UPI branch.
9. Treat region and data residency as future tenant-level properties, so no
   phase assumes a single global store for every tenant's data.
