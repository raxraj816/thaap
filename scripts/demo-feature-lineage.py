#!/usr/bin/env python3
"""Public typed feature-lineage demo with read-only durable reconciliation."""

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
from demo_feature_contract import BASE_FEATURE_CODES


EXPECTED_FEATURES = {
    "card.country": ("STRING", "US"),
    "card.funding": ("STRING", "CREDIT"),
    "merchant.risk_tier": ("STRING", "STANDARD"),
    "payment.amount_minor": ("LONG", "4200"),
    "payment.attempt_number": ("LONG", "1"),
    "payment.capture_method": ("STRING", "MANUAL"),
    "payment.currency": ("STRING", "USD"),
    "risk.context_present": ("BOOLEAN", "false"),
    "traffic.origin": ("STRING", "MERCHANT_API"),
    "velocity.card_attempts_10m": ("LONG", "0"),
    "velocity.customer_attempts_1h": ("LONG", "0"),
    "velocity.merchant_attempts_1m": ("LONG", "0"),
}
FORBIDDEN_TEXT = (
    "card_bin", "card_last4", "fingerprint", "payment_intent_id",
    "payment_attempt_id", "payment_method_id", "metadata", "description",
    "idempotency", "499999", "0001",
)


class DemoFailure(RuntimeError):
    pass


def require_feature_catalogue(features: dict) -> None:
    if set(EXPECTED_FEATURES) != BASE_FEATURE_CODES:
        raise DemoFailure("typed fixture expectations disagree with the governed no-context feature contract")
    if list(features) != sorted(EXPECTED_FEATURES):
        raise DemoFailure("snapshot does not contain the exact lexical current fixture catalogue")


def require_durable_lineage(rows: list[tuple[str, str, str, str]]) -> None:
    actual = {code: (value_type, value) for code, value_type, value, point_in_time in rows}
    if len(rows) != len(EXPECTED_FEATURES) or actual != EXPECTED_FEATURES \
            or any(point_in_time != "true" for *_, point_in_time in rows):
        raise DemoFailure("durable typed lineage did not reconcile with the exact fixture")


class RejectRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        raise DemoFailure("Feature lineage demo rejects redirects")


def client() -> urllib.request.OpenerDirector:
    return urllib.request.build_opener(urllib.request.ProxyHandler({}), RejectRedirects())


def request_json(opener: urllib.request.OpenerDirector, base_url: str, method: str, path: str,
                 body: dict | None = None, headers: dict[str, str] | None = None) -> tuple[bytes, dict]:
    payload = None if body is None else json.dumps(body, separators=(",", ":")).encode("utf-8")
    request_headers = {"Accept": "application/json", **(headers or {})}
    if payload is not None:
        request_headers["Content-Type"] = "application/json"
    request = urllib.request.Request(base_url + path, data=payload, headers=request_headers, method=method)
    try:
        with opener.open(request, timeout=10) as response:
            raw = response.read()
            if response.status not in (200, 201):
                raise DemoFailure(f"unexpected HTTP status {response.status} for {path}")
            return raw, json.loads(raw)
    except urllib.error.HTTPError as error:
        raise DemoFailure(f"HTTP {error.code} for {path}: {error.read().decode('utf-8', 'replace')}") from error


def require_loopback(base_url: str) -> str:
    parsed = urllib.parse.urlsplit(base_url.rstrip("/"))
    if parsed.scheme != "http" or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
        raise DemoFailure("Feature lineage demo accepts only a loopback HTTP API origin")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise DemoFailure("Feature lineage demo API origin contains prohibited components")
    return base_url.rstrip("/")


def postgres_rows(payment_attempt_id: uuid.UUID) -> list[tuple[str, str, str, str]]:
    user = os.environ.get("THAAP_POSTGRES_USER", "thaap")
    database = os.environ.get("THAAP_POSTGRES_DB", "thaap_payments")
    query = f"""
        SELECT fv.feature_code, fv.value_type,
               COALESCE(fv.long_value::text, fv.string_value,
                        fv.boolean_value::text, fv.decimal_value::text),
               (fv.observed_at <= fd.decision_started_at)::text
        FROM feature_values fv
        JOIN fraud_decisions fd
          ON fd.merchant_id = fv.merchant_id
         AND fd.payment_attempt_id = fv.payment_attempt_id
        WHERE fv.payment_attempt_id = '{payment_attempt_id}'::uuid
        ORDER BY fv.feature_code
    """
    command = [
        "docker", "exec", "-i", os.environ.get("THAAP_POSTGRES_CONTAINER", "thaap-local-postgres-1"), "psql", "-X", "-A", "-t",
        "-F", "|", "-U", user, "-d", database, "-c", query,
    ]
    try:
        completed = subprocess.run(command, check=True, capture_output=True, text=True, timeout=10)
    except (FileNotFoundError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        raise DemoFailure(
            "read-only lineage reconciliation requires the local thaap-postgres container"
        ) from error
    rows = []
    for line in completed.stdout.splitlines():
        if line.strip():
            parts = line.split("|")
            if len(parts) != 4:
                raise DemoFailure("unexpected PostgreSQL reconciliation row")
            rows.append(tuple(parts))
    return rows


def run() -> None:
    base_url = require_loopback(os.environ.get("THAAP_BASE_URL", "http://localhost:8080"))
    admin_token = os.environ.get("THAAP_ADMIN_BOOTSTRAP_TOKEN")
    if not admin_token:
        raise DemoFailure("THAAP_ADMIN_BOOTSTRAP_TOKEN is required")
    opener = client()
    request_json(opener, base_url, "GET", "/actuator/health")
    run_id = f"{int(time.time())}-{uuid.uuid4().hex[:8]}"

    _, merchant = request_json(opener, base_url, "POST", "/admin/merchants",
                               {"name": f"Feature lineage Demo {run_id}"},
                               {"X-Admin-Bootstrap-Token": admin_token,
                                "Idempotency-Key": f"feature-lineage-{run_id}-merchant"})
    api_key = merchant["apiKey"]
    merchant_headers = {"X-Api-Key": api_key}
    _, customer = request_json(opener, base_url, "POST", "/customers",
                               {"email": f"feature-lineage-{run_id}@example.com"}, merchant_headers)
    _, method = request_json(opener, base_url, "POST", "/payment_methods",
                             {"customerId": customer["id"], "cardNumber": "4999990000000001"},
                             {**merchant_headers, "Idempotency-Key": f"feature-lineage-{run_id}-method"})
    _, intent = request_json(opener, base_url, "POST", "/payment_intents",
                             {"customerId": customer["id"], "amount": 4200, "currency": "USD",
                              "captureMethod": "MANUAL", "description": "must not become a feature",
                              "metadata": {"unsafeFeature": "must-not-appear"}},
                             {**merchant_headers, "Idempotency-Key": f"feature-lineage-{run_id}-intent"})
    confirm_headers = {**merchant_headers, "Idempotency-Key": f"feature-lineage-{run_id}-confirm"}
    confirm_body = {"paymentMethodId": method["id"]}
    original_bytes, attempt = request_json(
        opener, base_url, "POST", f"/payment_intents/{intent['id']}/confirm", confirm_body, confirm_headers)
    replay_bytes, replay = request_json(
        opener, base_url, "POST", f"/payment_intents/{intent['id']}/confirm", confirm_body, confirm_headers)
    if replay_bytes != original_bytes or replay != attempt:
        raise DemoFailure("idempotent replay did not return the exact original response")
    _, detail = request_json(opener, base_url, "GET", f"/fraud/decisions/{attempt['fraudDecisionId']}",
                             headers=merchant_headers)

    decision = detail["decision"]
    snapshot = detail["featureSnapshot"]
    if decision["action"] != "ALLOW" or decision["rulesetVersion"] != "rules_v1":
        raise DemoFailure("current decision behavior must preserve the Feature lineage typed lineage boundary")
    if len(detail["ruleExecutionResults"]) != 3:
        raise DemoFailure("current rules_v1 decision must preserve three complete rule results")
    require_feature_catalogue(snapshot["features"])
    public_text = json.dumps(detail, separators=(",", ":"), sort_keys=True).lower()
    if any(forbidden in public_text for forbidden in FORBIDDEN_TEXT):
        raise DemoFailure("decision detail contains a prohibited feature surface")

    attempt_id = uuid.UUID(attempt["id"])
    rows = postgres_rows(attempt_id)
    require_durable_lineage(rows)
    if attempt["status"] != "AUTHORIZED" or not attempt.get("processorAuthorizationId"):
        raise DemoFailure("ALLOW processor behavior changed")

    print("Typed feature-lineage demo passed.")
    print(f"merchant={merchant['id']}")
    print(f"payment_intent={intent['id']}")
    print(f"payment_attempt={attempt['id']} status={attempt['status']}")
    print(f"fraud_decision={attempt['fraudDecisionId']} action={decision['action']}")
    print(f"typed_features={len(EXPECTED_FEATURES)} point_in_time=true replay=exact prohibited_surfaces=absent")


if __name__ == "__main__":
    try:
        run()
    except (DemoFailure, KeyError, ValueError, json.JSONDecodeError) as error:
        print(f"Feature lineage demo failed: {error}", file=sys.stderr)
        raise SystemExit(1)
