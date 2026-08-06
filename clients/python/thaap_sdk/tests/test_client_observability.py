"""C58-OBS-001 — the SDK must be quiet, and quiet in a specific way.

An HTTP client that logs by default is a credential exfiltration path that nobody
chose: the integrator's log aggregator ends up holding API keys, idempotency keys
and payment identifiers, indefinitely, in a system with different access rules
than the payment API itself. These tests assert on captured log records and on
exception text, because those are the two places secrets actually escape.
"""

from __future__ import annotations

import logging
import uuid

import pytest

from thaap_sdk import Credentials, ThaapApiError, ThaapClient, ThaapTransportError
from thaap_sdk.generated.models.create_customer_request import CreateCustomerRequest

from tests.sandbox_fixture import MERCHANT_KEY, RISK_KEY, SCENARIO_TOKEN, SandboxServer


SECRETS = (MERCHANT_KEY, RISK_KEY, SCENARIO_TOKEN)


def _all_text(records: list[logging.LogRecord]) -> str:
    parts: list[str] = []
    for record in records:
        parts.append(str(record.getMessage()))
        parts.append(str(record.args))
        if record.exc_info:
            parts.append(str(record.exc_info))
    return "\n".join(parts)


def test_a_successful_journey_emits_no_log_records_by_default(
    caplog: pytest.LogCaptureFixture,
) -> None:
    """Default-on wire logging is the failure mode; opting in must be the caller's choice."""
    with caplog.at_level(logging.DEBUG):
        with SandboxServer() as server:
            with ThaapClient(
                Credentials(merchant_api_key=MERCHANT_KEY, scenario_runner_token=SCENARIO_TOKEN),
                base_url=server.base_url,
            ) as client:
                client.customers().post_customers(
                    CreateCustomerRequest(email="quiet@example.test"),
                    idempotency_key="quiet-journey",
                )
    text = _all_text(caplog.records)
    for secret in SECRETS:
        assert secret not in text
    assert "Idempotency-Key" not in text


def test_debug_is_off_so_the_generated_transport_cannot_print_headers() -> None:
    with SandboxServer() as server:
        with ThaapClient(
            Credentials(merchant_api_key=MERCHANT_KEY), base_url=server.base_url
        ) as client:
            assert client.api_client.configuration.debug is False


def test_an_api_failure_carries_closed_typed_state_and_no_transcript(
    caplog: pytest.LogCaptureFixture,
) -> None:
    with caplog.at_level(logging.DEBUG):
        with SandboxServer() as server:
            with ThaapClient(
                Credentials(merchant_api_key=MERCHANT_KEY), base_url=server.base_url
            ) as merchant:
                with pytest.raises(ThaapApiError) as failure:
                    merchant.investigations().get_risk_workspace_payments_by_payment_intent_id(
                        uuid.uuid4()
                    )
    error = failure.value
    # Everything a caller needs to branch, and nothing a caller could parse for prose.
    assert isinstance(error.status, int)
    assert error.code.replace("_", "").isalnum()
    assert isinstance(error.retryable, bool)
    rendered = f"{error} {error!r}"
    assert "Safe human guidance" not in rendered
    for secret in (*SECRETS, "127.0.0.1"):
        assert secret not in rendered
    assert MERCHANT_KEY not in _all_text(caplog.records)


def test_a_transport_failure_reports_a_decision_not_a_stack_of_internals() -> None:
    """The only fact that matters here is whether replaying is safe."""
    with SandboxServer() as server:
        server.state.stall_paths = {"/customers"}
        client = ThaapClient(
            Credentials(merchant_api_key=MERCHANT_KEY),
            base_url=server.base_url,
            read_timeout=0.05,
        )
        with pytest.raises(ThaapTransportError) as failure:
            client.customers().post_customers(
                CreateCustomerRequest(email="opaque@example.test"),
                idempotency_key="opaque-timeout",
            )
        client.close()

    rendered = f"{failure.value} {failure.value!r}"
    assert failure.value.outcome_unknown is True
    assert "Idempotency-Key" in rendered  # the recovery instruction, not a key value
    for secret in (*SECRETS, "opaque-timeout", "opaque@example.test", "127.0.0.1"):
        assert secret not in rendered
    # A chained cause or context would carry the urllib3 exception text, including
    # the full URL, and any reporter that walks the chain would publish it.
    assert failure.value.__cause__ is None
    assert failure.value.__context__ is None


def test_the_exception_chain_never_reattaches_the_raw_response_body() -> None:
    with SandboxServer() as server:
        with ThaapClient(
            Credentials(merchant_api_key=MERCHANT_KEY), base_url=server.base_url
        ) as client:
            with pytest.raises(ThaapApiError) as failure:
                client.investigations().get_risk_workspace_payments_by_payment_intent_id(
                    uuid.uuid4()
                )
    assert failure.value.__cause__ is None
    assert failure.value.__context__ is None


def test_the_client_representation_stays_constant_regardless_of_configuration() -> None:
    """A repr that varied with the base URL would leak deployment topology into logs."""
    with SandboxServer() as server:
        with ThaapClient(
            Credentials(merchant_api_key=MERCHANT_KEY), base_url=server.base_url
        ) as client:
            rendered = repr(client)
    assert rendered == "ThaapClient(base_url=<configured>, credentials=<redacted>)"
    assert str(server.base_url) not in rendered
