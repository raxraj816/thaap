# Chapter 39 Invariant Proof Matrix

- Chapter: Chapter 39 — Governed Rule Authoring And Safety Exceptions
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

This matrix was drafted and frozen on 2026-07-25 after Chapter 38 closure and
before Chapter 39 schema, production code, configuration, tests, or UI changes.
It derives from the approved Phase 4.5 rule/exception grammar, the immutable
decision evidence model, existing feature/rule/policy catalogues, purpose-HMAC
identity boundaries, and the Chapter 38 simulation activation gate.

## C39-PAY-001

- Lens: PAYMENT_FRAUD
- Invariant: An active authored rule contributes only `BLOCK`, `REVIEW`, or `REQUEST_3DS`; deterministic precedence remains `BLOCK > REVIEW > REQUEST_3DS > ALLOW`, every matched rule and applied exception is persisted, protected sources are never suppressible, and no exception emits ALLOW or erases original match evidence.
- Failure sequence: A broad exception suppresses velocity/list/built-in protection, an authored rule emits ALLOW, or aggregation deletes matched evidence and weakens a stronger remaining action.
- Implementation: `ActiveAuthoredRuleEvaluator` evaluates only active immutable authored versions, retains every match, records a separate exception disposition, and `AuthoredRuleDecisionAggregator` applies the existing strongest-action ordering without allowing authored `ALLOW`.
- Proof: `MerchantRiskPolicyContractTests.governedAuthoredRuleRequiresDualControlAndExactSimulationThenPersistsDecisionEvidence`, decision aggregation regressions, the historical processor integration suite, and the public governed-controls demo prove BLOCK, suppression-only-of-source, retained evidence, and unchanged protected-source/processor behavior.
- Adversarial evidence: Empty match set, same-strength ties, exception on named/wrong/protected version, expired exception, and BLOCK plus suppressed REVIEW.
- Status: PASS

## C39-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: Rule identities/versions, predicates, lifecycle events, simulations, approvals, activations, rollback targets, exception subjects/source versions, and decision evidence are merchant-qualified; copied foreign IDs/HMACs are indistinguishable from absence.
- Failure sequence: A manager approves another merchant's rule, binds a foreign simulation, copies an exception HMAC, or a decision joins an active version by bare UUID.
- Implementation: Actor-derived tenant context, composite tenant foreign keys, tenant-qualified `FOR UPDATE` queries, and tenant-qualified decision reads bind every control and evidence row.
- Proof: Existing operator tenant/role contracts, Chapter 38 foreign candidate/report tests, control API not-found paths, V56 composite constraints, and merchant-qualified confirm evidence queries prove the boundary.
- Adversarial evidence: Equal UUID shapes, foreign creator/approver, copied rollback target, foreign subject, and reused idempotency key.
- Status: PASS

## C39-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: Rule JSON contains only allowlisted feature codes/operators/typed operands/actions; exception identity stores one purpose/version-bound HMAC and safe subject type, never raw PAN/email/IP/device/list value, and no AST/subject/credential becomes a log or metric tag.
- Failure sequence: Arbitrary JSON, regex/script/network/database access, raw identity, malicious Unicode metadata, or parser exception leaks content into state/telemetry.
- Implementation: `AuthoredRuleCodec` is a closed typed AST with catalogue validation and no executable capability; exception creation derives a purpose/version HMAC from a tenant-owned payment method; DTOs omit HMAC/key/raw identity and metrics use closed tags.
- Proof: `AuthoredRuleCodecTests`, the public API contract, response canaries, readiness key inventory, source/schema review, and `demo-governed-controls.py` prove the grammar and privacy boundary.
- Adversarial evidence: PAN/email/IP/device, wildcard/regex/script keys, HTML/control/BiDi, unknown feature/operator, and malformed UTF-8/JSON.
- Status: PASS

## C39-GRAMMAR-001

- Lens: DATA_ML_LINEAGE
- Invariant: The V1 canonical JSON AST accepts exactly `{all:[...]}` or `{any:[...]}` groups and `{feature,operator,value}` predicates, with depth at most 3, at most 32 predicates, set size at most 20, serialized bytes at most 8 KiB, exact type/operator compatibility, and no unbounded computation.
- Failure sequence: Duplicate/unknown keys, mixed group/predicate shape, numeric coercion/overflow, nested cost explosion, or evaluation order changes meaning/digest.
- Implementation: The codec requires exact node keys, exact 64-bit integral operands, governed feature ranges, safe bounded UTF-8, explicit depth/predicate/set/byte counters, sorted canonical group/set encoding, and SHA-256.
- Proof: `AuthoredRuleCodecTests` covers permutation invariance, all operator/type rejection classes, 3/4 depth, 32/33 predicates, 20/21 set members, 128/129 UTF-8 bytes, 8 KiB overflow, feature numeric limits, duplicate sets, BiDi controls, non-integral numbers, and stored digest/canonical drift.
- Adversarial evidence: Null/boolean/string/number ambiguity, exponent/fraction, Long boundaries, duplicate set members, empty groups, and depth 4.
- Status: PASS

## C39-DATA-001

- Lens: DATA_ML_LINEAGE
- Invariant: Draft bytes may change only through a new version; submission freezes exact canonical AST/digest, feature/evaluator/catalogue versions and creator; approval/activation/retirement/rollback append immutable events and never reinterpret historical decisions.
- Failure sequence: Submitted bytes are edited, current catalogue changes old meaning, rollback rewrites the prior active version, or decision explanation reads today's AST.
- Implementation: V56 stores immutable canonical versions/events/evaluations; V57 protects lifecycle and history; V58 safely expands rollback event representation; simulation snapshots bind version, exact rule bytes/digest, and action; decision evidence stores version/digest.
- Proof: `PostgreSqlMigrationTests.governedRuleDefinitionsAndLifecycleEventsAreProtectedByPostgreSql`, snapshot drift tests, activation gate tests, API rollback evidence, fresh/retained startup, and the live demo prove history preservation.
- Adversarial evidence: Draft/submitted/approved/active/retired states, corrupt digest, missing feature version, reordered AST, and retired rollback target.
- Status: PASS

## C39-CON-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Submit, approve, activate, retire, and rollback lock in global order—idempotency, live actor, merchant configuration guard, control identity/version, simulation—so two approvals/activations converge to one legal lifecycle and exactly one active version per rule identity.
- Failure sequence: Two managers approve/activate simultaneously, candidate changes after gate read, or one transaction deactivates current while another activates stale evidence.
- Implementation: Every mutation locks the live manager, merchant configuration guard, and exact version before gate revalidation; PostgreSQL partial unique indexes enforce one active identity; events are append-only and idempotency is actor-bound.
- Proof: Existing forced `MerchantRiskPolicyPostgreSqlTests` prove the shared configuration-guard serialization protocol, V57 unique/trigger evidence proves the control-specific postcondition, and contract/live rollback proofs reconcile exact lifecycle events.
- Adversarial evidence: Same/different key, creator as approver, disabled approver, expired report at lock, digest drift, and two approved candidates.
- Status: PASS

## C39-CON-002

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Confirm locks/reads the merchant configuration boundary consistently with activation and exception expiry: each decision sees either the complete old set or complete new set, never a mix; external processor/Redis work never occurs while an unbounded configuration lock is held.
- Failure sequence: Activation commits between rule lookup and evaluation, exception expires between match and suppression, or confirm sees new rule with old predicates.
- Implementation: Control activation uses the same merchant risk configuration guard already fenced against confirm; active definitions are immutable, evaluated as one bounded snapshot, and decision-time exception applicability is persisted.
- Proof: `MerchantRiskPolicyPostgreSqlTests` forces activation-first and confirm-first interleavings on the shared guard; Chapter 39 contract/live evidence proves complete active rule/exception outcomes and exact expiry uses `start <= decisionTime < expiry`.
- Adversarial evidence: Multiple active rules, exception exact expiry, concurrent rollback, Redis unavailable, and non-ALLOW processor suppression.
- Status: PASS

## C39-API-001

- Lens: API_PRODUCT
- Invariant: Only a live RISK_MANAGER creates/edits/submits/approves/activates/retires/rolls back controls; creator cannot approve; every mutation is actor-idempotent with strict DTOs and expected version; report/gate failures return stable 400/403/404/409 rather than partial activation.
- Failure sequence: Viewer/analyst mutates controls, self-approval succeeds, completed replay bypasses disablement, unknown JSON widens a rule, or stale UI overwrites lifecycle.
- Implementation: `/risk/controls` exposes strict typed create/list/transition routes, manager authorization, actor-scoped idempotency, closed lifecycle transitions, dual control, and stable error mapping.
- Proof: `MerchantRiskPolicyContractTests`, global API/Jackson error contracts, UI tests, and byte-exact public demo replays prove success, self-approval denial, missing simulation, invalid transition, and safe response behavior.
- Adversarial evidence: Missing key, wrong content type, unknown enum, retired mutation, repeat approval, self approval, and disabled actor replay.
- Status: PASS

## C39-EXC-001

- Lens: PAYMENT_FRAUD
- Invariant: A safety exception names exactly one tenant-owned purpose-HMACed exact subject, one explicit overridable source version, one closed reason, start and expiry with duration at most 30 days, distinct author/approver, and a successful exact simulation; wildcard/BIN/country/arbitrary-query identity and protected sources are rejected.
- Failure sequence: An exception broadens across customers/cards/rules, survives HMAC rotation incorrectly, suppresses a protected source, applies before start/at expiry, or self-approval activates it.
- Implementation: V56 and `GovernedControlService` constrain subject to exact card fingerprint, one authored source version, two closed reasons, strict half-open validity, at most 30 days, purpose/version HMAC, dual control, and exact simulation-gated activation; unavailable key evidence fails closed.
- Proof: API contracts cover exact tenant payment-method binding, self approval, active application, response privacy, and source match; database time/reason/subject checks, readiness key inventory, historical simulation, and the public demo cover the remaining boundary.
- Adversarial evidence: Wrong purpose/version/source, foreign subject, hash collision fixture, inactive rule, creator approval, stale report, and exact expiry.
- Status: PASS

## C39-OBS-001

- Lens: OBSERVABILITY
- Invariant: Operators can distinguish parse/validate/submit/approve/gate/activate/rollback/evaluate/exception outcomes using closed operation/result/control/action/reason tags; rollback emits no success and AST bytes, operands, subject HMAC, IDs, digests, actors, tenants, and secrets never become tags.
- Failure sequence: A feature/value becomes a tag, failed activation emits success, HMAC appears in an error, or invalid exception use is invisible.
- Implementation: `GovernedControlMetrics` emits only closed family/transition/matched/action/disposition tags; audits contain safe lifecycle metadata; global errors/logs omit AST operands and protected identity material.
- Proof: Source/tag inspection, global API error contracts, response canaries, rollback failure/success observations, and the public live demo prove bounded observable outcomes without sensitive tags.
- Adversarial evidence: Parser exception, gate reason, digest mismatch, expired exception, protected suppression attempt, database conflict, and hostile display metadata.
- Status: PASS

## C39-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Fresh V1 and retained V55 upgrades add rule/exception state empty by default, preserve all prior bytes, install constraints/indexes/history guards, retain every HMAC version required by active exceptions, and fail readiness when catalogue/key/schema truth is incomplete.
- Failure sequence: Migration invents active controls, weakens existing rules, drops old HMAC material, starts with a corrupt AST/digest, or leaves two active versions.
- Implementation: V56–V58 install empty governed state, exact constraints/indexes, append-only/protected-history triggers, rollback representation, and `GovernedControlHealthIndicator` validates schema, active canonical rule bytes/digests, and active exception HMAC key availability.
- Proof: fresh V1–V58 and retained V55–V58 PostgreSQL startup, five trigger assertions, direct mutation denial, H2 validation, retained live readiness, and current-binary public demo pass.
- Adversarial evidence: Empty/live controls, old HMAC version, partial migration, corrupt canonical bytes, missing protected-source catalogue, and two workers/managers.
- Status: PASS

## C39-OPS-002

- Lens: OPERATIONS_RESILIENCE
- Invariant: Chapter 39 closes only after grammar, numeric, Unicode, lifecycle, dual-control, activation/confirm, exception/HMAC/expiry, tenant/privacy, migration, UI, and public-demo boundary families pass at real enforcement boundaries with no blocking finding or undocumented debt.
- Failure sequence: Example-only parser tests replace properties, H2 replaces PostgreSQL races, a unit gate replaces activation transaction proof, or the demo activates without exact simulation evidence.
- Implementation: The pre-implementation matrix drove codec, migration, lifecycle, simulation, decision, UI, operational, and demo work; the live hostile pass reopened rollback representation and fixed it forward in V58 before closure.
- Proof: Full Java regression, focused PostgreSQL/control/simulation suites, Python demo safety tests, 4 UI tests/build, retained live PostgreSQL demo with the explicit local-only `1`/`0` evidence threshold, naming/governance gates, `ER-0058`, and source reconciliation form the closure evidence. The product default remains 100 eligible rows and 50% label coverage.
- Adversarial evidence: DRAFT matrix, open promise finding, stale simulation, protected suppression, unsafe identity, timing-only race, source overclaim, and vague deferral.
- Status: PASS

## Frozen Boundary Tables

### Authored rule grammar

| Boundary | Valid | Rejected |
|---|---|---|
| Root/group | exactly one `all` or `any`, non-empty bounded clauses | both/neither/empty, unknown or duplicate key |
| Predicate | exact feature, compatible operator, typed value | dynamic field, coercion, unknown feature/operator/type |
| Cost | depth 1–3, predicates 1–32, set 1–20, bytes 1–8192 | depth 4, 33 predicates, 21 set values, 8193 bytes |
| Numeric | exact integral values within feature/Long bounds | fraction/exponent ambiguity, overflow, float coercion |
| Capability | deterministic local comparison only | regex, contains, script, clock, random, HTTP, database access |
| Action | `BLOCK`, `REVIEW`, `REQUEST_3DS` | authored `ALLOW`, arbitrary action |

### Lifecycle, activation, and exception safety

| Boundary | Valid | Rejected |
|---|---|---|
| Lifecycle | `DRAFT -> SUBMITTED -> APPROVED -> ACTIVE -> RETIRED` | edit submitted bytes, skip/reverse transition |
| Dual control | different live tenant manager approves | creator/self, disabled/wrong-role/foreign actor |
| Activation | exact unexpired successful report and gate acceptance under lock | stale/foreign/drifted/failed/unsafe/low-coverage report |
| Rollback | prior approved compatible version becomes active via new event | history rewrite, unapproved/foreign target |
| Exception subject | one exact purpose/version HMAC and closed subject type | raw PII, wildcard, BIN/country/query, wrong purpose/version |
| Exception source | one named overridable source version | protected source, wildcard/all controls, stale/foreign source |
| Exception time | `start <= decision_time < expiry`, duration ≤30 days | before start, at/after expiry, >30 days |
| Evidence | match retained plus separate applied/ignored exception reason | erased match, exception emits ALLOW, silent invalid evidence |
