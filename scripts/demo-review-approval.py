#!/usr/bin/env python3
"""Public review-approval to processor/capture demo."""

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
        raise DemoFailure("Review approval demo rejects redirects")


def client() -> urllib.request.OpenerDirector:
    return urllib.request.build_opener(urllib.request.ProxyHandler({}), RejectRedirects())


def require_loopback(base_url: str) -> str:
    parsed = urllib.parse.urlsplit(base_url.rstrip("/"))
    if parsed.scheme != "http" or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
        raise DemoFailure("Review approval demo accepts only a loopback HTTP API origin")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise DemoFailure("Review approval demo API origin contains prohibited components")
    return base_url.rstrip("/")


def postgres_scalar(query: str) -> str:
    normalized = query.strip().lower()
    if not normalized.startswith("select"):
        raise DemoFailure("Review approval reconciliation accepts only SELECT")
    for prohibited in (" insert ", " update ", " delete ", " alter ", " drop ", " truncate "):
        if prohibited in f" {normalized} ":
            raise DemoFailure("Review approval reconciliation rejected mutating SQL")
    command = [
        "docker", "exec", "-i", os.environ.get("THAAP_POSTGRES_CONTAINER", "thaap-local-postgres-1"), "psql", "-X", "-A", "-t",
        "-U", os.environ.get("THAAP_POSTGRES_USER", "thaap"),
        "-d", os.environ.get("THAAP_POSTGRES_DB", "thaap_payments"), "-c", query,
    ]
    try:
        result = subprocess.run(command, check=True, capture_output=True, text=True, timeout=10)
    except (FileNotFoundError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        raise DemoFailure("read-only reconciliation requires the local thaap-postgres container") from error
    return result.stdout.strip()


def request_json(opener, base_url, method, path, body=None, headers=None):
    payload = None if body is None else json.dumps(body, separators=(",", ":")).encode()
    request_headers = {"Accept": "application/json", **(headers or {})}
    if payload is not None:
        request_headers["Content-Type"] = "application/json"
    request = urllib.request.Request(base_url + path, data=payload, headers=request_headers, method=method)
    try:
        with opener.open(request, timeout=10) as response:
            raw = response.read()
            if response.status not in (200, 201):
                raise DemoFailure(f"unexpected HTTP {response.status} for {path}")
            return raw, json.loads(raw)
    except urllib.error.HTTPError as error:
        raise DemoFailure(f"HTTP {error.code} for {path}: {error.read().decode('utf-8', 'replace')}") from error


def create_payment_method(opener, base_url, headers, customer_id, card_number, key):
    return request_json(
        opener, base_url, "POST", "/payment_methods",
        {"customerId": customer_id, "cardNumber": card_number},
        {**headers, "Idempotency-Key": key},
    )[1]


def confirm(opener, base_url, headers, intent_id, method_id, key):
    body = {"paymentMethodId": method_id}
    keyed_headers = {**headers, "Idempotency-Key": key}
    raw, attempt = request_json(
        opener, base_url, "POST", f"/payment_intents/{intent_id}/confirm", body, keyed_headers)
    replay_raw, replay = request_json(
        opener, base_url, "POST", f"/payment_intents/{intent_id}/confirm", body, keyed_headers)
    if replay_raw != raw or replay != attempt:
        raise DemoFailure("confirm replay was not byte-exact")
    return attempt


def run() -> None:
    base_url = require_loopback(os.environ.get("THAAP_BASE_URL", "http://localhost:8080"))
    admin_token = os.environ.get("THAAP_ADMIN_BOOTSTRAP_TOKEN")
    if not admin_token:
        raise DemoFailure("THAAP_ADMIN_BOOTSTRAP_TOKEN is required")
    opener = client()
    request_json(opener, base_url, "GET", "/actuator/health")
    run_id = f"{int(time.time())}-{uuid.uuid4().hex[:8]}"

    _, merchant = request_json(
        opener, base_url, "POST", "/admin/merchants", {"name": f"Review approval Demo {run_id}"},
        {"X-Admin-Bootstrap-Token": admin_token, "Idempotency-Key": f"ch27-{run_id}-merchant"})
    merchant_headers = {"X-Api-Key": merchant["apiKey"]}
    _, customer = request_json(
        opener, base_url, "POST", "/customers", {"email": f"review-approval-{run_id}@example.com"},
        merchant_headers)
    decline_method = create_payment_method(
        opener, base_url, merchant_headers, customer["id"], "4999990000000003",
        f"ch27-{run_id}-decline-method")
    approved_method = create_payment_method(
        opener, base_url, merchant_headers, customer["id"], "4999990000000001",
        f"ch27-{run_id}-approved-method")
    _, intent = request_json(
        opener, base_url, "POST", "/payment_intents",
        {"customerId": customer["id"], "amount": 99_999, "currency": "USD",
         "captureMethod": "AUTOMATIC"},
        {**merchant_headers, "Idempotency-Key": f"ch27-{run_id}-intent"})

    for number in (1, 2):
        attempt = confirm(opener, base_url, merchant_headers, intent["id"], decline_method["id"],
                          f"ch27-{run_id}-confirm-{number}")
        if attempt["status"] != "DECLINED":
            raise DemoFailure("review setup did not produce the required processor decline")
    reviewed_attempt = confirm(
        opener, base_url, merchant_headers, intent["id"], approved_method["id"],
        f"ch27-{run_id}-confirm-3")
    if reviewed_attempt["status"] != "IN_REVIEW" or reviewed_attempt["processorAuthorizationId"] is not None:
        raise DemoFailure("review intake called the processor before analyst approval")

    _, operator = request_json(
        opener, base_url, "POST", "/risk/operators", {"role": "ANALYST"},
        {**merchant_headers, "Idempotency-Key": f"ch27-{run_id}-operator"})
    risk_headers = {"X-Risk-Operator-Key": operator["credential"]}
    _, reviews = request_json(opener, base_url, "GET", "/risk/reviews?status=OPEN&limit=100",
                              headers=risk_headers)
    matching = [review for review in reviews if review["paymentIntentId"] == intent["id"]]
    if len(matching) != 1:
        raise DemoFailure("review queue did not expose exactly one reviewed payment")
    review = matching[0]
    request_json(
        opener, base_url, "POST", f"/risk/reviews/{review['id']}/assign",
        {"expectedAssignmentVersion": 0},
        {**risk_headers, "Idempotency-Key": f"ch27-{run_id}-assign"})

    _, webhook = request_json(
        opener, base_url, "POST", "/webhook_endpoints",
        {"url": "http://127.0.0.1:9191/review-approval-demo",
         "enabledEvents": ["payment_intent.captured", "review.resolved"]},
        {**merchant_headers, "Idempotency-Key": f"ch27-{run_id}-webhook"})
    if set(webhook["enabledEvents"]) != {"payment_intent.captured", "review.resolved"}:
        raise DemoFailure("webhook consent did not preserve the exact approval event set")

    merchant_id = uuid.UUID(merchant["id"])
    intent_id = uuid.UUID(intent["id"])
    before = postgres_scalar(f"""
        SELECT
          (SELECT COUNT(*) FROM authorizations authorization_row
             JOIN payment_attempts attempt ON attempt.id = authorization_row.payment_attempt_id
            WHERE attempt.merchant_id = '{merchant_id}'::uuid
              AND attempt.payment_intent_id = '{intent_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM ledger_entries WHERE merchant_id = '{merchant_id}'::uuid
             AND payment_intent_id = '{intent_id}'::uuid);
    """)
    if before != "2|0":
        raise DemoFailure(f"pre-approval processor/money truth was not 2|0: {before}")

    approval_body = {
        "outcome": "APPROVE", "classification": "INCONCLUSIVE",
        "reasonCode": "MANUAL_REVIEW_APPROVED",
        "evidenceOccurredAt": (datetime.datetime.now(datetime.timezone.utc)
                               - datetime.timedelta(seconds=5)).isoformat().replace("+00:00", "Z"),
    }
    approval_headers = {**risk_headers, "Idempotency-Key": f"ch27-{run_id}-approve"}
    approval_raw, approval = request_json(
        opener, base_url, "POST", f"/risk/reviews/{review['id']}/resolve",
        approval_body, approval_headers)
    replay_raw, replay = request_json(
        opener, base_url, "POST", f"/risk/reviews/{review['id']}/resolve",
        approval_body, approval_headers)
    distinct_raw, distinct = request_json(
        opener, base_url, "POST", f"/risk/reviews/{review['id']}/resolve", approval_body,
        {**risk_headers, "Idempotency-Key": f"ch27-{run_id}-approve-distinct"})
    if replay_raw != approval_raw or distinct_raw != approval_raw or replay != approval or distinct != approval:
        raise DemoFailure("same-key or terminal distinct-key approval replay was not byte-exact")
    if (approval["outcome"], approval["processorOutcome"], approval["paymentIntentStatus"],
            approval["paymentAttemptStatus"], approval["label"]) != (
            "APPROVE", "APPROVED", "CAPTURED", "AUTHORIZED", None):
        raise DemoFailure("approval response did not expose coherent processor and capture truth")

    expected_reference = "auth_sim_" + reviewed_attempt["id"].replace("-", "")
    summary = postgres_scalar(f"""
        SELECT
          (SELECT status FROM payment_intents WHERE id = '{intent_id}'::uuid) || '|' ||
          (SELECT status FROM payment_attempts WHERE id = '{reviewed_attempt['id']}'::uuid) || '|' ||
          (SELECT status FROM manual_review_cases WHERE id = '{review['id']}'::uuid) || '|' ||
          (SELECT action FROM fraud_decisions WHERE id = '{reviewed_attempt['fraudDecisionId']}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM review_resolutions WHERE merchant_id = '{merchant_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM fraud_labels WHERE merchant_id = '{merchant_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM authorizations authorization_row
             JOIN payment_attempts attempt ON attempt.id = authorization_row.payment_attempt_id
            WHERE attempt.merchant_id = '{merchant_id}'::uuid
              AND attempt.payment_intent_id = '{intent_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM ledger_entries WHERE merchant_id = '{merchant_id}'::uuid
             AND payment_intent_id = '{intent_id}'::uuid) || '|' ||
          (SELECT processor_reference_id FROM authorizations
            WHERE merchant_id = '{merchant_id}'::uuid
              AND payment_attempt_id = '{reviewed_attempt['id']}'::uuid);
    """)
    expected = f"CAPTURED|AUTHORIZED|RESOLVED|REVIEW|1|0|3|2|{expected_reference}"
    if summary != expected:
        raise DemoFailure(f"durable Review approval reconciliation failed: {summary}")

    print("Review-approval to processor demo passed.")
    print(f"merchant={merchant['id']} review={review['id']} attempt={reviewed_attempt['id']}")
    print("payment=CAPTURED attempt=AUTHORIZED review=RESOLVED decision=REVIEW")
    print("resolution=1 labels=0 authorizations=3 ledger_entries=2")
    print(f"processor_reference={expected_reference}")
    print("same_key_replay=exact distinct_key_terminal_replay=exact")


if __name__ == "__main__":
    try:
        run()
    except (DemoFailure, KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
        print(f"Review approval demo failed: {error}", file=sys.stderr)
        raise SystemExit(1)
