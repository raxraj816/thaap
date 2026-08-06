#!/usr/bin/env python3
"""Public payment investigation and decision explanation demo."""

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
        raise DemoFailure("Payment investigation demo rejects redirects")


def require_loopback(base_url: str) -> str:
    parsed = urllib.parse.urlsplit(base_url)
    if parsed.scheme != "http" or parsed.hostname not in ("localhost", "127.0.0.1", "::1"):
        raise DemoFailure("Payment investigation demo requires a loopback http origin")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise DemoFailure("Payment investigation demo API origin contains prohibited components")
    return base_url.rstrip("/")


def postgres_scalar(query: str) -> str:
    normalized = query.strip().lower()
    if not normalized.startswith("select"):
        raise DemoFailure("Payment investigation reconciliation accepts only SELECT")
    for prohibited in (" insert ", " update ", " delete ", " alter ", " drop ", " truncate "):
        if prohibited in f" {normalized} ":
            raise DemoFailure("Payment investigation reconciliation rejected mutating SQL")
    command = [
        "docker", "exec", "-i", "thaap-postgres", "psql", "-X", "-A", "-t",
        "-U", os.environ.get("THAAP_POSTGRES_USER", "thaap"),
        "-d", os.environ.get("THAAP_POSTGRES_DB", "thaap_payments"), "-c", query,
    ]
    try:
        result = subprocess.run(command, check=True, capture_output=True, text=True, timeout=10)
    except (FileNotFoundError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        raise DemoFailure(f"Payment investigation reconciliation query failed: {error}") from error
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


def post(opener, base_url, path, body, headers, key=None, expected=(200, 201)):
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
    raise DemoFailure("Payment investigation API readiness did not become UP")


def source_fingerprint(merchant_id: uuid.UUID) -> str:
    """Digest of every row the investigation read touches, so a read that writes is detectable."""
    return postgres_scalar(f"""
        SELECT
          (SELECT COUNT(*) FROM payment_intents WHERE merchant_id = '{merchant_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM payment_attempts WHERE merchant_id = '{merchant_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM fraud_decisions WHERE merchant_id = '{merchant_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM rule_execution_results WHERE merchant_id = '{merchant_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM manual_review_cases WHERE merchant_id = '{merchant_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM fraud_labels WHERE merchant_id = '{merchant_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM ledger_entries WHERE merchant_id = '{merchant_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM domain_events WHERE merchant_id = '{merchant_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM idempotency_records) || '|' ||
          (SELECT COALESCE(MAX(stream_version), 0) FROM domain_events
            WHERE merchant_id = '{merchant_id}'::uuid) || '|' ||
          (SELECT COALESCE(MD5(STRING_AGG(id::text || status || amount_minor::text,
                                          ',' ORDER BY id)), 'empty')
             FROM payment_intents WHERE merchant_id = '{merchant_id}'::uuid);
    """)


def confirmed_payment(opener, base_url, merchant_headers, prefix, customer_id, method_id,
                      label, amount, confirmations=1):
    _, intent = post(opener, base_url, "/payment_intents",
                     {"customerId": customer_id, "amount": amount,
                      "currency": "USD", "captureMethod": "MANUAL"},
                     merchant_headers, f"{prefix}-{label}-intent")
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
    prefix = f"payment-investigation-{run_id}"

    _, merchant = post(opener, base_url, "/admin/merchants",
                       {"name": f"Payment investigation {run_id}"},
                       {"X-Admin-Bootstrap-Token": admin_token}, prefix + "-merchant")
    merchant_id = uuid.UUID(merchant["id"])
    merchant_headers = {"X-Api-Key": merchant["apiKey"]}
    _, manager = post(opener, base_url, "/risk/operators", {"role": "RISK_MANAGER"},
                      merchant_headers, prefix + "-manager")
    manager_headers = {"X-Risk-Operator-Key": manager["credential"]}
    _, customer = post(opener, base_url, "/customers",
                       {"email": f"{prefix}@example.test"}, merchant_headers)
    _, method = post(opener, base_url, "/payment_methods",
                     {"customerId": customer["id"], "cardNumber": "4999990000000003"},
                     merchant_headers, prefix + "-method")

    allowed = confirmed_payment(opener, base_url, merchant_headers, prefix,
                                customer["id"], method["id"], "allowed", 5000)
    reviewed = confirmed_payment(opener, base_url, merchant_headers, prefix,
                                 customer["id"], method["id"], "reviewed", 99999, 3)
    blocked = confirmed_payment(opener, base_url, merchant_headers, prefix,
                                customer["id"], method["id"], "blocked", 6000000)

    before = source_fingerprint(merchant_id)

    search_body = {"filter": {"fraudActions": ["BLOCK", "REVIEW", "ALLOW"]},
                   "sort": "CREATED_AT_DESC"}
    _, page = post(opener, base_url, "/risk/workspace/payments/search?limit=2",
                   search_body, manager_headers)
    if len(page["data"]) != 2 or not page["nextCursor"]:
        raise DemoFailure("bounded investigation search did not page")
    _, second = post(opener, base_url,
                     f"/risk/workspace/payments/search?limit=2"
                     f"&cursor={urllib.parse.quote(page['nextCursor'], safe='')}",
                     search_body, manager_headers)
    found = {row["paymentIntentId"] for row in page["data"] + second["data"]}
    if not {allowed, reviewed, blocked} <= found:
        raise DemoFailure("investigation search did not reach every seeded payment")

    reordered = {"filter": {"fraudActions": ["ALLOW", "REVIEW", "BLOCK"]},
                 "sort": "CREATED_AT_DESC"}
    _, echoed = post(opener, base_url, "/risk/workspace/payments/search?limit=2",
                     reordered, manager_headers)
    if echoed["canonicalFilterJson"] != page["canonicalFilterJson"]:
        raise DemoFailure("equivalent filters did not canonicalize to one meaning")

    _, detail = request_json(opener, base_url, "GET",
                             f"/risk/workspace/payments/{blocked}", headers=manager_headers)
    explanation = detail["decisionExplanation"]
    if explanation["finalAction"] != "BLOCK":
        raise DemoFailure("blocked payment did not explain a BLOCK decision")
    if explanation["modelState"] != "NOT_CONFIGURED" or "modelScore" in explanation:
        raise DemoFailure("decision explanation implied a model the platform does not have")
    winning = [control for control in explanation["controlEvaluations"]
               if control["contributedToWinningAction"]]
    losing = [control for control in explanation["controlEvaluations"]
              if not control["contributedToWinningAction"]]
    if len(winning) != 1 or not losing:
        raise DemoFailure("explanation hid the controls that did not win")
    if detail["sourceSnapshot"]["authority"] != "SOURCE_DATABASE":
        raise DemoFailure("detail did not name the authoritative source")
    for section, status in detail["sectionStatuses"].items():
        if not status.get("authority"):
            raise DemoFailure(f"section {section} reported no authority")

    _, timeline = request_json(opener, base_url, "GET",
                               f"/risk/workspace/payments/{reviewed}/timeline",
                               headers=manager_headers)
    versions = [entry["streamVersion"] for entry in timeline["data"]]
    if versions != sorted(versions) or max(versions) > timeline["maximumStreamVersion"]:
        raise DemoFailure("timeline was not canonically ordered beneath its declared ceiling")

    _, related = request_json(opener, base_url, "GET",
                              f"/risk/workspace/payments/{reviewed}/related",
                              headers=manager_headers)
    if related["scope"] != "SAME_MERCHANT_SERVER_DERIVED":
        raise DemoFailure("related payments did not declare a same-merchant scope")
    if any(row["paymentIntentId"] == reviewed for row in related["data"]):
        raise DemoFailure("related payments returned the seed payment")

    _, webhooks = request_json(opener, base_url, "GET",
                               f"/risk/workspace/payments/{reviewed}/webhook-activity",
                               headers=manager_headers)
    if webhooks["evidenceAuthority"] != "WEBHOOK_TRANSPORT_EVIDENCE":
        raise DemoFailure("webhook activity was presented as money or fraud truth")

    _, other = post(opener, base_url, "/admin/merchants",
                    {"name": f"Payment investigation neighbour {run_id}"},
                    {"X-Admin-Bootstrap-Token": admin_token}, prefix + "-neighbour")
    _, neighbour = post(opener, base_url, "/risk/operators", {"role": "RISK_MANAGER"},
                        {"X-Api-Key": other["apiKey"]}, prefix + "-neighbour-manager")
    _, denied = request_json(opener, base_url, "GET", f"/risk/workspace/payments/{blocked}",
                             headers={"X-Risk-Operator-Key": neighbour["credential"]},
                             expected=(404,))
    _, absent = request_json(opener, base_url, "GET",
                             f"/risk/workspace/payments/{uuid.uuid4()}",
                             headers={"X-Risk-Operator-Key": neighbour["credential"]},
                             expected=(404,))
    if denied.get("error") != absent.get("error"):
        raise DemoFailure("a foreign payment was distinguishable from an absent payment")

    after = source_fingerprint(merchant_id)
    if before != after:
        raise DemoFailure("the investigation read changed authoritative source state")

    print(json.dumps({
        "merchantId": str(merchant_id),
        "searchedPayments": len(found),
        "explainedAction": explanation["finalAction"],
        "winningControl": winning[0]["code"],
        "retainedLosingControls": len(losing),
        "timelineCeiling": timeline["maximumStreamVersion"],
        "relatedScope": related["scope"],
        "sourceFingerprintStable": True,
    }, indent=2))


if __name__ == "__main__":
    try:
        run()
    except DemoFailure as failure:
        print(f"payment investigation demo failed: {failure}", file=sys.stderr)
        sys.exit(1)
