# Chapter 22 Typed Feature Lineage Demo — 2026-07-11

## Boundary

The demo ran `scripts/demo-feature-lineage.py` against the local
Spring Boot API and PostgreSQL 16 after upgrading the retained local schema
from V13 through V19. Product objects were created and read through public APIs;
the script used one read-only PostgreSQL query to reconcile normalized typed
rows that are intentionally not exposed as a merchant API.

## Public flow

```text
merchant -> customer -> tokenized payment method -> manual PaymentIntent
  -> confirm -> PaymentAttempt -> FraudDecision detail -> exact replay
```

Observed bounded output:

```text
Chapter 22 typed feature-lineage demo passed.
payment_attempt=e1457db9-ec0c-43c5-8a9e-04dc4f629a8f status=AUTHORIZED
fraud_decision=f59fdeb2-1887-4daa-9bb0-7b4619c2cf71 action=ALLOW
typed_features=7 point_in_time=true replay=exact prohibited_surfaces=absent
```

The one-time merchant API key was used by the process but never printed or
written to a report.

## Reconciliation

- New confirm returned `201` with the existing PaymentAttempt response shape.
- Exact same-key replay returned byte-identical response content.
- Fraud behavior remained `ALLOW`, `rules_stub_v1`, `RULES_NOT_ENABLED`.
- The processor produced one approved Authorization for the manual-capture
  attempt; no Chapter 23 rule action was activated.
- Decision detail exposed exactly seven lexical safe snapshot keys.
- PostgreSQL contained exactly seven matching typed V1 rows.
- Every row satisfied `observed_at <= decision_started_at`.
- Definition type, typed value, snapshot value, tenant, attempt, version, and
  traffic origin reconciled.
- Decision detail contained no BIN, last4, fingerprint, resource IDs,
  description, metadata, credentials, or idempotency material.

## Operational evidence

Startup applied V14 through V19 to the retained local V13 schema, then
Hibernate validation succeeded before the API accepted traffic. This local run
supplements the isolated fresh-schema and explicit V17-to-V19 PostgreSQL tests;
it does not replace them.

## Result

PASS for the Chapter 22 public demo boundary. Completion still depends on the
separate traceability, hostile-review, matrix, dossier, and readiness gates.
