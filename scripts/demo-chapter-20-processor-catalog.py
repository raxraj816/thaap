#!/usr/bin/env python3
"""Prove the complete THAAP simulator catalog through public local APIs."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import pathlib
import subprocess
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass
from typing import Any


ROOT = pathlib.Path(__file__).resolve().parents[1]
DEFAULT_BASE_URL = "http://localhost:8080"
DEFAULT_REPORT_DIR = ROOT / "build" / "reports" / "processor-catalog"


@dataclass(frozen=True)
class Fixture:
    fixture_id: str
    card_number: str
    intent_status: str
    attempt_status: str
    authorization_status: str
    response_code: str
    network_status: str
    cvc_check: str
    avs_line1_check: str
    avs_postal_code_check: str
    three_ds_result: str
    three_ds_status: str | None
    authorized_amount: int


FIXTURES = (
    Fixture(
        "approved",
        "4999990000000001",
        "REQUIRES_CAPTURE",
        "AUTHORIZED",
        "APPROVED",
        "APPROVED",
        "APPROVED",
        "PASS",
        "PASS",
        "PASS",
        "NOT_REQUIRED",
        None,
        4200,
    ),
    Fixture(
        "issuer_decline",
        "4999990000000002",
        "REQUIRES_PAYMENT_METHOD",
        "DECLINED",
        "DECLINED",
        "ISSUER_DECLINE",
        "DECLINED",
        "PASS",
        "PASS",
        "PASS",
        "NOT_REQUIRED",
        None,
        0,
    ),
    Fixture(
        "insufficient_funds",
        "4999990000000003",
        "REQUIRES_PAYMENT_METHOD",
        "DECLINED",
        "DECLINED",
        "INSUFFICIENT_FUNDS",
        "DECLINED",
        "PASS",
        "PASS",
        "PASS",
        "NOT_REQUIRED",
        None,
        0,
    ),
    Fixture(
        "suspected_fraud",
        "4999990000000004",
        "REQUIRES_PAYMENT_METHOD",
        "DECLINED",
        "DECLINED",
        "SUSPECTED_FRAUD",
        "DECLINED",
        "PASS",
        "PASS",
        "PASS",
        "NOT_REQUIRED",
        None,
        0,
    ),
    Fixture(
        "cvc_failure",
        "4999990000000005",
        "REQUIRES_PAYMENT_METHOD",
        "DECLINED",
        "DECLINED",
        "CVC_FAILURE",
        "DECLINED",
        "FAIL",
        "PASS",
        "PASS",
        "NOT_REQUIRED",
        None,
        0,
    ),
    Fixture(
        "avs_line1_failure",
        "4999990000000006",
        "REQUIRES_PAYMENT_METHOD",
        "DECLINED",
        "DECLINED",
        "AVS_LINE1_FAILURE",
        "DECLINED",
        "PASS",
        "FAIL",
        "PASS",
        "NOT_REQUIRED",
        None,
        0,
    ),
    Fixture(
        "avs_postal_failure",
        "4999990000000007",
        "REQUIRES_PAYMENT_METHOD",
        "DECLINED",
        "DECLINED",
        "AVS_POSTAL_FAILURE",
        "DECLINED",
        "PASS",
        "PASS",
        "FAIL",
        "NOT_REQUIRED",
        None,
        0,
    ),
    Fixture(
        "three_ds_required",
        "4999990000000008",
        "REQUIRES_ACTION",
        "REQUIRES_ACTION",
        "REQUIRES_ACTION",
        "THREE_DS_REQUIRED",
        "REQUIRES_ACTION",
        "PASS",
        "PASS",
        "PASS",
        "REQUIRED",
        "REQUESTED",
        0,
    ),
    Fixture(
        "unknown_default_approval",
        "4242424242424242",
        "REQUIRES_CAPTURE",
        "AUTHORIZED",
        "APPROVED",
        "APPROVED",
        "APPROVED",
        "PASS",
        "PASS",
        "PASS",
        "NOT_REQUIRED",
        None,
        4200,
    ),
)


class DemoFailure(RuntimeError):
    pass


class RejectRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(
        self,
        request: urllib.request.Request,
        file_pointer: Any,
        code: int,
        message: str,
        headers: Any,
        new_url: str,
    ) -> None:
        raise DemoFailure(
            "Chapter 20 demo rejects redirects to preserve its loopback credential boundary"
        )


class ApiClient:
    def __init__(self, base_url: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.opener = urllib.request.build_opener(RejectRedirectHandler())

    def get(self, path: str, api_key: str | None = None) -> Any:
        headers = {"Accept": "application/json"}
        if api_key:
            headers["X-Api-Key"] = api_key
        return self._request("GET", path, headers, None)

    def post(
        self,
        path: str,
        body: dict[str, Any],
        *,
        api_key: str | None = None,
        admin_token: str | None = None,
        idempotency_key: str | None = None,
        request_id: str | None = None,
    ) -> Any:
        headers = {"Accept": "application/json", "Content-Type": "application/json"}
        if api_key:
            headers["X-Api-Key"] = api_key
        if admin_token:
            headers["X-Admin-Bootstrap-Token"] = admin_token
        if idempotency_key:
            headers["Idempotency-Key"] = idempotency_key
        if request_id:
            headers["X-Request-Id"] = request_id
        payload = json.dumps(body, separators=(",", ":")).encode("utf-8")
        return self._request("POST", path, headers, payload)

    def _request(
        self,
        method: str,
        path: str,
        headers: dict[str, str],
        payload: bytes | None,
    ) -> Any:
        request = urllib.request.Request(
            f"{self.base_url}{path}", data=payload, headers=headers, method=method
        )
        try:
            with self.opener.open(request, timeout=10) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            response_body = error.read().decode("utf-8", errors="replace")
            raise DemoFailure(
                f"{method} {path} failed with HTTP {error.code}: {response_body}"
            ) from error
        except urllib.error.URLError as error:
            raise DemoFailure(f"{method} {path} could not reach the local API") from error


def require_loopback_origin(value: str) -> str:
    parsed = urllib.parse.urlsplit(value)
    if (
        parsed.scheme not in {"http", "https"}
        or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}
        or parsed.username is not None
        or parsed.password is not None
        or parsed.path not in {"", "/"}
        or parsed.query
        or parsed.fragment
    ):
        raise DemoFailure("Chapter 20 demo accepts only a loopback API origin")
    return value.rstrip("/")


def require_equal(actual: Any, expected: Any, claim: str) -> None:
    if actual != expected:
        raise DemoFailure(f"{claim}: expected={expected!r} actual={actual!r}")


def query_audit_counts(merchant_id: str) -> dict[str, int]:
    normalized_merchant_id = str(uuid.UUID(merchant_id))
    database = os.environ.get("THAAP_POSTGRES_DB", "thaap_payments")
    user = os.environ.get("THAAP_POSTGRES_USER", "thaap")
    sql = (
        "SELECT action, COUNT(*) "
        "FROM audit_logs "
        f"WHERE merchant_id = '{normalized_merchant_id}'::uuid "
        "GROUP BY action ORDER BY action"
    )
    command = [
        "docker",
        "compose",
        "-f",
        str(ROOT / "infra" / "docker" / "compose.yml"),
        "exec",
        "-T",
        "postgres",
        "psql",
        "-U",
        user,
        "-d",
        database,
        "-At",
        "-F",
        "\t",
        "-c",
        sql,
    ]
    try:
        result = subprocess.run(
            command,
            cwd=ROOT,
            check=True,
            capture_output=True,
            text=True,
        )
    except (FileNotFoundError, subprocess.CalledProcessError) as error:
        raise DemoFailure(
            "Chapter 20 requires the local PostgreSQL container for read-only audit proof"
        ) from error
    counts: dict[str, int] = {}
    for line in result.stdout.splitlines():
        if not line:
            continue
        action, count = line.split("\t", maxsplit=1)
        counts[action] = int(count)
    return counts


def evidence_for_fixture(
    client: ApiClient,
    fixture: Fixture,
    merchant_id: str,
    api_key: str,
    customer_id: str,
    run_id: str,
) -> dict[str, Any]:
    payment_method = client.post(
        "/payment_methods",
        {"customerId": customer_id, "cardNumber": fixture.card_number},
        api_key=api_key,
        idempotency_key=f"chapter20-{run_id}-{fixture.fixture_id}-payment-method",
        request_id=f"chapter20-{run_id}-{fixture.fixture_id}-payment-method",
    )
    payment_intent = client.post(
        "/payment_intents",
        {
            "customerId": customer_id,
            "amount": 4200,
            "currency": "USD",
            "captureMethod": "MANUAL",
            "description": f"Chapter 20 fixture {fixture.fixture_id}",
            "metadata": {"chapter": "20", "fixture": fixture.fixture_id},
        },
        api_key=api_key,
        idempotency_key=f"chapter20-{run_id}-{fixture.fixture_id}-intent",
        request_id=f"chapter20-{run_id}-{fixture.fixture_id}-intent",
    )
    attempt = client.post(
        f"/payment_intents/{payment_intent['id']}/confirm",
        {"paymentMethodId": payment_method["id"]},
        api_key=api_key,
        idempotency_key=f"chapter20-{run_id}-{fixture.fixture_id}-confirm",
        request_id=f"chapter20-{run_id}-{fixture.fixture_id}-confirm",
    )
    stored_intent = client.get(f"/payment_intents/{payment_intent['id']}", api_key)
    stored_attempt = client.get(f"/payment_attempts/{attempt['id']}", api_key)
    authorization = client.get(
        f"/payment_attempts/{attempt['id']}/authorization", api_key
    )
    fraud_detail = client.get(
        f"/fraud/decisions/{attempt['fraudDecisionId']}", api_key
    )

    checks = {
        "PaymentIntent status": (stored_intent["status"], fixture.intent_status),
        "PaymentAttempt status": (stored_attempt["status"], fixture.attempt_status),
        "Authorization status": (
            authorization["status"],
            fixture.authorization_status,
        ),
        "processor response": (
            authorization["processorResponseCode"],
            fixture.response_code,
        ),
        "network status": (authorization["networkStatus"], fixture.network_status),
        "CVC result": (authorization["cvcCheck"], fixture.cvc_check),
        "AVS line1 result": (
            authorization["avsLine1Check"],
            fixture.avs_line1_check,
        ),
        "AVS postal result": (
            authorization["avsPostalCodeCheck"],
            fixture.avs_postal_code_check,
        ),
        "3DS result": (authorization["threeDsResult"], fixture.three_ds_result),
        "3DS attempt status": (
            stored_attempt.get("threeDsStatus"),
            fixture.three_ds_status,
        ),
        "authorized amount": (
            authorization["authorizedAmount"],
            fixture.authorized_amount,
        ),
        "requested amount": (authorization["requestedAmount"], 4200),
        "currency": (authorization["currency"], "USD"),
        "processor": (authorization["processor"], "THAAP_SIMULATOR"),
        "fraud action": (fraud_detail["decision"]["action"], "ALLOW"),
        "ruleset": (fraud_detail["decision"]["rulesetVersion"], "rules_stub_v1"),
        "fraud reason": (
            fraud_detail["decision"]["reasonCodes"],
            ["RULES_NOT_ENABLED"],
        ),
        "feature freshness": (
            fraud_detail["featureSnapshot"]["freshnessStatus"],
            "FRESH",
        ),
        "merchant lineage": (stored_attempt["merchantId"], merchant_id),
    }
    for claim, (actual, expected) in checks.items():
        require_equal(actual, expected, f"{fixture.fixture_id} {claim}")

    return {
        "fixtureId": fixture.fixture_id,
        "paymentIntentId": stored_intent["id"],
        "paymentAttemptId": stored_attempt["id"],
        "fraudDecisionId": fraud_detail["decision"]["id"],
        "paymentIntentStatus": stored_intent["status"],
        "paymentAttemptStatus": stored_attempt["status"],
        "authorizationStatus": authorization["status"],
        "processorResponseCode": authorization["processorResponseCode"],
        "networkStatus": authorization["networkStatus"],
        "authorizedAmount": authorization["authorizedAmount"],
        "cvcCheck": authorization["cvcCheck"],
        "avsLine1Check": authorization["avsLine1Check"],
        "avsPostalCodeCheck": authorization["avsPostalCodeCheck"],
        "threeDsResult": authorization["threeDsResult"],
        "fraudAction": fraud_detail["decision"]["action"],
        "rulesetVersion": fraud_detail["decision"]["rulesetVersion"],
        "reasonCodes": fraud_detail["decision"]["reasonCodes"],
        "featureFreshnessStatus": fraud_detail["featureSnapshot"]["freshnessStatus"],
    }


def write_atomic_json(path: pathlib.Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    serialized = json.dumps(value, indent=2, sort_keys=True) + "\n"
    with tempfile.NamedTemporaryFile(
        mode="w",
        encoding="utf-8",
        dir=path.parent,
        prefix=f".{path.name}.",
        suffix=".tmp",
        delete=False,
    ) as temporary:
        temporary.write(serialized)
        temporary.flush()
        os.fsync(temporary.fileno())
        temporary_path = pathlib.Path(temporary.name)
    temporary_path.chmod(0o600)
    os.replace(temporary_path, path)


def assert_report_is_sanitized(
    report: dict[str, Any],
    *,
    api_key: str,
    signing_secret: str,
    customer_email: str,
    run_id: str,
) -> None:
    serialized = json.dumps(report, sort_keys=True)
    prohibited_values = [
        *(fixture.card_number for fixture in FIXTURES),
        api_key,
        signing_secret,
        customer_email,
        *(
            f"chapter20-{run_id}-{fixture.fixture_id}-{operation}"
            for fixture in FIXTURES
            for operation in ("payment-method", "intent", "confirm")
        ),
    ]
    leaked = [value for value in prohibited_values if value and value in serialized]
    if leaked:
        raise DemoFailure("sanitized report contains prohibited input or credential material")


def run(args: argparse.Namespace) -> pathlib.Path:
    base_url = require_loopback_origin(args.base_url)
    admin_token = os.environ.get("THAAP_ADMIN_BOOTSTRAP_TOKEN")
    if not admin_token:
        raise DemoFailure("THAAP_ADMIN_BOOTSTRAP_TOKEN must be set")

    client = ApiClient(base_url)
    health = client.get("/actuator/health")
    require_equal(health["status"], "UP", "payments-api health")

    run_id = dt.datetime.now(dt.UTC).strftime("%Y%m%d%H%M%S%f")
    merchant = client.post(
        "/admin/merchants",
        {"name": f"Chapter 20 Processor Catalog {run_id}"},
        admin_token=admin_token,
        request_id=f"chapter20-{run_id}-merchant",
    )
    merchant_id = merchant["id"]
    api_key = merchant["apiKey"]
    customer_email = f"chapter20-{run_id}@example.test"
    customer = client.post(
        "/customers",
        {"email": customer_email},
        api_key=api_key,
        request_id=f"chapter20-{run_id}-customer",
    )
    endpoint = client.post(
        "/webhook_endpoints",
        {
            "url": f"https://chapter20-{run_id}.example.test/webhooks",
            "enabledEvents": ["payment_attempt.created"],
        },
        api_key=api_key,
        request_id=f"chapter20-{run_id}-webhook",
    )
    if endpoint.get("signingSecret") is None:
        raise DemoFailure("webhook endpoint did not reveal its one-time signing secret")

    outcomes = [
        evidence_for_fixture(
            client, fixture, merchant_id, api_key, customer["id"], run_id
        )
        for fixture in FIXTURES
    ]
    outcome_by_attempt = {
        outcome["paymentAttemptId"]: outcome for outcome in outcomes
    }

    ledger_entries = client.get("/ledger/entries?limit=100", api_key)
    require_equal(
        ledger_entries,
        [],
        "MANUAL processor catalog demo must not create capture ledger truth",
    )

    deliveries = client.get("/webhook_deliveries?limit=100", api_key)
    catalog_deliveries = [
        delivery
        for delivery in deliveries
        if delivery["eventType"] == "payment_attempt.created"
        and delivery["resourceId"] in outcome_by_attempt
    ]
    require_equal(
        len(catalog_deliveries),
        len(FIXTURES),
        "every catalog attempt must create exactly one webhook delivery",
    )
    if len({delivery["resourceId"] for delivery in catalog_deliveries}) != len(FIXTURES):
        raise DemoFailure("catalog webhook deliveries do not have one unique attempt resource")
    delivery_by_attempt = {
        delivery["resourceId"]: delivery["id"] for delivery in catalog_deliveries
    }
    for outcome in outcomes:
        outcome["webhookDeliveryId"] = delivery_by_attempt[outcome["paymentAttemptId"]]

    audit_counts = query_audit_counts(merchant_id)
    expected_audit_counts = {
        "authorization.approved": 2,
        "authorization.declined": 6,
        "authorization.requires_action": 1,
        "fraud_decision.completed": 9,
        "payment_intent.confirmed": 9,
        "webhook_endpoint.created": 1,
    }
    require_equal(audit_counts, expected_audit_counts, "catalog audit action counts")

    report = {
        "chapter": 20,
        "runId": run_id,
        "merchantId": merchant_id,
        "fixtureCount": len(FIXTURES),
        "outcomes": outcomes,
        "ledgerEntryCount": 0,
        "webhookDeliveryCount": len(catalog_deliveries),
        "auditActionCounts": audit_counts,
        "sensitiveDataScan": {
            "passed": True,
            "excluded": [
                "raw card numbers",
                "CVV",
                "buyer email and phone",
                "API keys",
                "signing secrets",
                "idempotency keys and hashes",
                "card fingerprints",
            ],
        },
    }
    assert_report_is_sanitized(
        report,
        api_key=api_key,
        signing_secret=endpoint["signingSecret"],
        customer_email=customer_email,
        run_id=run_id,
    )

    output = (
        pathlib.Path(args.output)
        if args.output
        else DEFAULT_REPORT_DIR / f"chapter20-{run_id}.json"
    )
    output = output if output.is_absolute() else ROOT / output
    write_atomic_json(output, report)
    return output


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Prove every Chapter 20 processor fixture through local public APIs"
    )
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--output")
    return parser.parse_args()


def main() -> int:
    try:
        output = run(parse_args())
    except DemoFailure as error:
        print(f"Chapter 20 demo failed: {error}", file=os.sys.stderr)
        return 1
    print("Chapter 20 processor catalog demo passed.")
    print(f"Sanitized report: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
