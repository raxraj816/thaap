#!/usr/bin/env python3
"""Public durable card-testing incident and ephemeral rate-limit demo."""

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
        raise DemoFailure("Card-testing incident and rate limit demo rejects redirects")


def require_loopback(base_url: str) -> str:
    parsed = urllib.parse.urlsplit(base_url.rstrip("/"))
    if parsed.scheme != "http" or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
        raise DemoFailure("Card-testing incident and rate limit demo accepts only a loopback HTTP API origin")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise DemoFailure("Card-testing incident and rate limit demo API origin contains prohibited components")
    return base_url.rstrip("/")


def run_argv(command: list[str]) -> str:
    try:
        result = subprocess.run(command, check=True, capture_output=True, text=True, timeout=10)
    except (FileNotFoundError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        raise DemoFailure("Card-testing incident and rate limit reconciliation requires local THAAP containers") from error
    return result.stdout.strip()


def postgres_scalar(query: str) -> str:
    normalized = query.strip().lower()
    if not normalized.startswith("select"):
        raise DemoFailure("Card-testing incident and rate limit reconciliation accepts only SELECT")
    for prohibited in (" insert ", " update ", " delete ", " alter ", " drop ", " truncate "):
        if prohibited in f" {normalized} ":
            raise DemoFailure("Card-testing incident and rate limit reconciliation rejected mutating SQL")
    return run_argv([
        "docker", "exec", "-i", "thaap-postgres", "psql", "-X", "-A", "-t",
        "-U", os.environ.get("THAAP_POSTGRES_USER", "thaap"),
        "-d", os.environ.get("THAAP_POSTGRES_DB", "thaap_payments"), "-c", query,
    ])


def redis_rate_limit_keys() -> list[str]:
    output = run_argv(["docker", "exec", "thaap-redis", "redis-cli", "--scan",
                       "--pattern", "thaap:rate-limit:v1:*"])
    keys = [line for line in output.splitlines() if line]
    if any(not re.fullmatch(r"thaap:rate-limit:v1:[0-9a-f]{64}", key) for key in keys):
        raise DemoFailure("Redis contained a non-HMAC rate-limit key")
    return keys


def request_json(opener, base_url, method, path, body=None, headers=None, expected=(200, 201)):
    payload = None if body is None else json.dumps(body, separators=(",", ":")).encode()
    request_headers = {"Accept": "application/json", **(headers or {})}
    if payload is not None:
        request_headers["Content-Type"] = "application/json"
    request = urllib.request.Request(base_url + path, data=payload, headers=request_headers, method=method)
    try:
        with opener.open(request, timeout=12) as response:
            raw = response.read()
            if response.status not in expected:
                raise DemoFailure(f"unexpected HTTP {response.status} for {path}")
            return response.status, dict(response.headers), raw, json.loads(raw)
    except urllib.error.HTTPError as error:
        raw = error.read()
        if error.code in expected:
            return error.code, dict(error.headers), raw, json.loads(raw)
        raise DemoFailure(f"HTTP {error.code} for {path}: {raw.decode('utf-8', 'replace')}") from error


def post(opener, base_url, path, body, headers, key=None, expected=(200, 201)):
    request_headers = dict(headers)
    if key:
        request_headers["Idempotency-Key"] = key
    return request_json(opener, base_url, "POST", path, body, request_headers, expected)


def create_merchant(opener, base_url, admin_token, prefix):
    return post(opener, base_url, "/admin/merchants", {"name": prefix},
                {"X-Admin-Bootstrap-Token": admin_token}, prefix + "-merchant")[3]


def create_customer(opener, base_url, headers, prefix):
    return post(opener, base_url, "/customers", {"email": prefix + "@example.com"}, headers)[3]


def create_method(opener, base_url, headers, customer_id, card, key, expected=(201,)):
    return post(opener, base_url, "/payment_methods",
                {"customerId": customer_id, "cardNumber": card}, headers, key, expected)


def run() -> None:
    base_url = require_loopback(os.environ.get("THAAP_BASE_URL", "http://localhost:8080"))
    admin_token = os.environ.get("THAAP_ADMIN_BOOTSTRAP_TOKEN")
    if not admin_token:
        raise DemoFailure("THAAP_ADMIN_BOOTSTRAP_TOKEN is required")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), RejectRedirects())
    request_json(opener, base_url, "GET", "/actuator/health/readiness")
    run_id = f"{int(time.time())}-{uuid.uuid4().hex[:8]}"

    incident_merchant = create_merchant(opener, base_url, admin_token, "ch34-incident-" + run_id)
    merchant_headers = {"X-Api-Key": incident_merchant["apiKey"]}
    customer = create_customer(opener, base_url, merchant_headers, "ch34-incident-" + run_id)
    manager = post(opener, base_url, "/risk/operators", {"role": "RISK_MANAGER"},
                   merchant_headers, "ch34-manager-" + run_id)[3]
    manager_headers = {"X-Risk-Operator-Key": manager["credential"]}
    for number in range(5):
        method = create_method(opener, base_url, merchant_headers, customer["id"],
                               f"499999000000000{number + 2}", f"ch34-method-{run_id}-{number}")[3]
        intent = post(opener, base_url, "/payment_intents",
                      {"customerId": customer["id"], "amount": 1900, "currency": "USD",
                       "captureMethod": "MANUAL"}, merchant_headers,
                      f"ch34-intent-{run_id}-{number}")[3]
        attempt = post(opener, base_url, f"/payment_intents/{intent['id']}/confirm",
                       {"paymentMethodId": method["id"],
                        "riskContext": {"ipAddress": f"203.0.113.{number + 1}", "ipCountry": "US"}},
                       merchant_headers, f"ch34-confirm-{run_id}-{number}", (201,))[3]
        if attempt["status"] not in {"DECLINED", "IN_REVIEW"}:
            raise DemoFailure("card-testing fixture did not produce durable decline/review evidence")
    incidents = request_json(opener, base_url, "GET", "/risk/incidents", headers=manager_headers)[3]
    if len(incidents) != 1 or incidents[0]["sourceAttestation"] != "THAAP_PERSISTED_CONTROL_EVIDENCE":
        raise DemoFailure("durable suspected-card-testing incident was not opened exactly once")
    resolve_body = {"mitigationCode": "UPSTREAM_BLOCK_APPLIED"}
    first = post(opener, base_url, f"/risk/incidents/{incidents[0]['id']}/resolve",
                 resolve_body, manager_headers, "ch34-resolve-" + run_id, (200,))[2]
    replay = post(opener, base_url, f"/risk/incidents/{incidents[0]['id']}/resolve",
                  resolve_body, manager_headers, "ch34-resolve-" + run_id, (200,))[2]
    if first != replay:
        raise DemoFailure("incident resolution replay was not byte exact")

    limit_merchant = create_merchant(opener, base_url, admin_token, "ch34-limit-" + run_id)
    limit_headers = {"X-Api-Key": limit_merchant["apiKey"]}
    limit_customer = create_customer(opener, base_url, limit_headers, "ch34-limit-" + run_id)
    for number in range(20):
        create_method(opener, base_url, limit_headers, limit_customer["id"], "4242424242424242",
                      f"ch34-limit-{run_id}-{number}")
    status, response_headers, _, error = create_method(
        opener, base_url, limit_headers, limit_customer["id"], "4242424242424242",
        f"ch34-limit-{run_id}-20", (429,))
    if status != 429 or error.get("error") != "rate_limit_exceeded" or not response_headers.get("Retry-After"):
        raise DemoFailure("definite rate-limit exceedance did not return bounded 429 truth")

    merchant_id = uuid.UUID(incident_merchant["id"])
    incident_id = uuid.UUID(incidents[0]["id"])
    durable = postgres_scalar(f"""
        SELECT status || '|' || source_attestation || '|' || attempt_count || '|' || decline_count || '|' ||
               distinct_card_count || '|' || distinct_ip_count || '|' ||
               (SELECT COUNT(*) FROM incident_evidence_links WHERE merchant_id='{merchant_id}'::uuid
                    AND incident_id='{incident_id}'::uuid) || '|' ||
               (SELECT COUNT(*) FROM card_testing_incident_resolutions WHERE merchant_id='{merchant_id}'::uuid
                    AND incident_id='{incident_id}'::uuid) || '|' ||
               (SELECT COUNT(*) FROM merchant_risk_policy_versions WHERE merchant_id='{merchant_id}'::uuid)
        FROM card_testing_incidents WHERE merchant_id='{merchant_id}'::uuid AND id='{incident_id}'::uuid;
    """)
    if durable != "RESOLVED|THAAP_PERSISTED_CONTROL_EVIDENCE|5|3|5|5|5|1|0":
        raise DemoFailure(f"Card-testing incident and rate limit durable reconciliation failed: {durable}")
    redis_keys = redis_rate_limit_keys()
    joined = "\n".join(redis_keys)
    for secret in (incident_merchant["apiKey"], limit_merchant["apiKey"], "203.0.113."):
        if secret in joined:
            raise DemoFailure("raw identity leaked into Redis rate-limit keys")

    print("Card-testing incident and rate-limit demo passed.")
    print("incident=RESOLVED|persisted_evidence=5|declines=3|policy_mutations=0|replay=byte_exact")
    print(f"rate_limit=429|retry_after={response_headers['Retry-After']}|hmac_keys={len(redis_keys)}")
    print("rate_limit_truth=EPHEMERAL incident_truth=DURABLE readiness=UP")


if __name__ == "__main__":
    try:
        run()
    except (DemoFailure, KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
        print(f"Card-testing incident and rate limit demo failed: {error}", file=sys.stderr)
        raise SystemExit(1)
