# ADR-0008: Use Merchant As The V1 Tenant Boundary

Date: 2026-05-31
Status: Accepted

## Context

THAAP is a payment and fraud platform simulator. Payment, fraud, review, webhook, ledger, analytics, graph, and ML data are naturally owned by merchants. The platform needs multi-tenant behavior early so later payment and ML work does not accidentally bake in single-tenant assumptions.

The first implementation is a modular monolith backed by PostgreSQL. Separate databases or schemas per tenant would add operational complexity before the core domain lifecycle is stable.

## Decision

`Merchant` is the V1 tenant root.

THAAP will use a shared PostgreSQL database and shared schema. Merchant-owned rows use `merchant_id` as the tenant discriminator. Merchant-facing runtime APIs resolve tenant context from API credentials, initially `X-Api-Key`, and must not trust client-supplied `merchantId` for authorization.

Application code must enforce tenant isolation with tenant-scoped lookups such as `(id, merchant_id)`. Cross-merchant isolation tests are required for every merchant-facing endpoint family.

PostgreSQL row-level security is deferred for V1, but the data model should remain compatible with adding it later.

## Consequences

- Every payment, fraud, review, webhook, ledger, event, feature, analytics, graph, and ML path must carry or derive `merchant_id`.
- Idempotency keys are unique per `(merchant_id, key)`, not globally.
- API keys must move from local test-secret storage to hashed secrets with non-secret prefixes before any non-local deployment.
- Global ML signals are allowed only when explicitly designed as global/anonymized features; tenant-specific metrics and evaluations must remain segmented by merchant.
- Future organization-level accounts can be added above merchants without changing the V1 merchant-scoped payment API contract.
