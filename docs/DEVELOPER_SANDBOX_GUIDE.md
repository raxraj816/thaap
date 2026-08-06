# THAAP Developer Sandbox Guide

## What this guide proves

The sandbox journey follows the real product loop:

```text
merchant credential -> customer -> tokenized test card -> PaymentIntent
  -> confirm -> processor result -> fraud lineage -> risk-operator investigation
  -> analytics freshness
```

It uses only the published versioned contract, supported Python SDK, and this
guide. No controller or database knowledge is required.

## 1. Start the local sandbox

Start the repository's PostgreSQL, Redis, and Kafka services, then the payments
API according to the root README. Confirm that
`http://127.0.0.1:8080/actuator/health/readiness` is healthy.

Merchant creation is deliberately an administration boundary and is excluded
from the public SDK. A local administrator provisions one sandbox merchant and
hands its one-time credential to the developer through a secret manager. For a
local-only session:

```bash
merchant_json="$(curl --fail --silent --show-error \
  -H "X-Admin-Bootstrap-Token: ${THAAP_ADMIN_BOOTSTRAP_TOKEN}" \
  -H 'Content-Type: application/json' \
  --data '{"name":"SDK sandbox merchant"}' \
  http://127.0.0.1:8080/admin/merchants)"
export THAAP_MERCHANT_API_KEY="$(printf '%s' "$merchant_json" | jq -r '.apiKey')"
unset merchant_json
```

Do not paste the credential into URLs, shell history, tickets, logs, or browser
storage. This local command keeps it in the current process environment; a real
deployment should inject it from a secret manager.

## 2. Install and run the supported client

```bash
python3 -m venv .venv
. .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install -e 'clients/python/thaap_sdk[test]'
export THAAP_BASE_URL='http://127.0.0.1:8080'
python clients/python/thaap_sdk/examples/sandbox_quickstart.py
```

The journey creates a `RISK_MANAGER` credential through merchant authority and
uses that separate credential for the investigation and analytics calls. The
SDK sends exactly one applicable credential per ordinary operation and always
sends `Thaap-Version: 2026-08-01`.

## 3. Exercise deterministic outcomes

Fetch `GET /developer/test-cards` or open `/developer/scenarios` for the live
catalogue. The catalogue, not copied card folklore, is authoritative for
approved, issuer-declined, review, 3DS, blocked, timeout, and replay behavior.
Every synthetic run is tagged `SYNTHETIC_SCENARIO`; live-only insight policies
exclude it unless the response explicitly says otherwise.

Every scenario-run operation requires both the merchant credential and the
separately issued scenario-runner credential. This is an AND boundary: neither
credential can substitute for the other, and ordinary merchant traffic cannot
be mislabeled as synthetic. A claimed step carries a lease epoch; stale workers
cannot complete it.

## 4. Handle retries and errors safely

`ThaapApiError` means THAAP returned an authenticated structured response. Use
its `code`, `retryable`, and `recovery_action`; the human message is not a
compatibility contract. Browse `/developer/api` or call
`GET /developer/error-catalog` for the current catalogue.

`ThaapTransportError(outcome_unknown=True)` means a mutation may have committed
even though no authenticated response arrived. Reissue it only with the exact
same idempotency key and body. For the quickstart, preserve
`THAAP_QUICKSTART_KEY` across the retry:

```bash
export THAAP_QUICKSTART_KEY='merchant-onboarding-attempt-1'
python clients/python/thaap_sdk/examples/sandbox_quickstart.py
```

Changing either key or body can create a second business intent; automatic
mutation retries are intentionally disabled.

## 5. Verify webhook callbacks

Authenticate before JSON parsing:

```python
from thaap_sdk import InMemoryWebhookDeduplicator, verify_webhook

event = verify_webhook(raw_request_bytes, signature_header, event_id_header, secret)
if InMemoryWebhookDeduplicator().apply_once(str(event["id"]), raw_request_bytes):
    apply_business_effect(event)
```

The verifier rejects re-serialized bytes, stale/future timestamps, malformed
signatures, mismatched event IDs, invalid secrets, and oversized payloads. A
production consumer must persist the authenticated event ID and payload digest
in the same transaction as its business effect, then acknowledge success.

## 6. Page through investigation history

Every cursor-paged investigation surface — payment search, the canonical
timeline, related payments, and webhook activity — publishes the same envelope:
its rows under `data`, and a `nextCursor` that is `null` on the final page. One
pager therefore works across all of them, unmodified:

```python
from thaap_sdk import iterate_rows

investigations = risk.investigations()
for entry in iterate_rows(
    lambda cursor: investigations.get_risk_workspace_payments_by_payment_intent_id_timeline(
        payment_intent_id, cursor=cursor)
):
    print(entry.stream_version, entry.event_type)
```

Two properties are worth relying on. Order is by immutable `streamVersion`, not by
timestamp — two events can share a timestamp, and timestamps can arrive out of
order, so treating time as identity will eventually lose or duplicate a row.
Traversal is bounded and refuses a repeated cursor, so a server that stops
advancing fails loudly instead of looking like a hang.

## 7. Inspect the exact contract

- Portal: `/developer/api`
- Contract: `/developer/openapi/2026-08-01`
- Digest: `/developer/openapi/2026-08-01/digest`
- Version lifecycle: `/developer/api-versions`
- Stable errors: `/developer/error-catalog`

The served contract bytes must equal the committed artifact. Internal,
administrative, platform, and actuator routes are intentionally absent.
