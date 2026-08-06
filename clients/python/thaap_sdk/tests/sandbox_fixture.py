"""A loopback THAAP sandbox stand-in for SDK contract tests.

The fixture is deliberately not a mock of the SDK. It is a real HTTP server that
speaks the committed contract's wire shapes, so a test exercises URL building,
header attachment, serialization, status handling, and model parsing the same way
the local sandbox would. A mock could not certify any of those.

It also stores idempotency the way the API does — key plus exact request bytes —
because the property the quickstart depends on is that an ambiguous retry with the
same key and body produces one payment, and that a reused key with different bytes
is refused rather than silently applied.
"""

from __future__ import annotations

import hashlib
import json
import threading
import uuid
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Callable


MERCHANT_KEY = "sandbox-merchant-key"
RISK_KEY = "sandbox-risk-operator-key"
SCENARIO_TOKEN = "sandbox-scenario-runner-token"
API_VERSION = "2026-08-01"

# The fixture answers with the attempt statuses the real confirm endpoint can
# produce. A quickstart that only ever sees APPROVED teaches an integrator to
# write code that breaks the first time a payment is held for review.
CONFIRM_OUTCOMES: dict[str, dict[str, Any]] = {
    "APPROVED": {"status": "AUTHORIZED", "threeDsStatus": None},
    "REVIEW": {"status": "IN_REVIEW", "threeDsStatus": None},
    "THREE_DS": {
        "status": "REQUIRES_ACTION",
        "threeDsStatus": "REQUESTED",
        "nextAction": {
            "source": "FRAUD_ENGINE",
            "challengeId": "00000000-0000-4000-8000-000000000001",
            "completionEndpoint": "/payment_attempts/authentication",
            "expiresAt": "2026-08-01T00:10:00Z",
        },
    },
    "BLOCKED": {"status": "BLOCKED", "threeDsStatus": None},
    "DECLINED": {"status": "DECLINED", "threeDsStatus": None},
}


@dataclass
class SandboxState:
    """Everything the fixture remembers, so a test can assert at-most-once effects."""

    confirm_outcome: str = "APPROVED"
    # Maps idempotency key -> (request digest, stored response) exactly like the API.
    idempotent_responses: dict[str, tuple[str, tuple[int, dict[str, Any]]]] = field(
        default_factory=dict
    )
    confirm_calls: int = 0
    processor_authorizations: list[str] = field(default_factory=list)
    requests: list[tuple[str, str, str | None, bytes]] = field(default_factory=list)
    # Every credential-bearing header seen per request, so a test can prove what
    # actually left the socket rather than what the client meant to send.
    observed_credentials: list[dict[str, str | None]] = field(default_factory=list)
    stall_paths: set[str] = field(default_factory=set)
    stall_seconds: float = 0.5
    lock: threading.Lock = field(default_factory=threading.Lock)


class _SandboxHandler(BaseHTTPRequestHandler):
    state: SandboxState
    protocol_version = "HTTP/1.1"

    def log_message(self, _format: str, *args: object) -> None:
        return

    def handle_one_request(self) -> None:
        # A client that abandons a stalled request leaves this handler writing to a
        # closed socket. That is the condition under test, not a fixture defect, so
        # it must not print a traceback that hides a real failure in the output.
        try:
            super().handle_one_request()
        except (BrokenPipeError, ConnectionResetError):
            self.close_connection = True

    # -- request plumbing -------------------------------------------------

    def _read_body(self) -> bytes:
        length = int(self.headers.get("Content-Length", "0"))
        return self.rfile.read(length) if length else b""

    def _respond(self, status: int, document: dict[str, Any] | None) -> None:
        body = b"" if document is None else json.dumps(document).encode()
        self.send_response(status)
        self.send_header("Thaap-Version", API_VERSION)
        if body:
            self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)

    def _error(self, status: int, code: str, retryable: bool = False) -> None:
        self._respond(
            status,
            {
                "requestId": str(uuid.uuid4()),
                "timestamp": "2026-08-01T00:00:00Z",
                "status": status,
                "error": code,
                "message": "Safe human guidance that clients must not parse.",
                "path": self.path,
                "retryable": retryable,
                "recoveryAction": "refresh_authoritative_state_before_retry",
                "fieldErrors": [],
            },
        )

    def _record_credentials(self) -> None:
        self.state.observed_credentials.append({
            "path": self.path,
            "merchant": self.headers.get("X-Api-Key"),
            "risk": self.headers.get("X-Risk-Operator-Key"),
            "scenario": self.headers.get("X-Thaap-Scenario-Runner-Token"),
        })

    def _authorized(self, scheme: str) -> bool:
        if scheme == "merchantApi":
            return self.headers.get("X-Api-Key") == MERCHANT_KEY
        return self.headers.get("X-Risk-Operator-Key") == RISK_KEY

    def _idempotent(
        self, body: bytes, produce: Callable[[], tuple[int, dict[str, Any]]]
    ) -> None:
        """Replay protection keyed on the key *and* the exact request bytes."""
        key = self.headers.get("Idempotency-Key")
        digest = hashlib.sha256(self.path.encode() + b"\0" + body).hexdigest()
        with self.state.lock:
            if key is not None and key in self.state.idempotent_responses:
                stored_digest, stored = self.state.idempotent_responses[key]
                if stored_digest != digest:
                    self._error(409, "idempotency_key_reused_with_different_request")
                    return
                self._respond(*stored)
                return
            status, document = produce()
            if key is not None:
                self.state.idempotent_responses[key] = (digest, (status, document))
        self._respond(status, document)

    # -- routes -----------------------------------------------------------

    def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler contract
        self.state.requests.append(("GET", self.path, self.headers.get("Idempotency-Key"), b""))
        self._record_credentials()
        if self.path in self.state.stall_paths:
            threading.Event().wait(self.state.stall_seconds)
        if self.path.startswith("/risk/"):
            if not self._authorized("riskOperator"):
                self._error(401, "risk_operator_credential_required")
                return
        elif not self._authorized("merchantApi"):
            self._error(401, "api_key_required")
            return

        if self.path.startswith("/risk/workspace/payments/"):
            self._respond(200, _investigation_document(self.path.rsplit("/", 1)[-1]))
        elif self.path == "/risk/analytics/merchant/summary":
            self._respond(200, _merchant_summary_document())
        else:
            self._error(404, "resource_not_found")

    def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler contract
        body = self._read_body()
        self.state.requests.append(
            ("POST", self.path, self.headers.get("Idempotency-Key"), body)
        )
        self._record_credentials()
        if self.path in self.state.stall_paths:
            threading.Event().wait(self.state.stall_seconds)
        if not self._authorized("merchantApi"):
            self._error(401, "api_key_required")
            return
        supplied_token = self.headers.get("X-Thaap-Scenario-Runner-Token")
        if supplied_token is not None and supplied_token != SCENARIO_TOKEN:
            self._error(403, "scenario_runner_credential_invalid")
            return
        origin = "SYNTHETIC_SCENARIO" if supplied_token else "MERCHANT_API"

        if self.path == "/customers":
            self._idempotent(body, lambda: (201, {
                "id": str(uuid.uuid4()), "merchantId": str(uuid.uuid4())}))
        elif self.path == "/payment_methods":
            self._idempotent(body, lambda: (201, {
                "id": str(uuid.uuid4()),
                "merchantId": str(uuid.uuid4()),
                "customerId": json.loads(body)["customerId"],
                "type": "CARD", "token": "tok_sandbox", "cardBin": "424242",
                "cardLast4": "4242", "cardCountry": "US", "cardFunding": "CREDIT"}))
        elif self.path == "/payment_intents":
            self._idempotent(body, lambda: (201, _payment_intent_document(body, origin)))
        elif self.path.endswith("/confirm"):
            self._idempotent(body, lambda: self._confirm(origin))
        elif self.path == "/risk/operators":
            self._idempotent(body, lambda: (201, {
                "id": str(uuid.uuid4()), "credential": RISK_KEY,
                "credentialPrefix": RISK_KEY[:8], "operatorCode": "OPERATOR-1",
                "role": json.loads(body)["role"], "status": "ACTIVE"}))
        elif self.path == "/developer/scenario-runs":
            if supplied_token is None:
                self._error(403, "scenario_runner_credential_required")
                return
            self._idempotent(body, lambda: (201, {
                "id": str(uuid.uuid4()), "lifecycleStatus": "CREATED",
                "scenarioType": json.loads(body)["scenarioType"]}))
        elif "/claims" in self.path:
            if supplied_token is None:
                self._error(403, "scenario_runner_credential_required")
                return
            self._idempotent(body, lambda: (200, {
                "id": str(uuid.uuid4()), "lifecycleStatus": "RUNNING"}))
        else:
            self._error(404, "resource_not_found")

    def _confirm(self, origin: str) -> tuple[int, dict[str, Any]]:
        """Count only committed confirmations, so replays cannot hide a double charge."""
        self.state.confirm_calls += 1
        authorization = f"auth_{uuid.uuid4().hex[:12]}"
        self.state.processor_authorizations.append(authorization)
        outcome = CONFIRM_OUTCOMES[self.state.confirm_outcome]
        document: dict[str, Any] = {
            "id": str(uuid.uuid4()),
            "merchantId": str(uuid.uuid4()),
            "paymentIntentId": str(uuid.uuid4()),
            "paymentMethodId": str(uuid.uuid4()),
            "attemptNumber": 1,
            "fraudDecisionId": str(uuid.uuid4()),
            "processorAuthorizationId": authorization,
            "trafficOrigin": origin,
        }
        document.update(outcome)
        return 201, document


def _payment_intent_document(body: bytes, origin: str) -> dict[str, Any]:
    request = json.loads(body)
    return {
        "id": str(uuid.uuid4()),
        "merchantId": str(uuid.uuid4()),
        "customerId": request.get("customerId"),
        "amount": request["amount"],
        "currency": request["currency"],
        "status": "REQUIRES_CONFIRMATION",
        "captureMethod": request.get("captureMethod", "AUTOMATIC"),
        "metadata": request.get("metadata", {}),
        "trafficOrigin": origin,
        "createdAt": "2026-08-01T00:00:00Z",
        "updatedAt": "2026-08-01T00:00:00Z",
    }


def _investigation_document(payment_intent_id: str) -> dict[str, Any]:
    return {
        "identity": {"paymentIntentId": payment_intent_id},
        "sourceSnapshot": {
            "sourceAsOf": "2026-08-01T00:00:00.123456Z",
            "maximumPaymentStreamVersion": 7,
            "consistency": "CONSISTENT",
        },
        "analyticsContext": {
            "freshness": _freshness_document("FRESH"),
        },
        "decisionExplanation": {
            "riskScoreBps": 10_000,
            "riskLevel": "HIGH",
            "finalAction": "REVIEW",
            "reasonCodes": ["VELOCITY_EXCEEDED"],
            "modelState": "NOT_CONFIGURED",
            "featureCatalogueVersion": "feature-catalogue-v3",
            "featureFreshness": "FRESH",
            "latencyMs": 42,
            "maximumFeatureAgeMs": 900_000,
            "decisionStartedAt": "2026-08-01T00:00:00Z",
            "decisionCompletedAt": "2026-08-01T00:00:00.042Z",
            "missingFeatureNames": [],
            "staleFeatureNames": [],
        },
        "moneyAndDisputes": {
            "currency": "USD",
            "capturedAmountMinor": 9_007_199_254_740_993,
            "refundedAmountMinor": 0,
            "disputedAmountMinor": 0,
            "ledgerBalanced": True,
        },
        "sectionStatuses": {
            "relatedLinksSummary": {"state": "PARTIAL", "authority": "SOURCE"},
        },
    }


def _freshness_document(state: str) -> dict[str, Any]:
    return {
        "state": state,
        "generationId": str(uuid.uuid4()),
        "generationNumber": 4,
        "sourceCutoff": "2026-08-01T00:00:00Z",
        "lastAppliedEventTime": "2026-07-31T23:59:59Z",
        "lastProcessingTime": "2026-08-01T00:00:01Z",
        "eligibleEventCount": 120,
        "appliedEventCount": 118,
        "unavailableStreamCount": 2,
        "trafficOriginPolicy": "LIVE_ONLY",
    }


def _merchant_summary_document() -> dict[str, Any]:
    return {"freshness": _freshness_document("PARTIAL")}


class SandboxServer:
    """Context manager that starts the fixture on an ephemeral loopback port."""

    def __init__(self, confirm_outcome: str = "APPROVED") -> None:
        self.state = SandboxState(confirm_outcome=confirm_outcome)
        handler = type("_BoundHandler", (_SandboxHandler,), {"state": self.state})
        self._server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)

    @property
    def base_url(self) -> str:
        return f"http://127.0.0.1:{self._server.server_port}"

    def __enter__(self) -> "SandboxServer":
        self._thread.start()
        return self

    def __exit__(self, *_exception: object) -> None:
        self._server.shutdown()
        self._server.server_close()
