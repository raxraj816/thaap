# Chapter 43 Invariant Proof Matrix

- Chapter: Chapter 43 — Integrated Phase 4.5 Closure
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

The `ER-0064` through `ER-0073` recovery re-proved historical truth,
authorization, external side effects, report lineage, and the local product
surface. All ten findings are resolved; the four expanded PostgreSQL families
and the complete 759-test backend suite pass without skips.

This matrix was drafted and frozen on 2026-07-27 after Chapter 42's formal
readiness gate passed and before Chapter 43 integrated migration, demo,
regression, traceability, hostile-review, or source-reconciliation work. It
derives from the approved Phase 4.5 completion gate and the frozen promises of
Chapters 36–42. Chapter 43 adds no new product behavior unless integrated proof
finds a defect that must be closed before the phase can truthfully complete.

## C43-E2E-001

- Lens: PAYMENT_FRAUD
- Invariant: A newly provisioned merchant can execute the public payment-risk loop through documented API/console surfaces—merchant payment, durable risk context and decision, analyst action, label or operational feedback—without source reading, hidden database mutation, or a privileged alternate payment path.
- Failure sequence: A demo relies on seeded private IDs, source-only knowledge, direct SQL, synthetic traffic masquerades as merchant traffic, an analyst action loses the originating decision, or a recovery operation repeats money/fraud mutation.
- Implementation: Reconcile and execute the seven Phase 4.5 public clients and the memory-only cockpit against retained PostgreSQL using freshly provisioned tenants and documented credentials.
- Proof: All seven loopback public clients passed with freshly provisioned tenants against retained V64 PostgreSQL. The new-merchant console run reached `SUCCEEDED` with 4/4 verified steps and `MERCHANT_API:1|SYNTHETIC_SCENARIO:1`; monitoring, workspace, simulation, control, dispute, and webhook clients reconciled their exact durable lineage without mutating SQL.
- Adversarial evidence: New/existing tenant, merchant/synthetic origin, success/decline/review/dispute/webhook recovery, restart, duplicated request, and absent source access.
- Status: PASS

## C43-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: Every Phase 4.5 read, mutation, job, saved view, note, simulation, control, dispute, scenario, and webhook operation derives tenant and actor from authenticated context and rejects copied foreign identifiers without disclosure or cross-tenant state.
- Failure sequence: A foreign queue cursor, resource ID, idempotency key, run ID, endpoint, delivery, note, or control binds to the caller's tenant or leaks another tenant's existence.
- Implementation: Reconcile the actor/tenant matrices, composite PostgreSQL keys, replay authorization, and two-tenant contract/concurrency proofs from Chapters 36–42.
- Proof: The integrated traceability pass mapped all Chapter 36–42 actor/tenant matrices to authenticated controllers, tenant-qualified services/constraints, two-tenant contracts, replay authorization, and deterministic PostgreSQL tests; the 759-test backend regression passed without skips.
- Adversarial evidence: Merchant, viewer, analyst, manager, runner, disabled principal, wrong tenant, copied IDs/cursors/keys, and replay after authorization change.
- Status: PASS

## C43-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: Browser and public clients keep credentials in memory/environment only; protected note plaintext, card/security data, webhook secrets/tokens/signatures, purpose-HMAC subjects, raw seeds, URLs, payloads, and tenant/actor identifiers remain outside prohibited API, audit, log, metric, report, and UI sinks.
- Failure sequence: Retained note encryption cannot restart, redaction leaves recoverable plaintext, console persists credentials, a public demo prints a secret, or an error/metric/log exposes attacker-controlled sensitive bytes.
- Implementation: Re-run note key/retention/redaction, credential non-persistence, safe-client, privacy-canary, challenge hash-only, encryption-rotation, and closed-observability proofs.
- Proof: Note encryption, key inventory, correction, retention, terminal redaction, and privacy-canary suites passed; the retained V64 runtime restarted `UP` with current and previous note/authentication/webhook key versions and an active encrypted `local-v2` note. Public clients printed only sanitized evidence. Seven UI tests/build plus a live browser reload proved credential fields are memory-only.
- Adversarial evidence: Current/previous/missing key, before/at/after retention, local/session storage, URL/PAN/CVV/token/signature/note canaries, malformed protocol, and dependency error.
- Status: PASS

## C43-TXN-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Phase 4.5 operations preserve immutable money, fraud, label, event, and ledger truth while mutable projections advance once under deterministic PostgreSQL lock order, idempotency, leases, generations, versions, and fenced stale workers.
- Failure sequence: Simulation or webhook replay repeats a payment mutation, concurrent activation bypasses dual control, stale scenario worker commits, dispute submission binds changing evidence, or redelivery/disable produces an illegal sendable state.
- Implementation: Re-run forced PostgreSQL interleavings, history-trigger mutation rejection, idempotency replay, rollback, lease, activation, deadline, and webhook generation suites.
- Proof: Focused `PostgreSqlMigrationTests`, historical simulation, developer-scenario lease, review-resolution, dispute actor-fencing, and webhook forced-ordering suites passed; full regression passed 759 tests with zero failures/errors/skips, including rollback, idempotency, money/ledger, activation, dispute, and history-trigger families.
- Adversarial evidence: Same/different key, simultaneous actors, stale version/lease/claim/generation, before/at/after deadline, rollback, rotation, disable, timeout, and retry exhaustion.
- Status: PASS

## C43-ML-001

- Lens: DATA_ML_LINEAGE
- Invariant: Monitoring, simulation, governed controls, analyst labels, disputes, scenarios, and webhook evidence preserve exact origin, event time, feature/policy/rule version, label availability, denominator, coverage, and immutable decision-time lineage without claiming model training or genuine network truth.
- Failure sequence: Current features replace historical ones, synthetic events enter default merchant metrics, unlabeled rows disappear, a candidate activates without its exact fresh simulation, or synthetic dispute outcomes become money/network labels.
- Implementation: Reconcile monitoring denominator/origin queries, Spring Batch restart reports, rule property suites, exact activation evidence, immutable decision reasons, and synthetic/live separation.
- Proof: Historical simulation restart/cancel/resume, candidate snapshot, operational boundary, authored-rule grammar/property partitions, activation, monitoring formula/cohort/origin, and full regressions passed. Public evidence preserved unlabeled/origin/version truth and never claimed model training or network settlement.
- Adversarial evidence: Live/synthetic, labeled/unlabeled/contradictory, old/current feature and policy versions, restart/cancel/resume, rule boundary values, protected/overridable candidates, and stale simulation.
- Status: PASS

## C43-API-001

- Lens: API_PRODUCT
- Invariant: The documented local/test-mode cockpit and public clients expose bounded, typed, accessible, failure-honest workflows for Chapters 36–42; a new user needs no source-only constants, and the UI never invents optimistic server state or hosted identity claims.
- Failure sequence: README examples drift, an API accepts unknown state-shaping fields, empty/error/loading states disappear, credentials persist, a demo requires direct database access, or a browser action reports success before refresh.
- Implementation: Execute all public demos, all UI tests and production build, strict API contracts, and documentation-to-runtime command reconciliation.
- Proof: Monitoring, analyst workspace, historical simulation, governed controls, dispute operations, developer scenarios, and webhook operations public clients all passed. The browser rendered the accessible failure state and nine simulator-only cards, required both scenario credentials, and cleared all credential fields on reload; 7 Vitest tests and the Vite production build passed.
- Adversarial evidence: Empty/loading/error/success, invalid role/credential/input, stale state, refresh/restart, keyboard/accessible name, unknown JSON, unsupported media/method, and no-source use.
- Status: PASS

## C43-OBS-001

- Lens: OBSERVABILITY
- Invariant: Readiness and low-cardinality signals report committed monitoring, workspace, simulation, control, dispute, scenario, and webhook state with bounded dimensions and sanitized errors; a missing dependency/schema/key cannot report ready.
- Failure sequence: A rolled-back action increments success, high-cardinality IDs enter tags, missing note key or schema stays healthy, backlog disappears, or operator errors expose secrets.
- Implementation: Reconcile every Phase 4.5 health contributor, metric vocabulary, transaction-after-commit emission, sanitized logs/errors, and retained-stack readiness.
- Proof: Retained V64 readiness was `UP` across two process starts; every Chapter 36–42 health/metrics/privacy contract passed in the 759-test regression. The hostile source pass found only closed dimensions and no credential/note/payload persistence or metric/log expansion.
- Adversarial evidence: Dependency up/down, worker enabled/disabled, schema/key present/missing, rollback, stale backlog, sensitive canaries, and every closed outcome/error class.
- Status: PASS

## C43-MIG-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Fresh V1→V64 and retained Phase 4→Phase 4.5 upgrades install the same constraints, indexes, triggers, catalogues, and readiness substrate while preserving all existing payment/fraud/ledger/audit/event history and inventing no Phase 4.5 facts.
- Failure sequence: Retained upgrade rewrites history, backfills synthetic business state, loses encrypted-note compatibility, requeues exhausted delivery, omits a trigger/index, or diverges from a fresh install.
- Implementation: Re-run fresh and retained PostgreSQL migration suites plus retained local-stack startup through V64 and reconcile every V50–V64 object and compatibility claim.
- Proof: `PostgreSqlMigrationTests` passed fresh V1→V64 and retained intermediate upgrades, including real V62 exhausted webhook history; the retained local schema remained at V64 and started twice without migration or invented-state work. H2 and PostgreSQL both validated all 64 forward migrations.
- Adversarial evidence: Empty V1, retained V49/V50/V52/V54/V56/V58/V60/V62, populated terminal/in-progress rows, repeated startup, current/previous key presence, and downgrade rejection.
- Status: PASS

## C43-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Bounded jobs, schedulers, leases, expiries, simulations, clients, and webhook transport fail closed, resume idempotently, preserve exact durable progress, and remain truthful through process restart, timeout, proxy/redirect, malformed response, and optional-worker configuration.
- Failure sequence: Restart duplicates rows, cancellation becomes success, stale lease advances progress, network I/O holds database locks, redirect/proxy bypasses loopback policy, or an expired item remains silently actionable.
- Implementation: Re-run simulation restart/cancel/resume, note key restart, scenario lease, dispute/verification expiry, client security, webhook protocol, and retained runtime demos.
- Proof: Spring Batch restart/cancel/finalizer, scenario lease takeover/stale fencing, note retention/key startup, bounded expiry schedulers, client redirect/proxy/timeout, exact webhook response grammar, and two retained-runtime starts passed. External webhook HTTP remained outside database transactions and exact redelivery converged at generation 2.
- Adversarial evidence: Crash before/after checkpoint, repeated scheduler, timeout before/during/after write, redirect, proxy environment, malformed/oversized response, stale owner, and dependency outage.
- Status: PASS

## C43-GOV-001

- Lens: API_PRODUCT
- Invariant: Chapters 36–43 have `PRE_IMPLEMENTATION`/`FROZEN`/`VERIFIED` matrices with every row `PASS`, `READY_TO_MERGE` dossiers recommending `MERGE`, reconciled plans/READMEs/learning artifacts, valid debt and escaped-review registers, and zero undocumented blocking finding.
- Failure sequence: One source calls the phase complete while a matrix is pending, evidence names a nonexistent test, an accepted risk lacks `TD-NNNN`, an escaped defect lacks `ER-NNNN`, or the readiness gate is run against the wrong chapter artifacts.
- Implementation: Run structural gates for every phase matrix/dossier, independently trace claims to exact implementation/proof, perform a later hostile failure-sequence pass, and reconcile all source-of-truth documents only after evidence passes.
- Proof: Chapters 36–42 each pass the invariant checker with 78 total `PASS` rows and seven `READY_TO_MERGE`/`MERGE` dossiers. The separate traceability and later hostile passes resolved `ER-0058`–`ER-0073`; escaped-review reports 73 resolved findings, debt reports 5 resolved/0 open, naming and learning gates pass, and Chapter 43 final readiness is recorded in its dossier.
- Adversarial evidence: Contradictory status, missing/renamed test, stale debt, escaped finding, custom infrastructure without ADR, speculative scope, and false completion wording.
- Status: PASS

## Frozen Boundary Table

| Boundary | Valid | Rejected |
|---|---|---|
| Product loop | documented public API/cockpit over ordinary paths | source-only setup, SQL mutation, privileged alternate flow |
| Tenant/actor | authenticated tenant-qualified resources and closed roles | copied foreign identity, caller-selected tenant/role |
| Sensitive data | encrypted/hash-only or memory-only at the declared boundary | plaintext persistence, logs, metrics, reports, UI storage/output |
| Durable truth | immutable event/decision/evidence plus fenced projections | replayed money/fraud mutation, stale worker or version write |
| Fraud lineage | point-in-time, origin/version/coverage explicit | current-state substitution, synthetic/live or label conflation |
| API/UI | bounded typed documented workflows with server refresh | optimistic truth, unknown fields, hidden credentials/constants |
| Operations | bounded resumable jobs and fail-closed dependencies | unbounded scan, false readiness, lock-spanning external I/O |
| Migration | fresh and retained V1–V64 truth-preserving convergence | invented facts, history rewrite, missing protection |
| Governance | all matrices/dossiers/gates/sources agree | undocumented debt/finding or contradictory completion claim |
