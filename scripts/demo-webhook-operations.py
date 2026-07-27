#!/usr/bin/env python3
"""Public demo for signed endpoint verification, dead letters, and exact redelivery."""

from __future__ import annotations

import argparse
import hashlib
import hmac
import http.server
import json
import os
import socket
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from typing import Any


MERCHANT_KEY_ENV = "THAAP_MERCHANT_API_KEY"
RISK_KEY_ENV = "THAAP_RISK_OPERATOR_KEY"
ADMIN_TOKEN_ENV = "THAAP_ADMIN_BOOTSTRAP_TOKEN"
DEFAULT_BASE_URL = "http://127.0.0.1:8080"


class DemoFailure(RuntimeError):
    pass


class RejectRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        raise DemoFailure("The webhook operations client rejects redirects")


def require_loopback(base_url: str) -> str:
    parsed = urllib.parse.urlsplit(base_url)
    if parsed.scheme != "http" or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
        raise DemoFailure("Webhook operations demo accepts only a loopback HTTP API origin")
    if parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in {"", "/"}:
        raise DemoFailure("Webhook operations API origin contains prohibited components")
    try:
        port = parsed.port
    except ValueError as exception:
        raise DemoFailure("Webhook operations API origin has an invalid port") from exception
    if port is None:
        raise DemoFailure("Webhook operations API origin requires an explicit port")
    host = f"[{parsed.hostname}]" if parsed.hostname == "::1" else parsed.hostname
    return f"http://{host}:{port}"


class SafeClient:
    def __init__(self, base_url: str, merchant_key: str, risk_key: str, timeout_seconds: float = 10):
        if timeout_seconds <= 0 or timeout_seconds > 30:
            raise DemoFailure("Webhook operations client timeout must be at most 30 seconds")
        self.base_url = require_loopback(base_url)
        self.merchant_key = merchant_key
        self.risk_key = risk_key
        self.timeout_seconds = timeout_seconds
        self.opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}), RejectRedirectHandler())

    def request(self, method: str, path: str, body: dict[str, Any] | None = None,
                *, risk: bool = False, idempotency_key: str | None = None,
                expected_status: int = 200) -> Any:
        if not path.startswith("/") or path.startswith("//"):
            raise DemoFailure("API request path must remain on the configured origin")
        credential_header = "X-Risk-Operator-Key" if risk else "X-Api-Key"
        credential = self.risk_key if risk else self.merchant_key
        headers = {credential_header: credential, "Accept": "application/json"}
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
            raise DemoFailure(f"{method} {path} did not complete within the bounded request") from error
        if status != expected_status:
            raise DemoFailure(f"{method} {path} returned {status}; expected {expected_status}: {payload[:200]}")
        return json.loads(payload) if payload else None


def provision_credentials(base_url: str, admin_token: str) -> tuple[str, str]:
    if not admin_token:
        raise DemoFailure(
            f"Set both {MERCHANT_KEY_ENV}/{RISK_KEY_ENV}, or set {ADMIN_TOKEN_ENV} for demo provisioning")
    origin = require_loopback(base_url)
    opener = urllib.request.build_opener(
        urllib.request.ProxyHandler({}), RejectRedirectHandler())
    run_id = uuid.uuid4().hex

    def post(path: str, body: dict[str, Any], headers: dict[str, str], expected: int) -> dict[str, Any]:
        request_headers = {
            "Accept": "application/json", "Content-Type": "application/json",
            "Idempotency-Key": f"webhook-operations-provision-{run_id}-{path.rsplit('/', 1)[-1]}",
            **headers,
        }
        request = urllib.request.Request(
            origin + path,
            data=json.dumps(body, sort_keys=True, separators=(",", ":")).encode("utf-8"),
            headers=request_headers,
            method="POST")
        try:
            with opener.open(request, timeout=10) as response:
                payload = response.read().decode("utf-8")
                if response.status != expected:
                    raise DemoFailure(f"Credential provisioning returned HTTP {response.status}")
                return json.loads(payload)
        except urllib.error.HTTPError as error:
            error.read()
            raise DemoFailure(f"Credential provisioning returned HTTP {error.code}") from error
        except (urllib.error.URLError, TimeoutError, socket.timeout) as error:
            raise DemoFailure("Credential provisioning did not complete within the bounded request") from error

    merchant = post("/admin/merchants", {"name": f"Webhook operations {run_id[:12]}"},
                    {"X-Admin-Bootstrap-Token": admin_token}, 201)
    operator = post("/risk/operators", {"role": "RISK_MANAGER"},
                    {"X-Api-Key": merchant["apiKey"]}, 201)
    return merchant["apiKey"], operator["credential"]


class ReceiverState:
    def __init__(self):
        self.fail_events = True
        self.deliveries: list[dict[str, Any]] = []
        self.verification_count = 0
        self.lock = threading.Lock()


class DemoReceiver(http.server.BaseHTTPRequestHandler):
    state: ReceiverState

    def log_message(self, format, *args):
        return

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length < 1 or length > 65_536:
            self.send_response(413)
            self.end_headers()
            return
        payload_bytes = self.rfile.read(length)
        payload = json.loads(payload_bytes)
        if payload.get("type") == "webhook.endpoint.verification":
            challenge = payload.get("challenge", "")
            if not isinstance(challenge, str) or len(challenge) != 43:
                self.send_response(400)
                self.end_headers()
                return
            with self.state.lock:
                self.state.verification_count += 1
            self.send_response(204)
            self.send_header("THAAP-Webhook-Verification", challenge)
            self.end_headers()
            return
        captured = {
            "payload": payload_bytes,
            "eventId": self.headers.get("THAAP-Event-Id"),
            "deliveryId": self.headers.get("THAAP-Delivery-Id"),
            "signature": self.headers.get("THAAP-Signature"),
        }
        with self.state.lock:
            self.state.deliveries.append(captured)
            fail = self.state.fail_events
        self.send_response(400 if fail else 204)
        self.end_headers()


class Receiver:
    def __init__(self):
        self.state = ReceiverState()
        handler = type("BoundDemoReceiver", (DemoReceiver,), {"state": self.state})
        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    @property
    def url(self) -> str:
        return f"http://127.0.0.1:{self.server.server_port}/thaap-webhooks"

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, exception_type, exception, traceback):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)


def verify_signature(secret: str, payload: bytes, header: str) -> bool:
    fields = dict(part.split("=", 1) for part in header.split(","))
    signed = fields["t"].encode("ascii") + b"." + payload
    expected = hmac.new(secret.encode("utf-8"), signed, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, fields["v1"])


def wait_for(client: SafeClient, delivery_id: str | None, status: str,
             timeout_seconds: float = 15) -> dict[str, Any]:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        deliveries = client.request("GET", "/webhook_deliveries?limit=100")
        for delivery in deliveries:
            if delivery_id is None or delivery["id"] == delivery_id:
                if delivery["eventType"] == "payment_intent.created" and delivery["status"] == status:
                    return delivery
        time.sleep(0.1)
    raise DemoFailure(f"Webhook delivery did not reach {status} within {timeout_seconds} seconds")


def run_demo(base_url: str, merchant_key: str, risk_key: str) -> dict[str, Any]:
    if not merchant_key or not risk_key:
        raise DemoFailure(f"{MERCHANT_KEY_ENV} and {RISK_KEY_ENV} must be set")
    client = SafeClient(base_url, merchant_key, risk_key)
    run_id = uuid.uuid4().hex
    with Receiver() as receiver:
        endpoint = client.request("POST", "/webhook_endpoints", {
            "url": receiver.url, "enabledEvents": ["payment_intent.created"],
        }, idempotency_key=f"webhook-demo-endpoint-{run_id}", expected_status=201)
        verification = client.request("POST",
            f"/webhook_endpoints/{endpoint['id']}/verification_challenges",
            idempotency_key=f"webhook-demo-verification-{run_id}")
        if verification["lifecycleStatus"] != "VERIFIED":
            raise DemoFailure("Endpoint verification did not reach VERIFIED")
        customer = client.request("POST", "/customers", {
            "email": f"webhook-demo-{run_id[:12]}@example.test",
        }, expected_status=201)
        client.request("POST", "/payment_intents", {
            "customerId": customer["id"], "amount": 1700,
            "currency": "USD", "captureMethod": "MANUAL",
        }, idempotency_key=f"webhook-demo-intent-{run_id}", expected_status=201)
        exhausted = wait_for(client, None, "EXHAUSTED")
        dead_letters = client.request("GET", "/risk/webhook-dead-letters?limit=25", risk=True)
        if not any(row["id"] == exhausted["id"] for row in dead_letters):
            raise DemoFailure("Exhausted delivery was absent from the operator dead-letter view")
        with receiver.state.lock:
            receiver.state.fail_events = False
        redelivery = client.request("POST",
            f"/risk/webhook-deliveries/{exhausted['id']}/redeliver",
            {"reason": "RECEIVER_REPAIRED"}, risk=True,
            idempotency_key=f"webhook-demo-redelivery-{run_id}")
        succeeded = wait_for(client, exhausted["id"], "SUCCEEDED")
        with receiver.state.lock:
            captured = list(receiver.state.deliveries)
            verification_count = receiver.state.verification_count
        if len(captured) != 2 or verification_count != 1:
            raise DemoFailure("Receiver did not observe one verification and two event attempts")
        first, second = captured
        if first["eventId"] != second["eventId"] or first["payload"] != second["payload"]:
            raise DemoFailure("Manual redelivery changed the immutable event identity or bytes")
        if not all(verify_signature(endpoint["signingSecret"], item["payload"], item["signature"])
                   for item in captured):
            raise DemoFailure("Receiver could not verify a webhook signature over exact bytes")
        rendered = json.dumps({"verification": verification, "deadLetters": dead_letters,
                               "redelivery": redelivery, "succeeded": succeeded}, sort_keys=True)
        for secret in (merchant_key, risk_key, endpoint["signingSecret"]):
            if secret in rendered:
                raise DemoFailure("Sanitized demo result exposed a credential or signing secret")
        return {
            "verification": verification, "exhausted": exhausted,
            "redelivery": redelivery, "succeeded": succeeded,
            "eventId": first["eventId"],
            "payloadSha256": hashlib.sha256(first["payload"]).hexdigest(),
        }


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Run THAAP webhook operations proof.")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    arguments = parser.parse_args(argv)
    merchant_key = os.environ.get(MERCHANT_KEY_ENV, "")
    risk_key = os.environ.get(RISK_KEY_ENV, "")
    if bool(merchant_key) != bool(risk_key):
        raise DemoFailure(f"{MERCHANT_KEY_ENV} and {RISK_KEY_ENV} must be supplied together")
    if not merchant_key:
        merchant_key, risk_key = provision_credentials(
            arguments.base_url, os.environ.get(ADMIN_TOKEN_ENV, ""))
    result = run_demo(arguments.base_url, merchant_key, risk_key)
    print("Webhook operations demo passed.")
    print(f"verification={result['verification']['lifecycleStatus']} dead_letter={result['exhausted']['status']}")
    print(f"redelivery_generation={result['redelivery']['requestedGeneration']} final={result['succeeded']['status']}")
    print(f"event_id={result['eventId']} payload_sha256={result['payloadSha256']} exact_replay=true")
    print("client=loopback-only|no-proxy|redirect-blocked credentials=environment-only")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv[1:]))
    except (DemoFailure, ValueError, KeyError, json.JSONDecodeError) as exception:
        print(f"Webhook operations demo failed: {exception}", file=sys.stderr)
        raise SystemExit(1)
