"""C58-TEN-001 — one authority per client, proven on the wire.

The dangerous version of this bug is not a rejected call. It is a call that
succeeds because the client quietly presented whichever credential the server
would accept, so an operator key ends up authorizing merchant money movement.
These tests assert what actually left the socket, not what the client intended.
"""

from __future__ import annotations

import uuid

import pytest

from thaap_sdk import Credentials, ThaapApiError, ThaapClient
from thaap_sdk.generated.models.create_customer_request import CreateCustomerRequest

from tests.sandbox_fixture import MERCHANT_KEY, RISK_KEY, SCENARIO_TOKEN, SandboxServer


def test_a_risk_operator_client_cannot_reach_a_merchant_operation() -> None:
    with SandboxServer() as server:
        with ThaapClient(
            Credentials(risk_operator_key=RISK_KEY), base_url=server.base_url
        ) as risk:
            with pytest.raises(ThaapApiError) as rejection:
                risk.customers().post_customers(
                    CreateCustomerRequest(email="cross-authority@example.test"),
                    idempotency_key="cross-authority",
                )
    assert rejection.value.status == 401
    assert rejection.value.code == "api_key_required"


def test_a_merchant_client_cannot_reach_a_risk_operator_operation() -> None:
    with SandboxServer() as server:
        with ThaapClient(
            Credentials(merchant_api_key=MERCHANT_KEY), base_url=server.base_url
        ) as merchant:
            with pytest.raises(ThaapApiError) as rejection:
                merchant.investigations().get_risk_workspace_payments_by_payment_intent_id(
                    uuid.uuid4()
                )
    assert rejection.value.status == 401
    assert rejection.value.code == "risk_operator_credential_required"


def test_a_credential_is_never_offered_to_an_operation_it_does_not_authorize() -> None:
    """The substitution test: no key appears under another scheme's header.

    Asserting only on the 401 would pass even if the client had also sent the
    operator key on the merchant header, which is the leak that matters.
    """
    with SandboxServer() as server:
        with ThaapClient(
            Credentials(risk_operator_key=RISK_KEY), base_url=server.base_url
        ) as risk:
            with pytest.raises(ThaapApiError):
                risk.customers().post_customers(
                    CreateCustomerRequest(email="no-substitution@example.test"),
                    idempotency_key="no-substitution",
                )
        observed = [
            entry for entry in server.state.observed_credentials
            if entry["path"] == "/customers"
        ]
    # No credential at all — not the operator key moved onto the merchant header,
    # and not the operator key sent alongside in the hope the server picks one.
    assert observed == [
        {"path": "/customers", "merchant": None, "risk": None, "scenario": None}
    ]


def test_the_scenario_qualifier_never_travels_on_a_risk_operator_session() -> None:
    """A risk read has no traffic origin to qualify, so the token has no business there."""
    with pytest.raises(ValueError):
        Credentials(risk_operator_key=RISK_KEY, scenario_runner_token=SCENARIO_TOKEN)


def test_no_client_can_hold_two_authorities_at_once() -> None:
    with pytest.raises(ValueError):
        Credentials(merchant_api_key=MERCHANT_KEY, risk_operator_key=RISK_KEY)


def test_the_sdk_exposes_no_tenant_parameter_for_a_caller_to_override() -> None:
    """Tenant is derived from the credential at the server, never claimed by the client."""
    from thaap_sdk.generated.api.payment_intent_controller_api import (
        PaymentIntentControllerApi,
    )
    import inspect

    for name, member in inspect.getmembers(PaymentIntentControllerApi, inspect.isfunction):
        if name.startswith("_"):
            continue
        parameters = set(inspect.signature(member).parameters)
        assert not {"merchant_id", "tenant_id", "merchant"} & parameters, (
            f"{name} accepts a client-supplied tenant identity"
        )
