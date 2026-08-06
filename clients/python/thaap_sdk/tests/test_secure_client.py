from __future__ import annotations

import hashlib
import hmac
import json
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from thaap_sdk import (
    Credentials,
    InMemoryWebhookDeduplicator,
    ThaapApiError,
    ThaapClient,
    ThaapFieldError,
    ThaapTransportError,
    WebhookVerificationError,
    verify_webhook,
)
from thaap_sdk.generated.api.developer_contract_controller_api import (
    DeveloperContractControllerApi,
)
from thaap_sdk.generated.models.create_customer_request import CreateCustomerRequest
from thaap_sdk.generated.models.create_run_request import CreateRunRequest


def test_credentials_and_client_repr_never_expose_secret() -> None:
    secret = "merchant-secret-canary"
    credentials = Credentials(merchant_api_key=secret)
    client = ThaapClient(credentials)
    assert secret not in repr(credentials)
    assert secret not in repr(client)
    assert secret not in str(client.api_client.default_headers)


@pytest.mark.parametrize(
    "base_url",
    [
        "ftp://127.0.0.1:8080",
        "http://user:secret@127.0.0.1:8080",
        "http://127.0.0.1:8080/path",
        "http://127.0.0.1:8080?token=secret",
        "http://example.com",
    ],
)
def test_hostile_base_urls_are_rejected(base_url: str) -> None:
    with pytest.raises(ValueError):
        ThaapClient(Credentials(merchant_api_key="safe"), base_url=base_url)


def test_redirect_is_not_followed_and_proxy_environment_is_ignored(monkeypatch: pytest.MonkeyPatch) -> None:
    state = {"sink": 0}

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:
            if self.path == "/developer/api-versions":
                self.send_response(302)
                self.send_header("Location", "/sink")
                self.end_headers()
                return
            state["sink"] += 1
            self.send_response(204)
            self.end_headers()

        def log_message(self, _format: str, *args: object) -> None:
            return

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    monkeypatch.setenv("HTTPS_PROXY", "http://127.0.0.1:1")
    monkeypatch.setenv("HTTP_PROXY", "http://127.0.0.1:1")
    try:
        client = ThaapClient(
            Credentials(merchant_api_key="secret-canary"),
            base_url=f"http://127.0.0.1:{server.server_port}",
        )
        api = DeveloperContractControllerApi(client.api_client)
        with pytest.raises(ThaapApiError) as failure:
            api.get_developer_api_versions()
        assert failure.value.status == 302
        assert state["sink"] == 0
    finally:
        server.shutdown()
        server.server_close()


def test_stable_error_envelope_is_mapped_without_raw_body() -> None:
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:
            body = json.dumps(
                {
                    "status": 409,
                    "error": "payment_intent_not_confirmable",
                    "requestId": "request-123",
                    "retryable": False,
                    "recoveryAction": "refresh_authoritative_state",
                    "fieldErrors": [
                        {"field": "amount", "message": "must be positive"},
                        {"field": "currency", "message": "must be supported"},
                    ],
                    "message": "safe detail",
                }
            ).encode()
            self.send_response(409)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Thaap-Version", "2026-08-01")
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, _format: str, *args: object) -> None:
            return

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        client = ThaapClient(
            Credentials(merchant_api_key="secret-canary"),
            base_url=f"http://127.0.0.1:{server.server_port}",
        )
        with pytest.raises(ThaapApiError) as failure:
            DeveloperContractControllerApi(client.api_client).get_developer_api_versions()
        assert failure.value.code == "payment_intent_not_confirmable"
        assert failure.value.request_id == "request-123"
        assert failure.value.retryable is False
        assert failure.value.recovery_action == "refresh_authoritative_state"
        assert failure.value.effective_version == "2026-08-01"
        assert failure.value.field_errors == (
            ThaapFieldError("amount", "must be positive"),
            ThaapFieldError("currency", "must be supported"),
        )
        assert "safe detail" not in str(failure.value)
    finally:
        server.shutdown()
        server.server_close()


def test_oversized_or_untrusted_error_content_is_not_reflected() -> None:
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:
            body = (b"x" * 65_537)
            self.send_response(500)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Thaap-Version", "attacker-controlled")
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, _format: str, *args: object) -> None:
            return

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        client = ThaapClient(
            Credentials(merchant_api_key="secret-canary"),
            base_url=f"http://127.0.0.1:{server.server_port}",
        )
        with pytest.raises(ThaapApiError) as failure:
            DeveloperContractControllerApi(client.api_client).get_developer_api_versions()
        assert failure.value.code == "unclassified_api_error"
        assert failure.value.request_id is None
        assert failure.value.field_errors == ()
        assert failure.value.effective_version is None
        assert "x" * 100 not in str(failure.value)
        client.close()
    finally:
        server.shutdown()
        server.server_close()


def test_concurrent_clients_do_not_bleed_credentials_or_request_identity() -> None:
    arrivals = threading.Barrier(2, timeout=5)
    captured: list[tuple[str | None, str | None, bytes]] = []
    capture_lock = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self) -> None:
            body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
            credential = self.headers.get("X-Api-Key")
            idempotency_key = self.headers.get("Idempotency-Key")
            arrivals.wait()
            with capture_lock:
                captured.append((credential, idempotency_key, body))
            self.send_response(204)
            self.end_headers()

        def log_message(self, _format: str, *args: object) -> None:
            return

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base_url = f"http://127.0.0.1:{server.server_port}"

    def create_customer(credential: str, idempotency_key: str, email: str) -> None:
        with ThaapClient(Credentials(merchant_api_key=credential), base_url=base_url) as client:
            client.customers().post_customers_without_preload_content(
                CreateCustomerRequest(email=email), idempotency_key=idempotency_key
            )

    try:
        with ThreadPoolExecutor(max_workers=2) as executor:
            futures = [
                executor.submit(
                    create_customer, "merchant-alpha", "customer-alpha", "alpha@example.test"
                ),
                executor.submit(
                    create_customer, "merchant-beta", "customer-beta", "beta@example.test"
                ),
            ]
            for future in futures:
                future.result(timeout=10)

        observed = {
            (credential, idempotency_key, json.loads(body)["email"])
            for credential, idempotency_key, body in captured
        }
        assert observed == {
            ("merchant-alpha", "customer-alpha", "alpha@example.test"),
            ("merchant-beta", "customer-beta", "beta@example.test"),
        }
    finally:
        server.shutdown()
        server.server_close()


def test_generated_operations_send_one_applicable_credential_and_one_version_header() -> None:
    captured: dict[str, dict[str, str | None]] = {}

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self) -> None:
            self.rfile.read(int(self.headers.get("Content-Length", "0")))
            captured[self.path] = {
                "merchant": self.headers.get("X-Api-Key"),
                "risk": self.headers.get("X-Risk-Operator-Key"),
                "scenario": self.headers.get("X-Thaap-Scenario-Runner-Token"),
                "version": self.headers.get("Thaap-Version"),
            }
            self.send_response(204)
            self.end_headers()

        def do_GET(self) -> None:
            captured[self.path] = {
                "merchant": self.headers.get("X-Api-Key"),
                "risk": self.headers.get("X-Risk-Operator-Key"),
                "scenario": self.headers.get("X-Thaap-Scenario-Runner-Token"),
                "version": self.headers.get("Thaap-Version"),
            }
            self.send_response(204)
            self.end_headers()

        def log_message(self, _format: str, *args: object) -> None:
            return

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base_url = f"http://127.0.0.1:{server.server_port}"
    try:
        with ThaapClient(
            Credentials(merchant_api_key="merchant-secret"), base_url=base_url
        ) as merchant:
            merchant.customers().post_customers_without_preload_content(
                CreateCustomerRequest(email="sdk-contract@example.test"),
                idempotency_key="customer-once",
            )
        with ThaapClient(
            Credentials(risk_operator_key="risk-secret"), base_url=base_url
        ) as risk:
            risk.operators().get_risk_operators_me_without_preload_content()
        with ThaapClient(
            Credentials(
                merchant_api_key="merchant-secret",
                scenario_runner_token="scenario-secret",
            ),
            base_url=base_url,
        ) as scenario:
            scenario.developer().post_developer_scenario_runs_without_preload_content(
                CreateRunRequest(scenario_type="APPROVED_PAYMENT", seed="credential-proof"),
                idempotency_key="scenario-once",
            )
        assert captured["/customers"] == {
            "merchant": "merchant-secret",
            "risk": None,
            "scenario": None,
            "version": "2026-08-01",
        }
        assert captured["/risk/operators/me"] == {
            "merchant": None,
            "risk": "risk-secret",
            "scenario": None,
            "version": "2026-08-01",
        }
        assert captured["/developer/scenario-runs"] == {
            "merchant": "merchant-secret",
            "risk": None,
            "scenario": "scenario-secret",
            "version": "2026-08-01",
        }
    finally:
        server.shutdown()
        server.server_close()


def test_separate_authorities_cannot_be_combined_into_one_client() -> None:
    with pytest.raises(ValueError):
        Credentials(merchant_api_key="merchant-secret", risk_operator_key="risk-secret")
    with pytest.raises(ValueError):
        Credentials(risk_operator_key="risk-secret", scenario_runner_token="scenario-secret")
    with pytest.raises(ValueError):
        Credentials(scenario_runner_token="scenario-secret")


def test_scenario_qualifier_is_absent_unless_the_session_is_synthetic() -> None:
    observed: list[str | None] = []

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self) -> None:
            self.rfile.read(int(self.headers.get("Content-Length", "0")))
            observed.append(self.headers.get("X-Thaap-Scenario-Runner-Token"))
            self.send_response(204)
            self.end_headers()

        def log_message(self, _format: str, *args: object) -> None:
            return

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base_url = f"http://127.0.0.1:{server.server_port}"
    request = CreateCustomerRequest(email="origin-proof@example.test")
    try:
        with ThaapClient(
            Credentials(merchant_api_key="merchant-secret"), base_url=base_url
        ) as live:
            live.customers().post_customers_without_preload_content(
                request, idempotency_key="live-origin"
            )
        with ThaapClient(
            Credentials(
                merchant_api_key="merchant-secret",
                scenario_runner_token="scenario-secret",
            ),
            base_url=base_url,
        ) as synthetic:
            synthetic.customers().post_customers_without_preload_content(
                request, idempotency_key="synthetic-origin"
            )
        # A dropped qualifier does not fail; it silently records a synthetic payment
        # as MERCHANT_API traffic, which is how synthetic volume reaches live-only
        # fraud metrics.
        assert observed == [None, "scenario-secret"]
    finally:
        server.shutdown()
        server.server_close()


def test_read_timeout_distinguishes_read_from_unknown_mutation_outcome() -> None:
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:
            time.sleep(0.1)
            self.send_response(204)
            self.end_headers()

        def log_message(self, _format: str, *args: object) -> None:
            return

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        client = ThaapClient(
            Credentials(merchant_api_key="secret-canary"),
            base_url=f"http://127.0.0.1:{server.server_port}",
            read_timeout=0.02,
        )
        with pytest.raises(ThaapTransportError) as failure:
            DeveloperContractControllerApi(client.api_client).get_developer_api_versions()
        assert failure.value.outcome_unknown is False
        client.close()
    finally:
        server.shutdown()
        server.server_close()


def test_unknown_mutation_replay_uses_identical_idempotency_key_and_bytes() -> None:
    requests: list[tuple[str | None, bytes]] = []

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self) -> None:
            body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
            requests.append((self.headers.get("Idempotency-Key"), body))
            if len(requests) == 1:
                time.sleep(0.1)
            self.send_response(204)
            self.end_headers()

        def log_message(self, _format: str, *args: object) -> None:
            return

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    model = CreateCustomerRequest(email="sdk-replay@example.test")
    base_url = f"http://127.0.0.1:{server.server_port}"
    try:
        first = ThaapClient(
            Credentials(merchant_api_key="secret-canary"),
            base_url=base_url,
            read_timeout=0.02,
        )
        with pytest.raises(ThaapTransportError) as failure:
            first.customers().post_customers_without_preload_content(
                model, idempotency_key="stable-replay-key"
            )
        assert failure.value.outcome_unknown is True
        first.close()

        with ThaapClient(
            Credentials(merchant_api_key="secret-canary"), base_url=base_url
        ) as retry:
            retry.customers().post_customers_without_preload_content(
                model, idempotency_key="stable-replay-key"
            )
        assert len(requests) == 2
        assert requests[0] == requests[1]
    finally:
        server.shutdown()
        server.server_close()


def test_webhook_exact_bytes_timestamp_event_identity_and_deduplication() -> None:
    event_id = str(uuid.uuid4())
    payload = json.dumps({"id": event_id, "type": "payment.captured"}, separators=(",", ":")).encode()
    timestamp = 1_800_000_000
    secret = "webhook-secret"
    digest = hmac.new(
        secret.encode(), str(timestamp).encode() + b"." + payload, hashlib.sha256
    ).hexdigest()
    document = verify_webhook(
        payload, f"t={timestamp},v1={digest}", event_id, secret, now=timestamp
    )
    assert document["id"] == event_id
    deduplicator = InMemoryWebhookDeduplicator()
    assert deduplicator.apply_once(event_id, payload) is True
    assert deduplicator.apply_once(event_id, payload) is False
    with pytest.raises(WebhookVerificationError):
        deduplicator.apply_once(event_id, payload + b" ")
    with pytest.raises(WebhookVerificationError):
        verify_webhook(
            payload + b" ", f"t={timestamp},v1={digest}", event_id, secret, now=timestamp
        )
    with pytest.raises(WebhookVerificationError):
        verify_webhook(
            payload, f"t={timestamp},v1={digest}", str(uuid.uuid4()), secret, now=timestamp
        )
    with pytest.raises(WebhookVerificationError):
        verify_webhook(
            payload,
            f"t={timestamp},v1={digest}",
            event_id,
            secret,
            now=timestamp + 301,
        )
    with pytest.raises(WebhookVerificationError):
        verify_webhook(payload, f"t={timestamp},v1={digest}", event_id, "", now=timestamp)
    with pytest.raises(WebhookVerificationError):
        verify_webhook(
            b"x" * 65_537,
            f"t={timestamp},v1={digest}",
            event_id,
            secret,
            now=timestamp,
        )
