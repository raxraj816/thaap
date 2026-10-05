#!/usr/bin/env python3
"""Public safe RiskContext and all-action parity demo."""

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
        raise DemoFailure("Risk context demo rejects redirects")


def require_loopback(base_url: str) -> str:
    parsed = urllib.parse.urlsplit(base_url.rstrip("/"))
    if parsed.scheme != "http" or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
        raise DemoFailure("Risk context demo accepts only a loopback HTTP API origin")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise DemoFailure("Risk context demo API origin contains prohibited components")
    return base_url.rstrip("/")


def postgres_scalar(query: str) -> str:
    normalized = query.strip().lower()
    if not normalized.startswith("select"):
        raise DemoFailure("Risk context reconciliation accepts only SELECT")
    for prohibited in (" insert ", " update ", " delete ", " alter ", " drop ", " truncate "):
        if prohibited in f" {normalized} ":
            raise DemoFailure("Risk context reconciliation rejected mutating SQL")
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


def create_cohort(opener, base_url, admin_token, run_id, action, with_context):
    suffix = f"{action.lower()}-{'context' if with_context else 'legacy'}"
    prefix = f"ch29-{run_id}-{suffix}"
    _, merchant = request_json(
        opener, base_url, "POST", "/admin/merchants", {"name": f"Risk context {suffix} {run_id}"},
        {"X-Admin-Bootstrap-Token": admin_token, "Idempotency-Key": prefix + "-merchant"})
    headers = {"X-Api-Key": merchant["apiKey"]}
    _, customer = request_json(
        opener, base_url, "POST", "/customers", {"email": prefix + "@example.com"}, headers)
    card = "4999990000000003" if action == "REVIEW" else "4999990000000001"
    _, method = request_json(
        opener, base_url, "POST", "/payment_methods",
        {"customerId": customer["id"], "cardNumber": card},
        {**headers, "Idempotency-Key": prefix + "-method"})
    amount = {"ALLOW": 1_200, "BLOCK": 5_000_000, "REQUEST_3DS": 100_000, "REVIEW": 99_999}[action]
    _, intent = request_json(
        opener, base_url, "POST", "/payment_intents",
        {"customerId": customer["id"], "amount": amount, "currency": "USD", "captureMethod": "MANUAL"},
        {**headers, "Idempotency-Key": prefix + "-intent"})
    canary = f"private-device-{run_id}-{suffix}"
    risk_context = {
        "ipAddress": "203.0.113.129", "ipCountry": "US", "userAgent": "THAAP-PRIVATE-UA-29",
        "deviceId": canary, "sessionId": canary, "shippingCountry": "CA", "checkoutChannel": "WEB",
        "customerAccountAgeDays": 120, "loginAgeMinutes": 4, "botScoreBps": 321,
        "captchaOutcome": "PASSED",
    }
    attempts = 3 if action == "REVIEW" else 1
    result = None
    for number in range(1, attempts + 1):
        body = {"paymentMethodId": method["id"]}
        if with_context:
            body["riskContext"] = risk_context
            body["riskContext"]["ipAddress"] = f"203.0.113.{128 + number}"
        keyed = {**headers, "Idempotency-Key": f"{prefix}-confirm-{number}"}
        raw, result = request_json(
            opener, base_url, "POST", f"/payment_intents/{intent['id']}/confirm", body, keyed)
        replay_raw, replay = request_json(
            opener, base_url, "POST", f"/payment_intents/{intent['id']}/confirm", body, keyed)
        if raw != replay_raw or result != replay:
            raise DemoFailure(f"{suffix} confirm replay was not byte-exact")
    expected_status = {"ALLOW": "AUTHORIZED", "BLOCK": "BLOCKED",
                       "REQUEST_3DS": "REQUIRES_ACTION", "REVIEW": "IN_REVIEW"}[action]
    if result is None or result["status"] != expected_status:
        raise DemoFailure(f"{suffix} produced {None if result is None else result['status']}")
    merchant_id = uuid.UUID(merchant["id"])
    decision_id = uuid.UUID(result["fraudDecisionId"])
    context_count = attempts if with_context else 0
    # Risk context features remain stable; velocity and merchant-policy work adds four
    # always-materialized velocity features to the latest Phase 4 decision.
    feature_count = 22 if with_context else 12
    summary = postgres_scalar(f"""
        SELECT
          (SELECT action FROM fraud_decisions WHERE id = '{decision_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM risk_contexts WHERE merchant_id = '{merchant_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM feature_values WHERE merchant_id = '{merchant_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM risk_contexts WHERE merchant_id = '{merchant_id}'::uuid
             AND fraud_decision_id IS NULL);
    """)
    expected = f"{action}|{context_count}|{feature_count * attempts}|0"
    if summary != expected:
        raise DemoFailure(f"{suffix} durable lineage mismatch: {summary}, expected {expected}")
    if with_context:
        escaped = canary.replace("'", "''")
        leak_count = postgres_scalar(f"""
            SELECT
              (SELECT COUNT(*) FROM risk_contexts r WHERE row_to_json(r)::text LIKE '%{escaped}%') +
              (SELECT COUNT(*) FROM feature_snapshots s WHERE row_to_json(s)::text LIKE '%{escaped}%') +
              (SELECT COUNT(*) FROM audit_logs a WHERE row_to_json(a)::text LIKE '%{escaped}%') +
              (SELECT COUNT(*) FROM webhook_events w WHERE row_to_json(w)::text LIKE '%{escaped}%') +
              (SELECT COUNT(*) FROM idempotency_records i WHERE row_to_json(i)::text LIKE '%{escaped}%');
        """)
        if leak_count != "0":
            raise DemoFailure(f"raw context canary escaped into {leak_count} durable rows")
    return result["status"]


def run() -> None:
    base_url = require_loopback(os.environ.get("THAAP_BASE_URL", "http://localhost:8080"))
    admin_token = os.environ.get("THAAP_ADMIN_BOOTSTRAP_TOKEN")
    if not admin_token:
        raise DemoFailure("THAAP_ADMIN_BOOTSTRAP_TOKEN is required")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), RejectRedirects())
    request_json(opener, base_url, "GET", "/actuator/health")
    run_id = f"{int(time.time())}-{uuid.uuid4().hex[:8]}"
    outcomes = {}
    for action in ("ALLOW", "BLOCK", "REQUEST_3DS", "REVIEW"):
        legacy = create_cohort(opener, base_url, admin_token, run_id, action, False)
        context = create_cohort(opener, base_url, admin_token, run_id, action, True)
        if legacy != context:
            raise DemoFailure(f"{action} changed when RiskContext was supplied")
        outcomes[action] = context
    print("Safe RiskContext all-action demo passed.")
    print(" ".join(f"{action}={status}" for action, status in outcomes.items()))
    print("context_replay=exact context_lineage=complete raw_canary_leaks=0")


if __name__ == "__main__":
    try:
        run()
    except (DemoFailure, KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
        print(f"Risk context demo failed: {error}", file=sys.stderr)
        raise SystemExit(1)
