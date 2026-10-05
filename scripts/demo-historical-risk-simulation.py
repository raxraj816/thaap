#!/usr/bin/env python3
"""Public point-in-time risk-simulation and PostgreSQL reconciliation demo."""

from __future__ import annotations

import datetime
import hashlib
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
        raise DemoFailure("Historical simulation demo rejects redirects")


def require_loopback(base_url: str) -> str:
    parsed = urllib.parse.urlsplit(base_url.rstrip("/"))
    if parsed.scheme != "http" or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
        raise DemoFailure("Historical simulation demo accepts only a loopback HTTP API origin")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise DemoFailure("Historical simulation demo API origin contains prohibited components")
    return base_url.rstrip("/")


def postgres_scalar(query: str) -> str:
    normalized = query.strip().lower()
    if not normalized.startswith("select"):
        raise DemoFailure("Historical simulation reconciliation accepts only SELECT")
    for prohibited in (" insert ", " update ", " delete ", " alter ", " drop ", " truncate "):
        if prohibited in f" {normalized} ":
            raise DemoFailure("Historical simulation reconciliation rejected mutating SQL")
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


def post(opener, base_url, path, body, headers, key=None):
    request_headers = dict(headers)
    if key is not None:
        request_headers["Idempotency-Key"] = key
    return request_json(opener, base_url, "POST", path, body, request_headers)


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
    raise DemoFailure("Historical simulation API readiness did not become UP")


def wait_for_report(opener, base_url, manager_headers, simulation_id):
    deadline = time.monotonic() + 30
    observed = []
    while time.monotonic() < deadline:
        _, run = request_json(opener, base_url, "GET",
                              f"/risk/simulations/{simulation_id}", headers=manager_headers)
        observed.append(run["lifecycleStatus"])
        if run["lifecycleStatus"] == "SUCCEEDED":
            _, report = request_json(opener, base_url, "GET",
                                     f"/risk/simulations/{simulation_id}/report",
                                     headers=manager_headers)
            return run, report, observed
        if run["lifecycleStatus"] in {"FAILED", "CANCELED"}:
            raise DemoFailure(f"simulation terminated as {run['lifecycleStatus']}: {run}")
        time.sleep(0.1)
    raise DemoFailure("Historical simulation did not complete within 30 seconds")


def run() -> None:
    base_url = require_loopback(os.environ.get("THAAP_BASE_URL", "http://localhost:8080"))
    admin_token = os.environ.get("THAAP_ADMIN_BOOTSTRAP_TOKEN")
    if not admin_token:
        raise DemoFailure("THAAP_ADMIN_BOOTSTRAP_TOKEN is required")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), RejectRedirects())
    wait_readiness(opener, base_url)
    demo_id = f"{int(time.time())}-{uuid.uuid4().hex[:8]}"
    prefix = f"historical-simulation-{demo_id}"

    _, merchant = post(opener, base_url, "/admin/merchants",
                       {"name": f"Historical simulation {demo_id}"},
                       {"X-Admin-Bootstrap-Token": admin_token}, prefix + "-merchant")
    merchant_id = uuid.UUID(merchant["id"])
    merchant_headers = {"X-Api-Key": merchant["apiKey"]}
    _, manager = post(opener, base_url, "/risk/operators", {"role": "RISK_MANAGER"},
                      merchant_headers, prefix + "-manager")
    manager_headers = {"X-Risk-Operator-Key": manager["credential"]}
    _, policy = post(opener, base_url, "/risk/policies",
                     {"code": "HISTORICAL_DEMO", "displayName": "Historical demo candidate"},
                     manager_headers, prefix + "-policy")
    post(opener, base_url, f"/risk/policies/{policy['id']}/thresholds", {
        "code": "AMOUNT_REVIEW", "featureCode": "payment.amount_minor", "operator": "GTE",
        "longValue": 100, "candidateAction": "REVIEW", "priority": 100,
        "reasonCode": "MERCHANT_AMOUNT_THRESHOLD",
    }, manager_headers, prefix + "-threshold")

    _, customer = post(opener, base_url, "/customers",
                       {"email": f"{prefix}@example.test"}, merchant_headers)
    _, method = post(opener, base_url, "/payment_methods",
                     {"customerId": customer["id"], "cardNumber": "4242424242424242"},
                     merchant_headers, prefix + "-method")
    cohort_start = datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(seconds=1)
    _, intent = post(opener, base_url, "/payment_intents",
                     {"customerId": customer["id"], "amount": 1200, "currency": "USD",
                      "captureMethod": "MANUAL"}, merchant_headers, prefix + "-intent")
    post(opener, base_url, f"/payment_intents/{intent['id']}/confirm",
         {"paymentMethodId": method["id"]}, merchant_headers, prefix + "-confirm")
    as_of = datetime.datetime.now(datetime.timezone.utc)

    domain_before = postgres_scalar(f"""
        SELECT (SELECT COUNT(*) FROM payment_attempts WHERE merchant_id = '{merchant_id}'::uuid) || '|' ||
               (SELECT COUNT(*) FROM fraud_decisions WHERE merchant_id = '{merchant_id}'::uuid) || '|' ||
               (SELECT COUNT(*) FROM ledger_entries WHERE merchant_id = '{merchant_id}'::uuid) || '|' ||
               (SELECT COUNT(*) FROM fraud_labels WHERE merchant_id = '{merchant_id}'::uuid);
    """)
    simulation_body = {
        "candidateType": "MERCHANT_RISK_POLICY", "candidateVersionId": policy["id"],
        "cohortStart": utc(cohort_start), "cohortEnd": utc(as_of), "asOf": utc(as_of),
        "labelCutoff": utc(as_of), "trafficOriginPolicy": "LIVE_ONLY",
    }
    first_raw, queued = post(opener, base_url, "/risk/simulations", simulation_body,
                             manager_headers, prefix + "-simulation")
    replay_raw, _ = post(opener, base_url, "/risk/simulations", simulation_body,
                         manager_headers, prefix + "-simulation")
    if first_raw != replay_raw:
        raise DemoFailure("simulation creation replay was not byte-exact")

    completed, report, observed = wait_for_report(
        opener, base_url, manager_headers, queued["id"])
    population = report["report"]["population"]
    transitions = report["report"]["actionTransitions"]
    if population != {"eligible": 1, "excluded": 0, "processed": 1}:
        raise DemoFailure(f"simulation population did not reconcile: {population}")
    if transitions != {"ALLOW->REVIEW": 1}:
        raise DemoFailure(f"candidate transition was not point-in-time deterministic: {transitions}")
    canonical = json.dumps(report["report"], separators=(",", ":"), ensure_ascii=False)
    if hashlib.sha256(canonical.encode()).hexdigest() != report["reportDigest"]:
        raise DemoFailure("public report bytes did not reproduce the stored SHA-256 digest")

    simulation_id = uuid.UUID(queued["id"])
    reconciliation = postgres_scalar(f"""
        SELECT (SELECT COUNT(*) FROM risk_simulation_runs
                 WHERE merchant_id = '{merchant_id}'::uuid AND id = '{simulation_id}'::uuid
                   AND lifecycle_status = 'SUCCEEDED' AND processed_count = 1) || '|' ||
               (SELECT COUNT(*) FROM risk_simulation_results
                 WHERE merchant_id = '{merchant_id}'::uuid AND simulation_run_id = '{simulation_id}'::uuid) || '|' ||
               (SELECT COUNT(*) FROM risk_simulation_reports
                 WHERE merchant_id = '{merchant_id}'::uuid AND simulation_run_id = '{simulation_id}'::uuid
                   AND report_digest = '{report['reportDigest']}') || '|' ||
               (SELECT COUNT(*) FROM risk_simulation_events
                 WHERE merchant_id = '{merchant_id}'::uuid AND simulation_run_id = '{simulation_id}'::uuid) || '|' ||
               (SELECT COUNT(*) FROM batch_job_execution_params
                 WHERE parameter_name = 'runId' AND parameter_value = '{simulation_id}');
    """)
    if reconciliation != "1|1|1|4|1":
        raise DemoFailure(f"simulation durable reconciliation failed: {reconciliation}")
    domain_after = postgres_scalar(f"""
        SELECT (SELECT COUNT(*) FROM payment_attempts WHERE merchant_id = '{merchant_id}'::uuid) || '|' ||
               (SELECT COUNT(*) FROM fraud_decisions WHERE merchant_id = '{merchant_id}'::uuid) || '|' ||
               (SELECT COUNT(*) FROM ledger_entries WHERE merchant_id = '{merchant_id}'::uuid) || '|' ||
               (SELECT COUNT(*) FROM fraud_labels WHERE merchant_id = '{merchant_id}'::uuid);
    """)
    if domain_after != domain_before:
        raise DemoFailure(f"simulation changed payment/fraud/ledger/label truth: {domain_before} -> {domain_after}")

    print("Historical risk simulation demo passed.")
    print(f"lifecycle={'->'.join(dict.fromkeys(observed))} population=1/1 excluded=0")
    print("transition=ALLOW->REVIEW report_digest=reproduced creation_replay=byte_exact")
    print("payment_fraud_ledger_label_truth=unchanged jdbc_batch_metadata=proved readiness=UP")


if __name__ == "__main__":
    try:
        run()
    except (DemoFailure, KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
        print(f"Historical risk simulation demo failed: {error}", file=sys.stderr)
        raise SystemExit(1)
