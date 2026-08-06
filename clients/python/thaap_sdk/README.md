# THAAP Python SDK

This is the supported synchronous client for the local THAAP payment-risk
sandbox. Generated operations and models come from the committed
`2026-08-01` OpenAPI contract. The reviewed wrapper adds the contract version,
credential separation, bounded connect/read timeouts, TLS verification,
no-redirect/no-proxy behavior, structured safe errors, and exact-byte webhook
verification.

## Install

From the THAAP repository root:

```bash
python3 -m venv .venv
. .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install -e 'clients/python/thaap_sdk[test]'
```

## Run the merchant-to-investigation journey

Load a local sandbox merchant credential into process memory and run:

```bash
export THAAP_MERCHANT_API_KEY='<sandbox credential>'
export THAAP_BASE_URL='http://127.0.0.1:8080'
python clients/python/thaap_sdk/examples/sandbox_quickstart.py
```

Set `THAAP_SCENARIO_RUNNER_TOKEN` only when a separately issued trusted runner
credential is available. The example then creates and claims one durable
approved-payment scenario; ordinary merchant traffic never gains that
authority implicitly.

The example prints identifiers, states, action, and freshness only. It never
prints merchant, operator, scenario, or webhook credentials.

## Ambiguous mutation outcomes

The wrapper performs no automatic retries. A connect/read failure raises
`ThaapTransportError`; `outcome_unknown` is true for mutations. Retry a mutation
only with the identical `Idempotency-Key` and byte-equivalent request body. Set
`THAAP_QUICKSTART_KEY` to reuse every deterministic quickstart key after an
ambiguous interruption.

An authenticated API error raises `ThaapApiError` with only stable code,
status, request ID, retryability, and recovery action. Raw response bodies and
credentials are excluded from exception text and object representations.

## Webhooks

Call `verify_webhook(raw_bytes, signature, event_id, secret)` before parsing or
applying an event. The verifier authenticates the timestamp plus exact bytes,
enforces the bounded timestamp/payload grammar, and reconciles the authenticated
payload event ID with its header. `InMemoryWebhookDeduplicator` is suitable only
for the sandbox; production consumers must commit deduplication durably with the
business effect.

See `docs/DEVELOPER_SANDBOX_GUIDE.md` for local merchant provisioning, outcome
fixtures, scenarios, contract/error discovery, and troubleshooting.
