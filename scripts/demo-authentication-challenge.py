#!/usr/bin/env python3
"""Public fraud- and processor-source authentication challenge demos."""

from __future__ import annotations

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
        raise DemoFailure("Authentication challenge demo rejects redirects")


def require_loopback(base_url: str) -> str:
    parsed = urllib.parse.urlsplit(base_url.rstrip("/"))
    if parsed.scheme != "http" or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
        raise DemoFailure("Authentication challenge demo accepts only a loopback HTTP API origin")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise DemoFailure("Authentication challenge demo API origin contains prohibited components")
    return base_url.rstrip("/")


def postgres_scalar(query: str) -> str:
    normalized = query.strip().lower()
    if not normalized.startswith("select"):
        raise DemoFailure("Authentication challenge reconciliation accepts only SELECT")
    for prohibited in (" insert ", " update ", " delete ", " alter ", " drop ", " truncate "):
        if prohibited in f" {normalized} ":
            raise DemoFailure("Authentication challenge reconciliation rejected mutating SQL")
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


def create_cohort(opener, base_url, admin_token, run_id, source):
    prefix = f"ch28-{run_id}-{source.lower()}"
    _, merchant = request_json(
        opener, base_url, "POST", "/admin/merchants", {"name": f"Authentication challenge {source} {run_id}"},
        {"X-Admin-Bootstrap-Token": admin_token, "Idempotency-Key": prefix + "-merchant"})
    headers = {"X-Api-Key": merchant["apiKey"]}
    _, customer = request_json(
        opener, base_url, "POST", "/customers", {"email": prefix + "@example.com"}, headers)
    card = "4999990000000001" if source == "FRAUD_ENGINE" else "4999990000000008"
    _, method = request_json(
        opener, base_url, "POST", "/payment_methods",
        {"customerId": customer["id"], "cardNumber": card},
        {**headers, "Idempotency-Key": prefix + "-method"})
    capture = "AUTOMATIC" if source == "FRAUD_ENGINE" else "MANUAL"
    amount = 100_000 if source == "FRAUD_ENGINE" else 99_999
    _, intent = request_json(
        opener, base_url, "POST", "/payment_intents",
        {"customerId": customer["id"], "amount": amount, "currency": "USD",
         "captureMethod": capture}, {**headers, "Idempotency-Key": prefix + "-intent"})
    confirm_body = {"paymentMethodId": method["id"]}
    confirm_headers = {**headers, "Idempotency-Key": prefix + "-confirm"}
    raw, attempt = request_json(
        opener, base_url, "POST", f"/payment_intents/{intent['id']}/confirm",
        confirm_body, confirm_headers)
    replay_raw, replay = request_json(
        opener, base_url, "POST", f"/payment_intents/{intent['id']}/confirm",
        confirm_body, confirm_headers)
    if replay_raw != raw or replay != attempt:
        raise DemoFailure(f"{source} confirm replay was not byte-exact")
    action = attempt.get("nextAction")
    if not action or action.get("source") != source or not action.get("clientSecret", "").startswith("authc_"):
        raise DemoFailure(f"{source} did not expose its source-bound reveal-once next action")
    return merchant, headers, intent, attempt, action, prefix


def complete_cohort(opener, base_url, cohort, expected_intent_status):
    merchant, headers, intent, attempt, action, prefix = cohort
    merchant_id = uuid.UUID(merchant["id"])
    before = postgres_scalar(f"""
        SELECT
          (SELECT status FROM authentication_challenges WHERE id = '{action['challengeId']}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM authorizations WHERE merchant_id = '{merchant_id}'::uuid
             AND payment_attempt_id = '{attempt['id']}'::uuid AND status = 'APPROVED') || '|' ||
          (SELECT COUNT(*) FROM ledger_entries WHERE merchant_id = '{merchant_id}'::uuid
             AND payment_intent_id = '{intent['id']}'::uuid);
    """)
    if before != "PENDING|0|0":
        raise DemoFailure(f"pre-authentication funds truth was not PENDING|0|0: {before}")
    body = {"outcome": "SUCCESS", "clientSecret": action["clientSecret"]}
    path = action["completionEndpoint"]
    keyed = {**headers, "Idempotency-Key": prefix + "-complete"}
    raw, completed = request_json(opener, base_url, "POST", path, body, keyed)
    replay_raw, replay = request_json(opener, base_url, "POST", path, body, keyed)
    distinct_raw, distinct = request_json(
        opener, base_url, "POST", path, body,
        {**headers, "Idempotency-Key": prefix + "-complete-distinct"})
    if replay_raw != raw or distinct_raw != raw or replay != completed or distinct != completed:
        raise DemoFailure("completion replay was not exact and terminal-stable")
    if (completed["status"], completed["resultCode"], completed["paymentIntentStatus"],
            completed["paymentAttemptStatus"]) != (
            "SUCCEEDED", "AUTHENTICATED", expected_intent_status, "AUTHORIZED"):
        raise DemoFailure("completion response did not expose coherent authenticated truth")
    expected_ledger = 2 if expected_intent_status == "CAPTURED" else 0
    summary = postgres_scalar(f"""
        SELECT
          (SELECT status FROM authentication_challenges WHERE id = '{action['challengeId']}'::uuid) || '|' ||
          (SELECT source FROM authentication_challenges WHERE id = '{action['challengeId']}'::uuid) || '|' ||
          (SELECT status FROM payment_intents WHERE id = '{intent['id']}'::uuid) || '|' ||
          (SELECT status FROM payment_attempts WHERE id = '{attempt['id']}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM authorizations WHERE merchant_id = '{merchant_id}'::uuid
             AND payment_attempt_id = '{attempt['id']}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM ledger_entries WHERE merchant_id = '{merchant_id}'::uuid
             AND payment_intent_id = '{intent['id']}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM fraud_decisions WHERE merchant_id = '{merchant_id}'::uuid);
    """)
    expected = f"SUCCEEDED|{action['source']}|{expected_intent_status}|AUTHORIZED|1|{expected_ledger}|1"
    if summary != expected:
        raise DemoFailure(f"durable {action['source']} reconciliation failed: {summary}")
    return attempt["id"], action["challengeId"], expected_ledger


def run() -> None:
    base_url = require_loopback(os.environ.get("THAAP_BASE_URL", "http://localhost:8080"))
    admin_token = os.environ.get("THAAP_ADMIN_BOOTSTRAP_TOKEN")
    if not admin_token:
        raise DemoFailure("THAAP_ADMIN_BOOTSTRAP_TOKEN is required")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), RejectRedirects())
    request_json(opener, base_url, "GET", "/actuator/health")
    run_id = f"{int(time.time())}-{uuid.uuid4().hex[:8]}"

    fraud = complete_cohort(opener, base_url,
                            create_cohort(opener, base_url, admin_token, run_id, "FRAUD_ENGINE"),
                            "CAPTURED")
    processor = complete_cohort(opener, base_url,
                                create_cohort(opener, base_url, admin_token, run_id, "PROCESSOR"),
                                "REQUIRES_CAPTURE")

    print("Authentication challenge two-source authentication demos passed.")
    print(f"fraud_attempt={fraud[0]} fraud_challenge={fraud[1]} ledger_entries={fraud[2]}")
    print(f"processor_attempt={processor[0]} processor_challenge={processor[1]} ledger_entries={processor[2]}")
    print("same_key_replay=exact distinct_key_terminal_replay=exact decisions_per_cohort=1")


if __name__ == "__main__":
    try:
        run()
    except (DemoFailure, KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
        print(f"Authentication challenge demo failed: {error}", file=sys.stderr)
        raise SystemExit(1)
