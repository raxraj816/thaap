# API Credential Lifecycle From First Principles

## What The Concept Means

An API credential lifecycle defines how credentials are created, revealed,
stored, used, rotated, disabled, and audited.

In THAAP Day 5, the lifecycle is intentionally small:

- create a local test merchant API key
- require an admin bootstrap token before merchant creation
- reveal the raw key once in the admin creation response
- store only a non-secret prefix and SHA-256 hash
- authenticate merchant-facing requests by hashing `X-Api-Key`
- never persist the raw key

## What Problem It Solves

Merchant API keys are bearer credentials. Anyone who has the raw value can act
as that merchant.

If the database stores raw API keys, then database read access becomes merchant
account access. Prefix/hash storage lowers that risk: operators can identify a
key by prefix, and the API can authenticate by hash, but a database dump does
not directly reveal usable merchant credentials.

## Why It Matters In Payment And Fraud Systems

Payment APIs sit on high-trust boundaries:

- Merchants use credentials to create customers, payment methods, intents,
  refunds, and webhook endpoints.
- Fraud and analyst workflows depend on tenant-clean data.
- Support teams need enough key metadata to debug authentication issues without
  seeing raw secrets.

Credential leakage can become payment abuse, false fraud signals, tenant data
leakage, or a support incident.

## How THAAP Implements It

Current implementation:

- `AdminMerchantController` generates local test keys with the
  `thaap_test_` prefix after validating `X-Admin-Bootstrap-Token`.
- `Merchant` derives `apiKeyPrefix` and `apiKeyHash` from the raw key.
- `MerchantContextArgumentResolver` hashes incoming `X-Api-Key` and looks up an
  active merchant by `apiKeyHash`.
- `MerchantAdminContractTests` proves missing bootstrap tokens are rejected,
  the raw key is returned once, only prefix/hash are stored, and the returned
  key can authenticate follow-on merchant-facing requests.

Related boundary:

- Webhook signing secrets follow the same prefix/hash storage pattern, but real
  signing and rotation behavior will come later.

## Design Tradeoffs

Chosen now:

- One active local test API key per merchant creation.
- SHA-256 hashing without a per-key salt because the local simulator keys are
  high-entropy UUID-based secrets.
- Prefixes are non-secret lookup/debug metadata.
- Low-entropy sensitive values such as customer PII, card fingerprints, and
  idempotency request hashes use separate keyed HMAC domains instead.

Deferred:

- Key rotation.
- Key revocation and disabled-key audit events.
- Multiple keys per merchant.
- Full admin identity, RBAC, and audit workflow for merchant creation.
- Rate limits and anomaly detection for credential abuse.

## Failure Modes

- Logging raw `X-Api-Key` would leak credentials outside the database.
- Exposing merchant creation without a bootstrap token would let anyone mint a
  live merchant credential.
- Returning raw keys from later read/list endpoints would violate one-time
  reveal.
- Storing only a hash without a prefix would make safe support/debug flows
  harder.
- Accepting merchant IDs from request bodies would let a valid key target
  another tenant.

## Tests And Proof

Important tests:

- Merchant creation returns `apiKey` and `apiKeyPrefix` once.
- Merchant creation without `X-Admin-Bootstrap-Token` is rejected.
- Stored merchant rows contain `api_key_prefix` and `api_key_hash`, not raw
  `api_key`.
- The returned raw key authenticates merchant-facing requests.
- Missing or invalid keys return the standard unauthorized error shape.
- Tenant isolation tests prove credentials cannot access another merchant's
  rows.

## What Remains To Build

Future lifecycle work should add:

- explicit API key table
- rotation and revocation
- last-used metadata
- audit events
- admin authentication and authorization
- rate limiting and suspicious credential-use signals
