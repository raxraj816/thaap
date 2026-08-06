"""Reviewed synchronous transport wrapper around generated THAAP operations."""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from typing import Any
from urllib.parse import urlsplit

import urllib3

from thaap_sdk.generated.api_client import ApiClient
from thaap_sdk.generated.configuration import Configuration
from thaap_sdk.generated.exceptions import ApiException


API_VERSION = "2026-08-01"
SCENARIO_RUNNER_TOKEN_HEADER = "X-Thaap-Scenario-Runner-Token"


class ThaapError(Exception):
    """Base exception whose representation never contains credentials or raw bodies."""


class ThaapTransportError(ThaapError):
    def __init__(self, outcome_unknown: bool) -> None:
        self.outcome_unknown = outcome_unknown
        message = (
            "THAAP mutation did not produce an authenticated response; the outcome may be "
            "unknown, so retry only with the identical Idempotency-Key and body."
            if outcome_unknown
            else "THAAP read did not produce an authenticated response; no mutation retry is required."
        )
        super().__init__(message)


@dataclass(frozen=True)
class ThaapFieldError:
    field: str
    message: str


class ThaapApiError(ThaapError):
    def __init__(
        self,
        status: int,
        code: str,
        request_id: str | None,
        retryable: bool,
        recovery_action: str | None,
        field_errors: tuple[ThaapFieldError, ...] = (),
        effective_version: str | None = None,
    ) -> None:
        self.status = status
        self.code = code
        self.request_id = request_id
        self.retryable = retryable
        self.recovery_action = recovery_action
        self.field_errors = field_errors
        self.effective_version = effective_version
        super().__init__(
            f"THAAP API error {code} (HTTP {status}, requestId={request_id or 'unavailable'})"
        )


@dataclass(frozen=True, repr=False)
class Credentials:
    """One THAAP authority per client.

    Merchant and risk-operator keys are separate identities issued to separate
    principals, so a single client carries exactly one of them. Mixing them would
    let a caller downgrade an operation to whichever credential the server happens
    to accept.

    ``scenario_runner_token`` is not a third authority. It is the sandbox qualifier
    that marks merchant traffic as ``SYNTHETIC_SCENARIO`` instead of ``MERCHANT_API``,
    so it is only valid alongside a merchant key. If it were dropped, generated
    scenario payments would be recorded as live merchant traffic and would enter
    live-only fraud metrics.
    """

    merchant_api_key: str | None = None
    risk_operator_key: str | None = None
    scenario_runner_token: str | None = None

    def __post_init__(self) -> None:
        values = (
            self.merchant_api_key,
            self.risk_operator_key,
            self.scenario_runner_token,
        )
        if not any(values):
            raise ValueError("At least one THAAP credential is required")
        for value in values:
            if value is not None and (
                not value.strip()
                or len(value) > 512
                or any(character in "\r\n" for character in value)
            ):
                raise ValueError("THAAP credentials must be non-empty bounded header values")
        if self.merchant_api_key is not None and self.risk_operator_key is not None:
            raise ValueError(
                "Merchant and risk-operator identities are separate; use one client each"
            )
        if self.scenario_runner_token is not None and self.merchant_api_key is None:
            raise ValueError(
                "The scenario runner token qualifies merchant traffic and requires a merchant key"
            )

    def __repr__(self) -> str:
        return "Credentials(<redacted>)"


class _NoRedirectPoolManager(urllib3.PoolManager):
    def request(self, method: str, url: str, **kwargs: Any) -> Any:
        kwargs["redirect"] = False
        return super().request(method, url, **kwargs)


class _SafeApiClient(ApiClient):
    def __init__(
        self, configuration: Configuration, connect_timeout: float, read_timeout: float
    ) -> None:
        self._bounded_timeout = (connect_timeout, read_timeout)
        super().__init__(configuration)
        self.rest_client.pool_manager = _NoRedirectPoolManager(
            cert_reqs="CERT_REQUIRED" if configuration.verify_ssl else "CERT_NONE",
            ca_certs=configuration.ssl_ca_cert,
            retries=urllib3.Retry(
                total=0, connect=0, read=0, redirect=0, status=0, raise_on_redirect=False
            ),
        )

    def call_api(self, *args: Any, **kwargs: Any) -> Any:
        if kwargs.get("_request_timeout") is None:
            kwargs["_request_timeout"] = self._bounded_timeout
        # The safe error is built inside the handler but raised outside it. `raise ...
        # from None` only clears __cause__ and sets __suppress_context__; __context__
        # still references the underlying urllib3 exception, whose text carries the
        # full URL and whose attributes carry request headers. Anything that walks the
        # exception chain — error reporters, structured log handlers — reads through
        # the suppression flag and would publish the credential.
        transport_failure: ThaapTransportError | None = None
        try:
            return super().call_api(*args, **kwargs)
        except ThaapError:
            raise
        except Exception:
            method = str(args[0] if args else kwargs.get("method", "")).upper()
            transport_failure = ThaapTransportError(
                outcome_unknown=method not in {"GET", "HEAD", "OPTIONS"}
            )
        raise transport_failure

    def response_deserialize(self, *args: Any, **kwargs: Any) -> Any:
        api_failure: ThaapApiError | None = None
        try:
            return super().response_deserialize(*args, **kwargs)
        except ApiException as exception:
            api_failure = _safe_api_error(exception)
        raise api_failure


class ThaapClient:
    """Synchronous, no-redirect, bounded-timeout generated client entry point."""

    def __init__(
        self,
        credentials: Credentials,
        base_url: str = "http://127.0.0.1:8080",
        *,
        connect_timeout: float = 2.0,
        read_timeout: float = 10.0,
    ) -> None:
        normalized_url = _validated_base_url(base_url)
        if connect_timeout <= 0 or read_timeout <= 0:
            raise ValueError("THAAP SDK timeouts must be positive")
        # The contract models the runner token two ways, because the server enforces it
        # two ways: as a required `scenarioRunner` scheme on the three trusted scenario
        # operations, and as an optional traffic-origin header everywhere else. The
        # api_key entry covers the first; the default header below covers the second.
        keys = {
            name: value
            for name, value in {
                "merchantApi": credentials.merchant_api_key,
                "riskOperator": credentials.risk_operator_key,
                "scenarioRunner": credentials.scenario_runner_token,
            }.items()
            if value is not None
        }
        configuration = Configuration(
            host=normalized_url,
            api_key=keys,
            verify_ssl=True,
            proxy=None,
            retries=urllib3.Retry(total=0, redirect=0),
        )
        configuration.debug = False
        self.api_client = _SafeApiClient(
            configuration, connect_timeout=connect_timeout, read_timeout=read_timeout
        )
        self.api_client.set_default_header("Thaap-Version", API_VERSION)
        if credentials.scenario_runner_token is not None:
            # A client holding the runner token is a synthetic-traffic session, so the
            # qualifier belongs on every request it makes rather than on whichever call
            # the caller remembered to annotate. Forgetting it does not fail loudly; it
            # records a synthetic payment as live merchant traffic.
            self.api_client.set_default_header(
                SCENARIO_RUNNER_TOKEN_HEADER, credentials.scenario_runner_token
            )
        self.api_client.user_agent = "thaap-risk-sdk/0.1.0"

    def __repr__(self) -> str:
        return "ThaapClient(base_url=<configured>, credentials=<redacted>)"

    def __enter__(self) -> "ThaapClient":
        return self

    def __exit__(self, *_exception: object) -> None:
        self.close()

    def close(self) -> None:
        self.api_client.rest_client.pool_manager.clear()

    def payment_intents(self) -> Any:
        from thaap_sdk.generated.api.payment_intent_controller_api import (
            PaymentIntentControllerApi,
        )

        return PaymentIntentControllerApi(self.api_client)

    def payment_methods(self) -> Any:
        from thaap_sdk.generated.api.payment_method_controller_api import (
            PaymentMethodControllerApi,
        )

        return PaymentMethodControllerApi(self.api_client)

    def customers(self) -> Any:
        from thaap_sdk.generated.api.customer_controller_api import CustomerControllerApi

        return CustomerControllerApi(self.api_client)

    def developer(self) -> Any:
        from thaap_sdk.generated.api.developer_scenario_controller_api import (
            DeveloperScenarioControllerApi,
        )

        return DeveloperScenarioControllerApi(self.api_client)

    def operators(self) -> Any:
        from thaap_sdk.generated.api.risk_operator_controller_api import (
            RiskOperatorControllerApi,
        )

        return RiskOperatorControllerApi(self.api_client)

    def webhooks(self) -> Any:
        from thaap_sdk.generated.api.webhook_controller_api import WebhookControllerApi

        return WebhookControllerApi(self.api_client)

    def investigations(self) -> Any:
        from thaap_sdk.generated.api.payment_investigation_controller_api import (
            PaymentInvestigationControllerApi,
        )

        return PaymentInvestigationControllerApi(self.api_client)

    def analytics(self) -> Any:
        from thaap_sdk.generated.api.risk_analytics_controller_api import (
            RiskAnalyticsControllerApi,
        )

        return RiskAnalyticsControllerApi(self.api_client)


def _safe_api_error(exception: ApiException) -> ThaapApiError:
    """Translate a generated transport exception into the closed typed error.

    Only allowlisted, shape-validated fields cross this boundary. The body itself is
    server-controlled text and never becomes part of the raised exception.
    """
    document: dict[str, Any] = {}
    if exception.body and len(exception.body.encode("utf-8", errors="ignore")) <= 65_536:
        try:
            candidate = json.loads(exception.body)
            if isinstance(candidate, dict):
                document = candidate
        except (TypeError, ValueError):
            document = {}
    code = _safe_token(document.get("error"), 64) or "unclassified_api_error"
    response_version = None
    if exception.headers is not None:
        response_version = exception.headers.get("Thaap-Version")
    return ThaapApiError(
        int(exception.status or 0),
        code,
        _safe_visible(document.get("requestId"), 128),
        bool(document.get("retryable", False)),
        _safe_token(document.get("recoveryAction"), 128),
        _safe_field_errors(document.get("fieldErrors")),
        API_VERSION if response_version == API_VERSION else None,
    )


def _safe_token(value: Any, maximum: int) -> str | None:
    if not isinstance(value, str) or len(value) > maximum:
        return None
    return value if re.fullmatch(r"[a-z][a-z0-9_]*", value) else None


def _safe_visible(value: Any, maximum: int) -> str | None:
    if not isinstance(value, str) or not value or len(value) > maximum:
        return None
    return value if all(33 <= ord(character) <= 126 for character in value) else None


def _safe_message(value: Any, maximum: int) -> str | None:
    if (
        not isinstance(value, str)
        or not value
        or len(value) > maximum
        or value.strip() != value
    ):
        return None
    return value if all(32 <= ord(character) <= 126 for character in value) else None


def _safe_field_errors(value: Any) -> tuple[ThaapFieldError, ...]:
    if not isinstance(value, list) or len(value) > 100:
        return ()
    result: list[ThaapFieldError] = []
    for item in value:
        if not isinstance(item, dict):
            return ()
        field = _safe_visible(item.get("field"), 128)
        message = _safe_message(item.get("message"), 256)
        if field is None or message is None:
            return ()
        result.append(ThaapFieldError(field, message))
    return tuple(result)


def _validated_base_url(value: str) -> str:
    parsed = urlsplit(value)
    if (
        parsed.scheme not in {"http", "https"}
        or parsed.hostname is None
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
        or parsed.path not in {"", "/"}
    ):
        raise ValueError("THAAP base URL must be an origin without credentials, path, or query")
    if parsed.scheme == "http" and parsed.hostname not in {"127.0.0.1", "localhost", "::1"}:
        raise ValueError("Plain HTTP is allowed only for the loopback sandbox")
    return value.rstrip("/")
