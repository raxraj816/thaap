"""C58-PAY-001 — the supported quickstart is a payment journey, not a smoke test.

Every assertion here is about money safety. A quickstart is the first code a new
merchant copies, so whatever it demonstrates becomes the integration pattern that
runs against real cards later. If it hides an ambiguous outcome, or teaches an
integrator to mint a fresh idempotency key on retry, it has taught a double charge.
"""

from __future__ import annotations

import json
import runpy
import uuid

import pytest

from thaap_sdk import Credentials, ThaapApiError, ThaapClient, ThaapTransportError
from thaap_sdk.generated.models.confirm_payment_intent_request import (
    ConfirmPaymentIntentRequest,
)
from thaap_sdk.generated.models.create_customer_request import CreateCustomerRequest
from thaap_sdk.generated.models.create_payment_intent_request import (
    CreatePaymentIntentRequest,
)

from tests.sandbox_fixture import CONFIRM_OUTCOMES, MERCHANT_KEY, SCENARIO_TOKEN, SandboxServer


QUICKSTART = "clients/python/thaap_sdk/examples/sandbox_quickstart.py"


def _merchant(server: SandboxServer) -> ThaapClient:
    return ThaapClient(Credentials(merchant_api_key=MERCHANT_KEY), base_url=server.base_url)


def _confirm_once(client: ThaapClient, key: str) -> object:
    customer = client.customers().post_customers(
        CreateCustomerRequest(email=f"{key}@example.test"), idempotency_key=f"{key}-customer"
    )
    intent = client.payment_intents().post_payment_intents(
        CreatePaymentIntentRequest(
            amount=4_242,
            currency="USD",
            capture_method="AUTOMATIC",
            customer_id=customer.id,
        ),
        idempotency_key=f"{key}-intent",
    )
    return client.payment_intents().post_payment_intents_by_id_confirm(
        intent.id,
        ConfirmPaymentIntentRequest(payment_method_id=uuid.uuid4()),
        idempotency_key=f"{key}-confirm",
    )


@pytest.mark.parametrize("outcome", sorted(CONFIRM_OUTCOMES))
def test_every_confirm_outcome_is_a_typed_result_rather_than_an_exception(outcome: str) -> None:
    """Review, 3DS and decline are ordinary business results, not failures.

    An SDK that raised on REQUIRES_REVIEW would push integrators toward retrying a
    payment that a human is already looking at.
    """
    with SandboxServer(confirm_outcome=outcome) as server:
        with _merchant(server) as client:
            attempt = _confirm_once(client, f"outcome-{outcome}")
        expected = CONFIRM_OUTCOMES[outcome]
        assert attempt.status == expected["status"]
        assert attempt.three_ds_status == expected["threeDsStatus"]
        assert attempt.processor_authorization_id is not None
        assert server.state.confirm_calls == 1


def test_replaying_an_ambiguous_confirm_charges_at_most_once() -> None:
    """The whole reason the quickstart threads one stable key through the journey."""
    with SandboxServer() as server:
        server.state.stall_paths = {"/customers"}
        server.state.stall_seconds = 0.5
        request = CreateCustomerRequest(email="ambiguous@example.test")

        timing_out = ThaapClient(
            Credentials(merchant_api_key=MERCHANT_KEY),
            base_url=server.base_url,
            read_timeout=0.05,
        )
        with pytest.raises(ThaapTransportError) as ambiguous:
            timing_out.customers().post_customers(request, idempotency_key="stable-key")
        # The distinction that decides whether replaying is safe at all.
        assert ambiguous.value.outcome_unknown is True
        timing_out.close()

        server.state.stall_paths = set()
        with _merchant(server) as retry:
            first = retry.customers().post_customers(request, idempotency_key="stable-key")
            second = retry.customers().post_customers(request, idempotency_key="stable-key")

        # One resource, not three, despite three arriving requests.
        assert first.id == second.id
        posts = [entry for entry in server.state.requests if entry[0] == "POST"]
        assert len(posts) == 3
        assert {entry[2] for entry in posts} == {"stable-key"}
        assert len({entry[3] for entry in posts}) == 1


def test_reusing_a_key_with_different_bytes_is_refused_not_silently_applied() -> None:
    with SandboxServer() as server:
        with _merchant(server) as client:
            client.customers().post_customers(
                CreateCustomerRequest(email="first@example.test"),
                idempotency_key="shared-key",
            )
            with pytest.raises(ThaapApiError) as conflict:
                client.customers().post_customers(
                    CreateCustomerRequest(email="second@example.test"),
                    idempotency_key="shared-key",
                )
    assert conflict.value.status == 409
    assert conflict.value.code == "idempotency_key_reused_with_different_request"
    # An integrator must be able to branch on the code without reading prose.
    assert conflict.value.retryable is False


def test_the_sdk_never_retries_a_mutation_on_its_own() -> None:
    """Automatic retry is the failure mode; the caller must decide, holding the key."""
    with SandboxServer() as server:
        server.state.stall_paths = {"/customers"}
        client = ThaapClient(
            Credentials(merchant_api_key=MERCHANT_KEY),
            base_url=server.base_url,
            read_timeout=0.05,
        )
        with pytest.raises(ThaapTransportError):
            client.customers().post_customers(
                CreateCustomerRequest(email="no-auto-retry@example.test"),
                idempotency_key="single-attempt",
            )
        client.close()
        assert len([entry for entry in server.state.requests if entry[0] == "POST"]) == 1


def test_scenario_traffic_is_tagged_synthetic_and_live_traffic_is_not() -> None:
    """Synthetic volume entering live fraud metrics is a data-integrity defect."""
    with SandboxServer() as server:
        with _merchant(server) as live:
            live_intent = live.payment_intents().post_payment_intents(
                CreatePaymentIntentRequest(amount=1_000, currency="USD", customer_id=uuid.uuid4()),
                idempotency_key="live-intent",
            )
        with ThaapClient(
            Credentials(merchant_api_key=MERCHANT_KEY, scenario_runner_token=SCENARIO_TOKEN),
            base_url=server.base_url,
        ) as synthetic:
            synthetic_intent = synthetic.payment_intents().post_payment_intents(
                CreatePaymentIntentRequest(amount=1_000, currency="USD", customer_id=uuid.uuid4()),
                idempotency_key="synthetic-intent",
            )
    assert live_intent.traffic_origin == "MERCHANT_API"
    assert synthetic_intent.traffic_origin == "SYNTHETIC_SCENARIO"


def test_an_invalid_scenario_token_fails_before_a_payment_is_created() -> None:
    with SandboxServer() as server:
        with ThaapClient(
            Credentials(merchant_api_key=MERCHANT_KEY, scenario_runner_token="wrong-token"),
            base_url=server.base_url,
        ) as client:
            with pytest.raises(ThaapApiError) as rejection:
                client.payment_intents().post_payment_intents(
                    CreatePaymentIntentRequest(amount=1_000, currency="USD", customer_id=uuid.uuid4()),
                    idempotency_key="rejected-intent",
                )
        assert rejection.value.status == 403
        assert rejection.value.code == "scenario_runner_credential_invalid"
        assert server.state.confirm_calls == 0


def test_the_published_quickstart_runs_from_environment_alone(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    """The zero-source-reading claim, executed.

    The example is run as published — by path, through its documented environment
    variables — so documentation drift fails here rather than in a new merchant's
    first integration.
    """
    with SandboxServer(confirm_outcome="REVIEW") as server:
        monkeypatch.setenv("THAAP_MERCHANT_API_KEY", MERCHANT_KEY)
        monkeypatch.setenv("THAAP_BASE_URL", server.base_url)
        monkeypatch.setenv("THAAP_SCENARIO_RUNNER_TOKEN", SCENARIO_TOKEN)
        monkeypatch.setenv("THAAP_QUICKSTART_KEY", "published-journey")

        runpy.run_path(_repository_path(QUICKSTART), run_name="__main__")

        output = capsys.readouterr().out
        assert "THAAP quickstart complete:" in output
        assert "attemptStatus=IN_REVIEW" in output
        assert "finalAction=REVIEW" in output
        assert "analyticsFreshness=PARTIAL" in output
        assert "scenarioStatus=RUNNING" in output
        # The credential must never reach stdout an operator may paste into a ticket.
        assert MERCHANT_KEY not in output
        assert SCENARIO_TOKEN not in output
        assert server.state.confirm_calls == 1

        scenario_posts = [
            entry for entry in server.state.requests
            if entry[0] == "POST" and "scenario-run" in entry[1]
        ]
        assert scenario_posts, "the quickstart must exercise the scenario runner"
        for entry in server.state.requests:
            if entry[3]:
                assert MERCHANT_KEY not in json.loads(entry[3]).values()


def _repository_path(relative: str) -> str:
    import pathlib

    root = pathlib.Path(__file__).resolve().parents[4]
    path = root / relative
    assert path.is_file(), f"published example missing at {relative}"
    return str(path)
