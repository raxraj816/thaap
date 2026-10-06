#!/usr/bin/env python3
"""Public Phase 3 demo with read-only durable decision reconciliation."""

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


class DemoFailure(RuntimeError):
    pass


class RejectRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        raise DemoFailure("Phase 3 demo rejects redirects")


def client() -> urllib.request.OpenerDirector:
    return urllib.request.build_opener(urllib.request.ProxyHandler({}), RejectRedirects())


def require_loopback(base_url: str) -> str:
    parsed = urllib.parse.urlsplit(base_url.rstrip("/"))
    if parsed.scheme != "http" or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
        raise DemoFailure("Phase 3 demo accepts only a loopback HTTP API origin")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise DemoFailure("Phase 3 demo API origin contains prohibited components")
    return base_url.rstrip("/")


def request_json(opener: urllib.request.OpenerDirector, base_url: str, method: str, path: str,
                 body: dict | None = None, headers: dict[str, str] | None = None) -> tuple[bytes, object]:
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


def postgres_scalar(query: str) -> str:
    user = os.environ.get("THAAP_POSTGRES_USER", "thaap")
    database = os.environ.get("THAAP_POSTGRES_DB", "thaap_payments")
    command = ["docker", "exec", "-i", os.environ.get("THAAP_POSTGRES_CONTAINER", "thaap-local-postgres-1"), "psql", "-X", "-A", "-t",
               "-U", user, "-d", database, "-c", query]
    try:
        result = subprocess.run(command, check=True, capture_output=True, text=True, timeout=10)
    except (FileNotFoundError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        raise DemoFailure("read-only reconciliation requires the local thaap-postgres container") from error
    return result.stdout.strip()


def create_intent(opener, base_url, headers, customer_id, amount, key):
    _, intent = request_json(opener, base_url, "POST", "/payment_intents",
                             {"customerId": customer_id, "amount": amount, "currency": "USD",
                              "captureMethod": "MANUAL"},
                             {**headers, "Idempotency-Key": key})
    return intent


def confirm(opener, base_url, headers, intent_id, method_id, key):
    request_headers = {**headers, "Idempotency-Key": key}
    body = {"paymentMethodId": method_id}
    original, attempt = request_json(opener, base_url, "POST", f"/payment_intents/{intent_id}/confirm",
                                     body, request_headers)
    replay, repeated = request_json(opener, base_url, "POST", f"/payment_intents/{intent_id}/confirm",
                                    body, request_headers)
    if replay != original or repeated != attempt:
        raise DemoFailure("confirm replay was not byte-exact")
    return attempt


def assert_decision(opener, base_url, headers, attempt, action, matched_codes):
    _, detail = request_json(opener, base_url, "GET",
                             f"/fraud/decisions/{attempt['fraudDecisionId']}", headers=headers)
    if detail["decision"]["action"] != action or detail["decision"]["rulesetVersion"] != "rules_v1":
        raise DemoFailure(f"unexpected decision for {action}")
    results = detail["ruleExecutionResults"]
    if len(results) != 3:
        raise DemoFailure("decision does not contain exactly three rule results")
    actual_matches = [result["ruleCode"] for result in results if result["matched"]]
    if actual_matches != matched_codes:
        raise DemoFailure(f"unexpected matched rules for {action}: {actual_matches}")
    if set(detail["featureSnapshot"]["features"]) != BASE_FEATURE_CODES:
        raise DemoFailure("decision does not contain the exact current no-context governed feature set")


def run() -> None:
    base_url = require_loopback(os.environ.get("THAAP_BASE_URL", "http://localhost:8080"))
    admin_token = os.environ.get("THAAP_ADMIN_BOOTSTRAP_TOKEN")
    if not admin_token:
        raise DemoFailure("THAAP_ADMIN_BOOTSTRAP_TOKEN is required")
    opener = client()
    request_json(opener, base_url, "GET", "/actuator/health")
    run_id = f"{int(time.time())}-{uuid.uuid4().hex[:8]}"
    _, merchant = request_json(opener, base_url, "POST", "/admin/merchants",
                               {"name": f"Phase 3 Demo {run_id}"},
                               {"X-Admin-Bootstrap-Token": admin_token,
                                "Idempotency-Key": f"phase3-{run_id}-merchant"})
    headers = {"X-Api-Key": merchant["apiKey"]}
    _, customer = request_json(opener, base_url, "POST", "/customers",
                               {"email": f"phase3-{run_id}@example.com"}, headers)
    _, approved_method = request_json(opener, base_url, "POST", "/payment_methods",
                                      {"customerId": customer["id"], "cardNumber": "4242424242424242"},
                                      {**headers, "Idempotency-Key": f"phase3-{run_id}-approved-method"})
    # This cohort needs two genuine declines before the repeated-attempt rule wins. Sharing
    # the prior customer's three attempts would correctly trigger velocity REVIEW too early.
    _, review_customer = request_json(opener, base_url, "POST", "/customers",
                                      {"email": f"fraud-review-{run_id}@example.test"}, headers)
    _, decline_method = request_json(opener, base_url, "POST", "/payment_methods",
                                     {"customerId": review_customer["id"], "cardNumber": "4999990000000003"},
                                     {**headers, "Idempotency-Key": f"phase3-{run_id}-decline-method"})

    allow_intent = create_intent(opener, base_url, headers, customer["id"], 4_200,
                                 f"phase3-{run_id}-allow-intent")
    allow = confirm(opener, base_url, headers, allow_intent["id"], approved_method["id"],
                    f"phase3-{run_id}-allow-confirm")
    if allow["status"] != "AUTHORIZED" or not allow["processorAuthorizationId"]:
        raise DemoFailure("ALLOW did not produce processor authorization")
    assert_decision(opener, base_url, headers, allow, "ALLOW", [])

    step_intent = create_intent(opener, base_url, headers, customer["id"], 100_000,
                                f"phase3-{run_id}-step-intent")
    step = confirm(opener, base_url, headers, step_intent["id"], approved_method["id"],
                   f"phase3-{run_id}-step-confirm")
    if step["status"] != "REQUIRES_ACTION" or step["processorAuthorizationId"] is not None:
        raise DemoFailure("fraud step-up payment truth is incoherent")
    assert_decision(opener, base_url, headers, step, "REQUEST_3DS",
                    ["REQUEST_3DS_USD_ELEVATED_AMOUNT_V1"])

    block_intent = create_intent(opener, base_url, headers, customer["id"], 5_000_000,
                                 f"phase3-{run_id}-block-intent")
    block = confirm(opener, base_url, headers, block_intent["id"], approved_method["id"],
                    f"phase3-{run_id}-block-confirm")
    if block["status"] != "BLOCKED" or block["processorAuthorizationId"] is not None:
        raise DemoFailure("fraud block payment truth is incoherent")
    assert_decision(opener, base_url, headers, block, "BLOCK",
                    ["BLOCK_USD_EXTREME_AMOUNT_V1", "REQUEST_3DS_USD_ELEVATED_AMOUNT_V1"])

    review_intent = create_intent(opener, base_url, headers, review_customer["id"], 99_999,
                                  f"phase3-{run_id}-review-intent")
    for number in (1, 2):
        declined = confirm(opener, base_url, headers, review_intent["id"], decline_method["id"],
                           f"phase3-{run_id}-review-confirm-{number}")
        if declined["status"] != "DECLINED":
            raise DemoFailure("review setup decline did not reopen the intent")
    review = confirm(opener, base_url, headers, review_intent["id"], decline_method["id"],
                     f"phase3-{run_id}-review-confirm-3")
    if review["status"] != "IN_REVIEW" or review["processorAuthorizationId"] is not None:
        raise DemoFailure("review intake payment truth is incoherent")
    assert_decision(opener, base_url, headers, review, "REVIEW", ["REVIEW_REPEATED_ATTEMPT_V1"])
    _, reviews = request_json(opener, base_url, "GET", "/fraud/reviews?status=OPEN&limit=100", headers=headers)
    matching = [case for case in reviews if case["paymentIntentId"] == review_intent["id"]]
    if len(matching) != 1:
        raise DemoFailure("review intake did not create exactly one open case")
    review_case = matching[0]
    request_json(opener, base_url, "POST", f"/payment_intents/{review_intent['id']}/cancel",
                 headers={**headers, "Idempotency-Key": f"phase3-{run_id}-review-cancel"})
    _, canceled_case = request_json(opener, base_url, "GET", f"/fraud/reviews/{review_case['id']}",
                                    headers=headers)
    if canceled_case["status"] != "CANCELED" or canceled_case["cancellationReason"] != "MERCHANT_CANCELED":
        raise DemoFailure("review cancellation did not close the case coherently")

    merchant_id = uuid.UUID(merchant["id"])
    summary = postgres_scalar(f"""
        SELECT
          (SELECT COUNT(*) FROM fraud_decisions WHERE merchant_id = '{merchant_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM feature_values WHERE merchant_id = '{merchant_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM rule_execution_results WHERE merchant_id = '{merchant_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM authorizations WHERE merchant_id = '{merchant_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM manual_review_cases WHERE merchant_id = '{merchant_id}'::uuid
             AND status = 'CANCELED');
    """)
    if summary != f"6|{6 * len(BASE_FEATURE_CODES)}|18|3|1":
        raise DemoFailure(f"durable Phase 3 reconciliation failed: {summary}")

    print("Phase 3 fraud-decision MVP demo passed.")
    print(f"merchant={merchant['id']}")
    print("actions=ALLOW,REQUEST_3DS,BLOCK,REVIEW")
    print(f"decisions=6 features={6 * len(BASE_FEATURE_CODES)} rule_results=18 authorizations=3 canceled_reviews=1")
    print("replay=exact non_allow_processor_calls=zero decision_lineage=complete")


if __name__ == "__main__":
    try:
        run()
    except (DemoFailure, KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
        print(f"Phase 3 demo failed: {error}", file=sys.stderr)
        raise SystemExit(1)
