# Chapter 37 Closure Dossier

- Chapter: Chapter 37 — Analyst Workspace, Saved Searches, And Protected Notes
- Chapter number: 37
- Status: READY_TO_MERGE
- Invariant matrix: docs/reviews/CHAPTER_37_INVARIANT_PROOF_MATRIX.md
- Matrix provenance: PRE_IMPLEMENTATION
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Hostile review: PASS
- Boundary family review: PASS
- Custom infrastructure review: NOT_APPLICABLE
- Escaped review assessment: COMPLETE
- Merge recommendation: MERGE

This dossier was created after the Phase 4.5 plan was approved and before any
Chapter 37 production schema, code, tests, or UI artifacts. The frozen checklist
is now reconciled to implementation, real-boundary proof, and a separate hostile
review.

## Observable promises

- Queue filtering is typed, bounded, tenant-scoped, SLA-explicit, and stable at
  equal timestamps and under concurrent inserts.
- Saved searches preserve canonical versioned meaning and enforce private/shared
  ownership on every execution and lifecycle transition.
- Operational notes never change payment, fraud, label, ledger, policy, webhook,
  or simulation truth.
- Note plaintext is authorized, normalized, validated, AES-256-GCM encrypted
  with complete associated data, access-audited on every authorized
  decryption request, and absent from every non-response sink.
- Corrections append; manager/retention redaction atomically destroys ciphertext
  and preserves safe metadata plus one immutable event.
- Live ciphertext is never silently unreadable: exact key-version inventory is
  part of startup/readiness.
- The cockpit teaches the prohibited-data contract, stores credentials and note
  drafts only in memory, and exposes accessible queue/search/note workflows.

## Failure inventory

The frozen matrix covers rollback, duplicate request, stale expected version,
owner/share/disable and execute races, read/redact and retention/redact races,
case reassignment, disabled operators, tenant substitution, cursor tamper,
filter/version/digest ambiguity, Unicode and secret-pattern bypass, nonce/key/AAD
tamper, missing retained keys, plaintext sinks, retained migration, dependency
outage, operator error, browser secret persistence, unaudited authorized
decryption, and false access audit on a denied read.

## Boundary family evidence

The matrix's saved-search grammar and note cryptographic/lifecycle tables are
implemented by closed DTOs/enums, canonical V1 encoding, HMAC-bound cursors,
AES-GCM with exact AAD, live actor/case locks, and terminal ciphertext
destruction. Unit tables, public contracts, deterministic PostgreSQL
interleavings, source scans, and desktop/mobile browser inspection cover the
valid and invalid partitions.

## Enforcement boundaries

Enforcement is strict API DTO parsing, application-owned filter schema,
canonical JSON/digest generation, operator-derived merchant/owner identity,
merchant-qualified PostgreSQL predicates and composite keys, HMAC-bound keyset
cursors, row-lock/live-actor revalidation, dedicated content validation and
AES-GCM cipher, exact-version key readiness, atomic ciphertext destruction plus
append-only events, a same-transaction metadata-only `review_note.accessed`
audit after locked authorized reads, bounded database-backed retention, safe
DTOs, and memory-only browser state.

## Exact proof

- Full backend: 687/687 tests, zero failures, errors, or skips.
- Repository Python: 120/120 tests, including the analyst-workspace demo safety
  partitions and all governance checks.
- Cockpit: TypeScript lint, 3/3 Vitest component/accessibility tests, and Vite
  production build pass; `axe-core` reports no automated semantic violations.
- PostgreSQL: deterministic retention-first/redaction-first/read-first,
  saved-update/disable, equal-timestamp pagination, and append-only trigger
  proofs pass with lock probes and durable postconditions.
- Cryptography: exact-AAD cross-product, nonce/ciphertext tamper, rotation,
  malformed/missing keys, content normalization, and prohibited-secret tables
  pass.
- Access audit: `protectedNotesEncryptReplayCorrectRedactAndNeverChangeDomainTruth`
  proves denied VIEWER/unassigned-ANALYST reads create no event, a manager read
  records `noteCount=2` and `contentReturnedCount=2`, and an assigned-analyst
  read after redaction records `2/1`, with no plaintext in either audit. The
  focused analyst-workspace contract run passed `7/7`.
- Hostile regression: the first full suite exposed an authentication replay
  envelope compatibility change. Authentication responses now retain
  `authentication-replay-v1`; protected note responses use
  `sensitive-replay-v1`; readiness inventories both. The focused contracts and
  subsequent 687-test full suite pass.

## Migration evidence

Fresh V1-to-V53 and retained V51-to-V53 PostgreSQL migration tests pass. V52
creates saved-search/note/event state empty by default; V53 installs narrow
terminal-transition and append-only guards. The retained local database was
advanced from V51 to V53 without reset, and the current binary reported
readiness `UP`.

## Demo evidence

`scripts/demo-analyst-workspace.py` passes against the retained PostgreSQL
runtime. It proves typed queue search, private saved-search exact replay and
meaning binding, assigned note creation, append-only correction, manager
redaction, ciphertext destruction, absent plaintext sinks, unchanged
payment/fraud/ledger truth, and readiness `UP`. Durable reconciliation was
`1|2|1|1|1|1|0|0|0|0` for saved searches, notes, terminal event, destroyed
original, active correction, assigned case, labels, ledger, audit canary, and
idempotency canary respectively.

## Operational signals

Closed queue/search/note/retention/key-readiness operations and result causes
are allowlisted. Queue/search signals are emitted after the request outcome;
note signals use transaction completion. Metrics/audit tests and canary scans
prove that merchant/resource IDs, filter values, search names, cursor bytes,
key material, ciphertext, nonce, and note plaintext are absent.
Authorized plaintext reads emit one bounded `review_note.accessed` audit whose
only content-sensitive fact is the integer count of rows that returned content.

## Known debt

No Chapter 37 debt was accepted or deferred. The canonical registry remains at
five resolved entries and zero open entries.

## Custom infrastructure decision

`NOT_APPLICABLE`. The slice plans to use Spring MVC/JPA/scheduling, PostgreSQL,
JCE AES-GCM, React, and established browser APIs. It introduces no proprietary
cryptographic primitive, queue, distributed scheduler, or wire protocol.

## Escaped-review assessment

`ER-0069` escaped the prior Chapter 37 completion claim. The earlier proof
correctly established ciphertext protection and plaintext absence from sinks,
but it omitted the distinct positive promise that every authorized decryption
is access-audited. The service now records the metadata-only event in the read
transaction, and focused contract assertions cover denied, fully decrypted,
and partially redacted results. The process correction is to split privacy
reviews into negative non-disclosure claims and positive accountability claims.

## Post-closure recovery

The chapter was reopened while `ER-0069` was unresolved. The remediation adds
no plaintext or note identifiers to audit detail; it records bounded counts
against the already authorized review aggregate. Focused proof confirms that
the audit is truthful to the returned content while payment, fraud, ledger, and
label truth remains unchanged. The recovered boundary supports the chapter
recommendation returning to `MERGE`.

## Source-of-truth reconciliation

The Phase 4.5 detailed plan, implementation master plan, README, daily note,
weekly review, this dossier, and its matrix identify Chapters 36–37 as complete
and Chapter 38 as active. No artifact claims Phase 4.5 complete.

## Merge recommendation

`MERGE`. V52/V53, public API and cockpit behavior, focused and full tests,
fresh/retained migration, deterministic PostgreSQL interleavings, browser
proof, live demo, traceability, hostile review, and structural gates pass with
no blocking finding or undocumented debt.
