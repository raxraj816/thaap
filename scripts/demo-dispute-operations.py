#!/usr/bin/env python3
"""Public immutable dispute-evidence, submission, deadline, and outcome demo."""

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
        raise DemoFailure("Dispute-operations demo rejects redirects")


def require_loopback(base_url: str) -> str:
    parsed = urllib.parse.urlsplit(base_url.rstrip("/"))
    if parsed.scheme != "http" or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
        raise DemoFailure("Dispute-operations demo accepts only a loopback HTTP API origin")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise DemoFailure("Dispute-operations demo API origin contains prohibited components")
    return base_url.rstrip("/")


def postgres_scalar(query: str) -> str:
    normalized = query.strip().lower()
    if not normalized.startswith("select"):
        raise DemoFailure("Dispute reconciliation accepts only SELECT")
    for prohibited in (" insert ", " update ", " delete ", " alter ", " drop ", " truncate "):
        if prohibited in f" {normalized} ":
            raise DemoFailure("Dispute reconciliation rejected mutating SQL")
    command = ["docker", "exec", "-i", "thaap-postgres", "psql", "-X", "-A", "-t",
               "-U", os.environ.get("THAAP_POSTGRES_USER", "thaap"),
               "-d", os.environ.get("THAAP_POSTGRES_DB", "thaap_payments"), "-c", query]
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
    request = urllib.request.Request(base_url + path, data=payload, headers=request_headers, method=method)
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
        raise DemoFailure(f"HTTP {error.code} for {path}: {raw.decode('utf-8', 'replace')}") from error


def post(opener, base_url, path, body, headers, key=None, expected=(200, 201)):
    request_headers = dict(headers)
    if key:
        request_headers["Idempotency-Key"] = key
    return request_json(opener, base_url, "POST", path, body, request_headers, expected)


def utc(value):
    return value.isoformat(timespec="microseconds").replace("+00:00", "Z")


def run() -> None:
    base_url = require_loopback(os.environ.get("THAAP_BASE_URL", "http://localhost:8080"))
    admin_token = os.environ.get("THAAP_ADMIN_BOOTSTRAP_TOKEN")
    scenario_token = os.environ.get("THAAP_SCENARIO_RUNNER_TOKEN")
    if not admin_token or not scenario_token:
        raise DemoFailure("THAAP_ADMIN_BOOTSTRAP_TOKEN and THAAP_SCENARIO_RUNNER_TOKEN are required")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), RejectRedirects())
    request_json(opener, base_url, "GET", "/actuator/health/readiness")
    run_id = f"{int(time.time())}-{uuid.uuid4().hex[:8]}"
    prefix = f"dispute-ops-{run_id}"
    _, merchant = post(opener, base_url, "/admin/merchants", {"name": f"Dispute operations {run_id}"},
                       {"X-Admin-Bootstrap-Token": admin_token}, prefix + "-merchant")
    merchant_headers = {"X-Api-Key": merchant["apiKey"]}
    _, analyst = post(opener, base_url, "/risk/operators", {"role": "ANALYST"},
                      merchant_headers, prefix + "-analyst")
    _, manager = post(opener, base_url, "/risk/operators", {"role": "RISK_MANAGER"},
                      merchant_headers, prefix + "-manager")
    analyst_headers = {"X-Risk-Operator-Key": analyst["credential"]}
    manager_headers = {"X-Risk-Operator-Key": manager["credential"]}
    _, customer = post(opener, base_url, "/customers", {"email": f"{prefix}@example.test"}, merchant_headers)
    _, method = post(opener, base_url, "/payment_methods",
                     {"customerId": customer["id"], "cardNumber": "4242424242424242"},
                     merchant_headers, prefix + "-method")
    _, intent = post(opener, base_url, "/payment_intents",
                     {"customerId": customer["id"], "amount": 6100, "currency": "USD",
                      "captureMethod": "AUTOMATIC"}, merchant_headers, prefix + "-intent")
    _, confirmed = post(opener, base_url, f"/payment_intents/{intent['id']}/confirm",
                        {"paymentMethodId": method["id"]}, merchant_headers, prefix + "-confirm")
    if confirmed["status"] != "AUTHORIZED":
        raise DemoFailure("demo payment did not reach approved authorization")
    now = datetime.datetime.now(datetime.timezone.utc)
    chargeback_body = {
        "externalEventKey": prefix + ".chargeback", "paymentIntentId": intent["id"],
        "amount": 6100, "currency": "USD", "reasonCode": "FRAUDULENT_STOLEN_CREDENTIAL",
        "evidenceOccurredAt": utc(now - datetime.timedelta(hours=2)),
        "receivedAt": utc(now - datetime.timedelta(minutes=1)),
        "evidenceDueAt": utc(now + datetime.timedelta(days=1)),
    }
    _, chargeback = post(opener, base_url, "/chargebacks", chargeback_body,
                         merchant_headers, prefix + "-chargeback", expected=(201,))
    _, initial = request_json(opener, base_url, "GET", f"/risk/disputes/{chargeback['id']}",
                              headers=analyst_headers)
    if initial["lifecycleStatus"] != "DRAFT":
        raise DemoFailure("chargeback did not initialize one draft dispute workflow")
    if initial["dueAt"] == chargeback_body["evidenceDueAt"]:
        raise DemoFailure("workflow trusted caller-supplied deadline instead of governed catalogue")
    evidence_body = {"deliveryConfirmed": True, "customerCommunicationPresent": True,
                     "deviceContinuity": True}
    post(opener, base_url, f"/risk/disputes/{chargeback['id']}/evidence-versions",
         evidence_body, analyst_headers, prefix + "-analyst-evidence", expected=(403,))
    _, ready = post(opener, base_url, f"/risk/disputes/{chargeback['id']}/evidence-versions",
                    evidence_body, manager_headers, prefix + "-manager-evidence")
    if ready["lifecycleStatus"] != "READY" or ready["latestEvidenceVersion"] != 1:
        raise DemoFailure("closed evidence facts did not create ready immutable version 1")
    post(opener, base_url, f"/risk/disputes/{chargeback['id']}/submit",
         {"expectedEvidenceVersion": 1}, analyst_headers, prefix + "-analyst-submit", expected=(403,))
    submitted_raw, submitted = post(opener, base_url, f"/risk/disputes/{chargeback['id']}/submit",
                                    {"expectedEvidenceVersion": 1}, manager_headers,
                                    prefix + "-manager-submit")
    replay_raw, _ = post(opener, base_url, f"/risk/disputes/{chargeback['id']}/submit",
                         {"expectedEvidenceVersion": 1}, manager_headers, prefix + "-manager-submit")
    if submitted_raw != replay_raw or submitted["lifecycleStatus"] != "SUBMITTED":
        raise DemoFailure("manager submission replay was not byte-exact")
    outcome_body = {"externalEventKey": prefix + ".outcome", "outcome": "WON",
                    "occurredAt": utc(datetime.datetime.now(datetime.timezone.utc))}
    scenario_headers = {**merchant_headers, "X-Thaap-Scenario-Runner-Token": scenario_token}
    outcome_raw, outcome = post(opener, base_url, f"/simulator/disputes/{chargeback['id']}/outcomes",
                                outcome_body, scenario_headers, prefix + "-outcome")
    outcome_replay, _ = post(opener, base_url, f"/simulator/disputes/{chargeback['id']}/outcomes",
                             outcome_body, scenario_headers, prefix + "-outcome")
    if outcome_raw != outcome_replay or outcome["outcome"] != "WON":
        raise DemoFailure("trusted simulator outcome replay was not byte-exact")
    merchant_id = uuid.UUID(merchant["id"])
    workflow_id = uuid.UUID(initial["id"])
    reconciliation = postgres_scalar(f"""
        SELECT (SELECT lifecycle_status FROM dispute_workflows WHERE id = '{workflow_id}'::uuid) || '|' ||
               (SELECT COUNT(*) FROM dispute_evidence_versions WHERE dispute_workflow_id = '{workflow_id}'::uuid) || '|' ||
               (SELECT COUNT(*) FROM dispute_workflow_events WHERE dispute_workflow_id = '{workflow_id}'::uuid) || '|' ||
               (SELECT COUNT(*) FROM ledger_entries WHERE merchant_id = '{merchant_id}'::uuid
                  AND payment_intent_id = '{uuid.UUID(intent['id'])}'::uuid) || '|' ||
               (SELECT COUNT(*) FROM fraud_labels WHERE merchant_id = '{merchant_id}'::uuid
                  AND payment_intent_id = '{uuid.UUID(intent['id'])}'::uuid);
    """)
    if reconciliation != "WON|1|4|4|1":
        raise DemoFailure(f"durable dispute reconciliation failed: {reconciliation}")
    print("Dispute operations demo passed.")
    print("workflow=DRAFT->READY->SUBMITTED->WON evidence=immutable-v1 deadline=governed")
    print("role_boundary=analyst-read-only|manager-evidence-and-submit "
          "outcome=trusted-simulator|byte-exact")
    print("money_and_fraud=unchanged readiness=UP reconciliation=WON|1|4|4|1")


if __name__ == "__main__":
    try:
        run()
    except (DemoFailure, KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
        print(f"Dispute operations demo failed: {error}", file=sys.stderr)
        raise SystemExit(1)
