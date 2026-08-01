# THAAP

Threat Hunting & Adaptive Analytics for Payments.

THAAP V1 is a production-shaped payment processing and fraud intelligence
simulator. Its final destination is a hosted merchant-facing fraud/risk
platform that initially integrates with existing PSPs rather than replacing
their money-moving, acquiring, and settlement responsibilities. The simulator
is the safe proving ground for payment and fraud primitives, replay,
explainability, analyst operations, governed ML, and production regression—not
the product ceiling.

The authoritative cross-phase implementation source is
[docs/IMPLEMENTATION_MASTER_PLAN.md](docs/IMPLEMENTATION_MASTER_PLAN.md). Where
that plan incorporates a detailed phase plan, the incorporated plan controls
that phase's exact frozen contract; for Phase 5 and Phase 6 these are
[docs/PHASE_5_DETAILED_IMPLEMENTATION_PLAN.md](docs/PHASE_5_DETAILED_IMPLEMENTATION_PLAN.md)
and
[docs/PHASE_6_DETAILED_IMPLEMENTATION_PLAN.md](docs/PHASE_6_DETAILED_IMPLEMENTATION_PLAN.md).
Permanent challenger-promise ownership is tracked in
[docs/THAAP_VISION_COVERAGE_MATRIX.md](docs/THAAP_VISION_COVERAGE_MATRIX.md)
and enforced by `scripts/vision-coverage-check.py`.
Chapter completion is governed by
[the invariant-proof process](docs/INVARIANT_PROOF_REVIEW_PROCESS.md),
[the escaped-review register](docs/ESCAPED_REVIEW_REGISTER.md), and a
chapter-specific invariant matrix plus closure dossier under `docs/reviews/`.

## Current Status

Chapter 20 is complete and the canonical Phase 2 simulator boundary is closed.
Chapter 21 is complete, and the Phase 2.5 zero-debt entry gate is satisfied.
`ER-0034` through `ER-0042` close the complete persisted-HMAC rotation,
generator resource-lineage, merchant lifecycle-epoch, webhook exact-use,
stale-generation recovery, KEK-readiness, and endpoint-fencing boundary
families.
Persisted generator retry classification is authoritative across restart, and
V15 server-side merchant/customer creation idempotency closes the remote
commit-to-local-checkpoint gap without storing raw merchant API keys or
customer PII. V16 preserves merchant replay across HMAC-pepper rotation,
and V17 extends versioned replay safety to generic idempotency and webhook
endpoint creation while separating stable fraud/PII correlation keys,
general idempotency locks ownership before processor/mutation work, and every
generator request renews live run ownership. `TD-0001` through `TD-0005` are
resolved, and the technical debt register has zero non-resolved entries. Five
independent Chapter 21 recovery packets now carry complete transition tables,
mandatory closure questions, narrow implementation checkpoints, and later
hostile-proof checkpoints; the Phase 3 entry gate is satisfied.
Phase 3 is complete through Chapters 22–25. Chapter 23 is complete, Chapter 24
is complete, and Chapter 25 is complete. V18–V25 now provide immutable typed
feature lineage, the exact three-rule `rules_v1` catalogue, three ordered
match/non-match results per new decision, and database-bound rule, decision,
payment, review, tenant, and traffic-origin semantics. Confirm applies
deterministic precedence `BLOCK > REVIEW > REQUEST_3DS > ALLOW` before any
processor call. `BLOCK` is terminal fraud truth, fraud step-up creates no
Authorization, and the third attempt can create one open review case; bounded
tenant-scoped review APIs expose that case and merchant cancellation atomically
cancels intent, attempt, and case without rewriting the decision or creating
money truth. Historical `rules_stub_v1` decisions remain readable. The
pre-implementation matrices, migration and forced PostgreSQL proofs, 324-test
backend suite, 76-test repository suite, and loopback public demo reconcile all
four actions with exact replay and durable feature/rule evidence. V25 also
conservatively preserves legacy webhook subscription consent: an existing
endpoint receives no new fraud/review event without durable opt-in evidence.
ER-0047–ER-0050 close the escaped subscription, real SQL-flush, transaction-
outcome telemetry, and pagination-proof boundaries. Phase 4 owns
velocity, mutable merchant policies, analyst assignment/resolution, labels,
and model feedback.
Founder/domain review confirmed the Phase 4 detailed implementation plan on
2026-07-13. It sequences Chapters 26–35 and freezes each chapter before code.
Chapters 26–34 are complete. All nine implementation chapter matrices are
`FROZEN`/`VERIFIED`, every invariant is `PASS`, and all nine dossiers recommend
`MERGE`. Chapter 33 is complete with post-authorization recommendation/action,
manager refund, balanced ledger, label, rollback, forced PostgreSQL ordering,
V45/V46, and retained live-demo proof. Chapter 34 is complete with durable
suspected card-testing incidents, manager-only mitigation, exact Redis rate
limits, HMAC rotation/privacy, 429/503 truth, V47/V48, and a retained abuse-
traffic demo. The post-closure hardening in V49 resolves HMAC rotation,
Redis compensation ownership, operator replay authorization, and signal
lineage findings. Chapter 35 is complete after 629 backend tests, 114 repository
Python tests, 14 migration contracts, all nine public Phase 4 demos, and the
integrated matrix/dossier gates. Phase 4 is complete.
The dedicated Phase 4.5 detailed plan is approved and complete through Chapter
43R. Chapters 36–43 cover merchant-risk monitoring, analyst workspace,
historical simulation, governed rules/exceptions, dispute operations, developer
scenarios, webhook operations, and integrated closure; product promises were
founder-confirmed on 2026-07-25. V50–V64 provide exact point-in-time monitoring,
protected analyst notes, restart-safe mixed-control simulation, dual-controlled
rules and narrow safety exceptions, actor-fenced dispute operations, trusted
leased scenarios, and generation-fenced webhook verification/redelivery.
Chapter 36 is complete. Chapter 37 is complete. Chapter 38 is complete. Chapter
39 is complete. Chapter 40 is complete. Chapter 41 is complete. Chapter 42 is
complete. Chapter 43 is complete. Chapter 43R is complete.

The later `ER-0064`–`ER-0073` review withdrew the earlier completion claim and
found missing boundary partitions in incident-link availability, historical
control reconstruction, webhook terminal dispatch, live-actor locking,
note-read audit, scenario-report ownership, authorization reconciliation, and
cockpit integration/resilience. All ten findings are resolved. The four expanded
PostgreSQL recovery suites pass—monitoring 7/7, simulation 8/8, dispute 19/19,
and the post-sign terminal-winner webhook interleaving—and the final regression
passes 759 backend tests without skips, 140 repository tests, 20 UI tests, and
the production UI build. Every Chapter 36–43R matrix is
`FROZEN`/`VERIFIED`/all-`PASS`, every dossier is `READY_TO_MERGE`/`MERGE`, all
escaped findings are resolved, and no technical debt is open. Phase 5 is
unblocked. The
[Phase 5 detailed implementation plan](docs/PHASE_5_DETAILED_IMPLEMENTATION_PLAN.md)
is implemented end to end. Founder implementation
authorization on 2026-07-27 confirmed its sixteen decisions as one coherent
product set before Phase 5 production code, migrations, runtime configuration,
product fixtures, UI, or demo changes. Chapters 44–52 cover the canonical
event/outbox contract, payment and fraud event integration, Kafka publication,
inbox-idempotent consumption, rebuildable PostgreSQL analytics, projection
generation activation, and point-in-time offline replay datasets. V83–V85 bind
each fraud event to its exact decision, feature snapshot, payment attempt, and
payment stream; analytics collections are losslessly paginated and basis-point
scores are rendered to analysts as percentages. The Chapter
44–52 `PRE_IMPLEMENTATION` contracts are `FROZEN`/`VERIFIED`/all-`PASS`, with
exact matrix/dossier cross-links in the detailed plan. Chapter 44 is complete.
Chapter 45 is complete. Chapter 46 is complete. Chapter 47 is complete. Chapter
48 is complete. Chapter 49 is complete. Chapter 50 is complete. Chapter 51 is
complete. Chapter 52 is complete. Every dossier is `READY_TO_MERGE`/`MERGE`.
Phase 5 implementation status: COMPLETE
The
[Phase 6 detailed implementation plan](docs/PHASE_6_DETAILED_IMPLEMENTATION_PLAN.md)
was founder-confirmed on 2026-08-02. It freezes Chapters 53–59 for a source-reconciled
payment investigation and complete decision explanation, canonical lifecycle
timeline, HMAC-safe same-merchant pivots, investigation saved views, bounded
restart-safe review assignment, denominator-honest insights, a routed and
accessibility-tested workspace, date-versioned generated OpenAPI, stable error
contracts, and one supported Python sandbox SDK. Implementation has not
started. The twenty decisions are confirmed, and all Chapter 53–59
`PRE_IMPLEMENTATION` matrices are frozen proof contracts with open
`IN_PROGRESS` / `DO_NOT_MERGE` dossiers. Chapter 53 and Chapter 57 are the
dependency-ready implementation entries; all evidence remains pending.
The vision matrix registers 23 `VP-NNN` promises and rebaselines Phases 6–10
around unified decision/analyst product, developer contracts, governed ML and
drift, privacy-safe network/graph intelligence, merchant underwriting and
reserve/hold simulation, friendly-fraud/representment, advanced identity,
agents, hosted trust, evidence-triggered scale, external PSP integration, safe
shadow/advisory/enforcement progression, and measured real-merchant outcomes.
Commit readiness now rejects missing/vague owners, stale reviews, missing
merchant/analyst demos, loss of the rebaselined phase contracts, or removal of
the merchant-facing destination.
Trusted synthetic origin, restart-safe generator runs,
idempotent endpoint creation, bounded secret-rotation overlap, fair webhook
claiming, the V13 shipped-schema repair, and V14 fail-stop endpoint-reservation
recovery all have executable evidence. The final hostile re-review also proves
that credentialed Python clients ignore environment proxies, webhook signing
and rotation serialize at the endpoint lock, stale generations retry without
sending, startup inventories every required current/previous/replay KEK in
both worker modes, and generator abandonment retains only sanitized cleanup
references. Named deterministic interleavings provide closure evidence; full
backend and repository Python suites pass as regression context only.
The deterministic processor catalog, capture, cancel/void, full refund, ledger,
audit, and signed at-least-once webhook paths now have one integrated
pre-implementation proof matrix and closure dossier. Chapter 19 remains
complete after the 2026-07-02 final pre-merge recovery covering request-target
encoding, response-head grammar, timeout conversion, migration, signature
freshness, readiness enforcement, and observability interleavings.
Confirm now produces deterministic processor
authorization outcomes, durable fraud lineage, and safe audit records;
`AUTOMATIC` capture completes atomically during approved confirm, `MANUAL`
capture remains an explicit endpoint operation, and approved uncaptured
authorizations can be canceled only with durable void evidence. Captured
payments can now be fully refunded through a durable Refund object with exact
ledger reversal, safe audit/webhook truth, and tenant-scoped retrieval. The Phase 1
synthetic scenario generator continues to create repeatable payment-risk
scenarios through public APIs:

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
  creation-time API key reveal, exact keyed replay, stored prefix/hash behavior,
  validation errors, and follow-on API-key authentication.
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
  only after the mutation response is durable, locks and validates ownership
  before supplier or processor work, fences active work with a reclaimable
  lease, rejects or serializes in-progress duplicates, and releases owned
  reservations after failed mutations so retryable failures are not cached.
- Merchant and customer creation support server-side idempotency. Merchant
  replay returns the same merchant and API key without plaintext key storage,
  including across versioned hash-pepper rotation; customer replay is
  tenant-scoped, stores only safe response IDs, and verifies the request using
  its persisted original hash-key version.
- Chapter 8 service tests prove completed replay, operation mismatch conflict,
  in-progress duplicate rejection, rollback cleanup after mutation failure,
  merchant-scoped key reuse, and in-progress storage shape.
- `POST /payment_intents/{id}/confirm` now creates a tenant-scoped
  `PaymentAttempt` for a valid PaymentIntent and PaymentMethod owned by the
  same merchant/customer.
- Confirm evaluates all three immutable `rules_v1` policies against the exact
  seven-feature decision context and persists one tenant-scoped decision,
  snapshot, seven typed values, and three ordered rule results.
- Confirm applies ALLOW, BLOCK, fraud REQUEST_3DS, or REVIEW before any
  processor call. Only ALLOW reaches `ProcessorAdapter`; non-allow actions
  create no Authorization or money rows.
- REVIEW creates one open tenant-scoped case. Bounded `/fraud/reviews` reads
  expose it, and idempotent merchant cancellation closes intent, attempt, and
  case without rewriting the original decision.
- The deterministic simulator supports approved, issuer decline, insufficient
  funds, suspected fraud decline, CVC failure, AVS line1 failure, AVS postal
  failure, and 3DS-required outcomes from safe stored card metadata.
- Approved outcomes move the `PaymentAttempt` to `AUTHORIZED`; automatic
  PaymentIntents become `CAPTURED` with balanced ledger truth, while manual
  PaymentIntents move to `REQUIRES_CAPTURE`. Retryable declines move the attempt
  to `DECLINED` and the intent to `REQUIRES_PAYMENT_METHOD`; 3DS-required
  outcomes move the attempt and intent to action-required states.
- `GET /payment_attempts/{id}` fetches attempts through merchant scope.
- `GET /payment_attempts/{id}/authorization` returns tenant-scoped safe
  processor authorization evidence for a payment attempt, including processor
  response code, verification results, network status, and amount fields without
  exposing raw payment input or tenant secrets.
- Chapter 11 contract tests prove successful authorization, deterministic
  processor declines, verification failures, 3DS-required behavior, idempotent
  confirm replay, changed-body conflict, invalid status rejection,
  cross-merchant rejection, wrong-customer PaymentMethod rejection, one
  successful concurrent confirm, retry after retryable decline, no duplicate
  authorizations, non-ALLOW fraud decisions never calling the processor,
  processor failure rollback from `PROCESSING`, and unknown simulator card
  default approval.
- Chapter 12 tests prove approved, declined, 3DS-required, retry, replay,
  conflict, rollback, and tenant-isolation paths preserve exactly one fraud
  decision and one feature snapshot per successful confirm attempt, without
  leaking raw card input or duplicating lineage.
- Chapter 13 adds durable `audit_logs` for confirm, fraud-decision completion,
  and processor authorization outcomes, with request IDs and safe before/after
  payloads.
- Chapter 13 tests prove approved, declined, 3DS-required, replay, invalid
  transition, processor rollback, and snapshot rollback paths create the right
  audit records without leaking raw card input, raw PII, API keys, card
  fingerprints, or idempotency hashes.
- `POST /payment_intents/{id}/capture` now requires a tenant-scoped approved
  authorization, records a balanced capture ledger group, moves the
  PaymentIntent to `CAPTURED`, creates `payment_intent.captured` webhook
  delivery records, and writes a safe capture audit log inside the same
  idempotent transaction.
- `GET /ledger/entries` returns tenant-scoped safe ledger rows for the current
  merchant, ordered newest first, through a bounded read API.
- Capture contract tests prove approved capture, balanced debit/credit ledger
  entries, idempotent replay without duplicate ledger/webhook/audit truth,
  database rejection of orphan or duplicate capture ledger truth, double-capture
  rejection, invalid declined/3DS states, tenant isolation, and rollback when
  ledger persistence fails.
- Approved `AUTOMATIC` confirm records balanced capture ledger entries, capture
  webhook delivery records, and a safe capture audit log in the same idempotent
  transaction; approved `MANUAL` confirm remains in `REQUIRES_CAPTURE`.
- `POST /payment_intents/{id}/cancel` distinguishes cancellation without an
  approved authorization from voiding an approved manual authorization.
  Post-authorization cancel appends tenant-bound `authorization_voids` evidence,
  creates safe cancel audit/webhook truth, and prevents later capture.
- Authorization reads expose processor-neutral void evidence without rewriting
  the original authorization decision or leaking simulator/card secrets.
- Chapter 17 tests prove automatic/manual capture semantics, cancel/void
  idempotency, decline/3DS rules, terminal-state rejection, tenant and database
  lineage constraints, and rollback of false capture/cancel truth.
- `POST /refunds` now requires a captured PaymentIntent with matching approved
  authorization and complete capture-ledger evidence, persists a tenant-scoped
  `RECORDED` Refund, exactly reverses capture accounting, moves the intent to
  `REFUNDED`, and commits webhook, audit, and idempotency truth atomically.
- `GET /refunds/{id}` returns only the authenticated merchant's durable Refund
  lineage. Ledger reads expose both original PaymentIntent lineage and optional
  Refund lineage without weakening capture source identity.
- Chapter 18 tests prove automatic/manual full refund, idempotent replay,
  partial/over/double-refund rejection, invalid lifecycle states, tenant and
  database lineage, migration compatibility, rollback on ledger/webhook/audit
  failure, and one coherent winner under a real PostgreSQL refund race.
- Chapter 19 turns webhook records into an executable at-least-once delivery
  system: immutable event envelopes, endpoint-specific delivery state,
  append-only attempts, AES-256-GCM-protected signing keys, exact-byte HMAC
  signatures, explicit-CIDR SSRF-safe target validation, wall-clock request
  deadlines, exact positive-millisecond timeout conversion, exact HTTP/1.0/1.1
  request-target/status/response-field grammar, complete bounded response-head
  acknowledgement, exhaustive retryable DNS-failure classification,
  just-in-time lease recovery, deterministic retries, versioned key rollover,
  cursor-bounded tenant APIs, bounded failure-cause metrics, and bulk
  queued-delivery exhaustion during endpoint disable.
  Endpoint state transitions and event routing use compatible database locks;
  forced PostgreSQL orderings prove rotation cannot reactivate a disabled
  endpoint and concurrent event creation or retry finalization cannot strand
  claimable work after disable. Timestamp canonicalization matches database
  precision, Host authority remains correct across IPv4/IPv6 resolution, and
  merchant guidance plus the executable callback fixture deduplicate only the
  signed payload event ID through durable one-effect state that survives
  concurrent duplicates and process restart. Disabled worker polls still report
  the real due backlog without claiming or sending work.
- V9 honestly migrates legacy endpoints to `REQUIRES_SECRET_ROTATION`, backfills
  legacy delivery envelopes, and enforces same-tenant event/delivery/attempt
  lineage in both H2 and PostgreSQL. It exhausts queued work for already
  disabled legacy endpoints and fails before schema mutation when a V8 row
  claims delivery execution that cannot be reconciled with authoritative
  attempt evidence or contains an unsupported legacy event type.
- Dispatch signatures are minted only after potentially blocking preparation
  and endpoint-lock acquisition,
  the 64 KiB payload and cumulative 32 KiB response-head limits are enforced
  at their real runtime boundaries, every ASCII response-field character class
  and DNS/socket-duration conversion partition has executable boundary-family
  proof, Unicode request targets retain exact meaning through percent-encoding,
  and disable metrics count only rows actually changed after PostgreSQL
  predicate re-evaluation.
- `scripts/demo-webhook-delivery-flow.sh` proves a signed callback that fails
  once, retries with the same event and payload, succeeds, and leaves payment
  truth unchanged.
- The immutable Chapter 20 processor catalog names mapped approval, six
  decline/verification outcomes, 3DS-required, and explicit unknown-card
  default approval without coupling processor truth to FraudDecision truth.
- `scripts/demo-processor-outcome-catalog.py` drives all nine outcome
  partitions through loopback public APIs, reconciles processor/fraud/audit/
  webhook/ledger evidence, and writes only sanitized atomic evidence.
- `scripts/demo-confirm-flow.sh` runs a repeatable local merchant -> customer
  -> payment method -> PaymentIntent -> confirm -> fetch attempt/fraud detail
  demo against a running API.
- `scripts/generate-synthetic-scenarios.py` creates deterministic approved,
  retryable-decline, and basic card-testing scenario families through the public
  API against loopback targets only. Its owner-only run journal resumes safe
  checkpoints with stable idempotency, requires explicit abandonment for
  ambiguous non-idempotent work, rejects redirects before credential
  forwarding, and writes a sanitized atomic summary.
- Chapter 14 clean local demo evidence is recorded in
  `docs/learning/daily/2026-06-26-chapter-14-demo.md`.
- Lifecycle contract tests prove cancel is allowed from uncaptured states,
  capture is rejected before authorization or after cancellation, and refund
  requires capture state plus authorization and ledger evidence.
- Staff-apprenticeship learning notes, deep dives, rubric, weekly review, and
  repetition trackers are in place under `docs/learning/`.
- Non-trivial chapters now require a checked invariant-proof matrix with forced
  PostgreSQL interleavings, protocol-boundary evidence, plan traceability, and a
  separate hostile review before completion.

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
- [Technical and implementation debt register](docs/TECHNICAL_DEBT_REGISTER.md)
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
export THAAP_HASH_PEPPER_VERSION=v1
export THAAP_CORRELATION_PEPPER=change-me-local-sensitive-value-pepper
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
owner, contains a pre-V8 thin `REFUNDED` PaymentIntent without reconstructable
Refund/ledger lineage, or ran an earlier uncommitted draft of V9 while Chapter
19 was still being developed, reset it before normal local startup:

```bash
docker compose -f infra/docker/compose.yml down --volumes
scripts/local-infra-up.sh
scripts/payments-api-run.sh
```

Chapter 13 demo:

```bash
scripts/demo-confirm-flow.sh
THAAP_DEMO_CARD=4999990000000002 scripts/demo-confirm-flow.sh
THAAP_DEMO_CARD=4999990000000008 scripts/demo-confirm-flow.sh
```

Chapter 17 automatic-capture and authorization-void demo:

```bash
scripts/demo-payment-cancel-void-flow.sh
```

Chapter 18 durable-refund and exact ledger-reversal demo:

```bash
scripts/demo-refund-ledger-flow.sh
```

Chapter 13 learning verification:

```bash
scripts/learning-day-check.sh 2026-06-21 --deep-dive-required
```
