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

## Event, Endpoint, And Delivery

Think of webhooks as three separate ideas.

`Event`
: The fact that something happened. Example: `payment_intent.captured`.

`WebhookEndpoint`
: The merchant-owned URL that wants to receive event notifications. Example: `https://merchant.example.com/webhooks/thaap`.

`WebhookDelivery`
: One attempt to deliver one event to one endpoint.

This distinction matters.

The event is the truth. The delivery is an attempt to tell another system about that truth.

If delivery fails, the event is still true. THAAP should retry delivery without undoing the payment state.

## Why We Persist Webhook Deliveries

First principle:

```text
Do not let another company's broken endpoint break our payment transaction.
```

When a payment state changes, THAAP should persist its own state first:

```text
payment state changes
  -> write payment state
  -> write event/outbox row
  -> write webhook delivery row
  -> commit transaction
```

The actual HTTP callback happens later:

```text
worker reads pending delivery
  -> signs payload
  -> POSTs to merchant URL
  -> records success or failure
  -> schedules retry if needed
```

This makes payment state durable even when the merchant endpoint is down, slow, or buggy.

## How THAAP Uses Webhooks Today

The current implementation has the first webhook foundation:

- merchants can create webhook endpoints
- THAAP creates webhook delivery records for merchant-owned events
- merchants can list their own webhook deliveries
- webhook data is scoped by `merchant_id`

Current implemented endpoints:

- `POST /webhook_endpoints`
- `GET /webhook_deliveries`

Current implementation records delivery rows. It does not yet send real HTTP callbacks to merchant URLs.

That is intentional for this stage. Durable delivery state comes before network delivery, retries, and signing.

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

THAAP should eventually support:

- signing secrets per webhook endpoint
- signed payloads
- timestamped signatures
- replay protection
- no raw PAN or CVV in payloads
- retry limits
- delivery attempt history
- response status capture
- safe response-body truncation

The merchant should verify signatures before trusting the webhook body.

## What Remains To Build

The current foundation is deliberately incomplete.

Still needed:

- domain outbox events
- webhook event payload builder
- signing-secret simulation
- webhook sender worker
- retry schedule
- attempt count
- delivery timeout handling
- success/failure status transitions
- endpoint event filtering
- tests for failed delivery and retry
- documentation for merchant-side signature verification

## Mental Model To Keep

Do not think of webhooks as "just HTTP callbacks."

Think of them as:

```text
durable asynchronous integration between our payment state machine and the merchant's business state machine
```

That is why webhooks belong in V1. They teach a real payment-platform integration primitive, not a decorative feature.
