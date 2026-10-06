#!/usr/bin/env python3
"""Public captured/refunded chargeback and dispute-evidence demo."""

from __future__ import annotations

import datetime
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


class DemoFailure(RuntimeError):
    pass


class RejectRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        raise DemoFailure("Chargeback and dispute demo rejects redirects")


def require_loopback(base_url: str) -> str:
    parsed = urllib.parse.urlsplit(base_url.rstrip("/"))
    if parsed.scheme != "http" or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
        raise DemoFailure("Chargeback and dispute demo accepts only a loopback HTTP API origin")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise DemoFailure("Chargeback and dispute demo API origin contains prohibited components")
    return base_url.rstrip("/")


def postgres_scalar(query: str) -> str:
    normalized = query.strip().lower()
    if not normalized.startswith("select"):
        raise DemoFailure("Chargeback and dispute reconciliation accepts only SELECT")
    for prohibited in (" insert ", " update ", " delete ", " alter ", " drop ", " truncate "):
        if prohibited in f" {normalized} ":
            raise DemoFailure("Chargeback and dispute reconciliation rejected mutating SQL")
    command = [
        "docker", "exec", "-i", os.environ.get("THAAP_POSTGRES_CONTAINER", "thaap-local-postgres-1"), "psql", "-X", "-A", "-t",
        "-U", os.environ.get("THAAP_POSTGRES_USER", "thaap"),
        "-d", os.environ.get("THAAP_POSTGRES_DB", "thaap_payments"), "-c", query,
    ]
    try:
        result = subprocess.run(command, check=True, capture_output=True, text=True, timeout=10)
    except (FileNotFoundError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        raise DemoFailure("read-only reconciliation requires local thaap-postgres") from error
    return result.stdout.strip()


def request_json(opener, base_url, method, path, body=None, headers=None, expected=(200, 201)):
    payload = None if body is None else json.dumps(body, separators=(",", ":")).encode()
    request_headers = {"Accept": "application/json", **(headers or {})}
    if payload is not None:
        request_headers["Content-Type"] = "application/json"
    request = urllib.request.Request(base_url + path, data=payload, headers=request_headers, method=method)
    try:
        with opener.open(request, timeout=12) as response:
            raw = response.read()
            if response.status not in expected:
                raise DemoFailure(f"unexpected HTTP {response.status} for {path}")
            return raw, json.loads(raw)
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", "replace")
        if error.code in expected:
            return detail.encode(), json.loads(detail)
        raise DemoFailure(f"HTTP {error.code} for {path}: {detail}") from error


def post(opener, base_url, path, body, headers, key=None, expected=(200, 201)):
    request_headers = dict(headers)
    if key:
        request_headers["Idempotency-Key"] = key
    return request_json(opener, base_url, "POST", path, body, request_headers, expected)


def captured_payment(opener, base_url, merchant_headers, prefix, amount):
    _, customer = post(opener, base_url, "/customers",
                       {"email": f"{prefix}@example.com"}, merchant_headers)
    _, method = post(opener, base_url, "/payment_methods",
                     {"customerId": customer["id"], "cardNumber": "4242424242424242"},
                     merchant_headers, prefix + "-method")
    _, intent = post(opener, base_url, "/payment_intents",
                     {"customerId": customer["id"], "amount": amount, "currency": "USD",
                      "captureMethod": "AUTOMATIC"}, merchant_headers, prefix + "-intent")
    _, attempt = post(opener, base_url, f"/payment_intents/{intent['id']}/confirm",
                      {"paymentMethodId": method["id"]}, merchant_headers, prefix + "-confirm")
    if attempt["status"] != "AUTHORIZED":
        raise DemoFailure("Chargeback and dispute setup payment was not captured through approved authorization")
    return intent


def chargeback_body(event_key, intent_id, amount, reason):
    now = datetime.datetime.now(datetime.timezone.utc)
    return {
        "externalEventKey": event_key,
        "paymentIntentId": intent_id,
        "amount": amount,
        "currency": "USD",
        "reasonCode": reason,
        "evidenceOccurredAt": (now - datetime.timedelta(hours=2)).isoformat().replace("+00:00", "Z"),
        "receivedAt": (now - datetime.timedelta(minutes=1)).isoformat().replace("+00:00", "Z"),
        "evidenceDueAt": (now + datetime.timedelta(days=1)).isoformat().replace("+00:00", "Z"),
    }


def run() -> None:
    base_url = require_loopback(os.environ.get("THAAP_BASE_URL", "http://localhost:8080"))
    admin_token = os.environ.get("THAAP_ADMIN_BOOTSTRAP_TOKEN")
    if not admin_token:
        raise DemoFailure("THAAP_ADMIN_BOOTSTRAP_TOKEN is required")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), RejectRedirects())
    request_json(opener, base_url, "GET", "/actuator/health/readiness")
    run_id = f"{int(time.time())}-{uuid.uuid4().hex[:8]}"
    prefix = f"ch32-{run_id}"
    _, merchant = post(opener, base_url, "/admin/merchants", {"name": f"Chargeback and dispute {run_id}"},
                       {"X-Admin-Bootstrap-Token": admin_token}, prefix + "-merchant")
    merchant_headers = {"X-Api-Key": merchant["apiKey"]}
    post(opener, base_url, "/webhook_endpoints",
         {"url": f"http://127.0.0.1:9191/{prefix}",
          "enabledEvents": ["chargeback.created", "dispute_evidence_packet.created",
                            "fraud_label.created", "refund.created"]},
         merchant_headers, prefix + "-webhook")

    captured = captured_payment(opener, base_url, merchant_headers, prefix + "-captured", 6400)
    fraud_body = chargeback_body(prefix + ".fraud", captured["id"], 6400,
                                 "FRAUDULENT_STOLEN_CREDENTIAL")
    original_raw, fraud = post(opener, base_url, "/chargebacks", fraud_body,
                               merchant_headers, prefix + "-fraud-chargeback", expected=(201,))
    replay_raw, _ = post(opener, base_url, "/chargebacks", fraud_body,
                         merchant_headers, prefix + "-fraud-chargeback", expected=(201,))
    if replay_raw != original_raw or fraud["status"] != "ALLOCATED" or fraud["allocatedAmount"] != 6400:
        raise DemoFailure("captured chargeback allocation or exact replay failed")
    _, independently_deduplicated = post(opener, base_url, "/chargebacks", fraud_body,
                                         merchant_headers, prefix + "-external-dedup", expected=(200,))
    if independently_deduplicated != fraud:
        raise DemoFailure("external-event deduplication did not converge")
    _, packet = post(opener, base_url, f"/chargebacks/{fraud['id']}/evidence_packets",
                     {"deliveryConfirmed": True, "customerCommunicationPresent": False,
                      "deviceContinuity": True}, merchant_headers, prefix + "-evidence", expected=(201,))
    if packet["status"] != "DRAFT" or packet["sourceAttestation"] != "SIMULATOR_MERCHANT_INGESTED":
        raise DemoFailure("bounded DRAFT evidence truth failed")

    refunded = captured_payment(opener, base_url, merchant_headers, prefix + "-refunded", 7200)
    post(opener, base_url, "/refunds", {"paymentIntentId": refunded["id"], "amount": 7200},
         merchant_headers, prefix + "-refund", expected=(201,))
    _, offset = post(opener, base_url, "/chargebacks",
                     chargeback_body(prefix + ".nonfraud", refunded["id"], 7200, "PRODUCT_NOT_RECEIVED"),
                     merchant_headers, prefix + "-offset", expected=(201,))
    if offset["status"] != "OFFSET_BY_REFUND" or offset["allocatedAmount"] != 0:
        raise DemoFailure("refund-first chargeback did not expose zero offset")

    merchant_id = uuid.UUID(merchant["id"])
    captured_id = uuid.UUID(captured["id"])
    refunded_id = uuid.UUID(refunded["id"])
    reconciliation = postgres_scalar(f"""
        SELECT
          (SELECT status FROM payment_intents WHERE merchant_id = '{merchant_id}'::uuid
             AND id = '{captured_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM ledger_entries WHERE merchant_id = '{merchant_id}'::uuid
             AND payment_intent_id = '{captured_id}'::uuid AND source_type = 'CHARGEBACK') || '|' ||
          (SELECT COUNT(*) FROM fraud_labels WHERE merchant_id = '{merchant_id}'::uuid
             AND source_chargeback_id = '{uuid.UUID(fraud['id'])}'::uuid) || '|' ||
          (SELECT status FROM payment_intents WHERE merchant_id = '{merchant_id}'::uuid
             AND id = '{refunded_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM ledger_entries WHERE merchant_id = '{merchant_id}'::uuid
             AND payment_intent_id = '{refunded_id}'::uuid AND source_type = 'CHARGEBACK') || '|' ||
          (SELECT COUNT(*) FROM fraud_labels WHERE merchant_id = '{merchant_id}'::uuid
             AND source_chargeback_id = '{uuid.UUID(offset['id'])}'::uuid);
    """)
    if reconciliation != "DISPUTED|2|1|REFUNDED|0|0":
        raise DemoFailure(f"Chargeback and dispute durable reconciliation failed: {reconciliation}")

    print("Chargeback and dispute-evidence demo passed.")
    print("captured=DISPUTED|balanced_ledger|fraud_label refunded=REFUNDED|zero_offset|no_label")
    print("source=SIMULATOR_MERCHANT_INGESTED replay=byte_exact external_dedup=converged")
    print("evidence=DRAFT|bounded|pre_deadline readiness=UP")


if __name__ == "__main__":
    try:
        run()
    except (DemoFailure, KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
        print(f"Chargeback and dispute demo failed: {error}", file=sys.stderr)
        raise SystemExit(1)
