# Chapter 29 Invariant Proof Matrix

- Chapter: Chapter 29 — Safe RiskContext And Real-Time Feature Extraction
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

This matrix was drafted on 2026-07-13 after Chapter 28 closure and before any
Chapter 29 production code or migration. Every named class, migration, and test
below remains an obligation until implementation reconciles it.

## C29-PAY-001

- Lens: PAYMENT_FRAUD
- Invariant: Adding, omitting, or varying valid RiskContext never changes existing rules_v1 action precedence or payment lifecycle outcomes in Chapter 29.
- Failure sequence: New context silently blocks/allows, creates a second decision/attempt, changes capture truth, or affects a request that omitted it.
- Implementation: `RiskContextService`, `FeatureMaterializer`, and the immutable catalogue add safe point-in-time features; `rules_v1` consumes none of the new codes.
- Proof: `RiskContextContractTests` and the live demo prove ALLOW/BLOCK/REQUEST_3DS/REVIEW parity with and without context and reconcile payment consequences.
- Adversarial evidence: Same/distinct keys, every existing processor fixture, context missing/full/partial, and rollback/retry.
- Status: PASS

## C29-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: RiskContext and every derived feature belong to the same merchant, PaymentIntent, PaymentAttempt, FeatureSnapshot, and FraudDecision lineage.
- Failure sequence: A context or hashed identity from one merchant attaches to another tenant or attempt.
- Implementation: V32 copied semantic keys and composite foreign keys plus V34 insert/history/deferred-lineage triggers bind context to attempt and decision.
- Proof: Contract isolation, direct PostgreSQL mutation, deferred lineage, and forced competing-confirm tests reject mixed lineage.
- Adversarial evidence: Merchant, intent, attempt, snapshot, decision, payment method, and idempotency substitution.
- Status: PASS

## C29-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: Raw IP, user agent, device/session IDs, email, phone, address, PAN/CVC, API keys, and correlation values never persist or appear in logs, metrics, audit, webhook, error, or public response surfaces.
- Failure sequence: A prohibited value survives request memory or becomes observable through an error/diagnostic surface.
- Implementation: Strict DTOs normalize only in request memory; persistence stores purpose-HMACs and closed values; metrics use closed tags and existing bounded logs never include context inputs.
- Proof: Contract and public-demo canary scans cover context, snapshot, audit, webhook, idempotency, response, error, and meter surfaces.
- Adversarial evidence: Constraint/adapter failures, malformed values, Unicode, control characters, nested unknown fields, and overlong inputs.
- Status: PASS

## C29-AUTH-001

- Lens: SECURITY_PRIVACY
- Invariant: IP, device, session, user-agent, customer, and card correlation identities use distinct purpose-HMAC domains and explicit key versions; raw hashes are not public features.
- Failure sequence: Equal raw values collide across purposes, unkeyed hashes permit guessing, or missing historical keys start green.
- Implementation: `RiskContextService` delegates to the versioned `SensitiveValueHasher` using merchant-bound `risk-context:{purpose}:v1` domains.
- Proof: Exact persisted digest vectors prove domain/merchant/version input, equal device/session inputs differ by purpose, and readiness tests prove rotation/missing-key behavior.
- Adversarial evidence: Empty/duplicate/malformed versions, same value across merchants/purposes, old-only retained rows, and key material reuse.
- Status: PASS

## C29-API-001

- Lens: API_PRODUCT
- Invariant: Confirm accepts only the frozen optional RiskContext grammar while requests without the field retain their existing response and idempotency behavior.
- Failure sequence: Unknown/free-form/forbidden fields are accepted, ranges overflow, invalid countries/times pass, or old clients drift.
- Implementation: `RiskContextRequest`, closed enums, strict Jackson unknown-field and fractional-integer rejection, literal-only IP parsing, bounds, controls, and future-time checks enforce the grammar.
- Proof: The table-driven contract covers absent/full/partial IPv4/IPv6, unknown/forbidden fields, enum/country/range/type/control/time partitions, and no-truth failures.
- Adversarial evidence: Raw email/phone/address/card fields, NaN/exponent/fractional integers, duplicate JSON keys, Unicode confusables, and future observations.
- Status: PASS

## C29-DATA-001

- Lens: DATA_ML_LINEAGE
- Invariant: RiskContext stores immutable safe values, provenance, HMAC versions, observed/computed timestamps, traffic origin, and exact payment/decision lineage at microsecond precision.
- Failure sequence: Mutable context rewrites what the engine knew, merchant observation is mislabeled as trusted attestation, or time precision changes feature eligibility.
- Implementation: V32 creates microsecond context truth; V34 makes safe fields immutable, permits only null-to-decision attachment, and checks final semantic lineage at commit.
- Proof: PostgreSQL migration and contract suites reject mutation/delete/incomplete lineage and prove complete committed attempt/decision/origin edges.
- Adversarial evidence: Missing/multiple provenance, observed after computation/decision, future time, null key versions, and terminal history mutation.
- Status: PASS

## C29-DATA-002

- Lens: DATA_ML_LINEAGE
- Invariant: The eight Chapter 29 context features use immutable versioned definitions and exact typed FeatureValue rows with observed/computed/freshness/provenance evidence; identity HMACs never become feature values.
- Failure sequence: Snapshot JSON and typed rows disagree, wrong types persist, missing values become false/zero, or identity material leaks as a feature.
- Implementation: V33 installs ten immutable context definitions; extraction writes typed rows and matching snapshot JSON while explicit missing names preserve absent truth.
- Proof: Full produces 17 values, partial 10, absent 8; feature tests reconcile types, timestamps, missing names, catalogue immutability, and prohibit HMAC feature values.
- Adversarial evidence: Wrong definition version/type/code/snapshot, absent country match inputs, duplicate values, and direct mutation.
- Status: PASS

## C29-CON-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Context, feature snapshot/values, fraud decision, payment outcome, audit/webhook, and idempotency completion commit atomically or all roll back.
- Failure sequence: Context persists without a decision, decision sees uncommitted/missing context, or failed context is cached as complete.
- Implementation: The existing fenced confirm transaction writes context before the feature/decision flush under PaymentIntent-first locking and attaches its decision before commit.
- Proof: Chapter 29 forces a context constraint rollback and same-key retry; Chapters 22–28 retain snapshot, feature, decision, payment, audit, webhook, and idempotency rollback coverage with context integrated.
- Adversarial evidence: Processor ambiguity, duplicate same/distinct keys, restart, and failure before/after decision application.
- Status: PASS

## C29-CON-002

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Competing confirm requests serialize to one attempt/context/snapshot/decision and exact committed replay truth.
- Failure sequence: Two contexts or decisions survive, losing context contaminates the winner, or retries observe mixed bodies.
- Implementation: Existing idempotency ownership and PaymentIntent locks serialize one attempt-bound context lineage.
- Proof: `RiskContextPostgreSqlTests` forces observed feature/intent/idempotency waits for distinct keys/different contexts and same key/different bodies, leaving one exact winner.
- Adversarial evidence: Equal hash/different raw, same raw/different key, request restart, and committed terminal replay.
- Status: PASS

## C29-OBS-001

- Lens: OBSERVABILITY
- Invariant: Operators can distinguish context absent/present/rejected, extraction success/rollback, and key-readiness failure using closed low-cardinality values without identity/resource tags.
- Failure sequence: Context failure is invisible, rolled-back extraction looks committed, or raw/high-cardinality values enter telemetry.
- Implementation: `RiskContextMetrics` records present/absent commit versus rollback and closed rejection/readiness causes; bounded decision logs contain identifiers/actions but no input values.
- Proof: Exact metric tests constrain keys/values; context contracts and live canary scans prove no identity values in logs or durable diagnostic surfaces.
- Adversarial evidence: Raw IP/device/session/user-agent canaries, malformed grammar, constraint rollback, and tenant denial.
- Status: PASS

## C29-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Startup fails closed when any retained RiskContext HMAC version needed for supported reads cannot be resolved, while configured rotation preserves historical explanation.
- Failure sequence: A deployed key retirement makes old context unreadable or silently incomparable.
- Implementation: `RiskContextKeyConfiguration` inventories every retained key version against the current/previous HMAC keyring and fails startup closed.
- Proof: Empty/fresh startup, retained current/previous versions, and missing retired-version tests pass; live retained startup validates V34 data.
- Adversarial evidence: Partial rotation across identity columns, empty database, old-only database, duplicate material, and stale key version.
- Status: PASS

## C29-OPS-002

- Lens: OPERATIONS_RESILIENCE
- Invariant: Fresh and retained V31 upgrades add context/catalogue truth without rewriting Chapters 22–28 decisions, features, reviews, labels, challenges, or idempotent response bytes.
- Failure sequence: Migration mutates old definitions/history, defaults fabricate context, or current binary cannot validate retained rows.
- Implementation: Forward-only V32–V34 add context, catalogue, and protection without updating historical decisions or inventing context rows.
- Proof: Fresh V1→V34, retained V31→V34 byte comparisons, Hibernate validation, full suites, gates, and the retained live all-action demo pass.
- Adversarial evidence: Every historical action, pending/terminal challenge, approved/captured/refunded rows, old idempotency responses, and malformed seed rows.
- Status: PASS

## C29-API-002

- Lens: API_PRODUCT
- Invariant: Decision explanation exposes only safe typed feature values and merchant-observed provenance, never raw/HMAC identity material or a claim of trusted network/device attestation.
- Failure sequence: Product language overstates IP country or bot evidence, or explanation reveals correlation inputs.
- Implementation: Existing tenant-scoped explanation reads the governed typed snapshot; new codes are explicitly merchant-observed and HMAC identities are not definitions.
- Proof: Full/partial/missing contracts and all-action live cohorts prove safe typed JSON, explicit missing values, and prohibited key/value absence.
- Adversarial evidence: HMAC-shaped values, raw canaries, provenance substitution, missing feature values, and historical no-context reads.
- Status: PASS

## Closure review record

- Plan-to-implementation-to-proof traceability: COMPLETE on 2026-07-13.
- Temporally later hostile pass: COMPLETE after initial green tests; it found and fixed fractional integer coercion and deferred-trigger stale-event semantics before this completion claim.
- Boundary review: COMPLETE across strict HTTP grammar, H2 contracts, PostgreSQL V32–V34 constraints/triggers/forced waits, exact HMAC/key tests, and retained live infrastructure.
- Escaped findings: NONE. All findings occurred before the first Chapter 29 completion claim.
