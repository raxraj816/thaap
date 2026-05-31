# API Error Contracts And Request IDs

## Concept

An API error contract is the stable response shape clients can depend on when a
request fails. A request ID is a per-request correlation value returned to the
client and written into logs so a client-visible failure can be traced through
server-side diagnostics.

## Why It Matters In Payments And Fraud

Payment APIs sit in retry-heavy, integration-heavy systems. Merchants need to
know whether a request failed because their input was invalid, their credentials
were wrong, an object was missing, an idempotency key conflicted, or the
platform had an internal failure.

Fraud systems add another requirement: explainability and auditability. A fraud
decision or payment mutation may later be investigated by an analyst. Request
IDs make it possible to connect the merchant-facing API failure, application
logs, persisted records, and eventual support/audit trail.

## THAAP V1 Approach

- Accept `X-Request-Id` when provided and generate a UUID when missing.
- Return `X-Request-Id` on every response.
- Store the request ID on the servlet request for error handlers.
- Put the request ID into logging MDC as `requestId`.
- Use one JSON error shape across validation, authentication, missing object,
  conflict, and unexpected-error paths.
- Do not echo rejected request values in validation errors, because payment
  request bodies can contain sensitive card-adjacent data.

## Error Shape

```json
{
  "requestId": "req_demo_001",
  "timestamp": "2026-06-01T10:15:30Z",
  "status": 400,
  "error": "validation_failed",
  "message": "Request validation failed",
  "path": "/customers",
  "fieldErrors": [
    {
      "field": "email",
      "message": "must be a well-formed email address"
    }
  ]
}
```

## Trade-Offs

Keeping one compact error shape makes merchant integrations simpler, but it
means every new exception path must be deliberately mapped. The fallback 500
handler protects internals by returning a generic message while logging the real
exception server-side.
