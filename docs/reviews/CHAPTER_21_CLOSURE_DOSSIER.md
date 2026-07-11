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

Closure was reopened on 2026-07-11 by `ER-0036` through `ER-0040`. The repair
adds an immutable merchant lifecycle epoch, endpoint-lock-held exact-use
signing, retryable stale-generation convergence, mode-independent persisted
KEK readiness, and reservation locking before endpoint mutation. Focused H2
contracts and two forced PostgreSQL interleavings address the product defects.
The earlier closure was invalid because the five state machines were combined,
the required transition tables were absent before already-present fixes, the
hostile review lacked a later checkpoint, and aggregate test count was used as
persuasive evidence. Five honest `RETROACTIVE_RECOVERY` packets now enumerate
the transition grammars, answer both mandatory closure questions, name narrow
implementation commits, and point to later hostile-proof commits. The slice
validator proves the checkpoint ancestry before closure.

The first recovery-table pass found two additional blocking P1 sequences.
`ER-0041` covers a previous-generation signer waiting across expiry after its
timestamp was captured. `ER-0042` covers stale-generation no-send failure at
the final retry ceiling. Post-lock time capture and final-attempt current-secret
rebinding resolve them with deterministic evidence in Slices B and C.

Closure update: `ER-0029` and `ER-0030` invalidated the prior recommendation,
then were fixed at their durable-state and server-commit boundaries. Persisted
retry classification is now immutable; merchant/customer creation converges
through V15 fenced server reservations and stable generator keys across the
remote-response-before-local-write window.

The subsequent Senior Staff review invalidated closure again with `ER-0031`
through `ER-0033`. The repaired boundary now locks idempotency ownership before
processor or mutation work, preserves merchant replay through versioned pepper
rotation with V16 identity aliases and fail-closed key inventory, and heartbeats
generator ownership around every bounded HTTP request.

The subsequent end-to-end review invalidated closure again under `ER-0034` and
`ER-0035`. The secret-rotation review had scoped the HMAC boundary to merchant
creation instead of inventorying every persisted digest, and generator cleanup
lineage still depended on a step-name suffix convention. V17 now versions
generic and endpoint request hashes, adds tenant-scoped endpoint identity
aliases, inventories every live replay version, and separates stable
fraud/PII correlation from replay-key rotation. Generator resource type is
explicit immutable protocol state with legacy backfill. Clean/upgrade
migrations, rolling PostgreSQL contention, live/expired key partitions, and
complete cleanup-ledger reconciliation resolve both findings.

## Observable promises

- A dedicated authenticated boundary—not merchant metadata—assigns trusted
  synthetic origin, and the value reaches decision-time and event lineage.
- A loopback generator run survives restart without duplicating completed
  product mutations; persisted retry classification cannot drift; merchant and
  customer creation reconcile after an exact-window client death and pepper
  rotation; cumulative verification latency cannot surrender live ownership;
  genuinely ambiguous non-idempotent work stops for explicit operator
  disposition while preserving sanitized cleanup references.
- Endpoint creation is tenant-idempotent, safely replays its encrypted
  reveal-once response, conflicts on changed/superseded requests, and prevents
  same-tenant exact-URL duplicates; fail-stop reservations are leased and
  reclaimable without stealing active ownership, including after lease expiry
  while the live owner is blocked before its first mutation.
- Claimed deliveries remain signable with their bound previous generation
  only when the exact signature timestamp is strictly before expiry; expired
  generations retry without sending so a later claim can bind current state,
  while a final stale attempt rebinds current state before its first HTTP send;
  rotation cannot cross an in-progress signature, lock wait cannot hide expiry,
  and missing required KEKs fail startup even when workers are disabled.
- No merchant or endpoint can consume an entire poll indefinitely; claiming is
  round-robin, budgeted, observable, lease-safe, and one-at-a-time.
- Every debt originating before Phase 3 is resolved with retained evidence.

## Failure inventory

| Boundary | Hostile sequence |
| --- | --- |
| Origin | Merchant forges metadata/header or origin disappears between intent, attempt, decision, snapshot, and event |
| Effective destination | A redirect or environment proxy sends API, admin, runner credentials, or request body somewhere other than the validated loopback origin |
| Generator | Crash after merchant/customer remote commit but before any local write; caller changes durable retry or resource-type classification; a resource-producing step is absent from cleanup lineage; cumulative successful network waits outlive the original lease; two owners resume; unsafe outcome is ambiguous; abandonment erases cleanup identity |
| Resource create | Same key changes request, blank/oversized key bypasses validation, mutation runs before its ownership lock, live lease expires mid-supplier, dead lease is never reclaimed, pepper rotates between commit/replay, expired key/request is reused under the same pepper, a live required old key is absent, an expired record retains a dead key forever, concurrent callers create two objects, replay leaks API key or PII into durable state |
| Endpoint create | Lost response retries, process dies after reservation commit, lease expires while a live owner is blocked before its first mutation, changed body reuses a key before/after pepper rotation, rolling workers derive different identities, same URL uses another key, rotation supersedes replay, expired state blocks key retirement |
| Correlation | Routine replay-key rotation changes PAN fingerprint or customer email/phone pseudonym and splits fraud/ML history |
| Secret rotation | Claim precedes rotation, rotation races current-generation cryptographic use, wait crosses expiry before signing, stale claim is terminal instead of converging, a required previous/replay KEK is absent with workers enabled or disabled, second rotation overwrites active previous material |
| Fairness | Noisy merchant/endpoint fills every poll, quota exclusion duplicates claims, cursor strands due work |
| Migration | Clean schema passes while an actually shipped V9 schema cannot satisfy current Hibernate validation |
| Privacy | Journal, summary, audit, metric, API, or log leaks API/runner/idempotency/signing secrets, cards, PII, URL, or ciphertext |
| Closure | A debt remains open, PostgreSQL skips, an escaped finding is hidden, or README and plan disagree |

## Boundary family evidence

| Family | Partitions | Proof |
| --- | --- | --- |
| Origin authority | No credential, valid credential, invalid credential, synthetic-looking metadata, legacy default | Create/confirm contracts, V10 migration, live scenario run |
| Generator state | New, active lease, completed step, safe STARTED, unsafe STARTED, both retry-classification drift directions, resource-type drift, completed/interrupted legacy journal, verifier merchant, exact response-before-local-write death, cumulative real-HTTP latency beyond the original lease, completed run, abandonment, changed identity, sanitized cleanup references | Real-SQLite state/backfill tests, complete execution-plan-to-ledger reconciliation, delayed-server heartbeat proof, and live failpoint/resume |
| Merchant/customer create | Same/different request, missing/blank key, same/different tenant, active/expired lease before and during supplier execution, concurrent replay, live/expired pepper rotation, expired same-key/same-request reuse without pepper rotation, missing historical key, V15/V16 alias backfill, plaintext absence | H2 HTTP/rotation/lifecycle-epoch contracts, V15-to-V17 constraints and upgrade, deterministic PostgreSQL in-flight lock wait, exact-window client replay |
| Credentialed HTTP destination | Initial loopback request, 301, 302, 303, 307, 308, environment proxy, API/admin/runner headers | Empty proxy policy, fail-closed redirect handler, and real direct/redirect/proxy server proof in both clients |
| Endpoint idempotency | Same/different request, same/different tenant, active/expired reservation lease, lease expiry while the live owner is blocked before mutation, same URL/different key, replay before/after pepper rotation, mixed-version workers, live missing key, expired key retirement | `WebhookEndpointOperationsTests`, deterministic PostgreSQL live-owner/reclaim/rolling-version wait proof, V11/V14/V17 constraints, V12 uniqueness, V16-to-V17 backfill |
| Fraud/PII correlation | PAN/email/phone before and after replay-key rotation; explicit correlation-key continuity | `CustomerAndPaymentMethodContractTests` proves stable correlation digests while replay HMACs change |
| Rotation | Current, current-to-previous transition during use, previous before expiry, wait crossing expiry, previous at/after expiry, stale-claim retry/current rebind, missing current/previous/replay KEK with execution enabled/disabled, overlap rotation, disabled endpoint | Endpoint operation/security/executor contracts plus forced PostgreSQL signer/rotation lock ordering |
| Fairness | Noisy/quiet merchants, sibling endpoints, saturated merchant/endpoint budgets, concurrent workers | Executor unit proof and `WebhookDeliveryPostgreSqlConcurrencyTests` |
| Migration | Clean V1-to-V17, real V9-to-V13, known-send backfill, nullable tri-state send evidence, stranded V13 endpoint reservation, stranded V14 generic idempotency row, completed V15 merchant identity, V16 generic/webhook replay records | H2/PostgreSQL migration suites, ER-0022 live startup, V14-to-V17 historical-row and alias-backfill proof |
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

The final branch review found ER-0029 and ER-0030: caller-controlled retry
reclassification and a merchant/customer server-commit gap hidden by a later
client failpoint. V15 creation leases, secret-safe replay, stable generator
keys, and an exact earlier failpoint closed those families. The separate
hostile pass then caught and fixed present-but-blank key bypass and added the
missing safe-to-unsafe classification partition before closure.

The post-closure review found ER-0031 through ER-0033: the general idempotency
row was locked after supplier execution, merchant identity silently depended on
one static pepper, and verification reads did not renew the run lease. Closure
remained invalid until pre-supplier locking, V16 versioned identity aliases,
required-key startup validation, and real slow-network heartbeat proof closed
the entire transaction, key-rotation, and workflow-ownership families.

The 2026-07-11 hostile pass then found ER-0036 through ER-0040. It deliberately
held pepper material constant across an expired merchant epoch, rotated while
the signer was blocked, expired a claim-bound generation, disabled the worker
while keeping administration reachable, and expired endpoint-creation
ownership before its first mutation completed. The repaired tests force those
sequences at H2 and PostgreSQL enforcement boundaries, then a separate
plan-to-code-to-proof traceability pass confirmed that each promise has both a
product correction and a process correction.

## Enforcement boundaries

- `MerchantContextArgumentResolver` authenticates origin authority before a
  merchant operation; immutable entity columns and event construction carry it
  forward.
- `GeneratorRunStore` uses SQLite immediate transactions, an expiring owner
  lease, guarded run states, stable step keys, rollback journaling,
  `secure_delete`, and owner-only file permissions. `ApiClient` heartbeats that
  owner before and after every request, and the lease must exceed one request
  timeout. Public API idempotency remains the remote convergence boundary.
- The generator `ApiClient` rejects redirects before request creation; initial
  loopback validation, explicit no-proxy policy, and effective-destination
  enforcement are separate security boundaries.
- `generator_resources` retains only safe tenant/resource identifiers across
  terminal checkpoint-payload erasure. `generator_steps.resource_type` is
  immutable durable metadata; additive startup migration backfills known
  completed and interrupted legacy steps before reconciliation.
- `merchant_creation_records` stores only HMACed key/request identities, their
  derivation version, status, fenced lease state, and the completed merchant ID.
  V16 `merchant_creation_identities` gives every configured current/previous
  pepper a unique alias to the same record. The raw API key is derived with the
  record's original version and immutable record UUID lifecycle epoch, then is
  checked against the merchant hash; neither raw key is stored. Expired-record
  replacement flushes and clears deleted aliases before a new epoch is
  reserved. Startup rejects an unavailable version still required by
  in-progress or unexpired replay state.
- General `idempotency_records` now fence `IN_PROGRESS` ownership with a lease.
  The operation transaction locks and validates the reservation before invoking
  any supplier. V17 stores the request-hash key version, verifies live replay
  with the original version, and restarts expired records under the current
  version. Customer creation, safe-ID response storage, and lease completion
  share that merchant-scoped transaction.
- `CorrelationValueHasher` gives PAN fingerprints and customer email/phone
  pseudonyms a stable key lifecycle separate from replay-key rotation.
- `webhook_endpoint_creation_records` stores HMACed external identity and
  AES-GCM response material under endpoint/merchant AAD. Independent
  reservation and operation transactions distinguish retry from rollback; V14
  owner tokens and expiries fence active work and recover process death. The
  operation transaction locks and validates that owner before generating a
  secret or mutating endpoint, subscription, or audit state. V17
  endpoint identity aliases bind current/previous HMACs to one tenant record,
  preserve original-version request verification, and let expired windows
  release old replay keys.
- V12 tenant-plus-URL uniqueness is the duplicate-stream database boundary.
- Endpoint row locks and secret generations define rotation truth. The executor
  supplies only a preparation lower bound; `signForDelivery` acquires the row
  lock, captures exact use time, validates, decrypts, and signs before release.
  A stale generation below the retry ceiling is retryable no-send; on the final
  attempt, current state is rebound before the first HTTP opportunity.
- Startup derives required KEKs from active current, active unexpired previous,
  and unexpired generation-matching creation-replay ciphertext before applying
  the delivery-worker enable flag.
- `webhook_dispatch_cursor` serializes durable round-robin choice while
  delivery row locks and existing leases remain the claim boundary.
- V13 is additive; it backfills only rows already known to have sent HTTP and
  installs the current dispatch-state constraint.
- V14 additively leases endpoint-create reservations and makes historical
  stranded `IN_PROGRESS` rows immediately reclaimable.
- V15 additively leases general idempotency reservations and creates the
  secret-safe merchant creation state machine; historical generic
  `IN_PROGRESS` rows receive immediately reclaimable leases.
- V16 additively versions merchant derivation identity, backfills the V15 HMAC
  into a unique alias table, and preserves cross-version uniqueness during
  pepper rotation.
- V17 additively versions generic and endpoint request hashes, backfills
  historical rows as `v1`, creates tenant-scoped endpoint identity aliases,
  and preserves old-writer compatibility through `v1` defaults.

## Exact proof

- Full backend and repository Python suites pass as regression context only;
  neither aggregate total is used to prove a state-machine promise.
- `scripts/slice-review-check.py --require-verified` proves that all five
  packets are verified and each hostile checkpoint is a later descendant of
  its implementation checkpoint.
- Generator-focused Python covers leases, checkpoint replay,
  immutable retry classification in both drift directions, merchant/customer
  crash immediately before all local persistence, cumulative slow real-HTTP
  verification beyond the original lease, unsafe ambiguity, abandonment with
  retained safe references, redirect rejection, hostile proxy configuration,
  redaction, and atomic output.
- PostgreSQL proof includes clean/upgrade migrations, V13 historical repair,
  V14/V15 stranded-reservation recovery, V16/V17 identity and request-version
  backfill, deterministic endpoint and merchant/customer creation lock waiting
  including mixed replay-key versions and expiry while protected work is
  blocked, live endpoint-owner fencing past lease expiry, signer/rotation
  exclusion at cryptographic use, noisy/quiet fairness, endpoint fairness,
  exclusive claims, secret/disable ordering, payment lifecycle concurrency,
  and refund concurrency.
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
- V15 adds general idempotency leases and secret-safe merchant creation
  reservations; the V14-to-V15 fixture proves historical unfinished work is
  immediately reclaimable while completed state remains constrained.
- V16 versions merchant derivation identities and backfills every completed or
  in-progress V15 record into the unique alias table without losing lineage.
- V17 versions generic and endpoint request hashes, backfills endpoint identity
  aliases with tenant lineage, and proves clean plus V16-to-V17 upgrades.
- Fresh H2 and PostgreSQL schemas reach V17 and Hibernate validation. The real
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
- Merchant/customer creation conflicts expose malformed, changed-request, or
  active-work states without persisting duplicate resource truth. Expired
  leases are a bounded, operator-inspectable recovery signal controlled by
  `THAAP_IDEMPOTENCY_RESERVATION_LEASE`.
- Missing hash-pepper versions required by live merchant replay state stop
  startup with the exact version across merchant, generic, and endpoint replay;
  expired windows release obsolete requirements. Successful generator HTTP
  calls renew the owner lease before cumulative latency can hand the run to
  another process, and typed resource inventory makes cleanup scope explicit.

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
- ER-0029 records durable retry-classification drift; the stored protocol value
  is immutable and both caller-drift directions fail before operation.
- ER-0030 records the merchant/customer remote-commit gap; V15, stable keys,
  secret-safe replay, PostgreSQL locking, and exact-window client failure proof
  close it.
- ER-0031 records the post-supplier idempotency lock; pre-supplier ownership
  locking plus forced PostgreSQL expiry proves no second supplier can run.
- ER-0032 records pepper-dependent merchant identity; V16 version aliases,
  original-version derivation, rotation replay, upgrade proof, and fail-closed
  startup resolve it.
- ER-0033 records unrenewed verification latency; per-request heartbeats and a
  delayed real-server competing-owner test resolve it.
- ER-0034 records the incomplete persisted-HMAC family review; V17
  original-version verification, endpoint aliases, live-key inventories,
  expired-key retirement, forced mixed-version PostgreSQL execution, and the
  separate stable correlation key resolve it.
- ER-0035 records suffix-inferred cleanup lineage; immutable explicit resource
  types, additive legacy backfill, drift rejection, and complete
  verifier-merchant ledger proof resolve it.
- ER-0036 records lifecycle-epoch aliasing under unchanged pepper material;
  record-UUID API-key derivation and same-pepper expired reuse proof resolve it.
- ER-0037 records authorization-to-use rotation TOCTOU; exact-time signing
  inside the endpoint lock plus a blocked-signer PostgreSQL wait resolve it.
- ER-0038 records terminal handling of a recoverable stale generation; no-send
  retry and next-claim current-generation rebinding resolve it.
- ER-0039 records worker-mode-dependent KEK readiness; persisted ciphertext
  inventory now runs before the enable-mode branch.
- ER-0040 records late endpoint reservation locking; pre-mutation owner locking
  and forced lease expiry while the first insert is blocked resolve it.
- ER-0041 records timestamp capture before endpoint-lock acquisition;
  `previousGenerationWaitingForEndpointLockIsRejectedAtActualUseExpiry` forces
  the wait across expiry and proves zero HMAC use.
- ER-0042 records final-attempt stale exhaustion without HTTP;
  `finalAttemptStaleGenerationUsesCurrentSecretBeforeFirstHttpSend` proves the
  first real send uses the rotated current secret and completes durably.
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

`MERGE`. Five independently verified recovery packets contain the required
transition tables and closure questions. Their implementation checkpoints are
followed by later hostile-proof commits, `ER-0036` through `ER-0042` are
resolved, and named deterministic tests—not aggregate totals—support the
state-machine claims.
