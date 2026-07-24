#!/usr/bin/env python3
"""Public post-authorization signal-to-review-to-refund demo."""

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
        raise DemoFailure("Post-authorization demo rejects redirects")


def require_loopback(base_url: str) -> str:
    parsed = urllib.parse.urlsplit(base_url.rstrip("/"))
    if parsed.scheme != "http" or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
        raise DemoFailure("Post-authorization demo accepts only a loopback HTTP API origin")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise DemoFailure("Post-authorization demo API origin contains prohibited components")
    return base_url.rstrip("/")


def postgres_scalar(query: str) -> str:
    normalized = query.strip().lower()
    if not normalized.startswith("select"):
        raise DemoFailure("Post-authorization reconciliation accepts only SELECT")
    for prohibited in (" insert ", " update ", " delete ", " alter ", " drop ", " truncate "):
        if prohibited in f" {normalized} ":
            raise DemoFailure("Post-authorization reconciliation rejected mutating SQL")
    command = [
        "docker", "exec", "-i", "thaap-postgres", "psql", "-X", "-A", "-t",
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
        raise DemoFailure(f"HTTP {error.code} for {path}: {detail}") from error


def post(opener, base_url, path, body, headers, key=None, expected=(200, 201)):
    request_headers = dict(headers)
    if key:
        request_headers["Idempotency-Key"] = key
    return request_json(opener, base_url, "POST", path, body, request_headers, expected)


def captured_payment(opener, base_url, merchant_headers, prefix):
    _, customer = post(opener, base_url, "/customers", {"email": f"{prefix}@example.com"}, merchant_headers)
    _, method = post(opener, base_url, "/payment_methods",
                     {"customerId": customer["id"], "cardNumber": "4242424242424242"},
                     merchant_headers, prefix + "-method")
    _, intent = post(opener, base_url, "/payment_intents",
                     {"customerId": customer["id"], "amount": 5300, "currency": "USD",
                      "captureMethod": "AUTOMATIC"}, merchant_headers, prefix + "-intent")
    _, attempt = post(opener, base_url, f"/payment_intents/{intent['id']}/confirm",
                      {"paymentMethodId": method["id"]}, merchant_headers, prefix + "-confirm", (201,))
    if attempt["status"] != "AUTHORIZED":
        raise DemoFailure("Post-authorization setup did not produce a captured payment")
    return intent, attempt


def signal_body(event_key, intent_id, recommendation, reason):
    now = datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z")
    return {"externalEventKey": event_key, "paymentIntentId": intent_id,
            "recommendation": recommendation, "reasonCode": reason,
            "evidenceOccurredAt": now, "receivedAt": now}


def run() -> None:
    base_url = require_loopback(os.environ.get("THAAP_BASE_URL", "http://localhost:8080"))
    admin_token = os.environ.get("THAAP_ADMIN_BOOTSTRAP_TOKEN")
    if not admin_token:
        raise DemoFailure("THAAP_ADMIN_BOOTSTRAP_TOKEN is required")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), RejectRedirects())
    request_json(opener, base_url, "GET", "/actuator/health/readiness")
    run_id = f"{int(time.time())}-{uuid.uuid4().hex[:8]}"
    prefix = f"ch33-{run_id}"
    _, merchant = post(opener, base_url, "/admin/merchants", {"name": f"Post-authorization {run_id}"},
                       {"X-Admin-Bootstrap-Token": admin_token}, prefix + "-merchant")
    merchant_headers = {"X-Api-Key": merchant["apiKey"]}
    _, manager = post(opener, base_url, "/risk/operators", {"role": "RISK_MANAGER"},
                      merchant_headers, prefix + "-manager")
    manager_headers = {"X-Risk-Operator-Key": manager["credential"]}
    post(opener, base_url, "/webhook_endpoints",
         {"url": f"http://127.0.0.1:9191/{prefix}",
          "enabledEvents": ["post_authorization_signal.created", "fraud_review.opened",
                            "review.resolved", "fraud_label.created", "refund.created"]},
         merchant_headers, prefix + "-webhook")

    intent, attempt = captured_payment(opener, base_url, merchant_headers, prefix + "-review")
    body = signal_body(prefix + ".review", intent["id"], "REVIEW", "ACCOUNT_TAKEOVER_INDICATOR")
    first_raw, signal = post(opener, base_url, "/post_authorization_signals", body,
                             merchant_headers, prefix + "-signal", (201,))
    replay_raw, _ = post(opener, base_url, "/post_authorization_signals", body,
                         merchant_headers, prefix + "-signal", (201,))
    if first_raw != replay_raw or signal["actionTaken"] != "REVIEW_OPENED":
        raise DemoFailure("signal replay or REVIEW intake truth failed")
    resolution_body = {
        "outcome": "REFUND_AND_REPORT_FRAUD", "classification": "CONFIRMED_FRAUD",
        "fraudType": "ACCOUNT_TAKEOVER", "reasonCode": "ACCOUNT_TAKEOVER_CONFIRMED",
        "evidenceOccurredAt": signal["evidenceOccurredAt"],
    }
    _, resolution = post(opener, base_url, f"/risk/reviews/{signal['reviewCaseId']}/resolve",
                          resolution_body, manager_headers, prefix + "-resolve", (200,))
    if (resolution["paymentIntentStatus"] != "REFUNDED"
            or resolution["refundActionResult"] != "CREATED"
            or resolution["refundActionReasonCode"] != "CONFIRMED_FRAUD_CUSTOMER_PROTECTION"
            or resolution["label"]["fraudType"] != "ACCOUNT_TAKEOVER"):
        raise DemoFailure("manager refund-and-label truth failed")

    recommendation_intent, _ = captured_payment(opener, base_url, merchant_headers, prefix + "-recommendation")
    _, recommendation = post(opener, base_url, "/post_authorization_signals",
                             signal_body(prefix + ".refund-only", recommendation_intent["id"],
                                         "REFUND", "MERCHANT_RISK_ALERT"),
                             merchant_headers, prefix + "-refund-recommendation", (201,))
    if recommendation["actionTaken"] != "NONE":
        raise DemoFailure("REFUND recommendation incorrectly moved money")

    merchant_id = uuid.UUID(merchant["id"])
    review_intent_id = uuid.UUID(intent["id"])
    recommendation_intent_id = uuid.UUID(recommendation_intent["id"])
    reconciliation = postgres_scalar(f"""
        SELECT
          (SELECT status FROM payment_intents WHERE merchant_id='{merchant_id}'::uuid AND id='{review_intent_id}'::uuid) || '|' ||
          (SELECT action FROM fraud_decisions WHERE id='{uuid.UUID(attempt['fraudDecisionId'])}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM refunds WHERE merchant_id='{merchant_id}'::uuid AND payment_intent_id='{review_intent_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM review_refund_actions WHERE merchant_id='{merchant_id}'::uuid AND payment_intent_id='{review_intent_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM fraud_labels WHERE merchant_id='{merchant_id}'::uuid AND payment_intent_id='{review_intent_id}'::uuid) || '|' ||
          (SELECT status FROM payment_intents WHERE merchant_id='{merchant_id}'::uuid AND id='{recommendation_intent_id}'::uuid);
    """)
    if reconciliation != "REFUNDED|ALLOW|1|1|1|CAPTURED":
        raise DemoFailure(f"Post-authorization durable reconciliation failed: {reconciliation}")

    print("Post-authorization signal and review-money demo passed.")
    print("review=REFUNDED|refund_action_CREATED|fraud_label original_decision=ALLOW")
    print("refund_recommendation=CAPTURED|no_action source=SIMULATOR_MERCHANT_INGESTED")
    print("replay=byte_exact readiness=UP")


if __name__ == "__main__":
    try:
        run()
    except (DemoFailure, KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
        print(f"Post-authorization demo failed: {error}", file=sys.stderr)
        raise SystemExit(1)
