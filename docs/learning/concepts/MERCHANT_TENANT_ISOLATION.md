# Merchant Tenant Isolation From First Principles

This note explains multi-tenancy and merchant isolation in THAAP.

## The Core Idea

Multi-tenancy means many customers share one application while their data remains isolated.

In THAAP, the V1 tenant is `Merchant`.

The core rule:

```text
Merchant A must never read, mutate, replay, deliver, train from, or operate on Merchant B's data.
```

## The Problem It Solves

Payment platforms serve many merchants.

If tenant isolation is weak, one merchant could accidentally or maliciously access another merchant's:

- customers
- payment intents
- payment methods
- refunds
- webhook deliveries
- fraud decisions
- rules
- reviews
- future analytics and model data

That is a severe security and trust failure.

## Why It Matters In Payments And Fraud

Payment and fraud systems contain sensitive business and customer signals.

Fraud ML adds another risk: data leakage. A model or feature pipeline can accidentally blend tenant-specific behavior across merchants.

So tenant isolation is not only an API concern. It affects:

- storage
- events
- webhooks
- features
- model predictions
- training datasets
- graph records
- analytics projections

## How THAAP Uses It Today

THAAP resolves `MerchantContext` from `X-Api-Key`.

Merchant-facing APIs do not trust request-body `merchantId`.

Merchant-owned tables include `merchant_id` directly where practical.

Repository lookups use tenant-scoped access patterns such as:

```text
findByIdAndMerchantId(id, merchantId)
```

Idempotency is scoped by `(merchant_id, key)`.

## Design Tradeoffs

THAAP uses a shared PostgreSQL database and shared schema for V1.

That keeps local development simple and avoids operational overhead while the domain model is still changing.

Separate database or schema per merchant could improve isolation but would make migrations, local development, analytics, and future ML pipelines more complex.

PostgreSQL row-level security is deferred but remains a later defense-in-depth option.

## Failure Modes

Tenant isolation can fail if:

- a repository loads by object ID alone
- a controller trusts request-body `merchantId`
- a webhook query forgets merchant scope
- an analytics query omits merchant filters
- a feature pipeline trains on tenant-specific signals without marking scope
- admin APIs are exposed without protection
- API keys are stored or logged unsafely

## Tests And Proof

Current HTTP-level tests prove:

- Merchant A cannot fetch Merchant B's payment intent
- Merchant A cannot create child records for Merchant B's customer
- Merchant A cannot capture, cancel, or refund Merchant B's payment
- idempotency keys are merchant-scoped
- webhook deliveries are merchant-scoped
- fraud/rule/review lists are merchant-scoped

## What Remains To Build

- Store API keys as hashed secrets with non-secret prefixes.
- Protect merchant admin setup APIs.
- Add Flyway constraints and indexes for tenant-scoped tables.
- Add PostgreSQL integration tests.
- Consider RLS after application-level isolation is stable.
- Carry `merchant_id` through all future ML, analytics, graph, and event records.
