# Chapter 45 Invariant Proof Matrix

- Chapter: Chapter 45 — Payment And Money Event Integration
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

Verification update (2026-08-01): eleven payment/money event mappings execute
inside their authoritative transactions. Producer, rollback, observability,
existing lifecycle, PostgreSQL event, privacy, lineage, idempotency, and ledger
regressions passed with no replay-to-command dependency.

This matrix was drafted and frozen on 2026-07-27 after founder implementation
authorization and before Phase 5 production code, migrations, runtime
configuration, product fixtures, UI, or demo changes. Chapter 45 is not
dependency-ready until Chapter 44 is merge-ready. Planned integrations and
tests are proof obligations only; all rows remain `PENDING`.

Chapter 45 reuses ADR-0011 and the Chapter 44 catalogue/codec/writer. It owns
payment and money producer mappings, not a second event substrate. Chapter 46
owns analyst and risk-operation label producers even though their events use
the same PaymentIntent stream.

## C45-PAY-001

- Lens: PAYMENT_FRAUD
- Invariant: Each committed in-scope payment lifecycle fact appends exactly one catalogue event to the PaymentIntent stream with no event for a rejected or rolled-back transition and no event replay that repeats processor, payment, fraud, ledger, audit, or webhook mutation.
- Failure sequence: A failed confirm emits a success fact, idempotent replay appends another event, cancel emits capture truth, or consuming/replaying an event calls the original command path.
- Implementation: producer mappings inside the existing create, confirm, authentication, capture, cancel/void, refund, and chargeback transactions call the Chapter 44 writer only after each transition's authoritative state is known.
- Proof: `PaymentDomainEventServiceTests`, `PaymentDomainEventRollbackTests`, source call-graph checks, and retained public payment-lifecycle reconciliation assert exact event type/count and unchanged command behavior.
- Adversarial evidence: Every allowed/rejected lifecycle state, processor approval/decline/unknown, risk allow/block/review/authentication, exact/conflicting idempotency replay, rollback at each event boundary, and attempted event replay.
- Status: PASS

## C45-MNY-001

- Lens: PAYMENT_FRAUD
- Invariant: Capture, refund, authorization void, and chargeback events report already committed balanced money facts and exact source references; event publication, retry, consumption, or replay can never create or adjust a ledger entry, settlement, capture, refund, or chargeback.
- Failure sequence: Event insertion precedes ledger balance proof, a replay duplicates a ledger group, aggregate analytics becomes accounting authority, or a partial refund/chargeback reports an amount different from the authoritative committed allocation.
- Implementation: payload mappers read the just-committed transaction-owned identifiers and integer minor-unit amounts while retaining ledger-group authority and the existing payment lock order; no event handler dependency is allowed into money services.
- Proof: `PaymentDomainEventRollbackTests`, ledger balance/digest comparisons, event-replay non-interference tests, and exact source-to-payload reconciliation cover capture, partial/full refund, void, chargeback, and overlapping outcomes.
- Adversarial evidence: Zero/partial/full capture, multiple refunds, refund-versus-chargeback overlap, approved authorization void, duplicate chargeback reference, rollback, overflow boundary, currency mismatch, and event duplicate.
- Status: PASS

## C45-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: Payment events derive merchant and PaymentIntent stream identity only from the authenticated transaction-owned aggregate; a caller-supplied or copied customer, intent, attempt, authorization, capture, refund, chargeback, authentication, or event ID cannot bind another merchant's fact.
- Failure sequence: A join follows a bare UUID, an event payload supplies trusted merchant identity, or a copied foreign idempotency key returns or creates foreign event metadata.
- Implementation: producer adapters accept tenant-qualified locked domain objects, Chapter 44 composite constraints, and principal-bound idempotency; envelope merchant/stream fields cannot be set from request DTOs.
- Proof: `PaymentDomainEventServiceTests` and direct PostgreSQL composite-key tests use equal-shaped two-merchant fixtures and assert not-found/non-disclosure plus zero cross-tenant events.
- Adversarial evidence: Foreign customer/intent/attempt/authorization/refund/chargeback/challenge ID, same request key across merchants, copied event ID, mismatched payload identity, disabled merchant credential, and invalid direct insert.
- Status: PASS

## C45-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: Payment event schemas contain only allowlisted safe identifiers, integer minor-unit money, closed outcomes, version/digest lineage, and bounded reasons; they exclude PAN, CVV, API keys, authentication secrets, idempotency material, PII, raw risk context, device/session/IP identity, unrestricted metadata, exception text, and ledger internals not required by the consumer contract.
- Failure sequence: A broad entity serializer adds a sensitive field, processor error text enters payload, payment method details expose PAN-like data, or fixtures/docs teach unsafe logging or event inspection.
- Implementation: typed payload records and per-type schema codecs with no entity serialization or arbitrary maps; existing tokenized payment-method references and closed reason codes are mapped explicitly.
- Proof: `PaymentDomainEventServiceTests`, prohibited-value canaries across exact event bytes/database/logs/metrics/audit/fixtures, schema field inventory, and source scans for broad serialization.
- Adversarial evidence: PAN/CVV/email/phone/address/IP/device/session/API key/idempotency/processor-error canaries, Unicode reason input, maximum field lengths, null/absent optional fields, and database failure.
- Status: PASS

## C45-CON-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Existing payment lock and idempotency order remains authoritative; event stream allocation occurs after the owned aggregate locks and before commit, so confirm/capture/cancel/refund/chargeback races converge to one legal domain state and matching gap-free event sequence.
- Failure sequence: The writer introduces lock inversion, two conflicting transitions emit incompatible next versions, a losing transition leaves an event, or an expired idempotency lease permits two processor calls and two events.
- Implementation: integration preserves live credential and idempotency locks, then existing PaymentIntent/money resource locks, then Chapter 44 stream allocation/event/publication insertion; it introduces no independent transaction.
- Proof: `PaymentIntentCaptureCancelPostgreSqlConcurrencyTests` extend existing forced confirm/capture/refund/chargeback/cancel orderings with lock probes and exact durable event/ledger/idempotency postconditions.
- Adversarial evidence: Both orderings of confirm/cancel, capture/cancel, capture/refund, refund/refund, refund/chargeback, authentication completion/cancel, exact/conflicting replay, stale lease, and injected rollback.
- Status: PASS

## C45-DATA-001

- Lens: DATA_ML_LINEAGE
- Invariant: Payment event payloads preserve decision-time feature/policy/rule versions, server-assigned traffic origin, processor and authentication outcome, immutable source IDs, occurrence/recording time, and label availability semantics without replacing them with current state.
- Failure sequence: Current policy or risk context overwrites historical decision lineage, synthetic traffic becomes live, later label/chargeback evidence appears at decision time, or rebuild infers an absent processor/authentication fact.
- Implementation: mappers use transaction-owned immutable FraudDecision, FeatureSnapshot, PaymentAttempt, Authorization, AuthenticationChallenge, and source money rows; optional unavailable lineage remains explicit rather than fabricated.
- Proof: `PaymentDomainEventServiceTests`, before/exact/after availability fixtures, live/synthetic partitions, golden bytes, and retained public reconciliation trace source IDs and versions to each event.
- Adversarial evidence: Live/synthetic, labeled/unlabeled, later label and chargeback, old/current policy/rule/feature version, authentication required/completed/failed/expired, processor decline/unknown, and missing optional actor/request lineage.
- Status: PASS

## C45-CAT-001

- Lens: API_PRODUCT
- Invariant: The frozen payment catalogue has one semantic owner and one versioned payload contract per committed fact; lifecycle APIs and responses remain backward compatible, and an event type is not added without a demonstrated projection or lineage consumer purpose.
- Failure sequence: Create and confirm use competing names for one fact, Chapter 46 emits a duplicate label mapping, an additive field changes meaning, or API success begins depending on Kafka availability.
- Implementation: Chapter 44 catalogue entries and typed producer registrations for the exact table below; existing controllers and response DTOs remain unchanged except internal writer participation.
- Proof: `PaymentDomainEventServiceTests`, schema/golden inventory, producer-owner scan, existing API contract regression, Kafka-absent contract, and documentation reconciliation.
- Adversarial evidence: Known/unknown version, duplicate owner, missing schema fixture, additive optional versus semantic change, Kafka absent, producer disabled, unchanged HTTP response, and route/method/media boundaries.
- Status: PASS

## C45-OBS-001

- Lens: OBSERVABILITY
- Invariant: Bounded signals identify committed producer family, closed event type/version, outcome, transaction truth, and event-write failure without tenant, payment, event, actor, request, trace, payload, digest, money identifier, credential, or exception text in metric dimensions or unsafe logs.
- Failure sequence: Rollback increments committed-event count, one payment creates a high-cardinality tag, processor/database error text leaks values, or missing event insertion remains invisible while API reports success.
- Implementation: transaction-after-completion producer metrics, safe closed logs, Chapter 44 backlog/readiness, and no new source identifiers in tags.
- Proof: `PaymentEventObservabilityTests`, rollback-safe metrics, cardinality assertions, privacy canaries, and readiness degradation when the required producer catalogue/schema is unavailable.
- Adversarial evidence: Commit/rollback, each event family, producer enabled/disabled, schema missing, digest failure, processor/database exception, sensitive canary, and repeated high-volume payment IDs.
- Status: PASS

## C45-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Payment producer enablement is forward-compatible and fail-closed against Chapter 44 schema/catalogue readiness; retained V64 payment history is not backfilled, mixed-version operation never emits an unsupported version, and disabling producers preserves already pending events.
- Failure sequence: A rolling old consumer sees a new unsupported event, retained rows gain fabricated events, configuration rollback deletes pending work, or broker outage is incorrectly allowed to roll back payment API success.
- Implementation: producer-version readiness gate and capability-named additive migration only if integration requires new constraints; Chapter 44 publication rows remain durable and Kafka remains outside the transaction.
- Proof: `PaymentEventDeploymentPostgreSqlTests` proves the deployment claims that have a mechanism behind them, on real PostgreSQL with the broker absent by construction — the publisher and Kafka sender beans are asserted absent from the context, so every pending-evidence assertion is made with no broker in the picture. It asserts a payment API success commits its event and publication together with every publication `PENDING`, that pending evidence cannot be deleted or advanced to `PUBLISHED` out of band, that an unsupported catalogue version is refused before anything becomes durable while the supported version on the same stream still writes, and that a retained V64 upgrade carries payment history forward with zero `domain_events`, `event_publications` and `event_stream_heads` fabricated — then migrates a second time to prove repeated startup is a no-op with no failed migration. The producer-version gate the row describes now exists as `DomainEventProducerGate`, and `DomainEventProducerRolloutPostgreSqlTests` exercises it the way a rolling deployment does, starting and stopping real application instances against one database through `ApplicationRestartHarness`: an instance configured for a version the catalogue cannot serve refuses to start and writes nothing; producer off then on refuses emission while disabled, leaves every pending publication untouched, burns no stream version, and resumes on re-enable; an instance that starts disabled before any event exists allocates no stream head; and an emission at a version this deployment does not produce is refused before anything is durable while the configured version on the same stream still writes.
- Adversarial evidence: Fresh and retained V64-plus schema, supported and unsupported catalogue version, producer off/on/off across real restarts, old and new application order, broker absent, repeated startup, pre-existing pending event, and deletion or out-of-band advance of pending evidence.
- Status: PASS

## C45-SCP-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Chapter 45 owns source-side payment producers only; it cannot claim Kafka acknowledgement, consumer inbox behavior, analytics correctness, rebuild, dataset equivalence, or analyst label production, and its demos cannot use direct SQL or hidden alternate payment paths.
- Failure sequence: A mock producer is presented as Kafka proof, an event handler mutates money, analytics are inferred from source events without a consumer, or demo setup writes rows directly.
- Implementation: source and documentation boundaries retain Chapters 47–51 ownership while the public lifecycle demo uses ordinary merchant APIs and durable event reconciliation only.
- Proof: component/dependency scans, route/demo safety tests, no Kafka consumer or projection registration, and before/after source counts around event replay attempts.
- Adversarial evidence: Mock broker, no broker, duplicate record, direct SQL, hidden configuration, source reading, consumer classpath scan, and event replay with payment/ledger counts.
- Status: PASS

## Frozen Payment Catalogue

| Event type | Authoritative committed fact | Stream | Producer owner |
|---|---|---|---|
| `payment_intent.created` | new PaymentIntent committed | PaymentIntent | Chapter 45 |
| `payment_intent.canceled` | intent cancellation committed | PaymentIntent | Chapter 45 |
| `payment_attempt.completed` | processor/risk attempt reaches committed terminal or authentication-required outcome | PaymentIntent | Chapter 45 |
| `fraud_decision.created` | immutable decision and feature/control lineage committed | PaymentIntent | Chapter 45 |
| `authorization.created` | approved authorization committed | PaymentIntent | Chapter 45 |
| `authorization.voided` | approved authorization void committed during cancellation | PaymentIntent | Chapter 45 |
| `capture.created` | balanced capture group and source row committed | PaymentIntent | Chapter 45 |
| `refund.created` | balanced refund group and source row committed | PaymentIntent | Chapter 45 |
| `chargeback.created` | chargeback allocation and balanced money evidence committed | PaymentIntent | Chapter 45 |
| `authentication_challenge.created` | immutable authentication challenge committed | PaymentIntent | Chapter 45 |
| `authentication_challenge.completed` | immutable terminal authentication result committed | PaymentIntent | Chapter 45 |
| `fraud_label.recorded` / `fraud_label.superseded` | analyst or trusted operational label becomes available | PaymentIntent | Chapter 46 only |

## Frozen Hostile-Family Coverage

| Phase 5 family | Chapter 45 obligations |
|---|---|
| Atomic creation | C45-PAY-001 and C45-CON-001 |
| Stream ordering | C45-CON-001 |
| Canonical protocol | C45-SEC-001, C45-DATA-001, and C45-CAT-001 |
| Publication | C45-OPS-001 preserves pending truth; Kafka proof remains Chapter 47 |
| Consumption | C45-MNY-001 and C45-SCP-001 prove no command re-entry; inbox proof remains Chapter 48 |
| Tenant isolation and authorization | C45-TEN-001 |
| Projection correctness | C45-MNY-001 and C45-DATA-001 freeze inputs; derived proof remains Chapter 49 |
| Rebuild | C45-DATA-001 and C45-SCP-001 preserve immutable input/non-impact; runtime proof remains Chapter 50 |
| Security/privacy | C45-SEC-001 and C45-OBS-001 |
| Migration | C45-OPS-001 |
| Operations | C45-OBS-001 and C45-OPS-001 |
| Documentation/demo | C45-CAT-001 and C45-SCP-001 |
