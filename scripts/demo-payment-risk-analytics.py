#!/usr/bin/env python3
"""Public source-to-Kafka-to-analytics-to-rebuild/replay proof for Phase 5."""

from __future__ import annotations

import contextlib
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
        raise DemoFailure("Payment-risk analytics demo rejects redirects")


def require_loopback(base_url: str) -> str:
    parsed = urllib.parse.urlsplit(base_url.rstrip("/"))
    if parsed.scheme != "http" or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
        raise DemoFailure("Payment-risk analytics demo accepts only a loopback HTTP API origin")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise DemoFailure("Payment-risk analytics demo API origin contains prohibited components")
    return base_url.rstrip("/")


def postgres_scalar(query: str) -> str:
    normalized = " ".join(query.strip().lower().split())
    if not normalized.startswith("select "):
        raise DemoFailure("Payment-risk reconciliation accepts only SELECT")
    for prohibited in (" insert ", " update ", " delete ", " alter ", " drop ",
                       " truncate ", " create ", " grant ", " revoke "):
        if prohibited in f" {normalized} ":
            raise DemoFailure("Payment-risk reconciliation rejected mutating SQL")
    command = [
        "docker", "exec", "-i", "thaap-postgres", "psql", "-X", "-A", "-t",
        "-U", os.environ.get("THAAP_POSTGRES_USER", "thaap"),
        "-d", os.environ.get("THAAP_POSTGRES_DB", "thaap_payments"), "-c", query,
    ]
    try:
        result = subprocess.run(command, check=True, capture_output=True,
                                text=True, timeout=12)
    except (FileNotFoundError, subprocess.CalledProcessError,
            subprocess.TimeoutExpired) as error:
        raise DemoFailure("read-only reconciliation requires local thaap-postgres") from error
    return result.stdout.strip()


def broker_command(action: str) -> None:
    if action not in {"start", "stop"}:
        raise DemoFailure("Broker control action is not allowlisted")
    try:
        subprocess.run(["docker", action, "thaap-kafka"], check=True,
                       capture_output=True, text=True, timeout=30)
    except (FileNotFoundError, subprocess.CalledProcessError,
            subprocess.TimeoutExpired) as error:
        raise DemoFailure(f"local Kafka {action} failed") from error


@contextlib.contextmanager
def broker_outage():
    broker_command("stop")
    try:
        yield
    finally:
        broker_command("start")


def request_json(opener, base_url, method, path, body=None, headers=None):
    payload = None if body is None else json.dumps(body, separators=(",", ":")).encode()
    request_headers = {"Accept": "application/json", **(headers or {})}
    if payload is not None:
        request_headers["Content-Type"] = "application/json"
    request = urllib.request.Request(base_url + path, data=payload,
                                     headers=request_headers, method=method)
    try:
        with opener.open(request, timeout=15) as response:
            raw = response.read()
            if response.status not in (200, 201, 202):
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


def utc(value: datetime.datetime) -> str:
    return value.isoformat(timespec="microseconds").replace("+00:00", "Z")


def require_uuid_field(value, field):
    raw = value.get(field) if isinstance(value, dict) else None
    try:
        return uuid.UUID(raw)
    except (AttributeError, TypeError, ValueError) as error:
        raise DemoFailure(f"response field {field} must be a UUID") from error


def require_durable_evidence(value):
    try:
        counts = [int(field) for field in value.split("|")]
    except (AttributeError, ValueError) as error:
        raise DemoFailure("durable evidence has an invalid shape") from error
    if (len(counts) != 5 or counts[0] != 10 or counts[1] < counts[0]
            or counts[2] != counts[0] or counts[3:] != [5, 1]):
        raise DemoFailure(f"durable evidence did not reconcile: {value}")
    return counts


def wait_until(label, reader, predicate, seconds=45):
    deadline = time.monotonic() + seconds
    last = None
    while time.monotonic() < deadline:
        try:
            last = reader()
            if predicate(last):
                return last
        except DemoFailure as error:
            last = str(error)
        time.sleep(0.2)
    raise DemoFailure(f"{label} did not converge: {last}")


def source_fingerprint(merchant_id: uuid.UUID) -> str:
    return postgres_scalar(f"""
        SELECT
          (SELECT COUNT(*) || ':' || COALESCE(md5(string_agg(id::text || status, ',' ORDER BY id)), '')
             FROM payment_intents WHERE merchant_id = '{merchant_id}'::uuid) || '|' ||
          (SELECT COUNT(*) || ':' || COALESCE(md5(string_agg(id::text || status, ',' ORDER BY id)), '')
             FROM payment_attempts WHERE merchant_id = '{merchant_id}'::uuid) || '|' ||
          (SELECT COUNT(*) || ':' || COALESCE(md5(string_agg(id::text || action, ',' ORDER BY id)), '')
             FROM fraud_decisions WHERE merchant_id = '{merchant_id}'::uuid) || '|' ||
          (SELECT COUNT(*) || ':' || COALESCE(md5(string_agg(id::text || direction || amount, ',' ORDER BY id)), '')
             FROM ledger_entries WHERE merchant_id = '{merchant_id}'::uuid) || '|' ||
          (SELECT COUNT(*) || ':' || COALESCE(md5(string_agg(id::text || status, ',' ORDER BY id)), '')
             FROM authorizations WHERE merchant_id = '{merchant_id}'::uuid) || '|' ||
          (SELECT COUNT(*) || ':' || COALESCE(md5(string_agg(id::text || event_record_sha256, ',' ORDER BY id)), '')
             FROM domain_events WHERE merchant_id = '{merchant_id}'::uuid);
    """)


def data_operator_key(opener, base_url, prefix):
    configured = os.environ.get("THAAP_PLATFORM_DATA_OPERATOR_KEY")
    if configured:
        return configured
    bootstrap_token = os.environ.get("THAAP_PLATFORM_BOOTSTRAP_TOKEN")
    if not bootstrap_token:
        raise DemoFailure("THAAP_PLATFORM_BOOTSTRAP_TOKEN or data-operator key is required")
    _, administrator = post(
        opener, base_url, "/internal/platform/operators/bootstrap",
        {"displayName": "Phase 5 demo administrator"},
        {"X-Thaap-Platform-Bootstrap-Token": bootstrap_token}, prefix + "-platform-admin")
    credential = administrator.get("credential")
    if not credential:
        raise DemoFailure("platform bootstrap replay did not reveal a reusable credential")
    _, operator = post(
        opener, base_url, "/internal/platform/operators",
        {"displayName": "Phase 5 demo data operator", "role": "DATA_OPERATOR"},
        {"X-Platform-Operator-Key": credential}, prefix + "-data-operator")
    if not operator.get("credential"):
        raise DemoFailure("data operator creation did not reveal a credential")
    return operator["credential"]


def create_confirmed_payment(opener, base_url, merchant_headers, customer_id,
                             payment_method_id, prefix, amount):
    _, intent = post(opener, base_url, "/payment_intents",
                     {"customerId": customer_id, "amount": amount, "currency": "USD",
                      "captureMethod": "AUTOMATIC"}, merchant_headers, prefix + "-intent")
    post(opener, base_url, f"/payment_intents/{intent['id']}/confirm",
         {"paymentMethodId": payment_method_id}, merchant_headers, prefix + "-confirm")
    return intent


def run() -> None:
    base_url = require_loopback(os.environ.get("THAAP_BASE_URL", "http://localhost:8080"))
    admin_token = os.environ.get("THAAP_ADMIN_BOOTSTRAP_TOKEN")
    if not admin_token:
        raise DemoFailure("THAAP_ADMIN_BOOTSTRAP_TOKEN is required")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), RejectRedirects())
    wait_until("API readiness", lambda: request_json(
        opener, base_url, "GET", "/actuator/health/readiness")[1],
        lambda health: health.get("status") == "UP", 30)
    demo_id = f"{int(time.time())}-{uuid.uuid4().hex[:8]}"
    prefix = f"payment-risk-analytics-{demo_id}"

    _, merchant = post(opener, base_url, "/admin/merchants",
                       {"name": f"Payment risk analytics {demo_id}"},
                       {"X-Admin-Bootstrap-Token": admin_token}, prefix + "-merchant")
    merchant_id = uuid.UUID(merchant["id"])
    merchant_headers = {"X-Api-Key": merchant["apiKey"]}
    _, manager = post(opener, base_url, "/risk/operators", {"role": "RISK_MANAGER"},
                      merchant_headers, prefix + "-manager")
    manager_headers = {"X-Risk-Operator-Key": manager["credential"]}
    platform_headers = {"X-Platform-Operator-Key": data_operator_key(
        opener, base_url, prefix)}
    _, customer = post(opener, base_url, "/customers",
                       {"email": f"{prefix}@example.test"}, merchant_headers)
    _, method = post(opener, base_url, "/payment_methods",
                     {"customerId": customer["id"], "cardNumber": "4242424242424242"},
                     merchant_headers, prefix + "-method")

    cohort_start = datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(seconds=1)
    with broker_outage():
        first_intent = create_confirmed_payment(opener, base_url, merchant_headers,
                                                customer["id"], method["id"],
                                                prefix + "-outage", 4200)

    def analytics_page():
        return request_json(opener, base_url, "GET", "/risk/analytics/payments?limit=100",
                            headers=manager_headers)[1]

    page = wait_until("outbox publication and projection", analytics_page,
                      lambda value: any(item["paymentIntentId"] == first_intent["id"]
                                        and item["lastStreamVersion"] >= 5
                                        for item in value.get("data", [])))
    request_json(opener, base_url, "GET",
                 f"/risk/analytics/payments/{first_intent['id']}", headers=manager_headers)
    analytics_from = cohort_start.replace(minute=0, second=0, microsecond=0)
    analytics_range = urllib.parse.urlencode({
        "from": utc(analytics_from),
        "to": utc(datetime.datetime.now(datetime.timezone.utc)),
        "originPolicy": "ALL",
    })
    summary = request_json(opener, base_url, "GET",
                           f"/risk/analytics/merchant/summary?{analytics_range}",
                           headers=manager_headers)[1]
    timeseries_range = analytics_range + "&interval=HOUR&limit=100"
    timeseries = request_json(opener, base_url, "GET",
                              f"/risk/analytics/merchant/timeseries?{timeseries_range}",
                              headers=manager_headers)[1]
    status_before = request_json(opener, base_url, "GET", "/risk/analytics/projections",
                                 headers=manager_headers)[1]
    if not summary.get("currencies") or not timeseries.get("points"):
        raise DemoFailure("merchant analytics did not expose durable aggregate truth")
    baseline_generation = next(item["generationId"] for item in status_before["generations"]
                               if item["projectionName"] == "payment-risk-v1"
                               and item["lifecycleStatus"] == "ACTIVE")

    broker_command("stop")
    broker_command("start")
    second_intent = create_confirmed_payment(opener, base_url, merchant_headers,
                                             customer["id"], method["id"],
                                             prefix + "-restart", 5100)
    wait_until("producer and consumer reconnect", analytics_page,
               lambda value: any(item["paymentIntentId"] == second_intent["id"]
                                 and item["lastStreamVersion"] >= 5
                                 for item in value.get("data", [])))
    cohort_end = datetime.datetime.now(datetime.timezone.utc)
    source_before = source_fingerprint(merchant_id)

    _, rebuild = post(opener, base_url,
                      "/internal/data/projections/payment-risk-v1/rebuilds",
                      {"trafficOriginPolicy": "ALL"}, platform_headers,
                      prefix + "-rebuild")
    rebuild_id = rebuild["rebuildId"]
    rebuild = wait_until("shadow rebuild", lambda: request_json(
        opener, base_url, "GET",
        f"/internal/data/projections/payment-risk-v1/rebuilds/{rebuild_id}",
        headers=platform_headers)[1],
        lambda value: value.get("lifecycleStatus") == "CATCHING_UP")
    _, reconciliation = post(
        opener, base_url,
        f"/internal/data/projections/payment-risk-v1/rebuilds/{rebuild_id}/reconcile",
        None, platform_headers, prefix + "-reconcile")
    if not reconciliation.get("passed") or not reconciliation.get("catchUpComplete"):
        raise DemoFailure(f"shadow reconciliation did not pass: {reconciliation}")

    dataset_body = {
        "eventCutoffManifestId": rebuild["cutoffManifestId"],
        "projectionGenerationId": rebuild["generationId"],
        "compatibilitySimulationRunId": None,
        "cohortStart": utc(cohort_start), "cohortEnd": utc(cohort_end),
        "labelAvailabilityCutoff": utc(cohort_end),
        "trafficOriginPolicy": "LIVE_ONLY",
    }
    _, dataset = post(opener, base_url, "/risk/replay-datasets", dataset_body,
                      manager_headers, prefix + "-dataset")
    dataset_id = require_uuid_field(dataset, "datasetId")
    dataset = wait_until("offline replay dataset", lambda: request_json(
        opener, base_url, "GET", f"/risk/replay-datasets/{dataset_id}",
        headers=manager_headers)[1],
        lambda value: value.get("lifecycleStatus") == "SUCCEEDED")

    _, activation = post(
        opener, base_url,
        f"/internal/data/projections/payment-risk-v1/rebuilds/{rebuild_id}/activate",
        None, platform_headers, prefix + "-activate")
    if not activation.get("activated"):
        raise DemoFailure(f"shadow generation activation failed: {activation}")
    _, rollback = post(opener, base_url,
                       "/internal/data/projections/payment-risk-v1/rollback",
                       {"targetGenerationId": baseline_generation}, platform_headers,
                       prefix + "-rollback")
    if not rollback.get("activated"):
        raise DemoFailure(f"retained-generation rollback failed: {rollback}")

    source_after = source_fingerprint(merchant_id)
    if source_after != source_before:
        raise DemoFailure(f"rebuild/replay changed source truth: {source_before} -> {source_after}")
    durable = postgres_scalar(f"""
        SELECT
          (SELECT COUNT(*) FROM domain_events WHERE merchant_id = '{merchant_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM event_publication_attempts attempt
             JOIN domain_events event
               ON event.merchant_id = attempt.merchant_id
              AND event.id = attempt.domain_event_id
            WHERE event.merchant_id = '{merchant_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM consumer_inbox WHERE merchant_id = '{merchant_id}'::uuid
              AND lifecycle_status = 'APPLIED') || '|' ||
          (SELECT COUNT(*) FROM projection_reconciliations
              WHERE projection_rebuild_id = '{uuid.UUID(rebuild_id)}'::uuid
                AND result_status = 'PASS') || '|' ||
          (SELECT COUNT(*) FROM replay_dataset_manifests
              WHERE merchant_id = '{merchant_id}'::uuid
                AND id = '{dataset_id}'::uuid
                AND lifecycle_status = 'SUCCEEDED');
    """)
    require_durable_evidence(durable)

    print("Payment-risk analytics demo passed.")
    print(f"outage_recovery=proved broker_restart=proved analytics={page['freshness']['state']}")
    print(f"rebuild={rebuild_id} reconciliation=5/5 activation=proved rollback=proved")
    print(f"dataset={dataset_id} status=SUCCEEDED source_truth=unchanged durable={durable}")


if __name__ == "__main__":
    try:
        run()
    except (DemoFailure, KeyError, StopIteration, TypeError, ValueError,
            json.JSONDecodeError) as error:
        print(f"Payment-risk analytics demo failed: {error}", file=sys.stderr)
        raise SystemExit(1)
