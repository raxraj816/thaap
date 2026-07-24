# Durable Incidents And Ephemeral Rate Limits

## Mental model

A rate limit answers a short-lived operational question: “May this request
consume capacity now?” A fraud incident answers a durable product question:
“Which committed payment/control facts justify an operator investigation?”
They can observe related behavior, but they are not interchangeable truth.

## Why the boundary matters

Redis is fast and atomic for bounded counters, but keys may expire, be evicted,
or become ambiguous during a timeout. PostgreSQL transactions, tenant-qualified
foreign keys, and append-only history are the appropriate boundary for payment,
decision, evidence, incident, audit, and mitigation facts.

Therefore:

- a definite limit exceedance returns 429 with bounded retry guidance;
- Redis ambiguity returns 503 and creates no payment attempt or fraud decision;
- Redis keys/members use purpose-separated current/previous HMAC identities;
- only persisted attempts, decisions, authorizations, contexts, and controls can
  open or update a suspected card-testing incident;
- RISK_MANAGER mitigation appends operator truth and never silently mutates
  merchant fraud policy.

## Failure review

The dangerous shortcuts are treating an outage as an exceedance, treating an
exceedance as fraud, creating incidents from counters, resetting limits during
HMAC rotation, double-consuming retries, or storing raw IP/credential/request
keys. THAAP proves the alternatives with exact Lua bytes, real Redis concurrency
and TTL tests, PostgreSQL detector serialization, strict 429/503 contracts, and
read-only live reconciliation.
