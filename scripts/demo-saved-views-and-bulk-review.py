#!/usr/bin/env python3
"""Public saved investigation views and bounded bulk review assignment demo.

Shows the two saved-view and bulk-assignment promises against a running local sandbox: a saved view stores a typed
definition rather than a copy of its results, and a bounded bulk assignment distributes review
work with per-item truth — including an item that deliberately fails, because a bulk API that can
only be demonstrated succeeding has not been demonstrated at all.

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
        raise DemoFailure("Bulk review demo rejects redirects")


def require_loopback(base_url: str) -> str:
    parsed = urllib.parse.urlsplit(base_url)
    if parsed.scheme != "http" or parsed.hostname not in ("localhost", "127.0.0.1", "::1"):
        raise DemoFailure("Bulk review demo requires a loopback http origin")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise DemoFailure("Bulk review demo API origin contains prohibited components")
    return base_url.rstrip("/")


def postgres_scalar(query: str) -> str:
    normalized = query.strip().lower()
    if not normalized.startswith("select"):
        raise DemoFailure("Bulk review reconciliation accepts only SELECT")
    for prohibited in (" insert ", " update ", " delete ", " alter ", " drop ", " truncate "):
        if prohibited in f" {normalized} ":
            raise DemoFailure("Bulk review reconciliation rejected mutating SQL")
    command = [
        "docker", "exec", "-i", "thaap-postgres", "psql", "-X", "-A", "-t",
        "-U", os.environ.get("THAAP_POSTGRES_USER", "thaap"),
        "-d", os.environ.get("THAAP_POSTGRES_DB", "thaap_payments"), "-c", query,
    ]
    try:
        result = subprocess.run(command, check=True, capture_output=True, text=True, timeout=10)
    except (FileNotFoundError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        raise DemoFailure(f"Bulk review reconciliation query failed: {error}") from error
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
    raise DemoFailure("Bulk review API readiness did not become UP")


def prohibited_fingerprint(merchant_id: uuid.UUID) -> str:
    """Digest of every table bulk assignment must never touch."""
    return postgres_scalar(f"""
        SELECT
          (SELECT COUNT(*) FROM review_resolutions WHERE merchant_id = '{merchant_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM fraud_labels WHERE merchant_id = '{merchant_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM chargebacks WHERE merchant_id = '{merchant_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM ledger_entries WHERE merchant_id = '{merchant_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM authored_rule_versions WHERE merchant_id = '{merchant_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM fraud_lists WHERE merchant_id = '{merchant_id}'::uuid);
    """)


def reviewed_payment(opener, base_url, merchant_headers, prefix, customer_id, method_id, label):
    """Three confirms drives attempt_number to the repeated-attempt threshold and opens review."""
    _, intent = post(opener, base_url, "/payment_intents",
                     {"customerId": customer_id, "amount": 99999,
                      "currency": "USD", "captureMethod": "MANUAL"},
                     merchant_headers, f"{prefix}-{label}-intent")
    for attempt in range(1, 4):
        post(opener, base_url, f"/payment_intents/{intent['id']}/confirm",
             {"paymentMethodId": method_id}, merchant_headers,
             f"{prefix}-{label}-confirm-{attempt}", expected=(200, 201, 409))
    return intent["id"]


def review_case_for(opener, base_url, manager_headers, payment_intent_id):
    _, detail = request_json(opener, base_url, "GET",
                             f"/risk/workspace/payments/{payment_intent_id}",
                             headers=manager_headers)
    review = detail.get("reviewAndLabels", {}).get("review")
    if not review or not review.get("reviewCaseId"):
        raise DemoFailure(f"payment {payment_intent_id} opened no review case")
    return review["reviewCaseId"], review.get("assignmentVersion", 0)


def poll_bulk(opener, base_url, manager_headers, action_id, terminal, attempts=60):
    for _ in range(attempts):
        _, run = request_json(opener, base_url, "GET",
                              f"/risk/workspace/review-bulk-actions/{action_id}",
                              headers=manager_headers)
        if run["lifecycleStatus"] in terminal:
            return run
        time.sleep(0.25)
    raise DemoFailure(f"bulk action {action_id} never reached {terminal}")


def run() -> None:
    base_url = require_loopback(os.environ.get("THAAP_BASE_URL", "http://localhost:8080"))
    admin_token = os.environ.get("THAAP_ADMIN_BOOTSTRAP_TOKEN")
    if not admin_token:
        raise DemoFailure("THAAP_ADMIN_BOOTSTRAP_TOKEN is required")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), RejectRedirects())
    wait_readiness(opener, base_url)
    run_id = f"{int(time.time())}-{uuid.uuid4().hex[:8]}"
    prefix = f"bulk-review-{run_id}"

    _, merchant = post(opener, base_url, "/admin/merchants",
                       {"name": f"Bulk review {run_id}"},
                       {"X-Admin-Bootstrap-Token": admin_token}, prefix + "-merchant")
    merchant_id = uuid.UUID(merchant["id"])
    merchant_headers = {"X-Api-Key": merchant["apiKey"]}
    _, manager = post(opener, base_url, "/risk/operators", {"role": "RISK_MANAGER"},
                      merchant_headers, prefix + "-manager")
    manager_headers = {"X-Risk-Operator-Key": manager["credential"]}
    _, analyst = post(opener, base_url, "/risk/operators", {"role": "ANALYST"},
                      merchant_headers, prefix + "-analyst")
    analyst_headers = {"X-Risk-Operator-Key": analyst["credential"]}
    _, customer = post(opener, base_url, "/customers",
                       {"email": f"{prefix}@example.test"}, merchant_headers)
    _, method = post(opener, base_url, "/payment_methods",
                     {"customerId": customer["id"], "cardNumber": "4999990000000003"},
                     merchant_headers, prefix + "-method")

    first = reviewed_payment(opener, base_url, merchant_headers, prefix,
                             customer["id"], method["id"], "one")
    second = reviewed_payment(opener, base_url, merchant_headers, prefix,
                              customer["id"], method["id"], "two")
    first_case, first_version = review_case_for(opener, base_url, manager_headers, first)
    second_case, second_version = review_case_for(opener, base_url, manager_headers, second)

    # --- Saved view: a definition, not a copy of results -----------------------------------
    _, view = post(opener, base_url, "/risk/workspace/payment-views",
                   {"name": f"Open reviews {run_id}", "visibility": "MERCHANT_SHARED",
                    "filter": {"fraudActions": ["REVIEW"]}, "sort": "CREATED_AT_DESC"},
                   manager_headers, prefix + "-view")
    if view["lifecycleVersion"] != 1 or view["lifecycleStatus"] != "ACTIVE":
        raise DemoFailure("saved view was not created active at version 1")
    stored_columns = postgres_scalar(f"""
        SELECT STRING_AGG(column_name, ',' ORDER BY column_name)
        FROM information_schema.columns
        WHERE table_name = 'payment_investigation_saved_views';
    """)
    for forbidden in ("cursor", "result", "row_cache", "payload"):
        if forbidden in stored_columns:
            raise DemoFailure(f"saved view table stores '{forbidden}', so it can serve stale rows")

    _, executed = post(opener, base_url,
                       f"/risk/workspace/payment-views/{view['id']}/execute?limit=25",
                       None, manager_headers)
    if "freshness" not in executed:
        raise DemoFailure("executing a saved view did not report projection freshness")

    # A shared view is readable by the analyst who did not create it.
    _, analyst_views = request_json(opener, base_url, "GET", "/risk/workspace/payment-views",
                                    headers=analyst_headers)
    if view["id"] not in {row["id"] for row in analyst_views}:
        raise DemoFailure("merchant-shared view was not visible to another operator")

    # --- Bulk assignment: bounded, idempotent, per-item truth ------------------------------
    before = prohibited_fingerprint(merchant_id)

    absent_case = str(uuid.uuid4())
    items = [
        {"reviewCaseId": first_case, "expectedAssignmentVersion": first_version},
        {"reviewCaseId": second_case, "expectedAssignmentVersion": second_version + 99},
        {"reviewCaseId": absent_case, "expectedAssignmentVersion": 0},
    ]
    idempotency_key = prefix + "-bulk"
    _, started = post(opener, base_url, "/risk/workspace/review-bulk-actions",
                      {"action": "ASSIGN_TO_SELF", "items": items},
                      manager_headers, idempotency_key, expected=(202,))
    if started["itemCount"] != 3:
        raise DemoFailure("bulk request did not admit exactly the three submitted items")

    # An exact replay returns the same run rather than starting a second one.
    _, replay = post(opener, base_url, "/risk/workspace/review-bulk-actions",
                     {"action": "ASSIGN_TO_SELF", "items": items},
                     manager_headers, idempotency_key, expected=(202,))
    if replay["id"] != started["id"]:
        raise DemoFailure("idempotent replay started a second bulk run")

    # A changed body under the same key is a conflict, never a silent re-interpretation.
    conflict_body = {"action": "ASSIGN_TO_SELF", "items": items[:1]}
    _, conflict = post(opener, base_url, "/risk/workspace/review-bulk-actions",
                       conflict_body, manager_headers, idempotency_key, expected=(409,))
    if conflict.get("error") != "bulk_idempotency_mismatch":
        raise DemoFailure("changed replay was not refused as an idempotency mismatch")

    run_state = poll_bulk(opener, base_url, manager_headers, started["id"],
                          {"COMPLETED", "PARTIAL", "FAILED"})
    if run_state["lifecycleStatus"] != "PARTIAL":
        raise DemoFailure(f"mixed run reported {run_state['lifecycleStatus']}, not PARTIAL")
    if run_state["succeededCount"] != 1 or run_state["failedCount"] != 2:
        raise DemoFailure("partial run did not report exact per-item counts")

    _, item_page = request_json(opener, base_url, "GET",
                                f"/risk/workspace/review-bulk-actions/{started['id']}/items",
                                headers=manager_headers)
    outcomes = {row["reviewCaseId"]: row["lifecycleStatus"] for row in item_page["data"]}
    if outcomes.get(first_case) != "SUCCEEDED":
        raise DemoFailure("the eligible item did not succeed")
    if outcomes.get(second_case) != "STALE":
        raise DemoFailure("the stale-version item did not report STALE")
    if outcomes.get(absent_case) != "NOT_FOUND":
        raise DemoFailure("the absent review did not report NOT_FOUND")

    # --- Scope: bulk assignment changed nothing it must not ---------------------------------
    if prohibited_fingerprint(merchant_id) != before:
        raise DemoFailure("bulk assignment altered resolution, label, money, or control state")

    assignment_events = postgres_scalar(f"""
        SELECT COUNT(*) FROM review_assignment_events
        WHERE merchant_id = '{merchant_id}'::uuid;
    """)
    if assignment_events != "1":
        raise DemoFailure(
            f"expected exactly one assignment event for one successful item, saw {assignment_events}")

    # --- Scope: this chapter owns no insight endpoint ----------------------------------------
    for insight_route in ("/risk/workspace/review-bulk-actions/insights",
                          "/risk/workspace/review-bulk-actions/summary"):
        try:
            request_json(opener, base_url, "GET", insight_route,
                         headers=manager_headers, expected=(404, 405))
        except DemoFailure as error:
            raise DemoFailure(f"bulk surface exposed an aggregate route: {error}") from error

    print(json.dumps({
        "demo": "saved-views-and-bulk-review",
        "savedView": {"id": view["id"], "visibility": view["visibility"],
                      "lifecycleVersion": view["lifecycleVersion"]},
        "bulkAction": {"id": started["id"], "status": run_state["lifecycleStatus"],
                       "succeeded": run_state["succeededCount"],
                       "failed": run_state["failedCount"]},
        "itemOutcomes": outcomes,
        "prohibitedStateUnchanged": True,
    }, indent=2))


if __name__ == "__main__":
    try:
        run()
    except DemoFailure as failure:
        print(f"DEMO FAILED: {failure}", file=sys.stderr)
        sys.exit(1)
