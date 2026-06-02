# Spaced Repetition Tracker

Use this to revisit important concepts after 1 day, 1 week, and 1 month. The
goal is durable recall, not one-time reading.

Status values:

- `new`
- `reviewed-1d`
- `reviewed-1w`
- `reviewed-1m`
- `needs-work`

| Concept | Source | Added | 1-Day Review | 1-Week Review | 1-Month Review | Status | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Modular monolith first | `docs/adr/0001-use-modular-monolith-first.md` | 2026-05-29 | 2026-05-30 | 2026-06-05 | 2026-06-29 | new | Explain why service splitting is deferred. |
| No LLM in real-time authorization | `docs/adr/0003-keep-llms-out-of-real-time-authorization.md` | 2026-05-29 | 2026-05-30 | 2026-06-05 | 2026-06-29 | new | Explain deterministic bounded fraud decisions. |
| Merchant tenant isolation | `docs/learning/concepts/MERCHANT_TENANT_ISOLATION.md` | 2026-05-31 | 2026-06-01 | 2026-06-07 | 2026-06-30 | new | Trace `X-Api-Key` to `MerchantContext`. |
| Idempotency | `docs/learning/concepts/IDEMPOTENCY.md` | 2026-05-31 | 2026-06-01 | 2026-06-07 | 2026-06-30 | new | Explain replay, conflict, and race hardening. |
| API error contracts and request IDs | `docs/learning/concepts/API_ERROR_CONTRACTS_AND_REQUEST_IDS.md` | 2026-06-01 | 2026-06-02 | 2026-06-08 | 2026-07-01 | new | Explain support/debug/fraud investigation value. |
| Database migrations and Flyway | `docs/learning/concepts/DATABASE_MIGRATIONS_AND_FLYWAY.md` | 2026-06-02 | 2026-06-03 | 2026-06-09 | 2026-07-02 | new | Explain why Hibernate validate replaces update. |
| Payment method tokenization | `docs/learning/concepts/PAYMENT_METHOD_TOKENIZATION.md` | 2026-05-31 | 2026-06-01 | 2026-06-07 | 2026-06-30 | new | Explain safe simulator metadata boundary. |
