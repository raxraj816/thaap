# Chapter 37 Invariant Proof Matrix

- Chapter: Chapter 37 — Analyst Workspace, Saved Searches, And Protected Notes
- Matrix provenance: PRE_IMPLEMENTATION
- Scope status: FROZEN
- Review status: VERIFIED
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Fresh hostile review: PASS
- Boundary family review: PASS
- Custom infrastructure review: NOT_APPLICABLE
- Escaped review assessment: COMPLETE

Post-closure recovery completed on 2026-07-27 for `ER-0069`. Every authorized
note-list request records one metadata-only `review_note.accessed` audit in the
same transaction, including total note count and plaintext-return count;
denied reads record no access event and no audit field contains plaintext.

This matrix was drafted and frozen on 2026-07-25 before Chapter 37 production
schema, code, tests, or UI files were created. It derives from the approved
Phase 4.5 detailed plan, the Chapter 36 metric/filter boundary, the existing
risk-operator and manual-review contracts, and the repository closure process.
Every proof obligation below has been reconciled against its production
enforcement boundary and exact executable evidence.

## C37-PAY-001

- Lens: PAYMENT_FRAUD
- Invariant: Queue search, saved searches, SLA presentation, notes, correction, redaction, and retention never change PaymentIntent, PaymentAttempt, Authorization, FraudDecision, review resolution, FraudLabel, chargeback, refund, ledger, or active risk-profile truth; a free-form note is never consumed as a label, rule/model feature, webhook payload, simulation input, or payment decision.
- Failure sequence: An analyst saves or writes operational context and a broad entity cascade, event publisher, indexer, or query changes payment/fraud truth or later interprets note text as fraud evidence.
- Implementation: V52 workspace-only tables, safe DTO projections, no payment/fraud mutation or event hooks, separate structured label paths, and source-level dependency boundaries around note plaintext.
- Proof: `AnalystWorkspaceContractTests.protectedNotesEncryptReplayCorrectRedactAndNeverChangeDomainTruth`, before/after durable domain-table counts, webhook/audit/idempotency plaintext scans, and `scripts/demo-analyst-workspace.py` PostgreSQL reconciliation.
- Adversarial evidence: Fraud-looking note text, note correction, redaction, expired note, saved search execution, failed encryption, and a review resolved concurrently with a note request.
- Status: PASS

## C37-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: Queue rows, assigned-operator filters, saved-search ownership, note ciphertext, note reads, correction links, redaction events, and cursors are always derived from the authenticated operator's merchant; foreign resource substitution is indistinguishable from absence and cannot disclose existence, metadata, or timing.
- Failure sequence: A caller copies another merchant's review, operator, saved-search, note, superseded-note, or cursor UUID into a valid request and an unqualified lookup, foreign key, cache key, or error message crosses the tenant boundary.
- Implementation: Merchant-qualified repositories and SQL, tenant-bound HMAC cursors, composite foreign keys, actor-derived ownership, same-tenant correction constraints, and uniform not-found responses.
- Proof: `AnalystWorkspaceContractTests` covers copied cursor, foreign operator, saved-search ownership, note read/redact, and domain isolation; V52 composite keys plus PostgreSQL V53 history tests enforce stored tenant lineage.
- Adversarial evidence: Foreign assigned operator, copied private/shared search, copied cursor, cross-tenant supersedes ID, foreign note redaction, disabled foreign owner, and equal UUID-like request shapes.
- Status: PASS

## C37-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: Note plaintext is normalized and validated only inside an authorized request, encrypted with AES-256-GCM before persistence using versioned key material and associated data binding merchant, review, note ID, author, and microsecond creation time, and is absent from logs, audit details, metrics, errors, idempotency replay, webhooks, search indexes, exports, browser persistence, and simulation inputs. Every authorized request that decrypts note content appends one metadata-only access audit in the same transaction.
- Failure sequence: Plaintext or a prohibited PAN/CVV/API-key/webhook-secret/operator-secret shape reaches encryption, plaintext is returned without an access audit, a denied read creates a false access event, a nonce is reused, associated data omits identity, a decryption failure echoes content, or the UI stores the note/credential in local/session storage.
- Implementation: Dedicated validator and AES-GCM cipher, 96-bit random nonce, 128-bit tag, canonical binary AAD, encrypted sensitive idempotency replay, safe errors, and memory-only browser state. The locked authorized read counts returned rows and rows with decrypted content, then records `review_note.accessed` with only those counts and the review aggregate identity before transaction commit.
- Proof: `ReviewNoteCipherTests`, table-driven `ReviewNoteContentValidatorTests`, and `AnalystWorkspaceContractTests.protectedNotesEncryptReplayCorrectRedactAndNeverChangeDomainTruth` prove manager and assigned-analyst access events, denied-read absence, redacted-versus-active plaintext counts, and persisted/audit/idempotency/webhook plaintext canary exclusion.
- Adversarial evidence: Empty/2,001-code-point input, CR/LF/control/BiDi/zero-width/HTML, Unicode-normalized PAN, CVV, `sk-`, `whsec_`, basic-auth URL, operator secret, changed merchant/review/note/author/time byte, corrupted tag, reused ciphertext, denied read, redacted note, and hostile exception text.
- Status: PASS

## C37-SEC-002

- Lens: SECURITY_PRIVACY
- Invariant: Active ciphertext is readable only when its exact key version is configured; rotation writes with the current version while retained versions remain readable, and startup/readiness fails closed if any live ciphertext requires an unavailable or malformed key.
- Failure sequence: A key is removed while live notes still reference it, an unknown version silently uses the current key, an invalid key length starts successfully, or redacted/expired destroyed ciphertext falsely blocks readiness.
- Implementation: Versioned key inventory, strict Base64/AES-256 validation, persisted live-key inventory, current-version encryption, exact-version decryption, and dedicated health/readiness components; sensitive replay envelopes are included in authentication replay-key inventory.
- Proof: `ReviewNoteCipherTests`, `ReviewNoteKeyConfigurationTests`, `AuthenticationKeyReadinessTests`, fresh/retained migration fixtures, and the local PostgreSQL readiness smoke prove malformed/missing/retained/destroyed partitions and runtime `UP`.
- Adversarial evidence: Empty key ring, duplicate version, 128/192-bit key, invalid Base64, previous-key note, unknown key version, redacted note with old version, and concurrent rotation/read.
- Status: PASS

## C37-CON-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Saved-search create/update/share/disable operations are actor-scoped, idempotent where replay-safe, and serialized by one documented lock/version order so owner changes, manager sharing, disablement, and execution cannot create two active meanings or execute stale/disabled meaning as current truth.
- Failure sequence: The owner updates while a manager shares or disables, two same-key requests create divergent searches, or execution validates an old schema/filter then returns results after a conflicting disable/update commits.
- Implementation: Operator lock and live revalidation precede saved-search row lock; expected versions, canonical digests, immutable execution snapshots, unique active owner/name identity, and actor-scoped idempotency prevent ambiguous meaning.
- Proof: PostgreSQL `forcedSavedSearchUpdateFirstMakesDisableWaitAndRejectsItsStaleVersion` and `forcedSavedSearchDisableFirstMakesUpdateWaitAndPreservesDisabledMeaning`, contract replay/authorization/unsupported-schema tests, explicit lock probes, and durable postconditions.
- Adversarial evidence: Same/different idempotency keys, same name after disable, copied owner ID, role downgrade, unsupported schema during execution, and equal transaction timestamps.
- Status: PASS

## C37-CON-002

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Note creation/correction, authorized read plus access audit, manager redaction, and retention expiry use a single note-row lock order; after redaction/expiry commits no later read returns plaintext, exactly one redaction event exists, ciphertext destruction and event append are atomic, and rollback leaves a retryable live note rather than partial metadata.
- Failure sequence: A read decrypts after a concurrent redaction commit, retention and manager redaction create two events, event insert succeeds while ciphertext destruction rolls back, or a failed expiry claim permanently hides a note.
- Implementation: Case lock then note lock ordering, PostgreSQL `FOR UPDATE`, transactional metadata-only access audit after decryption, unique terminal event, atomic ciphertext nulling/event append, and bounded `SKIP LOCKED` retention batches.
- Proof: PostgreSQL `forcedRetentionFirstMakesManagerRedactionWaitAndConvergesToOneExpiredEvent`, `forcedManagerRedactionFirstMakesRetentionSkipLockedAndConvergesToOneRedactedEvent`, `forcedRedactionFirstBlocksReadUntilCiphertextIsDestroyed`, the contract's positive/negative access-audit assertions, V53 mutation/event rejection, and H2 retry/idempotence postconditions.
- Adversarial evidence: Exact retention deadline, repeated manager request, crash after encryption, crash between destroy/event operations, superseded note, resolved review, and disabled author/manager.
- Status: PASS

## C37-DATA-001

- Lens: DATA_ML_LINEAGE
- Invariant: A saved search preserves one canonical, versioned typed filter meaning, canonical sort, visible closed column codes, owner, visibility, digest, and lifecycle; every execution revalidates the stored schema and fails explicitly with `saved_search_upgrade_required` when meaning is unsupported rather than translating or widening it.
- Failure sequence: JSON member order or duplicate values change the digest, an old enum is dropped silently, a database edit changes bytes without digest failure, or an unsupported schema executes with today's defaults.
- Implementation: Application-owned V1 grammar, deterministic canonical JSON/set ordering, SHA-256 digest, database checks for closed lifecycle/visibility/sort/version, and execute-time parse/validate/digest verification.
- Proof: `ReviewQueueFilterCodecTests` covers order, duplicates, bounds, unknown fields, unsupported versions, and digest representations; contract tests prove `saved_search_upgrade_required`; fresh and retained migrations preserve the schema boundary.
- Adversarial evidence: Duplicate JSON keys, unknown field/enum/column, numeric/string ambiguity, mixed case, invalid UUID, missing field, 20/21 terms, 50/51 values, and corrupted digest.
- Status: PASS

## C37-API-001

- Lens: API_PRODUCT
- Invariant: The analyst queue exposes a typed bounded filter—not a generic query language—with at most 20 present terms, 50 total set values, a UTC half-open interval no wider than 90 days, page size 1–100, allowlisted sort/columns, explicit SLA state, and stable `(created_at,id)` keyset pagination with no duplicates or omissions at equal timestamps.
- Failure sequence: An unbounded filter exhausts PostgreSQL, malformed input broadens to all rows, offset pagination skips a concurrent row, cursor/filter mismatch changes meaning, or a 90-day endpoint/time-zone edge becomes 91 days.
- Implementation: Strict duplicate/unknown-field JSON parsing, closed DTO enums, normalized sets, HMAC cursor bound to merchant/filter/sort, microsecond time normalization, parameterized keyset SQL, five-second read transaction, and safe projections.
- Proof: `ReviewQueueFilterCodecTests`, `AnalystWorkspaceContractTests.typedQueueFiltersAndHmacCursorStayTenantAndMeaningBound`, null/duplicate/unknown grammar tests, and PostgreSQL `equalTimestampQueuePagesHaveNoDuplicateOrOmissionOnPostgreSql` prove the bounded grammar and stable ordering.
- Adversarial evidence: Zero/101 limit, negative age, 21 terms, 51 values, empty sets, duplicate values, unknown query keys/JSON fields, copied/tampered cursor, cursor with changed sort/filter, DST offsets, future/end-before-start, and 90 days plus one microsecond.
- Status: PASS

## C37-API-002

- Lens: API_PRODUCT
- Invariant: VIEWER may manage only owned private searches; ANALYST may create/read/correct notes only on a currently assigned case; RISK_MANAGER may manage merchant-shared searches and read/redact any tenant note; all mutations authenticate first and revalidate live operator status, role, ownership/assignment, and review identity under lock.
- Failure sequence: A viewer shares a search, an analyst notes an unassigned/reassigned case, an owner disabled after authentication mutates state, a manager redacts foreign content, or completed replay bypasses current authorization.
- Implementation: Workspace services derive merchant/owner from the actor, revalidate live operator/case state under lock, enforce closed role checks and assignment versions, and authorize again before replaying protected note responses.
- Proof: Workspace contract role/ownership/assignment/foreign-state tests, existing review reassignment/disable PostgreSQL interleavings, and public 400/403/404/409 contracts prove the permitted and denied boundaries.
- Adversarial evidence: Every role across private/shared create/read/update/disable/execute and assigned/unassigned note create/read/correct/redact; disabled credential/operator; reassignment at lock; self/other manager; and foreign resource IDs.
- Status: PASS

## C37-OBS-001

- Lens: OBSERVABILITY
- Invariant: Operators can distinguish queue success/invalid/timeout, saved-search create/update/disable/upgrade-required, note encrypt/decrypt/redact/retention success or rollback, and key-readiness failure using closed low-cardinality signals without merchant, operator, review, saved-search, note, cursor, filter value, ciphertext, nonce, or plaintext tags/messages.
- Failure sequence: A rollback emits success, decryption failure includes associated-data/plaintext bytes, retention retries are invisible, or a search name/filter value becomes a metric label.
- Implementation: After-completion note metrics, closed operation/result/readiness catalogues, metadata-only audit events, bounded request-ID errors, non-secret readiness details, and explicit timeout/upgrade/error classification.
- Proof: `AnalystWorkspaceMetricsTests`, contract rollback/error/upgrade paths, plaintext canary/source scans, key-health DOWN tests, and local readiness `UP` smoke.
- Adversarial evidence: Encryption failure, corrupt ciphertext, missing key, database timeout, stale version, foreign denial, duplicate request, retention rollback/retry, hostile search name, and secret-shaped note.
- Status: PASS

## C37-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Fresh V1 and retained V51 upgrades add workspace state empty-by-default, preserve all prior payment/fraud/review/webhook/consent bytes, install required constraints/indexes, start with valid key configuration, and permit bounded retention retry without Redis or an external queue.
- Failure sequence: Migration invents searches/notes, rewrites review ownership, weakens history triggers, blocks on a table rewrite, retention deletes metadata/audit, or startup ignores required live ciphertext keys.
- Implementation: Forward-only V52/V53, empty-by-default state, composite constraints, partial due-note index, append-only/event-matching triggers, bounded database-backed retention, and Hibernate validation.
- Proof: `PostgreSqlMigrationTests` passes fresh V1–V53 and retained V51–V53 upgrades; V53 PostgreSQL mutation tests, two-executor retention races, 687-test current-binary suite, and local retained-database startup prove the operational boundary without Redis dependence.
- Adversarial evidence: Active assigned/resolved reviews, same-time rows, disabled operators, empty and live note tables, old-key ciphertext, redacted ciphertext, partial migration, and two retention executors.
- Status: PASS

## C37-OPS-002

- Lens: OPERATIONS_RESILIENCE
- Invariant: Chapter 37 cannot claim completion until filter/authorization/content/key/concurrency boundary families pass at real enforcement boundaries, the cockpit passes accessibility and browser-storage inspection, a live queue-to-note-to-redaction demo reconciles durable state, and no blocking debt or escaped finding remains.
- Failure sequence: A polished UI or green service suite substitutes for PostgreSQL interleavings, cryptographic representation tests, key inventory, tenant substitution, plaintext sink scans, retained migration, browser proof, or public API reconciliation.
- Implementation: Matrix/dossier traceability, a separate hostile pass, schema/API/browser proof ladder, source/storage canary scans, live PostgreSQL demo, governance checks, learning artifacts, and source-of-truth reconciliation.
- Proof: 687/687 Java tests, 120/120 Python tests, 3/3 UI tests plus lint/build/accessibility, desktop/mobile browser inspection, public live demo reconciliation, and all named structural/readiness gates.
- Adversarial evidence: DRAFT matrix, unsupported saved-search version, missed secret shape, missing retained key, timing-only race test, UI localStorage credential, stale docs, open P1/P2 promise finding, or vague deferral.
- Status: PASS

## Frozen Boundary Tables

### Saved-search grammar

| Boundary | Valid partitions | Invalid partitions |
|---|---|---|
| Terms | 0–20 present typed terms | 21+, unknown/generic expression |
| Set values | 0–50 total normalized closed values | 51+, unknown value, wrong scalar type |
| Interval | absent or UTC `[start,end)` up to exactly 90 days | inverted, future end, 90 days + 1µs |
| Page | 1–100, deterministic created-descending/id-descending | 0, 101+, offset, foreign/filter-mismatched cursor |
| Stored schema | exact supported V1 with valid SHA-256 digest | V0/V2, duplicate key, unknown field, digest mismatch |
| Visibility | owned `PRIVATE`; manager-published `MERCHANT_SHARED` | viewer/analyst publish, foreign owner/merchant |

### Note cryptographic and lifecycle boundary

| Boundary | Authenticated / retained truth | Rejected / destroyed truth |
|---|---|---|
| Plaintext | normalized UTF-8, 1–2,000 code points, no prohibited content | empty/oversize, control/HTML, obvious payment/secret patterns |
| AES-GCM | 32-byte exact-version key, 12-byte unique nonce, 128-bit tag | malformed/missing/unknown key, nonce/tag/ciphertext tamper |
| Associated data | version + merchant + review + note + author + created-at micros | any changed field, ambiguous concatenation, precision drift |
| Read role | assigned ANALYST or tenant RISK_MANAGER under live lock | VIEWER, unassigned/reassigned/disabled actor, foreign tenant |
| Lifecycle | active ciphertext; append-only correction; one metadata-only audit per authorized read request; metadata after terminal event | edit-in-place, unaudited plaintext access/delete, plaintext after redaction/expiry |
| Browser | in-memory credential/note draft cleared after use/navigation | localStorage, sessionStorage, IndexedDB, URL, analytics/error sink |

## Post-closure recovery: `ER-0069`

The privacy family originally proved only a negative claim: plaintext did not
reach persistence, telemetry, browser storage, or replay. The recovered
positive claim is enforced inside `ReviewNoteService.list`: after live
actor/case authorization and locked note reads, the service records one
`review_note.accessed` audit with `noteCount` and `contentReturnedCount`.

`protectedNotesEncryptReplayCorrectRedactAndNeverChangeDomainTruth` proves that
VIEWER and unassigned ANALYST denials leave the access-audit count at zero, a
RISK_MANAGER read of two active notes records `2/2`, and an assigned ANALYST
read after one redaction records `2/1`. It also scans the audit document for the
plaintext canary. Because audit insertion and decryption are in one
transaction, audit failure prevents a successful plaintext response rather
than producing unaudited access.
