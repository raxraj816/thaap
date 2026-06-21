# Audit Logs

## Mental Model

An audit log is the durable "who did what, to which object, when, and with what
safe context" record. It is different from domain state. Domain tables answer
"what is true now?" Audit logs answer "how did we get here?"

In payments and fraud, this matters because important decisions must be
explainable after the fact. A merchant, support engineer, analyst, or regulator
may need to reconstruct a payment confirmation without trusting transient logs.

## Chapter 13 THAAP Shape

Chapter 13 adds `audit_logs` for successful confirm flows:

- `payment_intent.confirmed`
- `fraud_decision.completed`
- `authorization.approved`
- `authorization.declined`
- `authorization.requires_action`

The actor is `MERCHANT_API_KEY` with `actor_id = apiKeyPrefix` because the
tenant owner and the credential used for the action are different questions.
`merchant_id` answers "which merchant owns this event?" `actor_id` answers
"which API key acted?" The raw key is never stored. The request ID comes from
`X-Request-Id` through `RequestIdFilter`. Trace ID remains nullable until
OpenTelemetry tracing exists.

Confirm-specific audit event payloads are built by `ConfirmAuditEventMapper`,
not directly inside `PaymentIntentController`. That boundary keeps HTTP
orchestration separate from evidence-shape construction and gives audit event
ordering, action names, aggregate identity, and before/after payloads a focused
test surface.

## Payment And Fraud Invariants

- Audit records commit in the same idempotency transaction as confirm side
  effects.
- Idempotent replay returns the stored response and does not create duplicate
  audit records.
- Failed confirm paths do not leave partial audit stories.
- Audit payloads include safe state context only: IDs, statuses, amounts,
  currency, fraud action/reasons, and processor outcome metadata.
- Audit payloads must not include raw PAN, CVV, raw PII, API keys, card
  fingerprints, bank account/routing data, SSN/tax/DOB fields, merchant
  secrets, idempotency hashes, processor token references, or stored response
  bodies.
- Audit redaction belongs inside `AuditLogService`, not only in each caller,
  because audit records are permanent evidence and future call sites will be
  easy to get wrong.

## Transaction Policy

THAAP Chapter 13 deliberately fails closed for confirm audit persistence. The
three confirm audit records are part of the same idempotency transaction as the
PaymentAttempt, FraudDecision, FeatureSnapshot, Authorization, PaymentIntent
state change, webhook delivery creation, and stored idempotency response. If the
local audit store fails, confirm rolls back and the idempotency reservation is
released.

This is the right v1 simulator tradeoff because the product wedge is
explainable, replayable payment-risk truth. A successful local confirm without
audit evidence would create a weaker story for analysts and future model/rules
debugging.

Before THAAP calls a real external processor, this boundary must be revisited.
An external authorization can create irreversible processor truth outside the
database transaction. At that point, THAAP should move to a stronger
outbox/reconciliation design instead of relying on local rollback alone.

## Design Review Narrative

Chapter 13 makes the first local payment-risk demo defensible. A reviewer can
see payment state, fraud lineage, processor truth, webhook delivery records, and
audit records for the same confirm attempt. That is still not ledger truth, but
it is now explainable operational truth.
