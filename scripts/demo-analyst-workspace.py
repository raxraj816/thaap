#!/usr/bin/env python3
"""Public analyst queue, saved-search, and protected-note lifecycle demo."""

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
        raise DemoFailure("Analyst workspace demo rejects redirects")


def require_loopback(base_url: str) -> str:
    parsed = urllib.parse.urlsplit(base_url.rstrip("/"))
    if parsed.scheme != "http" or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
        raise DemoFailure("Analyst workspace demo accepts only a loopback HTTP API origin")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise DemoFailure("Analyst workspace demo API origin contains prohibited components")
    return base_url.rstrip("/")


def postgres_scalar(query: str) -> str:
    normalized = query.strip().lower()
    if not normalized.startswith("select"):
        raise DemoFailure("Analyst workspace reconciliation accepts only SELECT")
    for prohibited in (" insert ", " update ", " delete ", " alter ", " drop ", " truncate "):
        if prohibited in f" {normalized} ":
            raise DemoFailure("Analyst workspace reconciliation rejected mutating SQL")
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
    raise DemoFailure("Analyst workspace API readiness did not become UP")


def run() -> None:
    base_url = require_loopback(os.environ.get("THAAP_BASE_URL", "http://localhost:8080"))
    admin_token = os.environ.get("THAAP_ADMIN_BOOTSTRAP_TOKEN")
    if not admin_token:
        raise DemoFailure("THAAP_ADMIN_BOOTSTRAP_TOKEN is required")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), RejectRedirects())
    wait_readiness(opener, base_url)
    run_id = f"{int(time.time())}-{uuid.uuid4().hex[:8]}"
    prefix = f"analyst-workspace-{run_id}"

    _, merchant = post(opener, base_url, "/admin/merchants",
                       {"name": f"Analyst workspace {run_id}"},
                       {"X-Admin-Bootstrap-Token": admin_token}, prefix + "-merchant")
    merchant_id = uuid.UUID(merchant["id"])
    merchant_headers = {"X-Api-Key": merchant["apiKey"]}
    _, analyst = post(opener, base_url, "/risk/operators", {"role": "ANALYST"},
                      merchant_headers, prefix + "-analyst")
    _, manager = post(opener, base_url, "/risk/operators", {"role": "RISK_MANAGER"},
                      merchant_headers, prefix + "-manager")
    analyst_headers = {"X-Risk-Operator-Key": analyst["credential"]}
    manager_headers = {"X-Risk-Operator-Key": manager["credential"]}
    _, customer = post(opener, base_url, "/customers",
                       {"email": f"{prefix}@example.test"}, merchant_headers)
    _, method = post(opener, base_url, "/payment_methods",
                     {"customerId": customer["id"], "cardNumber": "4999990000000003"},
                     merchant_headers, prefix + "-method")
    _, intent = post(opener, base_url, "/payment_intents",
                     {"customerId": customer["id"], "amount": 99999,
                      "currency": "USD", "captureMethod": "MANUAL"},
                     merchant_headers, prefix + "-intent")
    for attempt in range(1, 4):
        post(opener, base_url, f"/payment_intents/{intent['id']}/confirm",
             {"paymentMethodId": method["id"]}, merchant_headers,
             f"{prefix}-confirm-{attempt}")

    _, initial_queue = post(opener, base_url, "/risk/workspace/reviews/search?limit=25",
                            {"filter": {"statuses": ["OPEN"]},
                             "sort": "CREATED_AT_ASC"}, analyst_headers)
    matches = [row for row in initial_queue["rows"] if row["paymentIntentId"] == intent["id"]]
    if len(matches) != 1:
        raise DemoFailure("typed queue did not expose exactly one review case")
    review = matches[0]
    review_id = uuid.UUID(review["id"])
    post(opener, base_url, f"/risk/reviews/{review_id}/assign",
         {"expectedAssignmentVersion": 0}, analyst_headers, prefix + "-assign")

    saved_body = {
        "name": "Assigned review priority",
        "visibility": "PRIVATE",
        "filter": {"statuses": ["ASSIGNED"], "assignedOperatorIds": [analyst["id"]]},
        "sort": "CREATED_AT_ASC",
        "visibleColumns": ["CREATED_AT", "STATUS", "SLA_STATE", "DECISION_ACTION"],
    }
    saved_raw, saved = post(opener, base_url, "/risk/workspace/saved-searches",
                            saved_body, analyst_headers, prefix + "-saved-search")
    replay_raw, _ = post(opener, base_url, "/risk/workspace/saved-searches",
                         saved_body, analyst_headers, prefix + "-saved-search")
    if saved_raw != replay_raw:
        raise DemoFailure("saved-search replay was not byte-exact")
    _, execution = post(opener, base_url,
                        f"/risk/workspace/saved-searches/{saved['id']}/execute",
                        None, analyst_headers)
    if [row["id"] for row in execution["rows"]] != [str(review_id)]:
        raise DemoFailure("saved-search meaning did not execute exactly")

    canary = f"Verified-device-context-{uuid.uuid4().hex[:10]}"
    note_body = {"expectedAssignmentVersion": 1, "content": canary}
    note_raw, note = post(opener, base_url, f"/risk/workspace/reviews/{review_id}/notes",
                          note_body, analyst_headers, prefix + "-note")
    note_replay_raw, _ = post(opener, base_url,
                              f"/risk/workspace/reviews/{review_id}/notes",
                              note_body, analyst_headers, prefix + "-note")
    if note_raw != note_replay_raw:
        raise DemoFailure("protected-note replay was not byte-exact")
    correction_text = "Correction: verification used the registered support channel"
    _, correction = post(opener, base_url, f"/risk/workspace/reviews/{review_id}/notes",
                         {"expectedAssignmentVersion": 1, "content": correction_text,
                          "supersedesNoteId": note["id"]},
                         analyst_headers, prefix + "-correction")
    if correction.get("supersedesNoteId") != note["id"]:
        raise DemoFailure("correction lineage was not append-only")
    _, manager_notes = request_json(
        opener, base_url, "GET",
        f"/risk/workspace/reviews/{review_id}/notes?expectedAssignmentVersion=1",
        headers=manager_headers)
    if [item.get("content") for item in manager_notes] != [canary, correction_text]:
        raise DemoFailure("authorized manager note read did not preserve append order")
    _, redacted = post(opener, base_url,
                       f"/risk/workspace/reviews/{review_id}/notes/{note['id']}/redact",
                       {}, manager_headers, prefix + "-redact")
    if redacted.get("lifecycleStatus") != "REDACTED" or redacted.get("content") is not None:
        raise DemoFailure("manager redaction did not destroy returned plaintext")

    note_id = uuid.UUID(note["id"])
    correction_id = uuid.UUID(correction["id"])
    reconciliation = postgres_scalar(f"""
        SELECT
          (SELECT COUNT(*) FROM review_saved_searches
            WHERE merchant_id = '{merchant_id}'::uuid AND id = '{uuid.UUID(saved['id'])}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM review_notes
            WHERE merchant_id = '{merchant_id}'::uuid AND id IN ('{note_id}'::uuid, '{correction_id}'::uuid)) || '|' ||
          (SELECT COUNT(*) FROM review_note_redaction_events
            WHERE merchant_id = '{merchant_id}'::uuid AND review_note_id = '{note_id}'::uuid) || '|' ||
          (SELECT CASE WHEN ciphertext IS NULL AND nonce IS NULL AND key_version IS NULL
                       THEN 1 ELSE 0 END FROM review_notes WHERE id = '{note_id}'::uuid) || '|' ||
          (SELECT CASE WHEN ciphertext IS NOT NULL AND nonce IS NOT NULL AND key_version IS NOT NULL
                       THEN 1 ELSE 0 END FROM review_notes WHERE id = '{correction_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM manual_review_cases
            WHERE id = '{review_id}'::uuid AND status = 'ASSIGNED' AND assignment_version = 1) || '|' ||
          (SELECT COUNT(*) FROM fraud_labels WHERE merchant_id = '{merchant_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM ledger_entries WHERE merchant_id = '{merchant_id}'::uuid) || '|' ||
          (SELECT COUNT(*) FROM audit_logs
            WHERE merchant_id = '{merchant_id}'::uuid
              AND (COALESCE(before_json, '') LIKE '%{canary}%'
                   OR COALESCE(after_json, '') LIKE '%{canary}%')) || '|' ||
          (SELECT COUNT(*) FROM idempotency_records
            WHERE principal_id = '{uuid.UUID(analyst['id'])}'::uuid
              AND COALESCE(response_body, '') LIKE '%{canary}%');
    """)
    if reconciliation != "1|2|1|1|1|1|0|0|0|0":
        raise DemoFailure(f"analyst workspace durable reconciliation failed: {reconciliation}")

    print("Analyst workspace demo passed.")
    print("typed_queue=proved saved_search=replay_exact meaning_bound")
    print("notes=encrypted correction=append_only redaction=ciphertext_destroyed")
    print("payment_fraud_ledger_truth=unchanged plaintext_sinks=absent readiness=UP")


if __name__ == "__main__":
    try:
        run()
    except (DemoFailure, KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
        print(f"Analyst workspace demo failed: {error}", file=sys.stderr)
        raise SystemExit(1)
