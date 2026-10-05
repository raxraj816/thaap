#!/usr/bin/env python3
"""Public zero-source-reading demo for the trusted developer scenario console."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import socket
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from typing import Any


API_KEY_ENV = "THAAP_MERCHANT_API_KEY"
RUNNER_TOKEN_ENV = "THAAP_SCENARIO_RUNNER_TOKEN"
DEFAULT_BASE_URL = "http://127.0.0.1:8080"
ALLOWED_STEP_PATHS = {"/customers", "/payment_methods", "/payment_intents"}


class DemoFailure(RuntimeError):
    pass


class RejectRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        raise DemoFailure("The developer scenario client rejects redirects")


def require_loopback(base_url: str) -> str:
    parsed = urllib.parse.urlsplit(base_url)
    if parsed.scheme != "http" or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
        raise DemoFailure("Developer scenario demo accepts only a loopback HTTP API origin")
    if parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in {"", "/"}:
        raise DemoFailure("Developer scenario API origin contains prohibited components")
    try:
        port = parsed.port
    except ValueError as exception:
        raise DemoFailure("Developer scenario API origin has an invalid port") from exception
    if port is None:
        raise DemoFailure("Developer scenario API origin requires an explicit port")
    host = f"[{parsed.hostname}]" if parsed.hostname == "::1" else parsed.hostname
    return f"http://{host}:{port}"


def require_step_path(path: str) -> str:
    parsed = urllib.parse.urlsplit(path)
    if parsed.scheme or parsed.netloc or parsed.query or parsed.fragment or not path.startswith("/"):
        raise DemoFailure("Scenario step path must remain on the configured API origin")
    if path in ALLOWED_STEP_PATHS:
        return path
    components = path.strip("/").split("/")
    if len(components) == 3 and components[0] == "payment_intents" and components[2] == "confirm":
        try:
            uuid.UUID(components[1])
            return path
        except ValueError as exception:
            raise DemoFailure("Scenario confirm path contains an invalid payment intent ID") from exception
    raise DemoFailure("Scenario step operation is outside the governed public API catalogue")


class SafeClient:
    def __init__(self, base_url: str, api_key: str, runner_token: str,
                 timeout_seconds: float = 30):
        if timeout_seconds <= 0 or timeout_seconds > 30:
            raise DemoFailure("Developer scenario client timeout must be greater than zero and at most 30 seconds")
        self.base_url = require_loopback(base_url)
        self.api_key = api_key
        self.runner_token = runner_token
        self.timeout_seconds = timeout_seconds
        self.opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}), RejectRedirectHandler())

    def request(self, method: str, path: str, body: dict[str, Any] | None = None,
                *, trusted: bool, idempotency_key: str | None = None,
                expected_status: int = 200) -> Any:
        if not path.startswith("/") or path.startswith("//"):
            raise DemoFailure("API request path must remain on the configured origin")
        headers = {"X-Api-Key": self.api_key, "Accept": "application/json"}
        if trusted:
            headers["X-Thaap-Scenario-Runner-Token"] = self.runner_token
        if idempotency_key:
            headers["Idempotency-Key"] = idempotency_key
        encoded = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            encoded = json.dumps(body, sort_keys=True, separators=(",", ":")).encode("utf-8")
        request = urllib.request.Request(self.base_url + path, data=encoded,
                                         headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=self.timeout_seconds) as response:
                status = response.status
                payload = response.read().decode("utf-8")
        except urllib.error.HTTPError as error:
            status = error.code
            payload = error.read().decode("utf-8")
        except (urllib.error.URLError, TimeoutError, socket.timeout) as error:
            raise DemoFailure(f"{method} {path} did not complete within the bounded client request") from error
        if status != expected_status:
            raise DemoFailure(f"{method} {path} returned {status}; expected {expected_status}")
        return json.loads(payload) if payload else None


def stable_key(seed: str, operation: str) -> str:
    return "developer-demo-" + hashlib.sha256(
        f"developer-demo-v1|{seed}|{operation}".encode("utf-8")).hexdigest()[:40]


def create_live_reference(client: SafeClient, seed: str) -> dict[str, str]:
    suffix = hashlib.sha256(seed.encode("utf-8")).hexdigest()[:12]
    customer = client.request("POST", "/customers",
        {"email": f"developer-live-{suffix}@example.test"}, trusted=False,
        idempotency_key=stable_key(seed, "live-customer"), expected_status=201)
    payment_method = client.request("POST", "/payment_methods",
        {"customerId": customer["id"], "cardNumber": "4999990000000001"},
        trusted=False, idempotency_key=stable_key(seed, "live-method"), expected_status=201)
    payment_intent = client.request("POST", "/payment_intents", {
        "customerId": customer["id"], "amount": 1700, "currency": "USD",
        "captureMethod": "MANUAL", "description": "Developer console live reference",
    }, trusted=False, idempotency_key=stable_key(seed, "live-intent"), expected_status=201)
    attempt = client.request("POST", f"/payment_intents/{payment_intent['id']}/confirm",
        {"paymentMethodId": payment_method["id"]}, trusted=False,
        idempotency_key=stable_key(seed, "live-confirm"), expected_status=201)
    if attempt["trafficOrigin"] != "MERCHANT_API":
        raise DemoFailure("Live reference was not classified as MERCHANT_API")
    return {"paymentIntentId": payment_intent["id"], "paymentAttemptId": attempt["id"]}


def run_scenario(client: SafeClient, seed: str, scenario_type: str) -> tuple[dict[str, Any], dict[str, Any]]:
    run = client.request("POST", "/developer/scenario-runs",
        {"scenarioType": scenario_type, "seed": seed}, trusted=True,
        idempotency_key=stable_key(seed, "scenario-run"), expected_status=201)
    worker_id = str(uuid.uuid4())
    while run["lifecycleStatus"] not in {"SUCCEEDED", "FAILED"}:
        run = client.request("POST", f"/developer/scenario-runs/{run['id']}/claims",
            {"workerId": worker_id}, trusted=True,
            idempotency_key=stable_key(seed,
                f"claim-{run['id']}-{run['nextStepIndex']}-{worker_id}"))
        step = run.get("nextStep")
        if step is None:
            break
        path = require_step_path(step["path"])
        resource = client.request("POST", path, step["body"], trusted=True,
            idempotency_key=step["idempotencyKey"], expected_status=201)
        run = client.request("POST",
            f"/developer/scenario-runs/{run['id']}/steps/{step['stepIndex']}/complete", {
                "workerId": worker_id, "leaseEpoch": run["leaseEpoch"],
                "resourceId": resource["id"],
            }, trusted=True, idempotency_key=stable_key(seed,
                f"complete-{run['id']}-{step['stepIndex']}-{run['leaseEpoch']}-{resource['id']}"))
    if run["lifecycleStatus"] != "SUCCEEDED":
        raise DemoFailure(f"Scenario ended in {run['lifecycleStatus']}")
    report = client.request("GET", f"/developer/scenario-runs/{run['id']}/report", trusted=True)
    return run, report


def require_scenario_origins(origin):
    if origin.get("MERCHANT_API", 0) != 0 or origin.get("SYNTHETIC_SCENARIO", 0) < 1:
        raise DemoFailure("Scenario report must contain only its trusted synthetic attempts")


def run_demo(base_url: str, api_key: str, runner_token: str, seed: str,
             scenario_type: str) -> dict[str, Any]:
    if not api_key or not runner_token:
        raise DemoFailure(f"{API_KEY_ENV} and {RUNNER_TOKEN_ENV} must be set")
    client = SafeClient(base_url, api_key, runner_token)
    catalogue = client.request("GET", "/developer/test-cards", trusted=False)
    if catalogue["catalogueVersion"] != "developer_scenarios_v1" or len(catalogue["testCards"]) != 9:
        raise DemoFailure("Developer test-card catalogue is incompatible")
    create_live_reference(client, seed)
    run, report = run_scenario(client, seed, scenario_type)
    origin = report["report"]["originBreakdown"]
    require_scenario_origins(origin)
    rendered = json.dumps({"run": run, "report": report}, sort_keys=True)
    for secret in (api_key, runner_token, seed):
        if secret in rendered:
            raise DemoFailure("Sanitized run/report exposed credential or raw seed material")
    return {"catalogue": catalogue, "run": run, "report": report}


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run THAAP's public trusted-scenario quickstart without reading application source.")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--seed", default="merchant-quickstart")
    parser.add_argument("--scenario", choices=("APPROVED_PAYMENT", "ISSUER_DECLINE", "CARD_TESTING_BURST"),
                        default="APPROVED_PAYMENT")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    arguments = parse_args(argv)
    result = run_demo(arguments.base_url, os.environ.get(API_KEY_ENV, ""),
                      os.environ.get(RUNNER_TOKEN_ENV, ""), arguments.seed, arguments.scenario)
    run = result["run"]
    report = result["report"]
    origins = report["report"]["originBreakdown"]
    print("Developer scenario demo passed.")
    print(f"catalogue=developer_scenarios_v1 cards={len(result['catalogue']['testCards'])} simulator_only=true")
    print(f"run={run['lifecycleStatus']} steps={run['nextStepIndex']}/{run['totalSteps']} report_digest={report['reportDigest']}")
    print(f"live_reference=MERCHANT_API:1 scenario_report=SYNTHETIC_SCENARIO:{origins.get('SYNTHETIC_SCENARIO', 0)}")
    print("client=loopback-only|no-proxy|redirect-blocked credentials=environment-only")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv[1:]))
    except (DemoFailure, ValueError) as exception:
        print(f"Developer scenario demo failed: {exception}", file=sys.stderr)
        raise SystemExit(1)
