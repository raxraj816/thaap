#!/usr/bin/env python3
"""Public queue-productivity and decision-outcome insight demo.

Walks the journey the insight surface exists for: what work is waiting, how it moved, what
outcomes are known, and — the part most dashboards skip — what remains unknown. Every rate is
shown with its exact numerator, denominator, and unknown count, money stays separated by
currency, and the two authorities keep their own timestamps.

Every effect travels the same HTTP routes a customer would use. The database is read only to
reconcile, never to seed.
"""

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
        raise DemoFailure("Insight demo rejects redirects")


def require_loopback(base_url: str) -> str:
    parsed = urllib.parse.urlsplit(base_url)
    if parsed.scheme != "http" or parsed.hostname not in ("localhost", "127.0.0.1", "::1"):
        raise DemoFailure("Insight demo requires a loopback http origin")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise DemoFailure("Insight demo API origin contains prohibited components")
    return base_url.rstrip("/")


def postgres_scalar(query: str) -> str:
    normalized = query.strip().lower()
    if not normalized.startswith("select"):
        raise DemoFailure("Insight reconciliation accepts only SELECT")
    for prohibited in (" insert ", " update ", " delete ", " alter ", " drop ", " truncate "):
        if prohibited in f" {normalized} ":
            raise DemoFailure("Insight reconciliation rejected mutating SQL")
    command = [
        "docker", "exec", "-i", "thaap-postgres", "psql", "-X", "-A", "-t",
        "-U", os.environ.get("THAAP_POSTGRES_USER", "thaap"),
        "-d", os.environ.get("THAAP_POSTGRES_DB", "thaap_payments"), "-c", query,
    ]
    try:
        result = subprocess.run(command, check=True, capture_output=True, text=True, timeout=10)
    except (FileNotFoundError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        raise DemoFailure(f"Insight reconciliation query failed: {error}") from error
    return result.stdout.strip()


def request_json(opener, base_url, method, path, body=None, headers=None, expected=(200, 201)):
    payload = None if body is None else json.dumps(body, separators=(",", ":")).encode()
    request_headers = {"Accept": "application/json", **(headers or {})}
    if payload is not None:
        request_headers["Content-Type"] = "application/json"
    request = urllib.request.Request(base_url + path, data=payload,
                                     headers=request_headers, method=method)
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


def post(opener, base_url, path, body, headers, key=None, expected=(200, 201, 202)):
    request_headers = dict(headers)
    if key is not None:
        request_headers["Idempotency-Key"] = key
    return request_json(opener, base_url, "POST", path, body, request_headers, expected)


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
    raise DemoFailure("Insight API readiness did not become UP")


def confirmed_payment(opener, base_url, merchant_headers, prefix, customer_id, method_id,
                      label, amount, currency="USD", confirmations=1):
    _, intent = post(opener, base_url, "/payment_intents",
                     {"customerId": customer_id, "amount": amount, "currency": currency,
                      "captureMethod": "MANUAL"}, merchant_headers, f"{prefix}-{label}-intent")
    for attempt in range(1, confirmations + 1):
        post(opener, base_url, f"/payment_intents/{intent['id']}/confirm",
             {"paymentMethodId": method_id}, merchant_headers,
             f"{prefix}-{label}-confirm-{attempt}", expected=(200, 201, 409))
    return intent["id"]


def run() -> None:
    base_url = require_loopback(os.environ.get("THAAP_BASE_URL", "http://localhost:8080"))
    admin_token = os.environ.get("THAAP_ADMIN_BOOTSTRAP_TOKEN")
    if not admin_token:
        raise DemoFailure("THAAP_ADMIN_BOOTSTRAP_TOKEN is required")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), RejectRedirects())
    wait_readiness(opener, base_url)
    run_id = f"{int(time.time())}-{uuid.uuid4().hex[:8]}"
    prefix = f"workspace-insights-{run_id}"

    _, merchant = post(opener, base_url, "/admin/merchants",
                       {"name": f"Workspace insights {run_id}"},
                       {"X-Admin-Bootstrap-Token": admin_token}, prefix + "-merchant")
    merchant_id = uuid.UUID(merchant["id"])
    merchant_headers = {"X-Api-Key": merchant["apiKey"]}
    _, manager = post(opener, base_url, "/risk/operators", {"role": "RISK_MANAGER"},
                      merchant_headers, prefix + "-manager")
    manager_headers = {"X-Risk-Operator-Key": manager["credential"]}
    _, viewer = post(opener, base_url, "/risk/operators", {"role": "VIEWER"},
                     merchant_headers, prefix + "-viewer")
    viewer_headers = {"X-Risk-Operator-Key": viewer["credential"]}
    _, customer = post(opener, base_url, "/customers",
                       {"email": f"{prefix}@example.test"}, merchant_headers)
    _, method = post(opener, base_url, "/payment_methods",
                     {"customerId": customer["id"], "cardNumber": "4999990000000003"},
                     merchant_headers, prefix + "-method")

    confirmed_payment(opener, base_url, merchant_headers, prefix, customer["id"], method["id"],
                      "allowed", 5000)
    confirmed_payment(opener, base_url, merchant_headers, prefix, customer["id"], method["id"],
                      "blocked", 6000000)
    confirmed_payment(opener, base_url, merchant_headers, prefix, customer["id"], method["id"],
                      "reviewed", 99999, confirmations=3)
    confirmed_payment(opener, base_url, merchant_headers, prefix, customer["id"], method["id"],
                      "euro", 7000, currency="EUR")

    # --- What work is waiting, from authoritative review rows -------------------------------
    _, reviews = request_json(opener, base_url, "GET",
                              "/risk/workspace/insights/review-operations",
                              headers=manager_headers)
    if reviews["authority"] != "AUTHORITATIVE_REVIEW_SOURCE":
        raise DemoFailure("review operations did not name its authority")
    if "freshness" in reviews:
        raise DemoFailure("review operations borrowed a projection freshness")
    backlog = reviews["backlog"]
    if backlog["unassigned"] + backlog["assigned"] != backlog["total"]:
        raise DemoFailure("backlog buckets did not partition the population exactly once")
    source_backlog = postgres_scalar(f"""
        SELECT COUNT(*) FROM manual_review_cases
        WHERE merchant_id = '{merchant_id}'::uuid AND status IN ('OPEN', 'ASSIGNED');
    """)
    if str(backlog["total"]) != source_backlog:
        raise DemoFailure(
            f"backlog {backlog['total']} disagrees with authoritative rows {source_backlog}")
    if reviews["conflictedAttempts"] is not None:
        raise DemoFailure("conflict attempts were reported as a number the source cannot support")

    # --- What outcomes are known, from the active projection ---------------------------------
    _, decisions = request_json(opener, base_url, "GET",
                                "/risk/workspace/insights/decision-outcomes",
                                headers=manager_headers)
    if "sourceAsOf" in decisions:
        raise DemoFailure("decision outcomes claimed a source as-of it does not have")
    if decisions["claimBoundary"] != "DESCRIPTIVE_NOT_CAUSAL":
        raise DemoFailure("decision outcomes did not state its claim boundary")

    # Every rate publishes its own arithmetic, and an empty denominator says so.
    for name, rate in decisions["rates"].items():
        if rate["denominator"] == 0 and rate["valueBasisPoints"] is not None:
            raise DemoFailure(f"{name} published a value over an empty denominator")
        if rate["denominator"] > 0 and rate["numerator"] > rate["denominator"]:
            raise DemoFailure(f"{name} numerator exceeds its denominator")

    # Money stays separated by currency; no total across currencies exists anywhere.
    currencies = [row["currency"] for row in decisions["moneyByCurrency"]]
    if len(currencies) != len(set(currencies)):
        raise DemoFailure("a currency appeared twice in the money breakdown")
    for forbidden in ("totalAmountMinor", "grandTotal"):
        if forbidden in json.dumps(decisions):
            raise DemoFailure(f"insights published a blended total field {forbidden}")

    # --- Drill-through reproduces exactly the segment it came from ---------------------------
    drilled = 0
    for segment in decisions["decisionDistribution"]:
        drill = segment.get("drillThrough")
        if not drill:
            continue
        for prohibited in ("merchantId", "operatorId", "generationId", "paymentIntentId"):
            if prohibited in json.dumps(drill):
                raise DemoFailure(f"drill-through descriptor carried {prohibited}")
        _, page = post(opener, base_url, "/risk/workspace/payments/search?limit=50",
                       {"filter": {"fraudActions": drill["fraudActions"]}}, manager_headers)
        if len(page["data"]) != segment["count"]:
            raise DemoFailure(
                f"drill-through for {segment['code']} returned {len(page['data'])} rows "
                f"but the card claimed {segment['count']}")
        drilled += 1
    if drilled == 0:
        raise DemoFailure("no segment offered a drill-through, so the journey was not shown")

    # --- Role boundaries --------------------------------------------------------------------
    _, viewer_reviews = request_json(opener, base_url, "GET",
                                     "/risk/workspace/insights/review-operations",
                                     headers=viewer_headers)
    if viewer_reviews.get("ownWorkload") is not None:
        raise DemoFailure("a viewer received an individual workload block")
    request_json(opener, base_url, "GET", "/risk/workspace/insights/operators",
                 headers=viewer_headers, expected=(403,))

    print(json.dumps({
        "demo": "workspace-insights",
        "reviewOperations": {"authority": reviews["authority"], "backlog": backlog,
                             "conflictAttempts": reviews["conflictedAttemptsStatus"]},
        "decisionOutcomes": {"claimBoundary": decisions["claimBoundary"],
                             "distribution": decisions["decisionDistribution"],
                             "currencies": currencies},
        "drillThroughSegmentsReconciled": drilled,
    }, indent=2, default=str))


if __name__ == "__main__":
    try:
        run()
    except DemoFailure as failure:
        print(f"DEMO FAILED: {failure}", file=sys.stderr)
        sys.exit(1)
