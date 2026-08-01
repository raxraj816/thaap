# Chapter 46 Invariant Proof Matrix

- Chapter: Chapter 46 — Fraud And Risk-Operations Event Integration
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

Verification update (2026-08-01): thirty-one fraud/risk-operation mappings,
actor-safe audit integration, availability/supersession/origin lineage, and
bounded signals are implemented. Producer, rollback, authorization, tenant,
privacy, and existing operation regressions passed.

This matrix was drafted and frozen on 2026-07-27 after founder implementation
authorization and before Phase 5 production code, migrations, runtime
configuration, product fixtures, UI, or demo changes. Chapter 46 waits on
Chapter 44 merge readiness and stable Chapter 45 event conventions. Planned
producer, audit, and proof names are obligations; every row remains `PENDING`.

Chapter 46 owns analyst and risk-operation event producers, including label
facts on the PaymentIntent stream. It reuses ADR-0011 and the Chapter 44
foundation. It reconciles `AuditLog` coverage rather than creating a second
audit product or reconstructing an actor from a domain event.

## C46-FRD-001

- Lens: PAYMENT_FRAUD
- Invariant: A committed review, label, incident, control, dispute, webhook-operation, post-authorization, or trusted-scenario event reports immutable operational evidence and never rewrites the originating FraudDecision, FeatureSnapshot, PaymentIntent, processor result, refund, chargeback, ledger group, webhook payload, or historical rule/control evaluation.
- Failure sequence: Resolving review rewrites the decision, later label changes decision-time features, dispute/webhook replay repeats money or callback creation, or scenario completion becomes merchant/network truth.
- Implementation: producer mappings append events inside existing operational transactions from authoritative committed rows; payloads carry source references and availability/origin semantics while event handlers have no dependency on payment, ledger, processor, or webhook-send command services.
- Proof: `RiskOperationsDomainEventContractTests`, source-state digest comparisons, event-replay non-interference tests, and public risk-operations reconciliation assert exact event counts with unchanged historical/payment/ledger/webhook truth.
- Adversarial evidence: Review open/assign/resolve, label record/supersede, incident open/resolve, activation/rollback/expiry, dispute lifecycle, webhook verification/redelivery, post-auth signal, live/synthetic scenario, duplicate event, and attempted replay.
- Status: PASS

## C46-AUD-001

- Lens: SECURITY_PRIVACY
- Invariant: Domain events answer what committed while `AuditLog` independently answers which live actor attempted or completed a sensitive action and why; every privileged human Phase 5 producer has actor/request/trace/source coverage, automated facts never impersonate an actor, and neither record is reconstructed from the other.
- Failure sequence: A domain event invents an audit actor, automated retry writes a fictional human audit, missing actor/role/request evidence makes a privileged action unattributable, or audit payload embeds full event bytes or protected note content.
- Implementation: `RiskOperationsAuditCatalogue` reconciles every mutation in the frozen inventory to existing `AuditLogService` calls or an explicit automated/no-human disposition; producer mappings receive the transaction's live actor context rather than reverse-resolving it later.
- Proof: `RiskOperationsAuditCompletenessTests`, positive/negative audit presence, actor disable races, payload/privacy canaries, and catalogue-to-service source reconciliation cover each frozen mutation family.
- Adversarial evidence: Human/automated origin, actor present/absent/disabled, merchant/risk/platform identity, request/trace present/absent, success/rejection/rollback, note/plaintext/event-byte canaries, and exact idempotent replay.
- Status: PASS

## C46-TEN-001

- Lens: TENANT_ISOLATION
- Invariant: Every risk-operation event derives merchant, stream, source resource, and actor from authenticated transaction-owned context; copied review, label, incident, profile, policy, rule, exception, dispute, webhook, signal, scenario, event, or idempotency identity reveals no foreign existence and creates no cross-tenant lineage.
- Failure sequence: A bare join or caller field substitutes tenant, a platform operator becomes a merchant actor, or an equal UUID-shaped foreign child row enters the event payload.
- Implementation: tenant-qualified locked repositories, composite source/event constraints, actor-derived merchant scope, principal-bound idempotency, and strict separation between risk-operator and platform-operator contexts.
- Proof: `RiskOperationsEventTenantIsolationTests`, two-merchant equal-ID fixtures, direct PostgreSQL composite-key tests, and cross-authority route tests assert not-found/non-disclosure plus zero mutation.
- Adversarial evidence: Every foreign resource family, copied cursor/key, same UUID under two tenants, wrong merchant/risk/platform credential, disabled merchant/operator, record-key mismatch, and direct invalid insert.
- Status: PASS

## C46-AUTH-001

- Lens: API_PRODUCT
- Invariant: Existing closed merchant/risk roles and dual-control rules remain authoritative for each operational mutation; event insertion grants no new permission, completed idempotent replay revalidates current authority where required, and platform data authority cannot invoke merchant risk operations.
- Failure sequence: An analyst activates a rule, a disabled manager receives a stored mutation success and starts new work, creator approves their own candidate, merchant credentials invoke operator mutation, or platform credentials cross into merchant APIs.
- Implementation: event writers run only after existing controller/service authorization and live actor/credential locking; no event-specific public mutation endpoint is added.
- Proof: `RiskOperationsEventAuthorizationTests`, retained role-matrix regressions, forced disable-after-authentication PostgreSQL tests, dual-control tests, and route inventory cover viewer/analyst/manager/runner/merchant/platform partitions.
- Adversarial evidence: Every role, enabled/disabled credential, creator/approver same/different, exact/conflicting replay, foreign tenant, bootstrap/platform credential, stale version, and event writer failure.
- Status: PASS

## C46-CON-001

- Lens: TRANSACTION_CONCURRENCY
- Invariant: Existing actor-first and business-resource lock orders, versions, leases, dual control, and idempotency remain intact; stream allocation follows the owned aggregate boundary, so concurrent operational transitions produce one legal source state and matching gap-free event sequence.
- Failure sequence: Writer integration inverts actor/resource order, two label/control/review/dispute/webhook/scenario winners emit incompatible versions, a stale worker emits after takeover, or a losing transition leaves event/audit evidence.
- Implementation: producer calls participate in existing transactions after live actor/idempotency/resource locks and before commit; Chapter 44 stream locking follows each domain's frozen global order.
- Proof: `RiskOperationsEventPostgreSqlConcurrencyTests` extend forced actor-disable, dual-control, label supersession, review resolution, dispute submit/expire, webhook disable/redelivery, and scenario lease orderings with exact event/audit/source postconditions.
- Adversarial evidence: Both conflict orderings, exact/different key, same/different actor, current/stale version/lease/generation, before/exact/after deadline or expiry, rollback, and retry after terminal state.
- Status: PASS

## C46-DATA-001

- Lens: DATA_ML_LINEAGE
- Invariant: Operational events preserve point-in-time source versions, server-owned traffic origin, label availability and supersession, feature/policy/rule/control evidence, actor/request lineage where real, and synthetic/network provenance without leaking future outcomes into decision-time or simulation inputs.
- Failure sequence: Label occurrence substitutes availability, superseded and contradictory labels double-count, current control replaces historical evaluation, synthetic outcome becomes network settlement, or scenario/run report loses run ownership.
- Implementation: typed mappers read immutable source rows and their availability/version/origin fields inside the source transaction; unavailable lineage remains absent with explicit schema semantics.
- Proof: `RiskOperationsEventLineageTests`, before/exact/after label and evidence availability, contradictory/superseded partitions, mixed-control reconstruction, live/synthetic separation, run-owned scenario report reconciliation, and golden bytes.
- Adversarial evidence: Before/at/after availability, labeled/unlabeled/contradictory/superseded, old/current mixed controls, trusted synthetic versus merchant API, simulated versus network dispute outcome, missing optional actor, and restart.
- Status: PASS

## C46-SEC-001

- Lens: SECURITY_PRIVACY
- Invariant: Risk-operation payloads and fixtures expose only closed safe evidence and references; protected note plaintext, PAN/CVV, PII, raw/HMAC identities, credentials, webhook secrets/tokens/signatures/payloads/URLs, scenario credentials, idempotency material, unrestricted metadata, and exception text never enter event bytes, logs, metrics, audit, API, UI, or reports.
- Failure sequence: A note or broad entity serializer enters payload, webhook recovery copies callback bytes, dispute/scenario evidence includes a credential, or a validation/database error logs attacker-controlled content.
- Implementation: typed per-event payload records, reference-only protected evidence, safe bounded enums/reasons, and existing encryption/hash boundaries; no arbitrary map or entity serialization.
- Proof: `RiskOperationsEventPrivacyTests`, prohibited-value canaries across exact bytes/database/log/metric/audit/fixture/report sinks, source scans, and safe-error tests.
- Adversarial evidence: Note, PAN, CVV, email, phone, IP, device/session HMAC, API/operator/runner key, webhook URL/secret/token/signature/payload, idempotency key, exception text, Unicode/control input, and maximum sizes.
- Status: PASS

## C46-CAT-001

- Lens: API_PRODUCT
- Invariant: The frozen risk-operations catalogue assigns exactly one producer owner, stream root, schema version, actor/audit disposition, and consumer or lineage purpose to every event below; existing APIs remain backward compatible and no generic event-write or arbitrary payload endpoint exists.
- Failure sequence: Payment and risk chapters both own label emission, audit-only actions become noisy domain events, one event name covers different meanings, or an unknown type/version is written through a string API.
- Implementation: Chapter 44 catalogue registrations, typed producer adapters, and the frozen ownership/audit tables below; controllers expose no event-specific route.
- Proof: `RiskOperationsEventCatalogueTests`, mutation-to-event-to-audit traceability, schema/golden inventory, duplicate-owner scan, existing API regression, and route/documentation reconciliation.
- Adversarial evidence: Known/unknown type/version, duplicate owner, automated/human action, additive versus semantic change, missing consumer purpose, unsupported writer version, unknown JSON field, and direct event route attempt.
- Status: PASS

## C46-OBS-001

- Lens: OBSERVABILITY
- Invariant: Closed signals distinguish committed producer family/type/version, actor class where safe, outcome, and event-write failure after transaction truth without tenant, resource, event, actor ID, request, trace, payload, digest, note, credential, URL, or exception text as tags or raw errors.
- Failure sequence: Rolled-back control action emits success, event type becomes attacker-controlled cardinality, note/webhook content enters logs, or a missing event silently accompanies successful source mutation.
- Implementation: transaction-aware `RiskOperationsEventMetrics`, catalogue-derived closed dimensions, safe structured logs, Chapter 44 backlog/readiness, and no duplicate operational success metric before commit.
- Proof: `RiskOperationsEventObservabilityTests`, rollback-safe counts, cardinality and privacy canaries, missing-schema readiness, and every producer family/outcome partition.
- Adversarial evidence: Commit/rollback, human/automated, each event family, schema/writer failure, sensitive input, dependency error, high-volume unique resource IDs, producer enabled/disabled, and repeated replay.
- Status: PASS

## C46-OPS-001

- Lens: OPERATIONS_RESILIENCE
- Invariant: Risk producer enablement is additive and fail-closed against Chapter 44 catalogue readiness, retained V64 facts are never backfilled as events, mixed versions do not emit unsupported schemas, pending events survive disable/restart, and Chapter 46 claims no Kafka, inbox, projection, rebuild, or offline-dataset behavior.
- Failure sequence: Upgrade fabricates historical operational events, old consumers receive unsupported versions, disabling a producer deletes pending work, broker outage rolls back source operations, or mock consumption is presented as product analytics proof.
- Implementation: producer-version readiness and schema-neutral integration unless the matrix reopens before a capability-named migration; Kafka remains outside transactions and Chapters 47–51 retain downstream ownership.
- Proof: `RiskOperationsEventDeploymentPostgreSqlTests`, fresh/retained schema reconciliation, mixed-version enable/disable/restart, Kafka-unavailable non-interference, component scans, and source-count checks around replay attempts.
- Adversarial evidence: Populated V64 domains, producer off/on/off, old/new application order, unsupported catalogue, broker absent, pending event, repeated startup, direct SQL/demo shortcut, and event replay.
- Status: PASS

## Frozen Risk-Operations Catalogue

| Stream | Event types | Authoritative source | Producer owner |
|---|---|---|---|
| PaymentIntent | `manual_review.opened`, `manual_review.assigned`, `manual_review.resolved` | ManualReviewCase lifecycle | Chapter 46 |
| PaymentIntent | `fraud_label.recorded`, `fraud_label.superseded` | immutable label and supersession evidence | Chapter 46 |
| RiskIncident | `risk_incident.opened`, `risk_incident.resolved` | incident lifecycle | Chapter 46 |
| RiskControl | `merchant_risk_profile.activated`, `merchant_risk_profile.rolled_back`, `risk_policy.activated`, `risk_policy.rolled_back` | governed profile/policy lifecycle | Chapter 46 |
| RiskControl | `authored_rule.activated`, `authored_rule.rolled_back`, `authored_rule.disabled`, `safety_exception.activated`, `safety_exception.expired`, `safety_exception.disabled` | dual-controlled rule/exception lifecycle | Chapter 46 |
| Dispute | `dispute_workflow.opened`, `dispute_evidence.submitted`, `dispute_outcome.recorded`, `dispute_workflow.expired` | dispute workflow/evidence/outcome | Chapter 46 |
| WebhookEndpoint | `webhook_endpoint.verified`, `webhook_endpoint.disabled`, `webhook_endpoint.secret_rotated`, `webhook_delivery.exhausted`, `webhook_redelivery.requested`, `webhook_redelivery.succeeded` | endpoint/delivery/redelivery lifecycle | Chapter 46 |
| PaymentIntent | `post_authorization_signal.recorded` | immutable post-authorization signal | Chapter 46 |
| DeveloperScenario | `developer_scenario.created`, `developer_scenario.completed`, `developer_scenario.failed` | trusted scenario run lifecycle | Chapter 46 |

## Frozen Audit Reconciliation

| Mutation family | Human audit disposition | Domain-event disposition |
|---|---|---|
| Review assignment/resolution and label record/supersede | required with live risk actor, role, request, source, outcome | committed semantic fact |
| Profile/policy/rule/exception activation, rollback, disable, or expiry | required for human privileged action; automated expiry records system provenance without fictional actor | committed semantic fact |
| Dispute evidence submission/outcome operation | required for human privileged action; trusted synthetic outcome retains its synthetic attestation | committed semantic fact |
| Webhook verify/disable/rotate/redelivery request | required for initiating merchant or risk actor | committed semantic fact |
| Delivery exhaustion/redelivery completion | no new human audit for automated transport result; initiating request remains auditable | committed operational fact only where catalogue purpose exists |
| Incident and post-authorization automated facts | no fictional human actor; retain source/system provenance | committed semantic fact |
| Trusted scenario create/terminal mutation | authenticated runner evidence and existing audit policy reconciled; no merchant actor invention | committed synthetic operational fact |

## Frozen Hostile-Family Coverage

| Phase 5 family | Chapter 46 obligations |
|---|---|
| Atomic creation | C46-FRD-001 and C46-CON-001 |
| Stream ordering | C46-CON-001 |
| Canonical protocol | C46-DATA-001, C46-SEC-001, and C46-CAT-001 |
| Publication | C46-OPS-001 preserves pending truth; runtime proof remains Chapter 47 |
| Consumption | C46-FRD-001 proves no command re-entry; runtime proof remains Chapter 48 |
| Tenant isolation and authorization | C46-TEN-001 and C46-AUTH-001 |
| Projection correctness | C46-DATA-001 freezes availability/origin inputs; derived proof remains Chapter 49 |
| Rebuild | C46-FRD-001 and C46-DATA-001 preserve immutable inputs/non-impact; runtime proof remains Chapter 50 |
| Security/privacy | C46-AUD-001, C46-SEC-001, and C46-OBS-001 |
| Migration | C46-OPS-001 |
| Operations | C46-OBS-001 and C46-OPS-001 |
| Documentation/demo | C46-CAT-001 and the planned public risk-operations reconciliation |
