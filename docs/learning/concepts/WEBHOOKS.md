# Webhooks From First Principles

This note explains webhooks as a payment-platform concept. It is a learning note, not the implementation source of truth. Implementation decisions still live in `docs/IMPLEMENTATION_MASTER_PLAN.md` and `docs/architecture/`.

## The Core Idea

A webhook is a way for one system to notify another system that something happened.

Without webhooks, a merchant has to keep asking THAAP whether payment state changed:

```text
Merchant -> THAAP: did payment pi_123 capture yet?
Merchant -> THAAP: did payment pi_123 capture yet?
Merchant -> THAAP: did payment pi_123 capture yet?
```

With webhooks, the merchant gives THAAP a callback URL, and THAAP calls that URL when an important event happens:

```text
THAAP -> Merchant: payment_intent.captured happened for pi_123
```

The short version:

```text
Polling means "I will keep asking you."
Webhooks mean "Tell me when it happens."
```

## Why Payment Systems Need Webhooks

Payment systems are not simple request/response CRUD systems. They are state machines.

A payment can change state after the original API call:

- a payment is authorized
- a payment is captured
- a payment is canceled
- a refund is created
- a chargeback arrives later
- a fraud review opens
- a fraud review is resolved
- a webhook delivery succeeds or fails

The merchant's own system needs to react to those state changes.

Examples:

```text
payment_intent.captured
  -> merchant marks order as paid
  -> warehouse can ship
```

```text
refund.created
  -> merchant updates support tooling
  -> customer receives refund status
```

```text
chargeback.created
  -> merchant opens dispute workflow
  -> fraud team reviews the transaction
```

Webhooks solve the problem of keeping the merchant's system synchronized with the payment platform without constant polling.

## Event, Endpoint, Delivery, And Attempt

Think of webhooks as four separate ideas.

`Event`
: The immutable fact and exact payload saying something happened. Example:
  `payment_intent.captured`.

`WebhookEndpoint`
: The merchant-owned URL that wants to receive event notifications. Example: `https://merchant.example.com/webhooks/thaap`.

`WebhookDelivery`
: The routing and aggregate state for one event going to one endpoint.

`WebhookDeliveryAttempt`
: Append-only evidence for one claimed transmission try for one delivery. A
  target-policy or signing failure can stop it before HTTP.

This distinction matters.

The event is the truth. The delivery routes that truth. Attempts record what
happened each time THAAP tried to communicate it.

If delivery fails, the event is still true. THAAP should retry delivery without undoing the payment state.

Retries also create an unavoidable boundary:

```text
THAAP sends callback
  -> merchant receives callback
  -> THAAP crashes before recording success
  -> THAAP must retry after lease recovery
```

The merchant can receive the same event twice. This is why webhook delivery is
at least once, not exactly once. The merchant first verifies the signature over
the raw body, then uses the stable `id` inside that authenticated body as the
deduplication key. `THAAP-Event-Id` must match it, but the header alone is not
signed and must not drive deduplication.

## Why We Persist Webhook Deliveries

First principle:

```text
Do not let another company's broken endpoint break our payment transaction.
```

When a payment state changes, THAAP should persist its own state first:

```text
payment state changes
  -> write payment state
  -> write immutable webhook event envelope
  -> write webhook delivery row
  -> commit transaction
```

The actual HTTP callback happens later:

```text
worker reads pending delivery
  -> claims delivery and numbered attempt
  -> signs payload
  -> POSTs to merchant URL
  -> records append-only attempt success or failure
  -> schedules retry if needed
```

This makes payment state durable even when the merchant endpoint is down, slow, or buggy.

The Chapter 19 webhook event envelope is not the general Kafka/outbox record.
Phase 5 introduces that broader cross-consumer event boundary.

## How THAAP Uses Webhooks Today

Chapter 19 implements the complete bounded V1 delivery loop:

- endpoint creation and rotation reveal a signing key once
- raw signing keys are AES-256-GCM encrypted under a separate configured key
- key-encryption-key rollover keeps versioned decrypt keys, fails closed on
  unreadable active versions, and lazily re-encrypts old ciphertext
- event subscriptions select only relevant active endpoints
- payment mutations persist one immutable event and endpoint deliveries
- a PostgreSQL lease worker claims each numbered attempt immediately before
  dispatch without holding locks over HTTP
- exact payload bytes are timestamped and signed with HMAC-SHA256
- signature time is read after dispatch preparation so database lock wait does
  not consume the merchant freshness tolerance
- payload bytes are produced by a JSON serializer, not manual string
  concatenation, so future event values cannot break the merchant-verifiable
  body
- failures use deterministic retries and append attempt evidence
- dispatch start is persisted with lease renewal immediately before HTTP
- pre-dispatch state records known-no-send evidence; in-flight, partial-write,
  and post-dispatch expiry state remains unknown until it can be finalized
- an expired in-flight row remains recoverable after endpoint disable and
  exhausts without creating a replacement attempt
- target policy pins approved addresses and explicitly rejects ULA, CGNAT,
  reserved, private, and other unsafe callback targets
- DNS uses a bounded resolver pool, bounded queue, and application deadline
- unknown host, empty DNS answers, timeout, saturation, interruption, and
  resolver failures retry as known-no-send; only explicit target-policy denial
  is terminal
- a wall-clock deadline bounds TLS, write, and response parsing
- malformed numeric statuses fail as transport errors, while bounded
  informational responses are consumed and the complete bounded final response
  head is received before classifying the acknowledgement
- the 64 KiB request limit is enforced at creation, execution, and transport;
  one cumulative exact 32 KiB budget covers every response-head byte
- endpoint disable is monotonic: exclusive rotation/disable locks plus shared
  event-routing locks prevent reactivation and post-disable queued deliveries
- endpoint disable prevents new claims and retries through a bulk queued-state
  update plus terminal recovery of any expired in-flight attempt
- metrics include recovered-unknown and terminal paths; the due gauge reports
  claimable backlog rather than recently processed throughput
- disable metrics use actual affected-row counts so a concurrent claim cannot
  create premature or duplicate terminal telemetry
- merchant reads are cursor-bounded and tenant scoped

V9 treats migration evidence conservatively. Released V8 had no executor or
attempt table, so only pristine zero-attempt queued rows can be upgraded.
Aggregate retry/success/failure claims fail before V9 schema creation rather
than becoming delivery history without attempts. Queued work for an inactive
legacy endpoint becomes terminal `ENDPOINT_DISABLED`.

Current implemented endpoints:

- `POST /webhook_endpoints`
- `POST /webhook_endpoints/{id}/rotate_secret`
- `POST /webhook_endpoints/{id}/disable`
- `GET /webhook_deliveries`
- `GET /webhook_deliveries/{id}`
- `GET /webhook_deliveries/{id}/attempts`

## Multi-Tenant Requirement

Webhooks are merchant-owned data.

Merchant A must never see or receive Merchant B's webhook deliveries.

That means:

- `WebhookEndpoint` rows carry `merchant_id`
- `WebhookDelivery` rows carry `merchant_id`
- delivery creation selects endpoints for the current merchant only
- delivery listing is scoped by `MerchantContext`

This is why our tenant isolation tests include webhook delivery visibility.

## Security Requirements

Real webhook systems need security controls because they call merchant-owned URLs over the network.

Chapter 19 supports:

- signing secrets per webhook endpoint
- encrypted raw signing keys plus safe prefix/hash metadata
- exact-byte timestamped HMAC signatures
- stable signed-payload event IDs for merchant deduplication, with
  header/body agreement validation
- strict payload allowlisting
- SSRF-safe outbound target policy
- retry limits
- delivery attempt history
- response status capture
- no response-body retention
- loopback HTTP is allowed only in local/test simulator mode; shared merchant
  delivery requires the non-local HTTPS and allowlist path

The merchant should verify signatures before trusting the webhook body.

A one-way signing-secret hash is useful for checking a presented secret, but it
cannot produce an HMAC with the original secret. Using the stored hash itself as
the signing key would let a database-only attacker forge webhooks. The implemented
boundary therefore encrypts the raw high-entropy signing key under a separately
configured key-encryption key.

## What Remains To Build

The bounded V1 is complete. Named later slices own merchant-triggered replay,
endpoint verification handshakes, overlapping signing-secret windows,
per-endpoint rate controls, managed egress, multi-region scaling, and the
general domain outbox/Kafka boundary. Those capabilities should extend stable
event identity and the at-least-once contract rather than replacing them.

## Mental Model To Keep

Do not think of webhooks as "just HTTP callbacks."

Think of them as:

```text
durable asynchronous integration between our payment state machine and the merchant's business state machine
```

That is why webhooks belong in V1. They teach a real payment-platform integration primitive, not a decorative feature.
