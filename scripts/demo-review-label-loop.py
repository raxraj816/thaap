#!/usr/bin/env python3
"""Public analyst review-to-label demo with read-only durable reconciliation."""

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
        raise DemoFailure("Review and label demo rejects redirects")


def client() -> urllib.request.OpenerDirector:
    return urllib.request.build_opener(urllib.request.ProxyHandler({}), RejectRedirects())


def require_loopback(base_url: str) -> str:
    parsed = urllib.parse.urlsplit(base_url.rstrip("/"))
    if parsed.scheme != "http" or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
        raise DemoFailure("Review and label demo accepts only a loopback HTTP API origin")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise DemoFailure("Review and label demo API origin contains prohibited components")
    return base_url.rstrip("/")


def sql_literal(value: str) -> str:
    if "\x00" in value:
        raise DemoFailure("PostgreSQL reconciliation value contains a prohibited NUL")
    return "'" + value.replace("'", "''") + "'"


def request_json(opener: urllib.request.OpenerDirector, base_url: str, method: str, path: str,
                 body: dict | None = None,
                 headers: dict[str, str] | None = None) -> tuple[bytes, object]:
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
        detail = error.read().decode("utf-8", "replace")
        raise DemoFailure(f"HTTP {error.code} for {path}: {detail}") from error


def expect_error(opener: urllib.request.OpenerDirector, base_url: str, method: str, path: str,
                 expected_status: int, headers: dict[str, str]) -> object:
    request = urllib.request.Request(base_url + path, headers={"Accept": "application/json", **headers},
                                     method=method)
    try:
        opener.open(request, timeout=10)
    except urllib.error.HTTPError as error:
        detail = json.loads(error.read())
        if error.code != expected_status:
            raise DemoFailure(f"expected HTTP {expected_status} for {path}, received {error.code}") from error
        return detail
    raise DemoFailure(f"expected HTTP {expected_status} for {path}")


def postgres_scalar(query: str) -> str:
    normalized = query.strip().lower()
    if not normalized.startswith("select"):
        raise DemoFailure("Review and label reconciliation accepts only SELECT")
    for prohibited in (" insert ", " update ", " delete ", " alter ", " drop ", " truncate "):
        if prohibited in f" {normalized} ":
            raise DemoFailure("Review and label reconciliation rejected mutating SQL")
    user = os.environ.get("THAAP_POSTGRES_USER", "thaap")
    database = os.environ.get("THAAP_POSTGRES_DB", "thaap_payments")
    command = ["docker", "exec", "-i", "thaap-postgres", "psql", "-X", "-A", "-t",
               "-U", user, "-d", database, "-c", query]
    try:
        result = subprocess.run(command, check=True, capture_output=True, text=True, timeout=10)
    except (FileNotFoundError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        raise DemoFailure("read-only reconciliation requires the local thaap-postgres container") from error
    return result.stdout.strip()


def create_merchant(opener, base_url, admin_token, name, key):
    return request_json(opener, base_url, "POST", "/admin/merchants", {"name": name},
                        {"X-Admin-Bootstrap-Token": admin_token, "Idempotency-Key": key})[1]


def confirm(opener, base_url, merchant_headers, intent_id, payment_method_id, key):
    body = {"paymentMethodId": payment_method_id}
    headers = {**merchant_headers, "Idempotency-Key": key}
    original, attempt = request_json(opener, base_url, "POST",
                                     f"/payment_intents/{intent_id}/confirm", body, headers)
    replay, repeated = request_json(opener, base_url, "POST",
                                    f"/payment_intents/{intent_id}/confirm", body, headers)
    if replay != original or repeated != attempt:
        raise DemoFailure("confirm replay was not byte-exact")
    return attempt


def run() -> None:
    base_url = require_loopback(os.environ.get("THAAP_BASE_URL", "http://localhost:8080"))
    admin_token = os.environ.get("THAAP_ADMIN_BOOTSTRAP_TOKEN")
    if not admin_token:
        raise DemoFailure("THAAP_ADMIN_BOOTSTRAP_TOKEN is required")
    opener = client()
    request_json(opener, base_url, "GET", "/actuator/health")
    run_id = f"{int(time.time())}-{uuid.uuid4().hex[:8]}"

    merchant = create_merchant(opener, base_url, admin_token,
                               f"Review and label Demo {run_id}", f"ch26-{run_id}-merchant")
    merchant_headers = {"X-Api-Key": merchant["apiKey"]}
    _, customer = request_json(opener, base_url, "POST", "/customers",
                               {"email": f"review-label-{run_id}@example.com"}, merchant_headers)
    _, payment_method = request_json(
        opener, base_url, "POST", "/payment_methods",
        {"customerId": customer["id"], "cardNumber": "4999990000000003"},
        {**merchant_headers, "Idempotency-Key": f"ch26-{run_id}-method"})
    _, intent = request_json(
        opener, base_url, "POST", "/payment_intents",
        {"customerId": customer["id"], "amount": 99_999, "currency": "USD",
         "captureMethod": "MANUAL"},
        {**merchant_headers, "Idempotency-Key": f"ch26-{run_id}-intent"})

    for attempt_number in (1, 2):
        declined = confirm(opener, base_url, merchant_headers, intent["id"], payment_method["id"],
                           f"ch26-{run_id}-confirm-{attempt_number}")
        if declined["status"] != "DECLINED":
            raise DemoFailure("review setup decline did not reopen the intent")
    review_attempt = confirm(opener, base_url, merchant_headers, intent["id"], payment_method["id"],
                             f"ch26-{run_id}-confirm-3")
    if review_attempt["status"] != "IN_REVIEW" or review_attempt["processorAuthorizationId"] is not None:
        raise DemoFailure("review intake payment truth is incoherent")

    operator_body = {"role": "ANALYST"}
    operator_headers = {**merchant_headers, "Idempotency-Key": f"ch26-{run_id}-operator"}
    operator_bytes, operator = request_json(opener, base_url, "POST", "/risk/operators",
                                            operator_body, operator_headers)
    operator_replay_bytes, operator_replay = request_json(
        opener, base_url, "POST", "/risk/operators", operator_body, operator_headers)
    if operator_replay_bytes != operator_bytes or operator_replay != operator:
        raise DemoFailure("operator creation replay was not byte-exact")
    risk_headers = {"X-Risk-Operator-Key": operator["credential"]}

    _, reviews = request_json(opener, base_url, "GET", "/risk/reviews?status=OPEN&limit=100",
                              headers=risk_headers)
    matching = [case for case in reviews if case["paymentIntentId"] == intent["id"]]
    if len(matching) != 1:
        raise DemoFailure("operator review queue did not expose exactly one case")
    review = matching[0]

    _, webhook = request_json(
        opener, base_url, "POST", "/webhook_endpoints",
        {"url": "http://127.0.0.1:9191/review-label-demo",
         "enabledEvents": ["review.assigned", "payment_intent.canceled",
                            "review.resolved", "fraud_label.created"]},
        {**merchant_headers, "Idempotency-Key": f"ch26-{run_id}-webhook"})
    if set(webhook["enabledEvents"]) != {"review.assigned", "payment_intent.canceled",
                                         "review.resolved", "fraud_label.created"}:
        raise DemoFailure("webhook endpoint did not preserve explicit risk-event consent")

    assign_body = {"expectedAssignmentVersion": 0}
    assign_headers = {**risk_headers, "Idempotency-Key": f"ch26-{run_id}-assign"}
    assigned_bytes, assigned = request_json(
        opener, base_url, "POST", f"/risk/reviews/{review['id']}/assign", assign_body, assign_headers)
    assigned_replay_bytes, assigned_replay = request_json(
        opener, base_url, "POST", f"/risk/reviews/{review['id']}/assign", assign_body, assign_headers)
    if assigned_replay_bytes != assigned_bytes or assigned_replay != assigned:
        raise DemoFailure("review assignment replay was not byte-exact")
    if assigned["status"] != "ASSIGNED" or assigned["assignedOperatorId"] != operator["id"]:
        raise DemoFailure("review assignment truth is incoherent")

    request_json(opener, base_url, "POST", f"/risk/reviews/{review['id']}/annotations",
                 {"code": "PAYMENT_PATTERN_CONFIRMED"},
                 {**risk_headers, "Idempotency-Key": f"ch26-{run_id}-annotation"})

    merchant_id = uuid.UUID(merchant["id"])
    intent_id = uuid.UUID(intent["id"])
    money_before = postgres_scalar(f"""
        SELECT
          (SELECT COUNT(*) FROM authorizations authorization_row
             JOIN payment_attempts attempt
               ON attempt.merchant_id = authorization_row.merchant_id
              AND attempt.id = authorization_row.payment_attempt_id
           WHERE authorization_row.merchant_id = '{merchant_id}'::uuid
             AND attempt.payment_intent_id = '{intent_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM ledger_entries WHERE merchant_id = '{merchant_id}'::uuid
             AND payment_intent_id = '{intent_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM refunds WHERE merchant_id = '{merchant_id}'::uuid
             AND payment_intent_id = '{intent_id}'::uuid);
    """)

    evidence_time = (datetime.datetime.now(datetime.timezone.utc)
                     - datetime.timedelta(seconds=5)).isoformat().replace("+00:00", "Z")
    resolution_body = {
        "outcome": "CANCEL_AND_REPORT_FRAUD",
        "classification": "CONFIRMED_FRAUD",
        "fraudType": "CARD_TESTING",
        "reasonCode": "CARD_TESTING_CONFIRMED",
        "evidenceOccurredAt": evidence_time,
    }
    resolution_headers = {**risk_headers, "Idempotency-Key": f"ch26-{run_id}-resolve"}
    resolution_bytes, resolution = request_json(
        opener, base_url, "POST", f"/risk/reviews/{review['id']}/resolve",
        resolution_body, resolution_headers)
    resolution_replay_bytes, resolution_replay = request_json(
        opener, base_url, "POST", f"/risk/reviews/{review['id']}/resolve",
        resolution_body, resolution_headers)
    if resolution_replay_bytes != resolution_bytes or resolution_replay != resolution:
        raise DemoFailure("review resolution replay was not byte-exact")
    if resolution["outcome"] != "CANCEL_AND_REPORT_FRAUD" or resolution["label"] is None:
        raise DemoFailure("fraud resolution did not return its immutable label")

    _, terminal_review = request_json(opener, base_url, "GET", f"/risk/reviews/{review['id']}",
                                      headers=risk_headers)
    _, labels = request_json(opener, base_url, "GET", "/risk/labels?limit=100", headers=risk_headers)
    if terminal_review["status"] != "RESOLVED" or len(labels) != 1:
        raise DemoFailure("terminal review or label read truth is incoherent")
    label = labels[0]
    if label["id"] != resolution["label"]["id"] or label["fraudType"] != "CARD_TESTING":
        raise DemoFailure("label list does not reconcile to the resolution")
    request_json(opener, base_url, "GET", f"/risk/labels/{label['id']}", headers=risk_headers)
    _, decision = request_json(opener, base_url, "GET",
                               f"/fraud/decisions/{review_attempt['fraudDecisionId']}",
                               headers=merchant_headers)
    if decision["decision"]["action"] != "REVIEW":
        raise DemoFailure("analyst resolution rewrote the historical fraud decision")

    foreign = create_merchant(opener, base_url, admin_token,
                              f"Review and label Foreign {run_id}", f"ch26-{run_id}-foreign")
    _, foreign_operator = request_json(
        opener, base_url, "POST", "/risk/operators", {"role": "RISK_MANAGER"},
        {"X-Api-Key": foreign["apiKey"], "Idempotency-Key": f"ch26-{run_id}-foreign-operator"})
    foreign_error = expect_error(
        opener, base_url, "GET", f"/risk/reviews/{review['id']}", 404,
        {"X-Risk-Operator-Key": foreign_operator["credential"]})
    if foreign_error.get("status") != 404:
        raise DemoFailure("cross-tenant review substitution did not use missing-equivalent behavior")

    money_after = postgres_scalar(f"""
        SELECT
          (SELECT COUNT(*) FROM authorizations authorization_row
             JOIN payment_attempts attempt
               ON attempt.merchant_id = authorization_row.merchant_id
              AND attempt.id = authorization_row.payment_attempt_id
           WHERE authorization_row.merchant_id = '{merchant_id}'::uuid
             AND attempt.payment_intent_id = '{intent_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM ledger_entries WHERE merchant_id = '{merchant_id}'::uuid
             AND payment_intent_id = '{intent_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM refunds WHERE merchant_id = '{merchant_id}'::uuid
             AND payment_intent_id = '{intent_id}'::uuid);
    """)
    if money_after != money_before:
        raise DemoFailure(f"analyst cancellation changed processor/money truth: {money_before} -> {money_after}")

    summary = postgres_scalar(f"""
        SELECT
          (SELECT status FROM payment_intents WHERE id = '{intent_id}'::uuid) || '|' ||
          (SELECT status FROM payment_attempts WHERE id = '{review_attempt['id']}'::uuid) || '|' ||
          (SELECT status FROM manual_review_cases WHERE id = '{review['id']}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM review_resolutions WHERE merchant_id = '{merchant_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM fraud_labels WHERE merchant_id = '{merchant_id}'::uuid
             AND source = 'MANUAL_REVIEW' AND label_value = 'FRAUD') || '|' ||
          (SELECT COUNT(*) FROM review_annotations WHERE merchant_id = '{merchant_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM webhook_events WHERE merchant_id = '{merchant_id}'::uuid
             AND event_type IN ('review.assigned', 'payment_intent.canceled',
                                'review.resolved', 'fraud_label.created')) || '|' ||
          (SELECT action FROM fraud_decisions WHERE id = '{review_attempt['fraudDecisionId']}'::uuid);
    """)
    if summary != "CANCELED|CANCELED|RESOLVED|1|1|1|4|REVIEW":
        raise DemoFailure(f"durable Review and label reconciliation failed: {summary}")

    secret = sql_literal(operator["credential"])
    leaked = postgres_scalar(f"""
        SELECT
          (SELECT COUNT(*) FROM risk_operator_credentials
           WHERE CONCAT_WS('|', secret_prefix, secret_sha256, secret_identity_hmac,
                           creation_identity_hmac, creation_request_hash,
                           COALESCE(replay_ciphertext, ''), COALESCE(replay_nonce, '')) LIKE '%' || {secret} || '%')
          +
          (SELECT COUNT(*) FROM audit_logs
           WHERE CONCAT_WS('|', action, reason, COALESCE(before_json, ''),
                           COALESCE(after_json, '')) LIKE '%' || {secret} || '%')
          +
          (SELECT COUNT(*) FROM webhook_events WHERE payload LIKE '%' || {secret} || '%');
    """)
    if leaked != "0":
        raise DemoFailure("operator credential leaked into durable review surfaces")

    print("Analyst review-to-label demo passed.")
    print(f"merchant={merchant['id']} review={review['id']} label={label['id']}")
    print("payment=CANCELED attempt=CANCELED review=RESOLVED decision=REVIEW")
    print("resolution=1 fraud_labels=1 annotations=1 explicit_risk_webhooks=4")
    print(f"processor_money_before={money_before} processor_money_after={money_after}")
    print("replay=exact tenant_isolation=missing-equivalent credential_leaks=zero")


if __name__ == "__main__":
    try:
        run()
    except (DemoFailure, KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
        print(f"Review and label demo failed: {error}", file=sys.stderr)
        raise SystemExit(1)
