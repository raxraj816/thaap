#!/usr/bin/env python3
"""Generate deterministic THAAP synthetic payment scenarios through public APIs."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import ipaddress
import json
import os
import pathlib
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass
from typing import Any


GENERATOR_VERSION = "synthetic_scenario_generator_v1"
DEFAULT_BASE_URL = "http://localhost:8080"
DEFAULT_OUTPUT_ROOT = pathlib.Path("build/reports/synthetic-scenarios")
ADMIN_TOKEN_ENVIRONMENT_VARIABLE = "THAAP_ADMIN_BOOTSTRAP_TOKEN"


@dataclass(frozen=True)
class ScenarioPaymentPlan:
    label: str
    card_number: str
    amount: int
    expected_payment_intent_status: str
    expected_attempt_status: str
    expected_processor_outcome_code: str
    expected_authorization_status: str
    expected_network_status: str
    expected_three_ds_result: str
    expected_fraud_action: str


@dataclass(frozen=True)
class ScenarioPlan:
    label: str
    merchant_name: str
    customer_email: str
    customer_phone: str
    payments: tuple[ScenarioPaymentPlan, ...]


class ApiClient:
    def __init__(self, base_url: str):
        self.base_url = base_url.rstrip("/")

    def get(self, path: str, headers: dict[str, str] | None = None,
            expected_status: int = 200) -> tuple[int, Any]:
        return self._request("GET", path, None, headers or {}, expected_status)

    def post(self, path: str, body: dict[str, Any], headers: dict[str, str] | None = None,
             expected_status: int = 200) -> tuple[int, Any]:
        return self._request("POST", path, body, headers or {}, expected_status)

    def _request(self, method: str, path: str, body: dict[str, Any] | None,
                 headers: dict[str, str], expected_status: int) -> tuple[int, Any]:
        encoded_body = None
        request_headers = dict(headers)
        if body is not None:
            encoded_body = json.dumps(body, sort_keys=True).encode("utf-8")
            request_headers["Content-Type"] = "application/json"
        request = urllib.request.Request(
                f"{self.base_url}{path}", data=encoded_body, headers=request_headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                status = response.status
                payload = response.read().decode("utf-8")
        except urllib.error.HTTPError as error:
            status = error.code
            payload = error.read().decode("utf-8")
        if status != expected_status:
            raise RuntimeError(f"{method} {path} returned {status}, expected {expected_status}: {payload}")
        if not payload:
            return status, None
        return status, json.loads(payload)


def build_scenario_plan(seed: str) -> tuple[ScenarioPlan, ...]:
    seed_slug = safe_slug(seed)
    suffixes = deterministic_suffixes(seed, 8)
    return (
        ScenarioPlan(
            label="happy_path_approved_authorization",
            merchant_name=f"Synthetic Approved Merchant {seed_slug}",
            customer_email=f"synthetic-approved-{suffixes[0]}@example.test",
            customer_phone=f"+155501{suffixes[0][:5]}",
            payments=(
                ScenarioPaymentPlan("approved_authorization", "4999990000000001", 4200,
                        "REQUIRES_CAPTURE", "AUTHORIZED", "APPROVED", "APPROVED", "APPROVED",
                        "NOT_REQUIRED", "ALLOW"),
            ),
        ),
        ScenarioPlan(
            label="retryable_processor_decline",
            merchant_name=f"Synthetic Decline Merchant {seed_slug}",
            customer_email=f"synthetic-decline-{suffixes[1]}@example.test",
            customer_phone=f"+155502{suffixes[1][:5]}",
            payments=(
                ScenarioPaymentPlan("issuer_decline", "4999990000000002", 3600,
                        "REQUIRES_PAYMENT_METHOD", "DECLINED", "ISSUER_DECLINE", "DECLINED", "DECLINED",
                        "NOT_REQUIRED", "ALLOW"),
            ),
        ),
        ScenarioPlan(
            label="basic_card_testing_pattern",
            merchant_name=f"Synthetic Card Testing Merchant {seed_slug}",
            customer_email=f"synthetic-card-testing-{suffixes[2]}@example.test",
            customer_phone=f"+155503{suffixes[2][:5]}",
            payments=(
                ScenarioPaymentPlan("suspected_fraud_decline", "4999990000000004", 1900,
                        "REQUIRES_PAYMENT_METHOD", "DECLINED", "SUSPECTED_FRAUD", "DECLINED", "DECLINED",
                        "NOT_REQUIRED", "ALLOW"),
                ScenarioPaymentPlan("cvc_failure_decline", "4999990000000005", 2100,
                        "REQUIRES_PAYMENT_METHOD", "DECLINED", "CVC_FAILURE", "DECLINED", "DECLINED",
                        "NOT_REQUIRED", "ALLOW"),
                ScenarioPaymentPlan("avs_line1_failure_decline", "4999990000000006", 2300,
                        "REQUIRES_PAYMENT_METHOD", "DECLINED", "AVS_LINE1_FAILURE", "DECLINED", "DECLINED",
                        "NOT_REQUIRED", "ALLOW"),
            ),
        ),
    )


def deterministic_suffixes(seed: str, count: int) -> list[str]:
    suffixes = []
    for index in range(count):
        digest = hashlib.sha256(f"{GENERATOR_VERSION}:{seed}:{index}".encode("utf-8")).hexdigest()
        suffixes.append(f"{int(digest[:16], 16) % 10_000_000_000:010d}")
    return suffixes


def safe_slug(value: str) -> str:
    digest = hashlib.sha256(value.encode("utf-8")).hexdigest()
    return digest[:8]


def plan_fingerprint(plan: tuple[ScenarioPlan, ...]) -> str:
    payload = json.dumps(plan_to_public_dict(plan), sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def plan_to_public_dict(plan: tuple[ScenarioPlan, ...]) -> list[dict[str, Any]]:
    return [
        {
            "label": scenario.label,
            "payments": [
                {
                    "label": payment.label,
                    "amount": payment.amount,
                    "expectedPaymentIntentStatus": payment.expected_payment_intent_status,
                    "expectedAttemptStatus": payment.expected_attempt_status,
                    "expectedProcessorOutcomeCode": payment.expected_processor_outcome_code,
                    "expectedAuthorizationStatus": payment.expected_authorization_status,
                    "expectedNetworkStatus": payment.expected_network_status,
                    "expectedThreeDsResult": payment.expected_three_ds_result,
                    "expectedFraudAction": payment.expected_fraud_action,
                    "cardFixture": card_fixture_label(payment.card_number),
                }
                for payment in scenario.payments
            ],
        }
        for scenario in plan
    ]


def card_fixture_label(card_number: str) -> str:
    return f"{card_number[:6]}:{card_number[-4:]}"


def run_generator(seed: str, base_url: str, admin_token: str) -> dict[str, Any]:
    validated_base_url = validate_local_base_url(base_url)
    client = ApiClient(validated_base_url)
    _, health = client.get("/actuator/health")
    if health.get("status") != "UP":
        raise RuntimeError(f"payments-api health is not UP: {health}")
    plan = build_scenario_plan(seed)
    generated_at_instant = dt.datetime.now(dt.timezone.utc)
    generated_at = generated_at_instant.isoformat(timespec="microseconds").replace("+00:00", "Z")
    run_id = new_run_id(seed, generated_at_instant)
    forbidden_values: list[str] = [admin_token]
    scenario_summaries: list[dict[str, Any]] = []
    first_payment_refs: dict[str, str] | None = None

    for scenario_index, scenario in enumerate(plan):
        merchant = create_merchant(client, admin_token, f"{scenario.merchant_name} {run_id[-12:]}", seed,
                scenario_index)
        forbidden_values.append(merchant["apiKey"])
        customer = create_customer(client, merchant["apiKey"], scenario.customer_email, scenario.customer_phone,
                seed, scenario_index)
        forbidden_values.extend([scenario.customer_email, scenario.customer_phone])
        payment_summaries = []

        for payment_index, payment in enumerate(scenario.payments):
            forbidden_values.append(payment.card_number)
            forbidden_values.extend([
                idempotency_key(seed, scenario_index, payment_index, "payment-method"),
                idempotency_key(seed, scenario_index, payment_index, "payment-intent"),
                idempotency_key(seed, scenario_index, payment_index, "confirm"),
            ])
            payment_method = create_payment_method(client, merchant["apiKey"], customer["id"], payment.card_number,
                    seed, scenario_index, payment_index)
            payment_intent = create_payment_intent(client, merchant["apiKey"], customer["id"], payment.amount,
                    scenario.label, payment.label, run_id, seed, scenario_index, payment_index)
            attempt = confirm_payment_intent(client, merchant["apiKey"], payment_intent["id"], payment_method["id"],
                    seed, scenario_index, payment_index)
            _, fetched_intent = client.get(f"/payment_intents/{payment_intent['id']}",
                    merchant_headers(merchant["apiKey"]))
            _, fetched_attempt = client.get(f"/payment_attempts/{attempt['id']}", merchant_headers(merchant["apiKey"]))
            _, fraud_detail = client.get(f"/fraud/decisions/{attempt['fraudDecisionId']}",
                    merchant_headers(merchant["apiKey"]))
            _, authorization = client.get(f"/payment_attempts/{attempt['id']}/authorization",
                    merchant_headers(merchant["apiKey"]))

            assert_equal(fetched_intent["status"], payment.expected_payment_intent_status,
                    f"{scenario.label}/{payment.label} PaymentIntent status")
            assert_equal(fetched_intent["metadata"].get("syntheticRunId"), run_id,
                    f"{scenario.label}/{payment.label} synthetic run linkage")
            assert_equal(fetched_intent["metadata"].get("generatorVersion"), GENERATOR_VERSION,
                    f"{scenario.label}/{payment.label} generator version linkage")
            assert_equal(fetched_intent["metadata"].get("syntheticOrigin"), "THAAP_LOCAL_GENERATOR",
                    f"{scenario.label}/{payment.label} synthetic origin linkage")
            assert_equal(fetched_attempt["status"], payment.expected_attempt_status,
                    f"{scenario.label}/{payment.label} attempt status")
            assert_equal(authorization["processorResponseCode"], payment.expected_processor_outcome_code,
                    f"{scenario.label}/{payment.label} processor outcome")
            assert_equal(authorization["status"], payment.expected_authorization_status,
                    f"{scenario.label}/{payment.label} authorization status")
            assert_equal(authorization["networkStatus"], payment.expected_network_status,
                    f"{scenario.label}/{payment.label} network status")
            assert_equal(authorization["threeDsResult"], payment.expected_three_ds_result,
                    f"{scenario.label}/{payment.label} 3DS result")
            assert_equal(authorization["requestedAmount"], payment.amount,
                    f"{scenario.label}/{payment.label} requested amount")
            expected_authorized_amount = payment.amount if payment.expected_authorization_status == "APPROVED" else 0
            assert_equal(authorization["authorizedAmount"], expected_authorized_amount,
                    f"{scenario.label}/{payment.label} authorized amount")
            assert_equal(authorization["currency"], "USD", f"{scenario.label}/{payment.label} currency")
            assert_fraud_lineage(fraud_detail, attempt["id"], attempt["fraudDecisionId"],
                    payment.expected_fraud_action, f"{scenario.label}/{payment.label}")

            payment_summary = {
                "label": payment.label,
                "amount": payment.amount,
                "currency": "USD",
                "paymentIntentId": payment_intent["id"],
                "paymentAttemptId": attempt["id"],
                "fraudDecisionId": attempt["fraudDecisionId"],
                "finalPaymentIntentStatus": fetched_intent["status"],
                "finalPaymentAttemptStatus": fetched_attempt["status"],
                "fraudAction": fraud_detail["decision"]["action"],
                "processorOutcomeCode": authorization["processorResponseCode"],
                "authorizationStatus": authorization["status"],
                "networkStatus": authorization["networkStatus"],
                "threeDsResult": authorization["threeDsResult"],
            }
            payment_summaries.append(payment_summary)
            if first_payment_refs is None:
                first_payment_refs = {
                    "paymentIntentId": payment_intent["id"],
                    "paymentAttemptId": attempt["id"],
                    "fraudDecisionId": attempt["fraudDecisionId"],
                }

        _, ledger_entries = client.get("/ledger/entries?limit=10", merchant_headers(merchant["apiKey"]))
        assert_no_ledger_truth(ledger_entries, scenario.label)
        scenario_summaries.append({
            "label": scenario.label,
            "merchantId": merchant["id"],
            "customerId": customer["id"],
            "payments": payment_summaries,
            "ledgerEntryCount": len(ledger_entries),
            "createsCaptureOrLedgerTruth": len(ledger_entries) > 0,
        })

    tenant_isolation_checks = run_tenant_isolation_checks(client, admin_token, seed, run_id, first_payment_refs,
            forbidden_values)
    summary = {
        "generatorVersion": GENERATOR_VERSION,
        "seed": seed,
        "runId": run_id,
        "generatedAt": generated_at,
        "baseUrl": validated_base_url,
        "scenarioPlanFingerprint": plan_fingerprint(plan),
        "scenarioPlan": plan_to_public_dict(plan),
        "scenarios": scenario_summaries,
        "tenantIsolationChecks": tenant_isolation_checks,
    }
    summary["sensitiveDataScan"] = sensitive_data_scan(summary, forbidden_values)
    if summary["sensitiveDataScan"]["status"] != "PASS":
        raise RuntimeError(f"summary sensitive-data scan failed: {summary['sensitiveDataScan']}")
    if any(check["status"] != "PASS" for check in tenant_isolation_checks):
        raise RuntimeError(f"tenant isolation checks failed: {tenant_isolation_checks}")
    return summary


def create_merchant(client: ApiClient, admin_token: str, name: str, seed: str, scenario_index: int) -> dict[str, Any]:
    _, response = client.post("/admin/merchants", {"name": name}, {
        "X-Admin-Bootstrap-Token": admin_token,
        "X-Request-Id": request_id(seed, scenario_index, "merchant"),
    }, expected_status=201)
    return response


def create_customer(client: ApiClient, api_key: str, email: str, phone: str, seed: str,
                    scenario_index: int) -> dict[str, Any]:
    _, response = client.post("/customers", {"email": email, "phone": phone}, {
        **merchant_headers(api_key),
        "X-Request-Id": request_id(seed, scenario_index, "customer"),
    }, expected_status=201)
    return response


def create_payment_method(client: ApiClient, api_key: str, customer_id: str, card_number: str, seed: str,
                          scenario_index: int, payment_index: int) -> dict[str, Any]:
    _, response = client.post("/payment_methods", {"customerId": customer_id, "cardNumber": card_number}, {
        **merchant_headers(api_key),
        "Idempotency-Key": idempotency_key(seed, scenario_index, payment_index, "payment-method"),
        "X-Request-Id": request_id(seed, scenario_index, f"payment-method-{payment_index}"),
    }, expected_status=201)
    return response


def create_payment_intent(client: ApiClient, api_key: str, customer_id: str, amount: int, scenario_label: str,
                          payment_label: str, run_id: str, seed: str, scenario_index: int,
                          payment_index: int) -> dict[str, Any]:
    _, response = client.post("/payment_intents", {
        "customerId": customer_id,
        "amount": amount,
        "currency": "USD",
        "captureMethod": "MANUAL",
        "description": "Synthetic scenario payment",
        "metadata": {
            "scenario": scenario_label,
            "payment": payment_label,
            "generatorVersion": GENERATOR_VERSION,
            "syntheticRunId": run_id,
            "syntheticOrigin": "THAAP_LOCAL_GENERATOR",
        },
    }, {
        **merchant_headers(api_key),
        "Idempotency-Key": idempotency_key(seed, scenario_index, payment_index, "payment-intent"),
        "X-Request-Id": request_id(seed, scenario_index, f"payment-intent-{payment_index}"),
    }, expected_status=201)
    return response


def confirm_payment_intent(client: ApiClient, api_key: str, payment_intent_id: str, payment_method_id: str,
                           seed: str, scenario_index: int, payment_index: int) -> dict[str, Any]:
    _, response = client.post(f"/payment_intents/{payment_intent_id}/confirm",
            {"paymentMethodId": payment_method_id}, {
                **merchant_headers(api_key),
                "Idempotency-Key": idempotency_key(seed, scenario_index, payment_index, "confirm"),
                "X-Request-Id": request_id(seed, scenario_index, f"confirm-{payment_index}"),
            }, expected_status=201)
    return response


def run_tenant_isolation_checks(client: ApiClient, admin_token: str, seed: str, run_id: str,
                                refs: dict[str, str] | None, forbidden_values: list[str]) -> list[dict[str, Any]]:
    if refs is None:
        return [{"name": "tenant_isolation_reference_exists", "status": "FAIL", "httpStatus": None}]
    verifier = create_merchant(client, admin_token,
            f"Synthetic Isolation Verifier {safe_slug(seed)} {run_id[-12:]}", seed, 90)
    forbidden_values.append(verifier["apiKey"])
    checks = [
        ("cross_merchant_payment_intent_read", f"/payment_intents/{refs['paymentIntentId']}"),
        ("cross_merchant_payment_attempt_read", f"/payment_attempts/{refs['paymentAttemptId']}"),
        ("cross_merchant_fraud_decision_read", f"/fraud/decisions/{refs['fraudDecisionId']}"),
        ("cross_merchant_authorization_read", f"/payment_attempts/{refs['paymentAttemptId']}/authorization"),
    ]
    results = []
    for name, path in checks:
        http_status, _ = client.get(path, merchant_headers(verifier["apiKey"]), expected_status=404)
        results.append({"name": name, "status": "PASS", "httpStatus": http_status})
    return results


def sensitive_data_scan(summary: dict[str, Any], forbidden_values: list[str]) -> dict[str, Any]:
    summary_json = json.dumps(summary, sort_keys=True)
    blocked_terms = ["cardNumber", "apiKey", "idempotency", "fingerprint", "cvv", "webhookSecret"]
    findings = []
    for value in forbidden_values:
        if value and value in summary_json:
            findings.append({"kind": "forbidden_value", "valueDigest": hashlib.sha256(value.encode()).hexdigest()})
    for term in blocked_terms:
        if term in summary_json:
            findings.append({"kind": "blocked_term", "term": term})
    return {"status": "PASS" if not findings else "FAIL", "findings": findings}


def validate_local_base_url(base_url: str) -> str:
    try:
        parsed = urllib.parse.urlsplit(base_url)
        port = parsed.port
    except ValueError as exception:
        raise ValueError(f"Invalid payments-api base URL: {base_url}") from exception
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise ValueError("payments-api base URL must be an HTTP(S) origin")
    if parsed.username is not None or parsed.password is not None:
        raise ValueError("payments-api base URL must not contain credentials")
    if parsed.query or parsed.fragment or parsed.path not in {"", "/"}:
        raise ValueError("payments-api base URL must not contain a path, query, or fragment")
    hostname = parsed.hostname
    is_loopback = hostname == "localhost"
    if not is_loopback:
        try:
            is_loopback = ipaddress.ip_address(hostname).is_loopback
        except ValueError:
            is_loopback = False
    if not is_loopback:
        raise ValueError("synthetic scenarios may target only a loopback payments-api")
    normalized_host = f"[{hostname}]" if ":" in hostname else hostname
    normalized_port = f":{port}" if port is not None else ""
    return f"{parsed.scheme}://{normalized_host}{normalized_port}"


def admin_token_from_environment(environ: dict[str, str]) -> str:
    admin_token = environ.get(ADMIN_TOKEN_ENVIRONMENT_VARIABLE, "")
    if not admin_token.strip():
        raise ValueError(f"{ADMIN_TOKEN_ENVIRONMENT_VARIABLE} must be set")
    return admin_token


def new_run_id(seed: str, generated_at: dt.datetime | None = None) -> str:
    timestamp = generated_at or dt.datetime.now(dt.timezone.utc)
    return f"{timestamp.strftime('%Y%m%d%H%M%S%f')}-{safe_slug(seed)}-{uuid.uuid4().hex[:12]}"


def assert_no_ledger_truth(ledger_entries: Any, scenario_label: str) -> None:
    if not isinstance(ledger_entries, list):
        raise RuntimeError(f"{scenario_label} ledger response was not a list")
    if ledger_entries:
        raise RuntimeError(f"{scenario_label} unexpectedly created {len(ledger_entries)} ledger entries")


def assert_fraud_lineage(fraud_detail: dict[str, Any], payment_attempt_id: str, fraud_decision_id: str,
                         expected_action: str, description: str) -> None:
    decision = fraud_detail.get("decision")
    feature_snapshot = fraud_detail.get("featureSnapshot")
    if not isinstance(decision, dict) or not isinstance(feature_snapshot, dict):
        raise RuntimeError(f"{description} fraud lineage is incomplete")
    assert_equal(decision.get("id"), fraud_decision_id, f"{description} fraud decision ID")
    assert_equal(decision.get("paymentAttemptId"), payment_attempt_id, f"{description} fraud attempt linkage")
    assert_equal(decision.get("action"), expected_action, f"{description} fraud action")
    assert_equal(feature_snapshot.get("fraudDecisionId"), fraud_decision_id,
            f"{description} feature snapshot decision linkage")
    assert_equal(feature_snapshot.get("paymentAttemptId"), payment_attempt_id,
            f"{description} feature snapshot attempt linkage")


def merchant_headers(api_key: str) -> dict[str, str]:
    return {"X-Api-Key": api_key}


def idempotency_key(seed: str, scenario_index: int, payment_index: int, operation: str) -> str:
    return f"synthetic-{safe_slug(seed)}-{scenario_index}-{payment_index}-{operation}"


def request_id(seed: str, scenario_index: int, operation: str) -> str:
    return f"synthetic-{safe_slug(seed)}-{scenario_index}-{operation}"


def assert_equal(actual: Any, expected: Any, description: str) -> None:
    if actual != expected:
        raise RuntimeError(f"{description}: expected {expected}, got {actual}")


def write_summary(summary: dict[str, Any], output: pathlib.Path | None) -> pathlib.Path:
    output_path = output
    if output_path is None:
        output_path = DEFAULT_OUTPUT_ROOT / f"{summary['runId']}-summary.json"
    output_path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path: pathlib.Path | None = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=output_path.parent,
                prefix=f".{output_path.name}.", suffix=".tmp", delete=False) as temporary_file:
            temporary_path = pathlib.Path(temporary_file.name)
            json.dump(summary, temporary_file, indent=2, sort_keys=True)
            temporary_file.write("\n")
            temporary_file.flush()
            os.fsync(temporary_file.fileno())
        temporary_path.chmod(0o600)
        os.replace(temporary_path, output_path)
    finally:
        if temporary_path is not None and temporary_path.exists():
            temporary_path.unlink()
    return output_path


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate THAAP synthetic payment scenarios through public APIs.")
    parser.add_argument("--seed", required=True, help="Deterministic seed for scenario planning.")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL, help=f"payments-api base URL. Default: {DEFAULT_BASE_URL}")
    parser.add_argument("--output", type=pathlib.Path, help="Summary JSON output path.")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    try:
        admin_token = admin_token_from_environment(os.environ)
        summary = run_generator(args.seed, args.base_url, admin_token)
    except ValueError as exception:
        print(f"Configuration error: {exception}", file=sys.stderr)
        return 2
    output_path = write_summary(summary, args.output)
    print(f"Wrote synthetic scenario summary: {output_path}")
    print(f"Run ID: {summary['runId']}")
    print(f"Scenario plan fingerprint: {summary['scenarioPlanFingerprint']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
