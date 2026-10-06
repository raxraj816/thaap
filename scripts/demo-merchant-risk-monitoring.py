#!/usr/bin/env python3
"""Public live/synthetic merchant-risk monitoring and recommendation demo."""

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
        raise DemoFailure("Merchant risk monitoring demo rejects redirects")


def require_loopback(base_url: str) -> str:
    parsed = urllib.parse.urlsplit(base_url.rstrip("/"))
    if parsed.scheme != "http" or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
        raise DemoFailure("Merchant risk monitoring demo accepts only a loopback HTTP API origin")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise DemoFailure("Merchant risk monitoring demo API origin contains prohibited components")
    return base_url.rstrip("/")


def postgres_scalar(query: str) -> str:
    normalized = query.strip().lower()
    if not normalized.startswith("select"):
        raise DemoFailure("Merchant risk monitoring reconciliation accepts only SELECT")
    for prohibited in (" insert ", " update ", " delete ", " alter ", " drop ", " truncate "):
        if prohibited in f" {normalized} ":
            raise DemoFailure("Merchant risk monitoring reconciliation rejected mutating SQL")
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
    request = urllib.request.Request(base_url + path, data=payload,
                                     headers=request_headers, method=method)
    try:
        with opener.open(request, timeout=12) as response:
            raw = response.read()
            if response.status not in (200, 201):
                raise DemoFailure(f"unexpected HTTP {response.status} for {path}")
            return raw, json.loads(raw)
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", "replace")
        raise DemoFailure(f"HTTP {error.code} for {path}: {detail}") from error


def wait_readiness(opener, base_url):
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        try:
            _, health = request_json(opener, base_url, "GET", "/actuator/health/readiness")
            if health.get("status") == "UP":
                return
        except DemoFailure:
            pass
        time.sleep(0.2)
    raise DemoFailure("Merchant risk monitoring API readiness did not become UP")


def post(opener, base_url, path, body, headers, key=None):
    keyed = dict(headers)
    if key is not None:
        keyed["Idempotency-Key"] = key
    return request_json(opener, base_url, "POST", path, body, keyed)


def create_payment(opener, base_url, merchant_headers, prefix, customer_id,
                   method_id, synthetic_token=None):
    headers = dict(merchant_headers)
    if synthetic_token is not None:
        headers["X-Thaap-Scenario-Runner-Token"] = synthetic_token
    _, intent = post(opener, base_url, "/payment_intents",
                     {"customerId": customer_id, "amount": 1200, "currency": "USD",
                      "captureMethod": "MANUAL"}, headers, prefix + "-intent")
    _, attempt = post(opener, base_url, f"/payment_intents/{intent['id']}/confirm",
                      {"paymentMethodId": method_id}, headers, prefix + "-confirm")
    return attempt


def metric(summary, code):
    matches = [item for item in summary["metrics"] if item["metricCode"] == code]
    if len(matches) != 1:
        raise DemoFailure(f"metric catalogue did not return exactly one {code}")
    return matches[0]


def utc(value):
    return value.isoformat(timespec="microseconds").replace("+00:00", "Z")


def run() -> None:
    base_url = require_loopback(os.environ.get("THAAP_BASE_URL", "http://localhost:8080"))
    admin_token = os.environ.get("THAAP_ADMIN_BOOTSTRAP_TOKEN")
    scenario_token = os.environ.get("THAAP_SCENARIO_RUNNER_TOKEN")
    if not admin_token or not scenario_token:
        raise DemoFailure("THAAP_ADMIN_BOOTSTRAP_TOKEN and THAAP_SCENARIO_RUNNER_TOKEN are required")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), RejectRedirects())
    wait_readiness(opener, base_url)
    run_id = f"{int(time.time())}-{uuid.uuid4().hex[:8]}"
    prefix = f"risk-monitoring-{run_id}"

    _, merchant = post(opener, base_url, "/admin/merchants",
                       {"name": f"Merchant risk monitoring {run_id}"},
                       {"X-Admin-Bootstrap-Token": admin_token}, prefix + "-merchant")
    merchant_id = uuid.UUID(merchant["id"])
    merchant_headers = {"X-Api-Key": merchant["apiKey"]}
    _, manager = post(opener, base_url, "/risk/operators", {"role": "RISK_MANAGER"},
                      merchant_headers, prefix + "-manager")
    manager_headers = {"X-Risk-Operator-Key": manager["credential"]}
    customer_email = f"{prefix}@example.test"
    _, customer = post(opener, base_url, "/customers",
                       {"email": customer_email}, merchant_headers)
    _, enumeration_customer = post(opener, base_url, "/customers",
                                   {"email": f"{prefix}-enumeration@example.test"},
                                   merchant_headers)
    methods = {}
    for name, owner, card in (
            ("approved", customer, "4242424242424242"),
            ("declined", customer, "4999990000000002"),
            ("enumeration", enumeration_customer, "4999990000000005")):
        _, methods[name] = post(opener, base_url, "/payment_methods",
                                {"customerId": owner["id"], "cardNumber": card},
                                merchant_headers, f"{prefix}-{name}-method")

    create_payment(opener, base_url, merchant_headers, prefix + "-live-approved",
                   customer["id"], methods["approved"]["id"])
    create_payment(opener, base_url, merchant_headers, prefix + "-live-declined",
                   customer["id"], methods["declined"]["id"])
    create_payment(opener, base_url, merchant_headers, prefix + "-synthetic-approved",
                   customer["id"], methods["approved"]["id"], scenario_token)

    as_of = datetime.datetime.now(datetime.timezone.utc)
    start = as_of - datetime.timedelta(hours=1)
    query = urllib.parse.urlencode({"windowStart": utc(start), "windowEnd": utc(as_of),
                                    "asOf": utc(as_of)})
    _, live = request_json(opener, base_url, "GET", f"/risk/monitoring/summary?{query}",
                           headers=manager_headers)
    _, combined = request_json(
        opener, base_url, "GET",
        f"/risk/monitoring/summary?{query}&trafficOriginPolicy=INCLUDE_SYNTHETIC",
        headers=manager_headers)
    live_approval = metric(live, "AUTHORIZATION_APPROVAL_RATE")
    combined_approval = metric(combined, "AUTHORIZATION_APPROVAL_RATE")
    if (live_approval["numerator"], live_approval["denominator"], live_approval["value"]) != (1, 2, 0.5):
        raise DemoFailure(f"live-only approval truth was {live_approval}")
    if (combined_approval["numerator"], combined_approval["denominator"]) != (2, 3):
        raise DemoFailure(f"synthetic-inclusive approval truth was {combined_approval}")
    _, approval_cohort = request_json(
        opener, base_url, "GET",
        f"/risk/monitoring/metrics/AUTHORIZATION_APPROVAL_RATE/cohort?{query}&limit=100",
        headers=manager_headers)
    approval_rows = approval_cohort.get("rows", [])
    cohort_numerator = sum(1 for row in approval_rows if row.get("numeratorMember"))
    cohort_denominator = sum(1 for row in approval_rows if row.get("denominatorMember"))
    if approval_cohort.get("nextCursor") is not None or (
            cohort_numerator, cohort_denominator) != (1, 2):
        raise DemoFailure(f"approval drill-down did not reconcile: {approval_cohort}")
    safe_cohort_bytes = json.dumps(approval_cohort, separators=(",", ":"))
    for prohibited in (merchant["apiKey"], customer_email, "4242424242424242",
                       "4999990000000002", "fingerprint", "identity"):
        if prohibited and prohibited in safe_cohort_bytes:
            raise DemoFailure("approval drill-down disclosed prohibited payment identity")

    for index in range(17):
        if index == 0:
            evidence_customer = enumeration_customer
            evidence_method = methods["enumeration"]
        else:
            _, evidence_customer = post(
                opener, base_url, "/customers",
                {"email": f"{prefix}-enumeration-{index}@example.test"}, merchant_headers)
            _, evidence_method = post(
                opener, base_url, "/payment_methods",
                {"customerId": evidence_customer["id"],
                 "cardNumber": f"499999{index:06d}0005"},
                merchant_headers, f"{prefix}-enumeration-{index}-method")
        create_payment(opener, base_url, merchant_headers, f"{prefix}-enumeration-{index}",
                       evidence_customer["id"], evidence_method["id"])
    _, denominator_customer = post(
        opener, base_url, "/customers",
        {"email": f"{prefix}-denominator@example.test"}, merchant_headers)
    _, denominator_method = post(
        opener, base_url, "/payment_methods",
        {"customerId": denominator_customer["id"], "cardNumber": "4999990000010001"},
        merchant_headers, prefix + "-denominator-method")
    create_payment(opener, base_url, merchant_headers, prefix + "-denominator-approved",
                   denominator_customer["id"], denominator_method["id"])
    snapshot_as_of = datetime.datetime.now(datetime.timezone.utc)
    snapshot_body = {"windowStart": utc(start), "windowEnd": utc(snapshot_as_of),
                     "asOf": utc(snapshot_as_of), "trafficOriginPolicy": "MERCHANT_API_ONLY"}
    first_raw, snapshot = post(opener, base_url, "/risk/monitoring/snapshots", snapshot_body,
                               manager_headers, prefix + "-snapshot")
    replay_raw, _ = post(opener, base_url, "/risk/monitoring/snapshots", snapshot_body,
                         manager_headers, prefix + "-snapshot")
    if first_raw != replay_raw:
        raise DemoFailure("monitoring snapshot replay was not byte-exact")
    recommendation = snapshot.get("recommendation") or {}
    if (recommendation.get("recommendedTier"), recommendation.get("reasonMetricCode"),
            recommendation.get("automaticallyActivated")) != ("HIGH", "ENUMERATION_RATIO", False):
        raise DemoFailure(f"non-activating recommendation was {recommendation}")

    snapshot_id = uuid.UUID(snapshot["id"])
    reconciliation = postgres_scalar(f"""
        SELECT
          (SELECT COUNT(*) FROM fraud_taxonomy_definitions
            WHERE taxonomy_version = 'fraud_taxonomy_v1') || '|' ||
          (SELECT COUNT(*) FROM payment_attempts attempt
            WHERE attempt.merchant_id = '{merchant_id}'::uuid
              AND attempt.traffic_origin = 'MERCHANT_API') || '|' ||
          (SELECT COUNT(*) FROM merchant_risk_metric_snapshots
            WHERE merchant_id = '{merchant_id}'::uuid AND id = '{snapshot_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM merchant_risk_profile_recommendations
            WHERE merchant_id = '{merchant_id}'::uuid AND metric_snapshot_id = '{snapshot_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM merchant_risk_profile_events
            WHERE merchant_id = '{merchant_id}'::uuid);
    """)
    if reconciliation != "18|20|1|1|0":
        raise DemoFailure(f"merchant risk monitoring durable reconciliation failed: {reconciliation}")

    print("Merchant risk monitoring demo passed.")
    print("live_approval=1/2 synthetic_inclusive_approval=2/3")
    print("approval_drilldown=1/2 bounded_safe_cohort=proved")
    print("taxonomy=fraud_taxonomy_v1 mappings=18 recommendation=HIGH:ENUMERATION_RATIO")
    print("profile_auto_activation=0 replay=byte_exact durable_reconciliation=proved readiness=UP")


if __name__ == "__main__":
    try:
        run()
    except (DemoFailure, KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
        print(f"Merchant risk monitoring demo failed: {error}", file=sys.stderr)
        raise SystemExit(1)
