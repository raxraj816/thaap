#!/usr/bin/env python3
"""Public merchant profile, risk policy, dual-control, and lineage demo."""

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
        raise DemoFailure("Merchant risk policy demo rejects redirects")


def require_loopback(base_url: str) -> str:
    parsed = urllib.parse.urlsplit(base_url.rstrip("/"))
    if parsed.scheme != "http" or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
        raise DemoFailure("Merchant risk policy demo accepts only a loopback HTTP API origin")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise DemoFailure("Merchant risk policy demo API origin contains prohibited components")
    return base_url.rstrip("/")


def postgres_scalar(query: str) -> str:
    normalized = query.strip().lower()
    if not normalized.startswith("select"):
        raise DemoFailure("Merchant risk policy reconciliation accepts only SELECT")
    for prohibited in (" insert ", " update ", " delete ", " alter ", " drop ", " truncate "):
        if prohibited in f" {normalized} ":
            raise DemoFailure("Merchant risk policy reconciliation rejected mutating SQL")
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
        with opener.open(request, timeout=12) as response:
            raw = response.read()
            if response.status not in (200, 201):
                raise DemoFailure(f"unexpected HTTP {response.status} for {path}")
            return raw, json.loads(raw)
    except urllib.error.HTTPError as error:
        raise DemoFailure(f"HTTP {error.code} for {path}: {error.read().decode('utf-8', 'replace')}") from error


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
    raise DemoFailure("Merchant risk policy API readiness did not become UP")


def post(opener, base_url, path, body, headers, key=None):
    keyed = dict(headers)
    if key is not None:
        keyed["Idempotency-Key"] = key
    return request_json(opener, base_url, "POST", path, body, keyed)


def transition(opener, base_url, resource, resource_id, action, headers, key):
    return post(opener, base_url, f"/risk/{resource}/{resource_id}/{action}", {}, headers, key)[1]


def create_payment(opener, base_url, merchant_headers, prefix, amount, card):
    _, customer = post(opener, base_url, "/customers", {"email": f"{prefix}@example.com"}, merchant_headers)
    _, method = post(opener, base_url, "/payment_methods",
                     {"customerId": customer["id"], "cardNumber": card},
                     merchant_headers, prefix + "-method")
    _, intent = post(opener, base_url, "/payment_intents",
                     {"customerId": customer["id"], "amount": amount, "currency": "USD",
                      "captureMethod": "MANUAL"}, merchant_headers, prefix + "-intent")
    body = {"paymentMethodId": method["id"]}
    raw, attempt = post(opener, base_url, f"/payment_intents/{intent['id']}/confirm",
                        body, merchant_headers, prefix + "-confirm")
    replay_raw, replay = post(opener, base_url, f"/payment_intents/{intent['id']}/confirm",
                              body, merchant_headers, prefix + "-confirm")
    if raw != replay_raw or attempt != replay:
        raise DemoFailure("Merchant risk policy confirm replay was not byte-exact")
    return attempt


def decision_action(attempt):
    return postgres_scalar(
        f"SELECT action FROM fraud_decisions WHERE id = '{uuid.UUID(attempt['fraudDecisionId'])}'::uuid")


def run() -> None:
    base_url = require_loopback(os.environ.get("THAAP_BASE_URL", "http://localhost:8080"))
    admin_token = os.environ.get("THAAP_ADMIN_BOOTSTRAP_TOKEN")
    if not admin_token:
        raise DemoFailure("THAAP_ADMIN_BOOTSTRAP_TOKEN is required")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), RejectRedirects())
    wait_readiness(opener, base_url)
    run_id = f"{int(time.time())}-{uuid.uuid4().hex[:8]}"
    prefix = f"ch31-{run_id}"

    _, merchant = post(opener, base_url, "/admin/merchants", {"name": f"Merchant risk policy {run_id}"},
                       {"X-Admin-Bootstrap-Token": admin_token}, prefix + "-merchant")
    merchant_headers = {"X-Api-Key": merchant["apiKey"]}
    _, creator = post(opener, base_url, "/risk/operators", {"role": "RISK_MANAGER"},
                      merchant_headers, prefix + "-creator")
    _, approver = post(opener, base_url, "/risk/operators", {"role": "RISK_MANAGER"},
                       merchant_headers, prefix + "-approver")
    creator_headers = {"X-Risk-Operator-Key": creator["credential"]}
    approver_headers = {"X-Risk-Operator-Key": approver["credential"]}

    profile_body = {"riskTier": "ELEVATED", "reasonCode": "MERCHANT_RISK_CHANGE"}
    profile_raw, profile = post(opener, base_url, "/risk/profiles", profile_body,
                                creator_headers, prefix + "-profile")
    replay_raw, _ = post(opener, base_url, "/risk/profiles", profile_body,
                         creator_headers, prefix + "-profile")
    if profile_raw != replay_raw:
        raise DemoFailure("Merchant risk policy profile replay was not byte-exact")
    transition(opener, base_url, "profiles", profile["id"], "submit",
               creator_headers, prefix + "-profile-submit")
    approved_profile = transition(opener, base_url, "profiles", profile["id"], "approve",
                                  approver_headers, prefix + "-profile-approve")
    if approved_profile["state"] != "APPROVED":
        raise DemoFailure("profile approval did not preserve the explicit APPROVED state")

    _, policy = post(opener, base_url, "/risk/policies",
                     {"code": "DEMO_THRESHOLDS", "displayName": "Demo thresholds"},
                     creator_headers, prefix + "-policy")
    thresholds = [
        ("REQUEST_3DS_AMOUNT", 1_000, "REQUEST_3DS"),
        ("REVIEW_AMOUNT", 2_000, "REVIEW"),
        ("BLOCK_AMOUNT", 3_000, "BLOCK"),
    ]
    for priority, (code, amount, action) in enumerate(thresholds, start=1):
        post(opener, base_url, f"/risk/policies/{policy['id']}/thresholds",
             {"code": code, "featureCode": "payment.amount_minor", "operator": "GTE",
              "longValue": amount, "candidateAction": action, "priority": priority,
              "reasonCode": "MERCHANT_AMOUNT_THRESHOLD", "currencyScope": "USD"},
             creator_headers, f"{prefix}-threshold-{priority}")
    _, preview = post(opener, base_url, f"/risk/policies/{policy['id']}/preview",
                      {"features": {"payment.amount_minor": 2_200}, "currency": "USD"},
                      creator_headers)
    if preview["previewType"] != "FIXTURE_PREVIEW" or preview["strongestCandidate"] != "REVIEW":
        raise DemoFailure("bounded fixture preview did not produce REVIEW")
    transition(opener, base_url, "policies", policy["id"], "submit",
               creator_headers, prefix + "-policy-submit")
    approved_policy = transition(opener, base_url, "policies", policy["id"], "approve",
                                 approver_headers, prefix + "-policy-approve")
    if approved_policy["state"] != "APPROVED":
        raise DemoFailure("policy approval did not preserve the explicit APPROVED state")

    before = create_payment(opener, base_url, merchant_headers, prefix + "-before", 1_200,
                            "4242424242424242")
    if decision_action(before) != "ALLOW":
        raise DemoFailure("approved-but-not-active policy changed a payment decision")

    transition(opener, base_url, "profiles", profile["id"], "activate",
               approver_headers, prefix + "-profile-activate")
    transition(opener, base_url, "policies", policy["id"], "activate",
               approver_headers, prefix + "-policy-activate")
    three_ds = create_payment(opener, base_url, merchant_headers, prefix + "-3ds", 1_200,
                              "4000000000003220")
    review = create_payment(opener, base_url, merchant_headers, prefix + "-review", 2_200,
                            "4000000000009995")
    blocked = create_payment(opener, base_url, merchant_headers, prefix + "-block", 3_200,
                             "4000000000000002")
    actions = [decision_action(three_ds), decision_action(review), decision_action(blocked)]
    if actions != ["REQUEST_3DS", "REVIEW", "BLOCK"]:
        raise DemoFailure(f"merchant thresholds produced {actions}")

    transition(opener, base_url, "policies", policy["id"], "retire",
               approver_headers, prefix + "-policy-retire")
    transition(opener, base_url, "profiles", profile["id"], "retire",
               approver_headers, prefix + "-profile-retire")
    decision_ids = [uuid.UUID(item["fraudDecisionId"]) for item in (before, three_ds, review, blocked)]
    summary = postgres_scalar(f"""
        SELECT
          (SELECT COUNT(*) FROM risk_control_evaluations
            WHERE fraud_decision_id IN ('{decision_ids[1]}'::uuid, '{decision_ids[2]}'::uuid,
                                        '{decision_ids[3]}'::uuid)
              AND control_type = 'PROFILE' AND observed_string = 'ELEVATED'
              AND profile_version_id = '{uuid.UUID(profile['id'])}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM risk_control_evaluations
            WHERE fraud_decision_id IN ('{decision_ids[1]}'::uuid, '{decision_ids[2]}'::uuid,
                                        '{decision_ids[3]}'::uuid)
              AND control_type = 'POLICY_THRESHOLD' AND policy_version_id =
                  '{uuid.UUID(policy['id'])}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM authorizations
            WHERE payment_attempt_id IN ('{uuid.UUID(three_ds['id'])}'::uuid,
                                         '{uuid.UUID(review['id'])}'::uuid,
                                         '{uuid.UUID(blocked['id'])}'::uuid)) || '|' ||
          (SELECT COUNT(*) FROM merchant_risk_policy_events
            WHERE policy_version_id = '{uuid.UUID(policy['id'])}'::uuid) || '|' ||
          (SELECT action FROM fraud_decisions WHERE id = '{decision_ids[0]}'::uuid);
    """)
    if summary != "3|9|0|4|ALLOW":
        raise DemoFailure(f"Merchant risk policy durable reconciliation failed: {summary}")

    print("Merchant risk policy demo passed.")
    print("approval_effect=none activation_effect=REQUEST_3DS|REVIEW|BLOCK")
    print("dual_control=proved replay=byte_exact preview=fixture_only")
    print("historical_lineage=immutable processor_exclusions=3 readiness=UP")


if __name__ == "__main__":
    try:
        run()
    except (DemoFailure, KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
        print(f"Merchant risk policy demo failed: {error}", file=sys.stderr)
        raise SystemExit(1)
