# Chapter 21 Closure Dossier

- Chapter: Chapter 21 — Previous-Phase Debt Retirement And Zero-Debt Entry Gate
- Chapter number: 21
- Status: READY_TO_MERGE
- Invariant matrix: docs/reviews/CHAPTER_21_INVARIANT_PROOF_MATRIX.md
- Matrix provenance: PRE_IMPLEMENTATION
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Hostile review: PASS
- Boundary family review: PASS
- Custom infrastructure review: NOT_APPLICABLE
- Escaped review assessment: COMPLETE
- Merge recommendation: MERGE

## Observable promises

- A dedicated authenticated boundary—not merchant metadata—assigns trusted
  synthetic origin, and the value reaches decision-time and event lineage.
- A loopback generator run survives restart without duplicating completed
  product mutations; ambiguous non-idempotent work stops for explicit operator
  disposition while preserving sanitized cleanup references.
- Endpoint creation is tenant-idempotent, safely replays its encrypted
  reveal-once response, conflicts on changed/superseded requests, and prevents
  same-tenant exact-URL duplicates; fail-stop reservations are leased and
  reclaimable without stealing active ownership.
- Claimed deliveries remain signable with their bound previous generation
  only when the exact signature timestamp is strictly before expiry; expired
  generations, missing required KEKs, and overlapping rotations fail closed.
- No merchant or endpoint can consume an entire poll indefinitely; claiming is
  round-robin, budgeted, observable, lease-safe, and one-at-a-time.
- Every debt originating before Phase 3 is resolved with retained evidence.

## Failure inventory

| Boundary | Hostile sequence |
| --- | --- |
| Origin | Merchant forges metadata/header or origin disappears between intent, attempt, decision, snapshot, and event |
| Effective destination | A redirect or environment proxy sends API, admin, runner credentials, or request body somewhere other than the validated loopback origin |
| Generator | Crash after remote commit but before checkpoint; two owners resume; non-idempotent outcome is ambiguous; abandonment erases cleanup identity |
| Endpoint create | Lost response retries, process dies after reservation commit, changed body reuses key, same URL uses another key, rotation supersedes replay |
| Secret rotation | Claim precedes rotation, wait crosses expiry before signing, a required previous/replay KEK is absent, second rotation overwrites active previous material |
| Fairness | Noisy merchant/endpoint fills every poll, quota exclusion duplicates claims, cursor strands due work |
| Migration | Clean schema passes while an actually shipped V9 schema cannot satisfy current Hibernate validation |
| Privacy | Journal, summary, audit, metric, API, or log leaks API/runner/idempotency/signing secrets, cards, PII, URL, or ciphertext |
| Closure | A debt remains open, PostgreSQL skips, an escaped finding is hidden, or README and plan disagree |

## Boundary family evidence

| Family | Partitions | Proof |
| --- | --- | --- |
| Origin authority | No credential, valid credential, invalid credential, synthetic-looking metadata, legacy default | Create/confirm contracts, V10 migration, live scenario run |
| Generator state | New, active lease, completed step, safe STARTED, unsafe STARTED, completed run, abandonment, changed identity, sanitized cleanup references | Real-SQLite generator tests and live failpoint/resume |
| Credentialed HTTP destination | Initial loopback request, 301, 302, 303, 307, 308, environment proxy, API/admin/runner headers | Empty proxy policy, fail-closed redirect handler, and real direct/redirect/proxy server proof in both clients |
| Endpoint idempotency | Same/different request, same/different tenant, active/expired reservation lease, same URL/different key, replay before/after rotation | `WebhookEndpointOperationsTests`, deterministic PostgreSQL reclaim/wait proof, V11/V14 constraints, V12 uniqueness |
| Rotation | Current, previous before expiry, wait crossing expiry, previous at/after expiry, missing current/previous/replay KEK, overlap rotation, disabled endpoint | Endpoint operation/security/executor tests and existing forced PostgreSQL lock orders |
| Fairness | Noisy/quiet merchants, sibling endpoints, saturated merchant/endpoint budgets, concurrent workers | Executor unit proof and `WebhookDeliveryPostgreSqlConcurrencyTests` |
| Migration | Clean V1-to-V14, real V9-to-V13, known-send backfill, nullable tri-state send evidence, stranded V13 creation reservation | H2/PostgreSQL migration suites, ER-0022 live startup, and V14 historical-row proof |
| Runtime | Test scheduler lifecycle, H2/Flyway, Testcontainers 2, Mockito agent, native DNS/access | Warning scan plus full zero-skip suite |

The temporally separate hostile pass found ER-0022: clean migration tests rebuilt
V9 from mutable Java source and missed the shape already applied locally. V13
now repairs that historical shape additively and a PostgreSQL fixture preserves
known-send evidence through the upgrade. The final effective-destination pass
then found ER-0023: the older generator still used default redirect handling.
Its dedicated no-redirect opener and real-server status partition now prove
privileged headers never reach a redirect target.

The end-to-end re-review then found ER-0024 through ER-0028: inherited proxy
configuration, signature-use expiry TOCTOU, incomplete KEK inventory, stranded
endpoint-create reservations, and erased cleanup references. Closure was
reopened until explicit proxy policy, use-time validation, complete ciphertext
inventory, V14 fenced leases, and a sanitized resource ledger had executable
hostile proof.

## Enforcement boundaries

- `MerchantContextArgumentResolver` authenticates origin authority before a
  merchant operation; immutable entity columns and event construction carry it
  forward.
- `GeneratorRunStore` uses SQLite immediate transactions, an expiring owner
  lease, guarded run states, stable step keys, rollback journaling,
  `secure_delete`, and owner-only file permissions. Public API idempotency
  remains the remote convergence boundary.
- The generator `ApiClient` rejects redirects before request creation; initial
  loopback validation, explicit no-proxy policy, and effective-destination
  enforcement are separate security boundaries.
- `generator_resources` retains only safe tenant/resource identifiers across
  terminal checkpoint-payload erasure.
- `webhook_endpoint_creation_records` stores HMACed external identity and
  AES-GCM response material under endpoint/merchant AAD. Independent
  reservation and operation transactions distinguish retry from rollback; V14
  owner tokens and expiries fence active work and recover process death.
- V12 tenant-plus-URL uniqueness is the duplicate-stream database boundary.
- Endpoint row locks and secret generations define rotation truth; claim rows
  retain their generation, and a bounded use window is checked against the
  exact signature timestamp.
- Startup derives required KEKs from active current, active unexpired previous,
  and unexpired generation-matching creation-replay ciphertext.
- `webhook_dispatch_cursor` serializes durable round-robin choice while
  delivery row locks and existing leases remain the claim boundary.
- V13 is additive; it backfills only rows already known to have sent HTTP and
  installs the current dispatch-state constraint.
- V14 additively leases endpoint-create reservations and makes historical
  stranded `IN_PROGRESS` rows immediately reclaimable.

## Exact proof

- Full backend: Maven passed 240 tests with zero failures, errors, or
  skips across 39 reports.
- Repository Python: `python3 -m unittest discover -s scripts -p '*_tests.py'`
  passed 60 tests.
- Generator-focused Python covers leases, checkpoint replay,
  crash-after-commit, unsafe ambiguity, abandonment with retained safe
  references, redirect rejection, hostile proxy configuration, redaction, and
  atomic output.
- PostgreSQL proof includes clean/upgrade migrations, V13 historical repair,
  V14 stranded-reservation recovery, deterministic endpoint-reservation lock
  waiting, noisy/quiet fairness, endpoint fairness, exclusive claims,
  secret/disable ordering, payment lifecycle concurrency, and refund
  concurrency.
- Matrix: all 17 entries across all eight required lenses are `PASS`.
- Debt check: five immutable entries, zero open, five resolved.

## Migration evidence

- V10 adds non-null origin lineage with conservative `MERCHANT_API` legacy
  defaults.
- V11 adds secret generations, encrypted endpoint-create replay records,
  state/expiry constraints, and the durable dispatch cursor.
- V12 rejects pre-existing tenant URL duplicates before installing uniqueness.
- V13 repairs the shipped pre-dispatch attempt representation and conservatively
  maps known sends to `dispatch_started_at = started_at`.
- V14 adds fenced expiring endpoint-create reservations and backfills older
  `IN_PROGRESS` rows into an immediately recoverable state.
- Fresh H2 and PostgreSQL schemas reach V14 and Hibernate validation. The real
  local V9 database upgraded through V10–V13 and started successfully.

## Demo evidence

- `chapter21-live-resume` targeted a loopback API backed by the existing
  PostgreSQL database.
- A failpoint stopped after the first PaymentIntent remote commit and before
  checkpoint completion.
- Reusing the same run ID resumed safely and produced exactly five distinct
  intended PaymentIntents, five attempts, and five FraudDecisions.
- All five intents and their decision-time lineage are
  `SYNTHETIC_SCENARIO`; four cross-tenant reads and the sensitive-data scan
  passed.
- The SQLite journal and JSON report are mode `0600`.

## Operational signals

- Generator run/step status, lease, cleanup disposition, and safe terminal
  summary/resource inventory make restart and cleanup state inspectable without
  publishing checkpoint secrets.
- `thaap.webhook.claim.throttled` uses only bounded
  `scope=merchant|endpoint`; due, queue-age, attempt, failure, and terminal
  webhook signals remain intact.
- Endpoint audit records contain status, safe secret prefix, subscriptions, and
  request correlation—not raw secret, URL, ciphertext, or idempotency key.
- V13 migration/Hibernate startup and V14 stranded-row recovery are executable
  deployment signals rather than clean-schema assumptions.

## Known debt

`docs/TECHNICAL_DEBT_REGISTER.md` contains five entries and all are
`RESOLVED`. Chapter 21 accepts no new technical, implementation, security,
data/ML, or operations debt. Phase 3 starts from zero non-resolved debt.

## Custom infrastructure decision

No new proprietary HTTP, cryptography, queue, or scheduling protocol was
introduced. The implementation uses Spring transactions, PostgreSQL row locks,
AES-GCM already reviewed in Chapter 19, Python standard-library SQLite, and
public API idempotency. Chapter 19 ADR-0009 remains the owner of the existing
webhook socket transport and is unchanged.

## Escaped-review assessment

- ER-0001 through ER-0021 remain resolved and their relevant regression proof
  reran.
- ER-0022 records the historical V9 schema drift found by the live Chapter 21
  upgrade. V13, the immutable-shape PostgreSQL fixture, and successful live
  startup resolve both product and review-process causes.
- ER-0023 records the generator redirect escape. A fail-closed opener plus
  real-server proof for 301/302/303/307/308 resolves both product and process
  causes.
- ER-0024 records inherited environment proxy routing; explicit empty proxy
  policy and hostile real-proxy proof resolve it in both clients.
- ER-0025 and ER-0026 record exact-use expiry and complete-KEK-inventory gaps;
  signature-time validation and current/previous/replay inventory resolve them.
- ER-0027 records fail-stop endpoint reservation stranding; V14 fenced leases,
  historical migration proof, and deterministic PostgreSQL reclaim resolve it.
- ER-0028 records lost generator cleanup lineage; a sanitized resource ledger
  survives terminal secret erasure.
- No unresolved P0/P1 or frozen-promise P2 finding exists.

## Source-of-truth reconciliation

- README and the master plan mark Chapter 21 and Phase 2.5 complete and leave
  Phase 3 behind the now-satisfied zero-debt gate.
- Chapter 16, Chapter 19, Phase 4.5, and Phase 7 references identify all five
  debt IDs as resolved while preserving future feature scope.
- The debt register, escaped register, webhook concept, script README, daily
  note, deep dive, weekly review, matrix, and this dossier describe the same
  implemented boundaries.
- No implementation claim depends on chat history or untracked generated
  evidence.

## Merge recommendation

`MERGE`. All frozen Chapter 21 promises have real enforcement, fresh H2 and
PostgreSQL proof, a successful crash/resume demo, reconciled sources, zero
non-resolved debt, and no blocking escaped finding.
