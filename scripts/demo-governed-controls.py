#!/usr/bin/env python3
"""Public governed-rule, simulation-gate, exception, and rollback demo."""

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
        raise DemoFailure("Governed-controls demo rejects redirects")


def require_loopback(base_url: str) -> str:
    parsed = urllib.parse.urlsplit(base_url.rstrip("/"))
    if parsed.scheme != "http" or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
        raise DemoFailure("Governed-controls demo accepts only a loopback HTTP API origin")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise DemoFailure("Governed-controls demo API origin contains prohibited components")
    return base_url.rstrip("/")


def postgres_scalar(query: str) -> str:
    normalized = query.strip().lower()
    if not normalized.startswith("select"):
        raise DemoFailure("Governed-controls reconciliation accepts only SELECT")
    for prohibited in (" insert ", " update ", " delete ", " alter ", " drop ", " truncate "):
        if prohibited in f" {normalized} ":
            raise DemoFailure("Governed-controls reconciliation rejected mutating SQL")
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
            return raw, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as error:
        raw = error.read()
        if error.code in expected:
            return raw, json.loads(raw) if raw else {}
        detail = raw.decode("utf-8", "replace")
        raise DemoFailure(f"HTTP {error.code} for {path}: {detail}") from error


def post(opener, base_url, path, body, headers, key=None, expected=(200, 201)):
    request_headers = dict(headers)
    if key is not None:
        request_headers["Idempotency-Key"] = key
    return request_json(opener, base_url, "POST", path, body, request_headers, expected)


def utc(value):
    return value.isoformat(timespec="microseconds").replace("+00:00", "Z")


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
    raise DemoFailure("Governed-controls API readiness did not become UP")


def wait_for_simulation(opener, base_url, headers, simulation_id):
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        _, run = request_json(opener, base_url, "GET",
                              f"/risk/simulations/{simulation_id}", headers=headers)
        if run["lifecycleStatus"] == "SUCCEEDED":
            _, report = request_json(opener, base_url, "GET",
                                     f"/risk/simulations/{simulation_id}/report", headers=headers)
            return report
        if run["lifecycleStatus"] in {"FAILED", "CANCELED"}:
            raise DemoFailure(f"control simulation terminated as {run['lifecycleStatus']}")
        time.sleep(0.1)
    raise DemoFailure("Control simulation did not complete within 30 seconds")


def transition(opener, base_url, family, control_id, name, headers, key, simulation_id=None,
               expected=(200, 201)):
    body = {} if simulation_id is None else {"simulationRunId": simulation_id}
    return post(opener, base_url, f"/risk/controls/{family}/{control_id}/{name}",
                body, headers, key, expected)


def create_simulation(opener, base_url, headers, prefix, candidate_type, candidate_id,
                      cohort_start, cohort_end):
    body = {
        "candidateType": candidate_type, "candidateVersionId": candidate_id,
        "cohortStart": utc(cohort_start), "cohortEnd": utc(cohort_end),
        "asOf": utc(cohort_end), "labelCutoff": utc(cohort_end),
        "trafficOriginPolicy": "LIVE_ONLY",
    }
    first_raw, run = post(opener, base_url, "/risk/simulations", body, headers, prefix)
    replay_raw, _ = post(opener, base_url, "/risk/simulations", body, headers, prefix)
    if replay_raw != first_raw:
        raise DemoFailure("control simulation creation replay was not byte-exact")
    report = wait_for_simulation(opener, base_url, headers, run["id"])
    if report["report"]["population"] != {"eligible": 1, "excluded": 0, "processed": 1}:
        raise DemoFailure(f"control simulation population did not reconcile: {report}")
    return run["id"]


def run() -> None:
    base_url = require_loopback(os.environ.get("THAAP_BASE_URL", "http://localhost:8080"))
    admin_token = os.environ.get("THAAP_ADMIN_BOOTSTRAP_TOKEN")
    if not admin_token:
        raise DemoFailure("THAAP_ADMIN_BOOTSTRAP_TOKEN is required")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), RejectRedirects())
    wait_readiness(opener, base_url)
    demo_id = f"{int(time.time())}-{uuid.uuid4().hex[:8]}"
    prefix = f"governed-controls-{demo_id}"

    _, merchant = post(opener, base_url, "/admin/merchants",
                       {"name": f"Governed controls {demo_id}"},
                       {"X-Admin-Bootstrap-Token": admin_token}, prefix + "-merchant")
    merchant_id = uuid.UUID(merchant["id"])
    merchant_headers = {"X-Api-Key": merchant["apiKey"]}
    _, creator = post(opener, base_url, "/risk/operators", {"role": "RISK_MANAGER"},
                      merchant_headers, prefix + "-creator")
    _, approver = post(opener, base_url, "/risk/operators", {"role": "RISK_MANAGER"},
                       merchant_headers, prefix + "-approver")
    creator_headers = {"X-Risk-Operator-Key": creator["credential"]}
    approver_headers = {"X-Risk-Operator-Key": approver["credential"]}
    _, customer = post(opener, base_url, "/customers",
                       {"email": f"{prefix}@example.test"}, merchant_headers)
    _, method = post(opener, base_url, "/payment_methods",
                     {"customerId": customer["id"], "cardNumber": "4242424242424242"},
                     merchant_headers, prefix + "-method")

    cohort_start = datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(seconds=1)
    _, baseline_intent = post(opener, base_url, "/payment_intents",
                              {"customerId": customer["id"], "amount": 1200,
                               "currency": "USD", "captureMethod": "MANUAL"},
                              merchant_headers, prefix + "-baseline-intent")
    post(opener, base_url, f"/payment_intents/{baseline_intent['id']}/confirm",
         {"paymentMethodId": method["id"]}, merchant_headers, prefix + "-baseline-confirm")
    cohort_end = datetime.datetime.now(datetime.timezone.utc)

    rule_body = {
        "code": "HIGH_VALUE_BLOCK", "displayName": "High value USD block",
        "candidateAction": "BLOCK", "expression": {"all": [
            {"feature": "payment.currency", "operator": "EQ", "value": "USD"},
            {"feature": "payment.amount_minor", "operator": "GTE", "value": 1000},
        ]},
    }
    created_raw, rule = post(opener, base_url, "/risk/controls/rules", rule_body,
                             creator_headers, prefix + "-rule")
    replay_raw, _ = post(opener, base_url, "/risk/controls/rules", rule_body,
                         creator_headers, prefix + "-rule")
    if created_raw != replay_raw:
        raise DemoFailure("authored-rule creation replay was not byte-exact")
    transition(opener, base_url, "rules", rule["id"], "submit", creator_headers,
               prefix + "-rule-submit")
    transition(opener, base_url, "rules", rule["id"], "approve", creator_headers,
               prefix + "-rule-self-approve", expected=(403,))
    transition(opener, base_url, "rules", rule["id"], "approve", approver_headers,
               prefix + "-rule-approve")
    rule_simulation = create_simulation(opener, base_url, creator_headers,
                                        prefix + "-rule-simulation", "AUTHORED_RULE",
                                        rule["id"], cohort_start, cohort_end)
    _, active_rule = transition(opener, base_url, "rules", rule["id"], "activate",
                                approver_headers, prefix + "-rule-activate", rule_simulation)
    if active_rule["lifecycleStatus"] != "ACTIVE":
        raise DemoFailure("simulation-gated rule did not become active")

    _, blocked_intent = post(opener, base_url, "/payment_intents",
                             {"customerId": customer["id"], "amount": 1200,
                              "currency": "USD", "captureMethod": "MANUAL"},
                             merchant_headers, prefix + "-blocked-intent")
    _, blocked = post(opener, base_url, f"/payment_intents/{blocked_intent['id']}/confirm",
                      {"paymentMethodId": method["id"]}, merchant_headers,
                      prefix + "-blocked-confirm")
    if blocked["status"] != "BLOCKED":
        raise DemoFailure("active authored rule did not block its matching payment")

    now = datetime.datetime.now(datetime.timezone.utc)
    exception_body = {
        "code": "TRUSTED_CARD", "sourceRuleVersionId": rule["id"],
        "paymentMethodId": method["id"], "reasonCode": "FALSE_POSITIVE_REMEDIATION",
        "validFrom": utc(cohort_start - datetime.timedelta(seconds=1)),
        "expiresAt": utc(now + datetime.timedelta(days=1)),
    }
    exception_raw, exception = post(opener, base_url, "/risk/controls/exceptions",
                                    exception_body, creator_headers, prefix + "-exception")
    if any(secret in exception_raw.decode() for secret in
           ("4242424242424242", "subjectIdentityHmac", "subjectHmacKeyVersion")):
        raise DemoFailure("safety-exception response exposed protected identity material")
    transition(opener, base_url, "exceptions", exception["id"], "submit", creator_headers,
               prefix + "-exception-submit")
    transition(opener, base_url, "exceptions", exception["id"], "approve", creator_headers,
               prefix + "-exception-self-approve", expected=(403,))
    transition(opener, base_url, "exceptions", exception["id"], "approve", approver_headers,
               prefix + "-exception-approve")
    exception_simulation = create_simulation(opener, base_url, creator_headers,
                                             prefix + "-exception-simulation", "SAFETY_EXCEPTION",
                                             exception["id"], cohort_start, cohort_end)
    transition(opener, base_url, "exceptions", exception["id"], "activate", approver_headers,
               prefix + "-exception-activate", exception_simulation)

    _, excepted_intent = post(opener, base_url, "/payment_intents",
                              {"customerId": customer["id"], "amount": 1200,
                               "currency": "USD", "captureMethod": "MANUAL"},
                              merchant_headers, prefix + "-excepted-intent")
    _, excepted = post(opener, base_url, f"/payment_intents/{excepted_intent['id']}/confirm",
                       {"paymentMethodId": method["id"]}, merchant_headers,
                       prefix + "-excepted-confirm")
    if excepted["status"] == "BLOCKED":
        raise DemoFailure("active exact-subject safety exception did not suppress its source rule")

    transition(opener, base_url, "rules", rule["id"], "retire", approver_headers,
               prefix + "-rule-retire")
    _, rolled_back = transition(opener, base_url, "rules", rule["id"], "rollback",
                                approver_headers, prefix + "-rule-rollback", rule_simulation)
    if rolled_back["lifecycleStatus"] != "ACTIVE":
        raise DemoFailure("simulation-gated rollback did not reactivate immutable rule bytes")

    reconciliation = postgres_scalar(f"""
        SELECT
          (SELECT COUNT(*) FROM authored_rule_events
            WHERE merchant_id = '{merchant_id}'::uuid AND rule_version_id = '{uuid.UUID(rule['id'])}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM risk_safety_exception_events
            WHERE merchant_id = '{merchant_id}'::uuid
              AND exception_version_id = '{uuid.UUID(exception['id'])}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM authored_rule_evaluations
            WHERE merchant_id = '{merchant_id}'::uuid AND matched = TRUE
              AND exception_disposition = 'NONE') || '|' ||
          (SELECT COUNT(*) FROM authored_rule_evaluations
            WHERE merchant_id = '{merchant_id}'::uuid AND matched = TRUE
              AND exception_disposition = 'APPLIED'
              AND applied_exception_version_id = '{uuid.UUID(exception['id'])}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM authored_rule_versions
            WHERE merchant_id = '{merchant_id}'::uuid AND rule_code = 'HIGH_VALUE_BLOCK'
              AND lifecycle_status = 'ACTIVE') || '|' ||
          (SELECT COUNT(*) FROM authorizations auth
            JOIN payment_attempts attempt ON attempt.id = auth.payment_attempt_id
            WHERE attempt.merchant_id = '{merchant_id}'::uuid);
    """)
    if reconciliation != "6|4|1|1|1|2":
        raise DemoFailure(f"governed-controls durable reconciliation failed: {reconciliation}")

    print("Governed controls demo passed.")
    print("rule=canonical dual_control=proved simulation_gate=exact activation=BLOCK")
    print("exception=purpose_hmac exact_subject=proved match_evidence=retained")
    print("rollback=append_only public_replays=byte_exact readiness=UP")


if __name__ == "__main__":
    try:
        run()
    except (DemoFailure, KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
        print(f"Governed controls demo failed: {error}", file=sys.stderr)
        raise SystemExit(1)
