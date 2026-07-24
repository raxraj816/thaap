# Chapter 35 Invariant Proof Matrix

- Chapter: Chapter 35 — Integrated Phase 4 Closure
- Matrix provenance: PRE_IMPLEMENTATION
- Scope status: FROZEN
- Review status: VERIFIED
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Fresh hostile review: PASS
- Boundary family review: PASS
- Custom infrastructure review: PASS
- Escaped review assessment: COMPLETE

This matrix was frozen after Chapter 34 implementation and live workflow proof,
but before Phase 4 source reconciliation, integrated regression, all-demo
execution, learning closure, or any Phase 4 completion claim.

The matrix was reopened on 2026-07-25 after ER-0052 through ER-0057 exposed
missing rotation, rollback/retry, actor-replay, signal-lineage, and exact-proof
partitions. Those families are re-closed by V49, exact Lua contracts, real
Redis interleavings, rolling-key identity tests, operator API replay proof, and
signal response-lineage proof. The matrix now returns to
`FROZEN`/`VERIFIED` without changing its honest pre-implementation provenance.

## C35-PAY-001

- Lens: PAYMENT_FRAUD
- Invariant: The integrated merchant-to-payment-to-risk-to-analyst-to-label loop preserves one authoritative payment lifecycle, immutable original fraud decisions, and exactly balanced money outcomes across approval, review, refund, chargeback, and incident cohorts.
- Failure sequence: A later Phase 4 control rewrites authorization-time truth, double-allocates captured value, or leaves contradictory payment, review, label, refund, chargeback, or ledger state.
- Implementation: Planned phase-wide traceability reconciliation over the Chapters 26–34 shared payment, decision, refund, dispute, and incident boundaries.
- Proof: Planned full Java suite, all public demos, retained SQL reconciliation, and exact ledger/payment/decision postconditions.
- Adversarial evidence: Refund/chargeback ordering, review remediation, stale signal, authentication continuation, and incident mitigation.
- Status: PASS

## C35-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: Every Phase 4 operator, review, authentication, risk-context, velocity, list, policy, dispute, signal, incident, rate-limit, audit, webhook, and idempotency boundary remains merchant-qualified and foreign resources are missing-equivalent.
- Failure sequence: Cross-tenant identifiers, credentials, Redis identities, retained rows, or public demos disclose or mutate another merchant's truth.
- Implementation: Planned composite-key, tenant-context, HMAC-domain, repository, and API reconciliation across every chapter.
- Proof: Planned full tenant suites, migration checks, Redis scans, and traceability mapping to each chapter matrix.
- Adversarial evidence: Same external keys, IPs, cards, idempotency keys, operator codes, and UUID-shaped foreign resources.
- Status: PASS

## C35-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: Phase 4 introduces no raw PAN/CVV, credential, IP, email, free text, hostile key, or unbounded identifier into persistence, Redis keys/members, logs, metrics, audit, webhook, demos, or replay bytes.
- Failure sequence: The integrated workflow or failure path leaks sensitive material or creates an unclassified analyst data store.
- Implementation: Planned strict DTO/enums, domain-separated HMACs, bounded diagnostics, and sink-safe demo reconciliation.
- Proof: Planned full security/privacy suites, Python demo-safety discovery, Redis key scan, source scan, and hostile error review.
- Adversarial evidence: Unicode/control payloads, malformed JSON, proxy spoofing, dependency exception, and key rotation.
- Status: PASS

## C35-CON-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Phase 4 lock ordering and fenced idempotency produce one committed winner for payment, refund, chargeback, signal, policy, list, incident, and rate-limit races without duplicate money, lineage, notification, or replay truth.
- Failure sequence: An integrated ordering deadlocks, double-consumes value, duplicates control truth, or exposes an uncommitted winner.
- Implementation: Planned reconciliation of PaymentIntent-first, merchant advisory/pessimistic, version activation, and atomic Redis Lua boundaries.
- Proof: Planned forced PostgreSQL interleavings, real Redis concurrency tests, complete suites, and exact postconditions.
- Adversarial evidence: Same/different keys, rollback winner, stale worker, simultaneous detector, and threshold boundary.
- Status: PASS

## C35-DATA-001

- Lens: DATA_ML_LINEAGE
- Invariant: Phase 4 facts remain append-only, point-in-time reproducible, and explicit about occurrence, receipt, availability, source attestation, rules/list/policy version, analyst action, and label provenance.
- Failure sequence: Migration or later workflow rewrites historical features, decisions, reviews, signals, labels, disputes, or incidents, or backdates training availability.
- Implementation: V26–V48 lineage and immutability reconciliation plus V49 retained identity aliases and principal-scoped idempotency.
- Proof: Planned PostgreSQL migration/history tests, append-only mutation rejection, retained cohort comparison, and full lineage suites.
- Adversarial evidence: Out-of-order evidence, legacy refunds, version rotation, direct SQL substitution, and retained subscriptions.
- Status: PASS

## C35-API-001

- Lens: API_PRODUCT
- Invariant: Public Phase 4 contracts distinguish recommendation, fraud decision, authentication request, analyst action, financial action, simulator evidence, suspected incident, and rate limiting with exact replay and stable closed errors.
- Failure sequence: Documentation or API language overclaims network truth, equates a 429 with fraud, hides money action provenance, or changes terminal replay bytes.
- Implementation: Planned all-demo workflow and README/API/source reconciliation.
- Proof: Planned public demos Chapters 26–34, contract suites, vocabulary scan, and source-of-truth review.
- Adversarial evidence: Recommendation-only signal, zero-offset dispute, reused refund, Redis outage, and resolved incident.
- Status: PASS

## C35-OBS-001

- Lens: OBSERVABILITY
- Invariant: Phase 4 operational signals distinguish commit/rollback, control outcome, dependency ambiguity, analyst action, and durable incident lifecycle using only closed low-cardinality tags.
- Failure sequence: Rollback appears committed, outage appears fraud/abuse, or identifiers enter telemetry.
- Implementation: Planned phase-wide metric, after-completion, log, readiness, and error-mapping reconciliation.
- Proof: Planned metrics tests, failure injection, live readiness, source scans, and exact 429/503 proof.
- Adversarial evidence: Audit/webhook failure, Redis ambiguity, foreign denial, concurrent loser, and hostile input.
- Status: PASS

## C35-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Fresh V1 and retained V25/V44/V46/V48 upgrades converge to V49 with Hibernate validation, preserved historical bytes and webhook consent, empty-by-default new truth, and successful current-binary startup.
- Failure sequence: A migration fabricates Phase 4 state, mutates history/consent, loses a required key, or passes fresh while failing retained startup.
- Implementation: Planned forward-only migration and retained local-volume validation.
- Proof: `PostgreSqlMigrationTests`, retained V48-to-V49 migration, schema/history/constraint checks, and the clean full suite.
- Adversarial evidence: Legacy refunds, active reviews, authentication replay keys, policies, chargebacks, signals, and absent Redis history.
- Status: PASS

## C35-OPS-002

- Lens: OPERATIONS_RESILIENCE
- Invariant: Phase 4 closure has zero blocking escaped findings or undocumented debt, all structural gates pass, and every source of truth names the same completion state and next owner slice.
- Failure sequence: A green suite masks stale docs, missing learning artifacts, contradictory matrix/dossier state, stale debt, or an unowned deferral.
- Implementation: Planned debt, escaped-review, learning, invariant, dossier, commit-readiness, and status reconciliation gates.
- Proof: Planned exact script outputs and repository-wide status scan.
- Adversarial evidence: P0/P1 finding, frozen-promise P2, vague deferral, stale review date, and retroactive provenance claim.
- Status: PASS

## C35-PROD-001

- Lens: API_PRODUCT
- Invariant: Phase 4 ends with a demoable and defensible closed loop from merchant payment through risk context and decision to analyst action, label feedback, post-authorization remediation, and suspected-abuse operations without claiming broad network parity.
- Failure sequence: Closure consists only of disconnected APIs/tests, omits a public workflow, or overstates Stripe/Mastercard-class coverage.
- Implementation: Planned integrated chapter/demo map, architecture narrative, founder-facing learning summary, and explicit deferrals.
- Proof: Planned all public demos, READMEs/master plan reconciliation, and closure dossier review.
- Adversarial evidence: No-action, review, 3DS, policy, chargeback, refund, incident, rate-limit, and dependency-failure cohorts.
- Status: PASS

## Verification evidence supplement

### Integrated product and source-of-truth proof

- All public Chapter 26–34 demos previously passed sequentially against the V48
  binary and retained PostgreSQL/Redis substrate. They cover review-to-label,
  approval-to-processor, both authentication sources, all RiskContext actions,
  velocity/lists/outage recovery, policy activation, chargeback/refund offsets,
  post-authorization refund/label remediation, and incident/rate-limit truth.
- The integrated pass re-opened two cross-chapter contracts: Chapter 29 now
  reconciles the later velocity/policy feature catalogue, and Chapter 30 now
  proves Chapter 34's fail-closed Redis guard precedence (`503`, zero attempt)
  before same-key recovery. Both demo safety suites pass.
- Root/service/docs/script READMEs, master plan, detailed plan, learning notes,
  nine implementation matrices/dossiers, and this integrated closure now name
  the same Phase 4 completion state.

### Regression, migration, concurrency, and operations proof

- Clean Java regression passes 629/629 with zero failures, errors, or skips.
  Full repository Python discovery passes 114/114.
- `PostgreSqlMigrationTests` passes 14/14: fresh V1-to-V49 Hibernate validation,
  retained staged upgrades, empty-by-default new tables, prior row/subscription
  preservation, semantic/history triggers, and intentional negative migration
  fixtures. The current binary also started on and exercised the real retained
  V48-to-V49 retained upgrade proof.
- The real retained local PostgreSQL volume advanced from V48 to V49 without a
  reset. Startup with omitted historical webhook KEK failed closed after the
  migration, as required; an isolated fresh V49 runtime with valid ephemeral
  key configuration returned `UP` for health and readiness before clean
  shutdown and smoke-database removal.
- Forced PostgreSQL interleavings cover review/action, authentication, context,
  lists/policy, refund/chargeback, post-authorization refund, source-event, and
  incident detector winners. Real Redis covers atomic threshold concurrency,
  TTL/cardinality, exact retries, script reload, and ambiguity.
- Technical-debt check passes with five resolved and zero open entries; escaped-
  review check passes for 57 immutable findings; daily/deep-dive/weekly learning
  gates pass. ADR-0010 remains Accepted and exact Lua boundary proof passes.

### Review disposition

- Plan-to-implementation traceability: PASS.
- Temporally separate hostile failure-sequence pass: PASS. It re-opened API
  vocabulary, feature-count reconciliation, rate-limit-versus-velocity outage
  precedence, HMAC rotation, SQL timestamp binding, concurrent signal/incident
  convergence, migration, demo, and bounded-log families.
- Boundary-family review: PASS; each discovered partition has table-driven,
  forced-ordering, real-dependency, migration, safety-test, or live-demo proof.
- Blocking debt/findings: zero. ER-0052 through ER-0057 are resolved with
  product evidence and permanent process corrections.

### Reopened remediation proof

- ER-0052: a real Redis previous-version window remains visible under a rolling
  keyring; max aggregation preserves count without double-counting dual writes.
- ER-0053: insertion-only owner tokens and exact compare-delete compensation
  prove both stale-cleanup and retry-rollback interleavings on real Redis.
- ER-0054: V49 retained IP/device/session aliases and matching inner/outer HMAC
  versions preserve all three typed-list identity families through rotation.
- ER-0055: V49 principal/credential scoped idempotency and an API contract prove
  same-actor replay while a lower-role second actor cannot consume that replay.
- ER-0056: earlier no-action signal POST replay and GET remain `NONE` after a
  later review signal for the same payment.
- ER-0057: current completion evidence is exactly 629 Java and 114 Python tests.
