# Chapter 14 Demo And Readiness Note: 2026-06-26

## 1. Goal

Chapter 14 exists to prove that the Chapter 13 create-to-confirm simulator flow
is reproducible from a clean local database before deeper Phase 2 money
movement work continues.

This note records the demo commands, sanitized response evidence, known gaps,
and next sprint risks.

## 2. Clean Local Setup

Exact commands run from the repository root:

```bash
docker compose -f infra/docker/compose.yml down --volumes
scripts/local-infra-up.sh
THAAP_ADMIN_BOOTSTRAP_TOKEN=change-me-local-admin-bootstrap-token \
  THAAP_HASH_PEPPER=change-me-local-sensitive-value-pepper \
  scripts/payments-api-run.sh
```

Observed startup evidence:

- PostgreSQL and Redis became healthy.
- Flyway validated and applied 5 migrations against a fresh PostgreSQL schema.
- `payments-api` started on port `8080`.

## 3. Demo Commands

Approved authorization path:

```bash
scripts/demo-confirm-flow.sh
```

Issuer decline path:

```bash
THAAP_DEMO_CARD=4999990000000002 scripts/demo-confirm-flow.sh
```

3DS-required path:

```bash
THAAP_DEMO_CARD=4999990000000008 scripts/demo-confirm-flow.sh
```

## 4. Sanitized Response Evidence

Approved card `4999990000000001`:

```json
{
  "paymentIntent": {
    "amount": 4200,
    "currency": "USD",
    "captureMethod": "AUTOMATIC",
    "status": "REQUIRES_CAPTURE"
  },
  "paymentAttempt": {
    "status": "AUTHORIZED",
    "attemptNumber": 1,
    "fraudDecisionId": "present",
    "processorAuthorizationId": "auth_sim_...",
    "threeDsStatus": null
  },
  "fraudDecision": {
    "riskScore": 0.0000,
    "riskLevel": "LOW",
    "action": "ALLOW",
    "modelVersion": null,
    "rulesetVersion": "rules_stub_v1",
    "reasonCodes": ["RULES_NOT_ENABLED"],
    "featureFreshnessStatus": "FRESH"
  },
  "featureSnapshot": {
    "featureVersion": "features_v1",
    "features": {
      "payment_amount": 4200,
      "payment_currency": "USD",
      "capture_method": "AUTOMATIC",
      "attempt_number": 1,
      "card_bin": "499999",
      "card_last4": "0001",
      "card_country": "US",
      "card_funding": "CREDIT"
    }
  }
}
```

Issuer decline card `4999990000000002`:

```json
{
  "paymentIntent": {
    "amount": 4200,
    "currency": "USD",
    "status": "REQUIRES_PAYMENT_METHOD"
  },
  "paymentAttempt": {
    "status": "DECLINED",
    "attemptNumber": 1,
    "fraudDecisionId": "present",
    "processorAuthorizationId": "auth_sim_...",
    "threeDsStatus": null
  },
  "fraudDecision": {
    "action": "ALLOW",
    "rulesetVersion": "rules_stub_v1",
    "reasonCodes": ["RULES_NOT_ENABLED"]
  }
}
```

3DS-required card `4999990000000008`:

```json
{
  "paymentIntent": {
    "amount": 4200,
    "currency": "USD",
    "status": "REQUIRES_ACTION"
  },
  "paymentAttempt": {
    "status": "REQUIRES_ACTION",
    "attemptNumber": 1,
    "fraudDecisionId": "present",
    "processorAuthorizationId": "auth_sim_...",
    "threeDsStatus": "REQUESTED"
  },
  "fraudDecision": {
    "action": "ALLOW",
    "rulesetVersion": "rules_stub_v1",
    "reasonCodes": ["RULES_NOT_ENABLED"]
  }
}
```

Sensitive data handling in the demo evidence:

- Raw API keys are not recorded.
- Raw card numbers are not recorded in response evidence.
- Raw buyer email and phone are not recorded.
- Processor authorization references are shortened to `auth_sim_...`.
- Internal UUIDs were omitted from the sanitized examples because the status,
  lineage, and state transition evidence are the useful product proof.

## 5. Known Product Gaps

- The demo script proves confirm, fraud lineage, processor authorization, and
  safe fetch APIs. It does not yet drive capture or ledger reads.
- Real fraud rules are not enabled; the current decision engine honestly
  records `RULES_NOT_ENABLED`.
- Manual review, rule governance, velocity counters, risk context, model
  serving, graph intelligence, disputes, and analyst dashboard workflows remain
  future slices.

## 6. Known Reliability Gaps

- Webhook delivery records exist, but delivery execution and retry workers are
  not implemented.
- Idempotency cleanup/expiration is modeled but no cleanup job exists.
- Local demo startup still depends on Docker Compose and a foreground API
  process; there is no one-command full demo runner yet.
- No outbox/Kafka boundary exists yet for event publishing.

## 7. Known Fraud/ML Lineage Gaps

- Feature snapshots are durable but intentionally small.
- Risk score is a deterministic stub score, not a calibrated model output.
- Model version is nullable because no model service exists.
- Fraud labels, delayed chargeback labels, review labels, and fraud taxonomy
  are not yet attached to outcomes.

## 8. Next Sprint Risks

- Capture and ledger work must not create a `CAPTURED` status without balanced
  ledger evidence.
- Refunds must not mutate money state without reversal ledger entries.
- Cancel/void must distinguish uncaptured authorization reversal from refund.
- Webhook retry work must avoid duplicate delivery truth under retries.
- Future rules/model work must preserve the existing FraudDecision and
  FeatureSnapshot lineage contract instead of rewriting decisions.

## 9. Chapter 14 Completion Assessment

Chapter 14 is complete for the Chapter 13 confirm demo/readiness goal:

- The clean local database demo was run.
- Approved, issuer-decline, and 3DS-required deterministic card paths were
  proven.
- Sanitized response evidence was recorded.
- Product, reliability, fraud/ML, and next sprint risks were listed.
- README, service README, master plan, daily notes, deep dive, and weekly review
  have been updated by the Chapter 15 work that followed.

Follow-up correction:

- During verification, the Chapter 14 "next detailed chapter plan for Phase 2"
  checklist item was found to be only partially satisfied. The repo had Phase 2
  direction and Chapter 15 implementation evidence, but it did not yet have a
  detailed remaining chapter plan with order, invariants, required tests,
  transaction boundaries, and deferrals.
- The authoritative master plan now records the Phase 1 closure plan for Chapter
  16 synthetic scenario generation and the detailed Phase 2 chapter plan for
  Chapter 15 capture, Chapter 17 cancel/void, Chapter 18 refund ledgering,
  Chapter 19 webhook delivery retry behavior, and Chapter 20 deterministic
  processor edge cases.
