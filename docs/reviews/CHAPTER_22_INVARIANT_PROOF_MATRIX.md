# Chapter 22 Invariant Proof Matrix

- Chapter: Chapter 22 — Feature Contract And Point-In-Time Lineage
- Matrix provenance: PRE_IMPLEMENTATION
- Scope status: FROZEN
- Review status: IN_PROGRESS
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PENDING
- Fresh hostile review: PENDING
- Boundary family review: PENDING
- Custom infrastructure review: NOT_APPLICABLE
- Escaped review assessment: COMPLETE

This matrix was drafted before Chapter 22 implementation from the promises and
failure families in
`docs/PHASE_3_DETAILED_IMPLEMENTATION_PLAN.md`. Planned test names are proof
obligations, not claims that the tests already exist. Founder/domain review
confirmed the freeze before production code or migration changes began.

## C22-PAY-001

- Lens: PAYMENT_FRAUD
- Invariant: Replacing ad hoc snapshot construction with typed feature materialization does not change the allow-only confirm, processor, capture, cancel, refund, ledger, audit, or webhook behavior inherited by Chapter 22.
- Failure sequence: Feature materialization changes a value or throws after a payment transition, causing a previously valid allow to block, duplicate authorization, omit automatic capture, or commit partial payment truth.
- Implementation: Planned `FeatureMaterializer` runs inside the existing idempotent confirm transaction before the unchanged allow-only processor branch; existing payment lifecycle services remain the enforcement boundary.
- Proof: Planned `Chapter22FeatureLineageContractTests.typedMaterializationPreservesEveryProcessorOutcomeAndCaptureMode`, existing Chapter 20 processor catalogue contracts, money lifecycle suites, and an allow-only public demo.
- Adversarial evidence: Exercise all nine processor outcomes, automatic/manual capture, replay, retryable decline, and injected feature persistence failure; reconcile unchanged processor and money postconditions.
- Status: PENDING

## C22-PAY-002

- Lens: PAYMENT_FRAUD
- Invariant: Every committed new PaymentAttempt owns exactly one FraudDecision, one FeatureSnapshot, and the complete V1 FeatureValue set before processor truth is created.
- Failure sequence: A processor decline or exception commits without feature lineage, a retry duplicates lineage, or an allow reaches the processor before the complete feature set is durable in the transaction.
- Implementation: Existing unique decision/snapshot constraints plus planned unique FeatureValue identity and ordered confirm orchestration enforce completeness.
- Proof: Planned `everyCommittedAttemptHasOneCompleteFeatureLineageBeforeAuthorization` and rollback/replay contract tests.
- Adversarial evidence: Fail each feature write position and the processor call boundary; assert zero partial rows on rollback and exact cardinality on retry.
- Status: PENDING

## C22-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: FeatureValue, FeatureSnapshot, FraudDecision, PaymentAttempt, and PaymentIntent lineage cannot cross merchant boundaries through API substitution, repository misuse, or direct database writes.
- Failure sequence: Merchant A confirms with Merchant B's PaymentMethod or reads a decision ID, while a weakened lookup or non-composite foreign key attaches B's feature values to A's attempt.
- Implementation: API-key-derived MerchantContext, owned resource lookups, merchant-scoped repositories, and same-tenant composite foreign keys enforce identity.
- Proof: Planned `crossMerchantSubstitutionCreatesNoFeatureLineage`, tenant read contracts, and database constraint tests for every mismatched lineage pair.
- Adversarial evidence: Substitute intent, method, attempt, decision, snapshot, and feature-value identifiers in both application and direct-SQL partitions; return not-found without side effects or existence leaks.
- Status: PENDING

## C22-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: The V1 feature surfaces never persist, log, audit, webhook, or return raw PAN, CVV, card fingerprint, BIN, last4, PII, credentials, idempotency material, free-form metadata, or descriptions.
- Failure sequence: The materializer copies the current broad snapshot map or logs a failed serialized feature value, exposing prohibited card or merchant-controlled data.
- Implementation: An allowlisted typed feature catalogue, safe API projection, audit/webhook allowlists, and log/metric restrictions enforce minimization.
- Proof: Planned `featureCatalogueRejectsEveryProhibitedSource`, persistence/API/audit/webhook sensitive-data scans, and `AuditSanitizerTests` regression.
- Adversarial evidence: Seed each prohibited exact value across input fields, force serialization and persistence failures, then scan database rows, responses, events, audit payloads, logs, and metrics.
- Status: PENDING

## C22-SEC-002

- Lens: SECURITY_PRIVACY
- Invariant: Feature type and size validation fails before durable mutation and cannot be bypassed through malformed strings, Unicode, numeric overflow, or serializer representation differences.
- Failure sequence: A value passes the Java type check but changes type/precision in stored JSON or exceeds the bounded snapshot only after partial feature rows are written.
- Implementation: Definition-driven typed values, exact numeric range checks, lexical feature keys, bounded UTF-8 serialization, database typed-value constraints, and one transaction enforce representation safety.
- Proof: Planned table-driven `rejectsEveryFeatureTypeAndSizeBoundaryPartition`, converter round-trip property tests, and real database boundary tests.
- Adversarial evidence: Cover empty/max/over-max strings, Unicode byte expansion, long minima/maxima/overflow input representations, nulls, duplicate keys, unknown keys, and mismatched typed columns.
- Status: PENDING

## C22-CON-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Decision, FeatureSnapshot, all FeatureValues, attempt mutation, processor/money truth, audit, webhook, and idempotency completion commit atomically or roll back together.
- Failure sequence: FeatureValue N commits while N+1 fails, or authorization/capture commits while snapshot completion or idempotency response fails.
- Implementation: The existing idempotency mutation transaction surrounds all planned lineage writes and downstream side effects; unique and foreign-key constraints provide defense in depth.
- Proof: Planned `Chapter22FeatureLineageRollbackTests` injecting failure before, during, and after every new persistence boundary.
- Adversarial evidence: Assert absence of every partial artifact, reclaim/retry of the original idempotency reservation, and one coherent result after retry.
- Status: PENDING

## C22-CON-002

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Concurrent confirms cannot create multiple attempts or divergent feature snapshots from one eligible PaymentIntent.
- Failure sequence: Two distinct idempotency keys both pass eligibility, materialize at different times, and commit two attempts; or a same-key retry evaluates while the first owner is blocked in feature persistence.
- Implementation: PaymentIntent `FOR UPDATE`, fenced idempotency ownership, attempt numbering, and unique lineage constraints serialize the conflict.
- Proof: Planned `Chapter22FeatureLineagePostgreSqlConcurrencyTests` with forced distinct-key and same-key orderings plus PostgreSQL lock probes.
- Adversarial evidence: Block after PaymentIntent lock and after the first FeatureValue write, prove the competing backend waits on the intended row, then assert one attempt and one internally consistent feature set.
- Status: PENDING

## C22-DATA-001

- Lens: DATA_ML_LINEAGE
- Invariant: Every Phase 3 V1 feature has one immutable definition and one typed value whose definition, type, version, observed time, computed time, attempt, merchant, and traffic origin agree with its snapshot and decision.
- Failure sequence: A renamed definition silently changes an old feature, a typed column disagrees with JSON, or origin/version is copied from a different resource.
- Implementation: Immutable seeded definitions, typed-value database constraint, composite definition identity, unique per-attempt value identity, and materializer reconciliation enforce lineage.
- Proof: Planned `definitionValueSnapshotAgreementIsExact`, migration seed tests, and direct database constraint tests.
- Adversarial evidence: Attempt definition mutation, duplicate code/version, wrong type, multiple/nonexistent typed columns, mismatched origin, and snapshot/value disagreement.
- Status: PENDING

## C22-DATA-002

- Lens: DATA_ML_LINEAGE
- Invariant: A historical training join can use only FeatureValues observed at or before the decision start, and later payment or reference-data changes cannot rewrite the historical row.
- Failure sequence: A value observed after decision time is inserted, or an offline query selects the latest current value and leaks future information into training.
- Implementation: Immutable per-attempt FeatureValues, observed/computed timestamp checks, application comparison to decision start, and a canonical point-in-time repository query enforce temporal lineage.
- Proof: Planned `pointInTimeJoinExcludesBeforeAtAfterDecisionPartitions` against PostgreSQL and immutability tests.
- Adversarial evidence: Insert values one database precision tick before, exactly at, and one tick after decision start; include later entity mutation and verify the earlier row/query result is unchanged.
- Status: PENDING

## C22-API-001

- Lens: API_PRODUCT
- Invariant: Existing confirm and decision APIs remain backward compatible while decision detail exposes the exact V1 snapshot without internal definition IDs or prohibited values.
- Failure sequence: A new field changes confirm replay bytes/status, old stub decisions fail to deserialize, or the detail endpoint leaks internal/sensitive fields.
- Implementation: Existing PaymentAttempt response remains unchanged; decision detail retains current fields and snapshot shape; old rows require no backfill rewrite.
- Proof: Planned API compatibility tests for old/new decisions, exact idempotent response replay, and OpenAPI/example reconciliation if present.
- Adversarial evidence: Read legacy `rules_stub_v1` rows, new V1 rows, missing lineage, and cross-tenant IDs; compare response fields and sensitive-data absence.
- Status: PENDING

## C22-OBS-001

- Lens: OBSERVABILITY
- Invariant: Operators can distinguish materialization, validation, persistence, and freshness failures using bounded dimensions without feature values or merchant/resource identifiers in metrics.
- Failure sequence: All failures collapse into a generic confirm error, or a metric/log label includes merchant ID, feature value, card data, or free-form exception text.
- Implementation: Planned bounded counters/timers by stage, cause, feature code, freshness status, and traffic origin plus safe structured logs enforce diagnosability.
- Proof: Planned `FeatureMaterializationMetricsTests` and log/metric sensitive-dimension scans.
- Adversarial evidence: Force every failure stage and compare exact bounded signals while seeded secret/PII/card values remain absent.
- Status: PENDING

## C22-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Fresh and supported Chapter 21 databases migrate to the Phase 3 feature schema without rewriting historical decisions, and application readiness rejects missing, duplicate, or incompatible V1 definitions before serving confirms.
- Failure sequence: Seed migration partially applies, legacy rows fail Hibernate validation, or a deployment starts with a missing/wrong-type definition and creates incomplete lineage.
- Implementation: Forward-only Flyway migration, idempotent immutable reference seeds, Hibernate validation, and startup catalogue validation enforce readiness.
- Proof: Planned fresh-schema and V1-to-current PostgreSQL migration tests plus startup validation partition tests.
- Adversarial evidence: Remove, duplicate, rename, and type-change each required definition; prove startup fails before traffic and prior schema/data remains intact.
- Status: PENDING

## C22-OPS-002

- Lens: OPERATIONS_RESILIENCE
- Invariant: Chapter 22 cannot close from schema presence or aggregate green tests; every planned promise must trace to production enforcement and exact proof, with no false claim that deterministic fraud actions are active.
- Failure sequence: README calls Phase 3 operational after feature tables land, or the chapter dossier recommends merge while matrix entries, migrations, hostile review, or accepted debt remain incomplete.
- Implementation: Source-of-truth reconciliation, debt/escaped-review gates, matrix checker, closure checker, and commit readiness enforce honest status.
- Proof: Planned source-status search and final readiness invocation naming the Chapter 22 matrix and dossier.
- Adversarial evidence: Search for contradictory Phase 3/rules status, skipped proof, non-resolved debt, and premature `READY_TO_MERGE`/`MERGE` claims.
- Status: PENDING
