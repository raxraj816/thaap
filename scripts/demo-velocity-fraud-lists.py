#!/usr/bin/env python3
"""Public velocity, typed fraud-list, and Redis-loss demo."""

from __future__ import annotations

import json
import os
import re
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
        raise DemoFailure("Velocity and fraud list demo rejects redirects")


def require_loopback(base_url: str) -> str:
    parsed = urllib.parse.urlsplit(base_url.rstrip("/"))
    if parsed.scheme != "http" or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
        raise DemoFailure("Velocity and fraud list demo accepts only a loopback HTTP API origin")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise DemoFailure("Velocity and fraud list demo API origin contains prohibited components")
    return base_url.rstrip("/")


def postgres_scalar(query: str) -> str:
    normalized = query.strip().lower()
    if not normalized.startswith("select"):
        raise DemoFailure("Velocity and fraud list reconciliation accepts only SELECT")
    for prohibited in (" insert ", " update ", " delete ", " alter ", " drop ", " truncate "):
        if prohibited in f" {normalized} ":
            raise DemoFailure("Velocity and fraud list reconciliation rejected mutating SQL")
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


def redis_container(action: str) -> None:
    if action not in {"pause", "unpause"}:
        raise DemoFailure("Velocity and fraud list Redis control action is not allowlisted")
    container = os.environ.get("THAAP_REDIS_CONTAINER", "thaap-local-redis-1")
    if not re.fullmatch(r"[A-Za-z0-9_.-]{1,128}", container):
        raise DemoFailure("Velocity and fraud list Redis container name is invalid")
    try:
        subprocess.run(["docker", action, container], check=True, capture_output=True,
                       text=True, timeout=10)
    except (FileNotFoundError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        raise DemoFailure(f"could not {action} local {container}") from error


def redis_surface() -> str:
    container = os.environ.get("THAAP_REDIS_CONTAINER", "thaap-local-redis-1")
    if not re.fullmatch(r"[A-Za-z0-9_.-]{1,128}", container):
        raise DemoFailure("Velocity and fraud list Redis container name is invalid")
    try:
        scanned = subprocess.run(["docker", "exec", container, "redis-cli", "--raw", "--scan"],
                                 check=True, capture_output=True, text=True, timeout=10)
        keys = [line for line in scanned.stdout.splitlines() if line]
        values = list(keys)
        for key in keys:
            if re.fullmatch(r"thaap:velocity:v1:[0-9a-f]{64}", key):
                redis_command = ["ZRANGE", key, "0", "-1"]
            elif re.fullmatch(r"thaap:velocity:v1:owner:[0-9a-f]{64}:[0-9a-f]{64}", key):
                redis_command = ["GET", key]
            elif re.fullmatch(r"thaap:rate-limit:v1:[0-9a-f]{64}", key):
                redis_command = ["HGETALL", key]
            else:
                raise DemoFailure(f"unexpected Velocity and fraud list Redis key grammar: {key}")
            members = subprocess.run(
                ["docker", "exec", container, "redis-cli", "--raw", *redis_command],
                check=True, capture_output=True, text=True, timeout=10)
            values.extend(members.stdout.splitlines())
    except (FileNotFoundError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        raise DemoFailure("Redis privacy reconciliation requires local thaap-redis") from error
    return "\n".join(values)


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


def request_expected_error(opener, base_url, method, path, body, headers, expected_status):
    payload = json.dumps(body, separators=(",", ":")).encode()
    request = urllib.request.Request(base_url + path, data=payload,
                                     headers={"Accept": "application/json",
                                              "Content-Type": "application/json", **headers},
                                     method=method)
    try:
        opener.open(request, timeout=12)
    except urllib.error.HTTPError as error:
        response = json.loads(error.read())
        if error.code != expected_status:
            raise DemoFailure(f"unexpected HTTP {error.code} for {path}") from error
        return response
    raise DemoFailure(f"expected HTTP {expected_status} for {path}")


def wait_readiness(opener, base_url) -> None:
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        try:
            _, health = request_json(opener, base_url, "GET", "/actuator/health/readiness")
            if health.get("status") == "UP":
                return
        except DemoFailure:
            pass
        time.sleep(0.2)
    raise DemoFailure("Velocity and fraud list API did not recover readiness after Redis returned")


def create_fixture(opener, base_url, admin_token, prefix, card, email):
    _, merchant = request_json(
        opener, base_url, "POST", "/admin/merchants", {"name": f"Velocity and fraud list {prefix}"},
        {"X-Admin-Bootstrap-Token": admin_token, "Idempotency-Key": prefix + "-merchant"})
    headers = {"X-Api-Key": merchant["apiKey"]}
    _, customer = request_json(opener, base_url, "POST", "/customers", {"email": email}, headers)
    _, method = request_json(
        opener, base_url, "POST", "/payment_methods", {"customerId": customer["id"], "cardNumber": card},
        {**headers, "Idempotency-Key": prefix + "-method"})
    return merchant, headers, customer, method


def create_intent(opener, base_url, headers, customer_id, key):
    _, intent = request_json(
        opener, base_url, "POST", "/payment_intents",
        {"customerId": customer_id, "amount": 1_200, "currency": "USD", "captureMethod": "MANUAL"},
        {**headers, "Idempotency-Key": key})
    return intent


def confirm(opener, base_url, headers, intent_id, method_id, key):
    body = {"paymentMethodId": method_id}
    keyed = {**headers, "Idempotency-Key": key}
    raw, attempt = request_json(
        opener, base_url, "POST", f"/payment_intents/{intent_id}/confirm", body, keyed)
    replay_raw, replay = request_json(
        opener, base_url, "POST", f"/payment_intents/{intent_id}/confirm", body, keyed)
    if raw != replay_raw or attempt != replay:
        raise DemoFailure("Velocity and fraud list confirm replay was not byte-exact")
    return attempt


def prove_typed_list(opener, base_url, admin_token, run_id):
    prefix = f"ch30-{run_id}-list"
    card = "4242424242424242"
    email = f"private-list-{run_id}@example.com"
    merchant, headers, customer, method = create_fixture(
        opener, base_url, admin_token, prefix, card, email)
    _, manager = request_json(
        opener, base_url, "POST", "/risk/operators", {"role": "RISK_MANAGER"},
        {**headers, "Idempotency-Key": prefix + "-manager"})
    risk_headers = {"X-Risk-Operator-Key": manager["credential"]}
    create_body = {"code": "DEMO_BAD_CARD", "displayName": "Demo bad cards",
                   "identityType": "CARD_FINGERPRINT"}
    raw, fraud_list = request_json(
        opener, base_url, "POST", "/risk/fraud-lists", create_body,
        {**risk_headers, "Idempotency-Key": prefix + "-create-list"})
    replay_raw, _ = request_json(
        opener, base_url, "POST", "/risk/fraud-lists", create_body,
        {**risk_headers, "Idempotency-Key": prefix + "-create-list"})
    if raw != replay_raw:
        raise DemoFailure("Velocity and fraud list list replay was not byte-exact")
    entry_body = {"value": card, "action": "BLOCK", "reasonCode": "KNOWN_FRAUD"}
    entry_raw, entry = request_json(
        opener, base_url, "POST", f"/risk/fraud-lists/{fraud_list['id']}/entries", entry_body,
        {**risk_headers, "Idempotency-Key": prefix + "-entry"})
    if card.encode() in entry_raw:
        raise DemoFailure("typed fraud-list response exposed the raw card")

    blocked_intent = create_intent(opener, base_url, headers, customer["id"], prefix + "-blocked-intent")
    blocked = confirm(opener, base_url, headers, blocked_intent["id"], method["id"], prefix + "-blocked-confirm")
    if blocked["status"] != "BLOCKED":
        raise DemoFailure(f"typed fraud-list match produced {blocked['status']}")
    request_json(
        opener, base_url, "POST",
        f"/risk/fraud-lists/{fraud_list['id']}/entries/{entry['id']}/deactivate", {},
        {**risk_headers, "Idempotency-Key": prefix + "-deactivate"})
    future_intent = create_intent(opener, base_url, headers, customer["id"], prefix + "-future-intent")
    future = confirm(opener, base_url, headers, future_intent["id"], method["id"], prefix + "-future-confirm")
    if future["status"] == "BLOCKED":
        raise DemoFailure("deactivated fraud-list entry changed future decisions")

    merchant_id = uuid.UUID(merchant["id"])
    summary = postgres_scalar(f"""
        SELECT
          (SELECT action FROM fraud_decisions WHERE id = '{uuid.UUID(blocked['fraudDecisionId'])}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM fraud_list_evaluations WHERE fraud_decision_id =
             '{uuid.UUID(blocked['fraudDecisionId'])}'::uuid AND matched = TRUE
             AND matched_entry_id = '{uuid.UUID(entry['id'])}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM fraud_list_evaluations WHERE fraud_decision_id =
             '{uuid.UUID(future['fraudDecisionId'])}'::uuid AND matched = FALSE) || '|' ||
          (SELECT COUNT(*) FROM authorizations WHERE merchant_id = '{merchant_id}'::uuid
             AND payment_attempt_id = '{uuid.UUID(blocked['id'])}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM fraud_list_lifecycle_events WHERE merchant_id = '{merchant_id}'::uuid);
    """)
    if summary != "BLOCK|1|1|0|2":
        raise DemoFailure(f"typed fraud-list durable reconciliation failed: {summary}")
    return card, email


def prove_velocity(opener, base_url, admin_token, run_id):
    prefix = f"ch30-{run_id}-velocity"
    card = "4000000000000077"
    email = f"private-velocity-{run_id}@example.com"
    merchant, headers, customer, method = create_fixture(
        opener, base_url, admin_token, prefix, card, email)
    fourth = None
    for number in range(1, 5):
        intent = create_intent(opener, base_url, headers, customer["id"], f"{prefix}-intent-{number}")
        fourth = confirm(opener, base_url, headers, intent["id"], method["id"], f"{prefix}-confirm-{number}")
    if fourth is None or fourth["status"] != "IN_REVIEW":
        raise DemoFailure(f"velocity threshold produced {None if fourth is None else fourth['status']}")
    decision_id = uuid.UUID(fourth["fraudDecisionId"])
    summary = postgres_scalar(f"""
        SELECT
          (SELECT action FROM fraud_decisions WHERE id = '{decision_id}'::uuid) || '|' ||
          (SELECT reason_codes LIKE '%VELOCITY_THRESHOLD_MATCH%' FROM fraud_decisions
             WHERE id = '{decision_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM velocity_observations WHERE fraud_decision_id = '{decision_id}'::uuid
             AND availability = 'AVAILABLE' AND other_attempt_count = 3 AND script_version = 'velocity_v1');
    """)
    if summary != "REVIEW|true|3":
        raise DemoFailure(f"velocity durable reconciliation failed: {summary}")
    return card, email


def prove_redis_loss(opener, base_url, admin_token, run_id):
    prefix = f"ch30-{run_id}-outage"
    card = "4000000000000093"
    email = f"private-outage-{run_id}@example.com"
    merchant, headers, customer, method = create_fixture(
        opener, base_url, admin_token, prefix, card, email)
    intent = create_intent(opener, base_url, headers, customer["id"], prefix + "-intent")
    confirm_path = f"/payment_intents/{intent['id']}/confirm"
    confirm_headers = {**headers, "Idempotency-Key": prefix + "-confirm"}
    paused = False
    try:
        redis_container("pause")
        paused = True
        unavailable = request_expected_error(
            opener, base_url, "POST", confirm_path, {"paymentMethodId": method["id"]},
            confirm_headers, 503)
    finally:
        if paused:
            redis_container("unpause")
    wait_readiness(opener, base_url)
    if unavailable.get("error") != "rate_limit_unavailable":
        raise DemoFailure("Redis loss was not reported as rate-limit dependency ambiguity")
    merchant_id = uuid.UUID(merchant["id"])
    if postgres_scalar(f"""
        SELECT COUNT(*) FROM payment_attempts WHERE merchant_id='{merchant_id}'::uuid
          AND payment_intent_id='{uuid.UUID(intent['id'])}'::uuid;
    """) != "0":
        raise DemoFailure("503 rate-limit ambiguity created durable payment-attempt truth")
    recovered = confirm(opener, base_url, headers, intent["id"], method["id"], prefix + "-confirm")
    if recovered["status"] == "IN_REVIEW":
        raise DemoFailure("Redis recovery did not restore fresh decisioning")
    return card, email


def prove_no_raw_leak(canaries):
    escaped = [value.replace("'", "''") for value in canaries]
    predicates = " OR ".join(
        f"surface LIKE '%{value}%'" for value in escaped)
    leak_count = postgres_scalar(f"""
        SELECT COUNT(*) FROM (
          SELECT row_to_json(entry)::text AS surface FROM fraud_list_entries entry
          UNION ALL SELECT row_to_json(customer)::text FROM customers customer
          UNION ALL SELECT row_to_json(context)::text FROM risk_contexts context
          UNION ALL SELECT row_to_json(observation)::text FROM velocity_observations observation
          UNION ALL SELECT row_to_json(audit)::text FROM audit_logs audit
          UNION ALL SELECT row_to_json(webhook)::text FROM webhook_events webhook
          UNION ALL SELECT row_to_json(idempotency)::text FROM idempotency_records idempotency
        ) durable_surfaces WHERE {predicates};
    """)
    if leak_count != "0":
        raise DemoFailure(f"raw Velocity and fraud list canary escaped into {leak_count} durable rows")
    redis_text = redis_surface()
    if any(canary in redis_text for canary in canaries):
        raise DemoFailure("raw Velocity and fraud list canary escaped into Redis")


def run() -> None:
    base_url = require_loopback(os.environ.get("THAAP_BASE_URL", "http://localhost:8080"))
    admin_token = os.environ.get("THAAP_ADMIN_BOOTSTRAP_TOKEN")
    if not admin_token:
        raise DemoFailure("THAAP_ADMIN_BOOTSTRAP_TOKEN is required")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), RejectRedirects())
    wait_readiness(opener, base_url)
    run_id = f"{int(time.time())}-{uuid.uuid4().hex[:8]}"
    canaries = []
    canaries.extend(prove_typed_list(opener, base_url, admin_token, run_id))
    canaries.extend(prove_velocity(opener, base_url, admin_token, run_id))
    canaries.extend(prove_redis_loss(opener, base_url, admin_token, run_id))
    prove_no_raw_leak(canaries)
    print("Velocity and typed fraud-list demo passed.")
    print("list_match=BLOCK future_after_deactivation=not_BLOCK")
    print("velocity_threshold=REVIEW redis_unavailable=503 redis_recovery=UP")
    print("replay=exact durable_lineage=complete raw_canary_leaks=0")


if __name__ == "__main__":
    try:
        run()
    except (DemoFailure, KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
        print(f"Velocity and fraud list demo failed: {error}", file=sys.stderr)
        raise SystemExit(1)
