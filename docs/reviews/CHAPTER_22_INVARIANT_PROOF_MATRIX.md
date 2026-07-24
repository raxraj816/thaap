# Chapter 22 Invariant Proof Matrix

- Chapter: Chapter 22 — Feature Contract And Point-In-Time Lineage
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

This matrix was drafted before Chapter 22 implementation from the promises and
failure families in
`docs/PHASE_3_DETAILED_IMPLEMENTATION_PLAN.md`. Planned test names are proof
obligations, not claims that the tests already exist. Founder/domain review
confirmed the freeze before production code or migration changes began.
Implementation and focused proof were completed first; a later hostile pass
then reopened deferred SQL flush behavior and immutable-row cleanup as full
transaction and database-lifecycle families. Explicit pre-processor flush and
constraint-aware test cleanup close those findings before verification.

A post-closure Senior Staff review on 2026-07-12 reopened the matrix before
repair implementation. ER-0043 found that the database definition foreign key
did not bind `value_type`; ER-0044 found that the decision engine received an
independently reconstructed input instead of the governed materialized feature
set; and ER-0045 found that rollback proof stopped at mocked repository writes
instead of a real PostgreSQL flush failure. The repair binds definition type in
the composite foreign key, makes the exact materialized context the engine's
only input, and forces a real flush failure through confirm. The complete
affected type, decision-input, privacy, transaction, and retry families passed
before this matrix returned to VERIFIED.

## C22-PAY-001

- Lens: PAYMENT_FRAUD
- Invariant: Replacing ad hoc snapshot construction with typed feature materialization does not change the allow-only confirm, processor, capture, cancel, refund, ledger, audit, or webhook behavior inherited by Chapter 22.
- Failure sequence: Feature materialization changes a value or throws after a payment transition, causing a previously valid allow to block, duplicate authorization, omit automatic capture, or commit partial payment truth.
- Implementation: `FeatureMaterializer` runs inside the existing idempotent confirm transaction before the unchanged allow-only `StubConfirmDecisionEngine` and processor branch; capture, cancel, refund, ledger, audit, and webhook services remain the enforcement boundaries.
- Proof: `PaymentIntentConfirmContractTests`, `PaymentIntentConfirmProcessorAdapterCallTests`, the processor catalogue and money lifecycle suites, plus `scripts/demo-feature-lineage.py` prove unchanged outcomes and an allow-only public flow.
- Adversarial evidence: The 298-test backend suite covers the processor catalogue, automatic/manual capture, replay, retryable declines, rollback, and downstream money behavior; the live demo reconciled `rules_stub_v1`, authorization, seven typed values, and exact replay.
- Status: PASS

## C22-PAY-002

- Lens: PAYMENT_FRAUD
- Invariant: Every committed new PaymentAttempt owns exactly one FraudDecision, one FeatureSnapshot, and the complete V1 FeatureValue set before processor truth is created.
- Failure sequence: A processor decline or exception commits without feature lineage, a retry duplicates lineage, or an allow reaches the processor before the complete feature set is durable in the transaction.
- Implementation: Existing unique decision/snapshot constraints, exact context/value reconciliation, V18 FeatureValue uniqueness, and `featureValueRepository.flush()` inside confirm force the complete seven-value lineage through SQL before processor authorization.
- Proof: `PaymentIntentConfirmContractTests` asserts one decision, one snapshot, and seven exact values for committed outcomes and replay; `PaymentIntentConfirmFraudLineageRollbackTests` covers feature positions zero through six; the PostgreSQL concurrency suite forces both a blocked successful flush and a failing flush.
- Adversarial evidence: Each injected feature-write failure rolls back attempt, decision, snapshot, values, and processor work; `realFeatureFlushFailureRollsBackBeforeProcessorReleasesReservationAndAllowsOneRetry` proves a real constraint failure makes zero processor calls, removes all partial lineage and the failed reservation, and permits one same-key retry.
- Status: PASS

## C22-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: FeatureValue, FeatureSnapshot, FraudDecision, PaymentAttempt, and PaymentIntent lineage cannot cross merchant boundaries through API substitution, repository misuse, or direct database writes.
- Failure sequence: Merchant A confirms with Merchant B's PaymentMethod or reads a decision ID, while a weakened lookup or non-composite foreign key attaches B's feature values to A's attempt.
- Implementation: API-key-derived `MerchantContext`, owned intent/method lookups, merchant-scoped decision reads, and V18 composite merchant/origin foreign keys enforce identity across the full lineage.
- Proof: `MerchantTenantIsolationTests`, confirm substitution contracts, and `FeatureLineageDatabaseContractTests` cover API and direct-SQL boundaries.
- Adversarial evidence: Cross-merchant intent, method, attempt, decision, snapshot, FeatureValue, and traffic-origin substitutions either return not-found with no rows or fail a PostgreSQL constraint.
- Status: PASS

## C22-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: The V1 feature surfaces never persist, log, audit, webhook, or return raw PAN, CVV, card fingerprint, BIN, last4, PII, credentials, idempotency material, free-form metadata, or descriptions.
- Failure sequence: The materializer copies the current broad snapshot map or logs a failed serialized feature value, exposing prohibited card or merchant-controlled data.
- Implementation: `FeatureCatalogue` fixes exactly seven safe codes; `DecisionFeatureContext` accepts only that exact typed catalogue; the decision engine interface receives no entity, BIN, last4, resource, credential, or metadata fields; responses expose only the lexical safe snapshot; metrics use bounded operational labels.
- Proof: `DecisionFeatureContextTests`, `FeatureMaterializerTests`, `PaymentIntentConfirmProcessorAdapterCallTests`, tenant/API assertions, audit/webhook regressions, and source scans prove the allowlist and prohibited-surface absence.
- Adversarial evidence: Missing, extra, wrong-type, and mutation attempts fail at context construction; engine input, snapshot, and typed rows reconcile exactly; seeded card and merchant-controlled material is absent from engine input, persistence, API, audit, webhook, and metric dimensions.
- Status: PASS

## C22-SEC-002

- Lens: SECURITY_PRIVACY
- Invariant: Feature type and size validation fails before durable mutation and cannot be bypassed through malformed strings, Unicode, numeric overflow, or serializer representation differences.
- Failure sequence: A value passes the Java type check but changes type/precision in stored JSON or exceeds the bounded snapshot only after partial feature rows are written.
- Implementation: `FeatureValue` validates exact Java types, nonempty 256-byte UTF-8 strings, LONG bounds, exact `NUMERIC(19,6)`, time order, and a single typed column; `FeatureMapConverter` preserves integral and decimal representation; V18 repeats storage constraints.
- Proof: `FeatureValueBoundaryTests`, `FraudJsonConverterTests`, `FeatureCatalogueValidatorTests`, and PostgreSQL contract cases cover application, serialization, and storage boundaries.
- Adversarial evidence: Empty, maximum, oversized, and multi-byte strings; long minima/maxima/wrong representations; decimal precision/scale; null/type mismatch; duplicate/unknown definitions; and zero/multiple typed columns are proven at their real boundary.
- Status: PASS

## C22-CON-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Decision, FeatureSnapshot, all FeatureValues, attempt mutation, processor/money truth, audit, webhook, and idempotency completion commit atomically or roll back together.
- Failure sequence: FeatureValue N commits while N+1 fails, or authorization/capture commits while snapshot completion or idempotency response fails.
- Implementation: `IdempotencyService.execute` surrounds attempt, decision, snapshot, seven values, authorization, money, audit, webhook, and replay completion in one transaction; V18 constraints provide defense in depth.
- Proof: `PaymentIntentConfirmFraudLineageRollbackTests` injects snapshot and every FeatureValue write-position failure; `FeatureLineagePostgreSqlConcurrencyTests` forces a real constraint failure at `flush()`; existing processor, audit, capture, and webhook rollback suites cover later boundaries.
- Adversarial evidence: The real PostgreSQL failure leaves zero attempt, decision, snapshot, value, authorization, or confirm reservation rows and zero processor calls; the identical key then succeeds once with one coherent lineage and completed replay record.
- Status: PASS

## C22-CON-002

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Concurrent confirms cannot create multiple attempts or divergent feature snapshots from one eligible PaymentIntent.
- Failure sequence: Two distinct idempotency keys both pass eligibility, materialize at different times, and commit two attempts; or a same-key retry evaluates while the first owner is blocked in feature persistence.
- Implementation: PaymentIntent `FOR UPDATE`, fenced idempotency ownership, attempt numbering, unique lineage constraints, and pre-processor lineage flush serialize competing confirms.
- Proof: `FeatureLineagePostgreSqlConcurrencyTests` uses database locks and PostgreSQL activity probes for same-key and distinct-key forced orderings.
- Adversarial evidence: A first owner blocked at FeatureValue SQL has no authorization; a distinct key waits on the PaymentIntent and returns 409, while a same key waits on its idempotency row and replays the exact 201 response. Postconditions are one attempt, decision, snapshot, seven values, and authorization.
- Status: PASS

## C22-DATA-001

- Lens: DATA_ML_LINEAGE
- Invariant: Every Phase 3 V1 feature has one immutable definition and one typed value whose definition, type, version, observed time, computed time, attempt, merchant, and traffic origin agree with its snapshot and decision.
- Failure sequence: A renamed definition silently changes an old feature, a typed column disagrees with JSON, or origin/version is copied from a different resource.
- Implementation: V18 makes `(definition ID, code, version, value type)` one foreign-key identity, retains typed-column and tenant/origin constraints, and permits one value per attempt/code/version; `MaterializedFeatureSet` rejects any engine-context/value disagreement; V19 rejects reference or lineage mutation.
- Proof: `DecisionFeatureContextTests`, `FeatureMaterializerTests`, `PaymentIntentConfirmProcessorAdapterCallTests`, migration tests, and `FeatureLineageDatabaseContractTests` reconcile definition, declared type, typed column, engine context, snapshot, and values.
- Adversarial evidence: Every seeded definition accepts its required declared type and rejects each incompatible LONG/STRING/BOOLEAN/DECIMAL declaration at PostgreSQL; missing/extra/wrong-type engine contexts, context/value divergence, definition mutation, origin, tenant, typed-column ambiguity, and time inversion all fail.
- Status: PASS

## C22-DATA-002

- Lens: DATA_ML_LINEAGE
- Invariant: A historical training join can use only FeatureValues observed at or before the decision start, and later payment or reference-data changes cannot rewrite the historical row.
- Failure sequence: A value observed after decision time is inserted, or an offline query selects the latest current value and leaks future information into training.
- Implementation: Immutable per-attempt values, `observed_at <= computed_at`, application comparison to decision start, and `FeatureValueRepository.findPointInTimeForDecision` enforce temporal lineage.
- Proof: `FeatureLineageDatabaseContractTests` executes the canonical join against PostgreSQL with microsecond-precision before/at/after partitions and mutation rejection.
- Adversarial evidence: Values one database precision tick before and exactly at decision start are included; the after partition is excluded, time inversion is rejected, and V19 prevents later update/delete rewriting history.
- Status: PASS

## C22-API-001

- Lens: API_PRODUCT
- Invariant: Existing confirm and decision APIs remain backward compatible while decision detail exposes the exact V1 snapshot without internal definition IDs or prohibited values.
- Failure sequence: A new field changes confirm replay bytes/status, old stub decisions fail to deserialize, or the detail endpoint leaks internal/sensitive fields.
- Implementation: The PaymentAttempt response is unchanged; decision detail keeps its contract while the safe snapshot contains exact V1 lexical keys; V18 performs no fabricated value backfill for historical decisions.
- Proof: `PaymentIntentConfirmContractTests` reads new typed and directly inserted historical `rules_stub_v1` decisions, verifies exact replay bytes/status, and checks absent typed backfill.
- Adversarial evidence: New, historical, missing, cross-tenant, and replay partitions preserve status and shape, disclose no definition IDs, and expose none of the prohibited values.
- Status: PASS

## C22-OBS-001

- Lens: OBSERVABILITY
- Invariant: Operators can distinguish materialization, validation, persistence, and freshness failures using bounded dimensions without feature values or merchant/resource identifiers in metrics.
- Failure sequence: All failures collapse into a generic confirm error, or a metric/log label includes merchant ID, feature value, card data, or free-form exception text.
- Implementation: `FeatureMaterializationMetrics` emits freshness counters, latency timers, and failure counters using bounded status, origin, stage, cause, and catalogue-code labels; exception messages omit values.
- Proof: `FeatureMaterializationMetricsTests` asserts exact names/tags and rejects merchant/resource/value/card/PII/idempotency dimensions; materializer tests cover success, stale, future, validation, and persistence stages.
- Adversarial evidence: Forced failures remain distinguishable while secret, PII, card, merchant, and resource values are absent from metric identities and exception messages.
- Status: PASS

## C22-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Fresh and supported Chapter 21 databases migrate to the Phase 3 feature schema without rewriting historical decisions, and application readiness rejects missing, duplicate, or incompatible V1 definitions before serving confirms.
- Failure sequence: Seed migration partially applies, legacy rows fail Hibernate validation, or a deployment starts with a missing/wrong-type definition and creates incomplete lineage.
- Implementation: Forward-only V18/V19 migrations add definitions, values, constraints, indexes, and immutable triggers; Hibernate validates mappings; `FeatureCatalogueValidator` is an `ApplicationRunner` readiness gate.
- Proof: `PostgreSqlMigrationTests` covers fresh V19 and realistic V17-to-V19 upgrade; `FeatureCatalogueValidatorTests` covers exact, missing, duplicate, malformed, invalid-age, and incompatible-type catalogues.
- Adversarial evidence: Upgrade preserves historical decision and snapshot bytes and creates zero fake values; every invalid required catalogue state fails validation before confirm traffic.
- Status: PASS

## C22-OPS-002

- Lens: OPERATIONS_RESILIENCE
- Invariant: Chapter 22 cannot close from schema presence or aggregate green tests; every planned promise must trace to production enforcement and exact proof, with no false claim that deterministic fraud actions are active.
- Failure sequence: README calls Phase 3 operational after feature tables land, or the chapter dossier recommends merge while matrix entries, migrations, hostile review, or accepted debt remain incomplete.
- Implementation: Root and service status, master plan, learning trail, matrix, dossier, debt register, escaped-review register, and executable readiness gates are reconciled without claiming Chapter 23 deterministic actions.
- Proof: Matrix, closure, debt, escaped-review, learning, backend, Python, and named Chapter 22 commit-readiness checks provide the final gate.
- Adversarial evidence: Final source scans find no claim that Phase 3 or `rules_v1` behavior is complete, no skipped test, no non-resolved debt, and no Chapter 22 pending/merge contradiction.
- Status: PASS
