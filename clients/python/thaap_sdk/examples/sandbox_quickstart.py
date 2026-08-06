#!/usr/bin/env python3
"""Complete a safe merchant-to-investigation THAAP sandbox journey."""

from __future__ import annotations

import os
import uuid
from typing import Any

from thaap_sdk import Credentials, ThaapApiError, ThaapClient, ThaapTransportError
from thaap_sdk.generated.models.claim_request import ClaimRequest
from thaap_sdk.generated.models.confirm_payment_intent_request import (
    ConfirmPaymentIntentRequest,
)
from thaap_sdk.generated.models.create_customer_request import CreateCustomerRequest
from thaap_sdk.generated.models.create_payment_intent_request import (
    CreatePaymentIntentRequest,
)
from thaap_sdk.generated.models.create_payment_method_request import (
    CreatePaymentMethodRequest,
)
from thaap_sdk.generated.models.create_risk_operator_request import (
    CreateRiskOperatorRequest,
)
from thaap_sdk.generated.models.create_run_request import CreateRunRequest


def required(value: Any, name: str) -> Any:
    if value is None:
        raise RuntimeError(f"THAAP response omitted required quickstart field {name}")
    return value


def main() -> None:
    merchant_key = os.environ.get("THAAP_MERCHANT_API_KEY")
    if not merchant_key:
        raise SystemExit("Set THAAP_MERCHANT_API_KEY to a local sandbox merchant credential")
    base_url = os.environ.get("THAAP_BASE_URL", "http://127.0.0.1:8080")
    run_key = os.environ.get("THAAP_QUICKSTART_KEY", uuid.uuid4().hex[:12])
    stage = "initialize merchant client"

    try:
        with ThaapClient(Credentials(merchant_api_key=merchant_key), base_url) as merchant:
            stage = "create customer"
            customer = merchant.customers().post_customers(
                CreateCustomerRequest(email=f"quickstart-{run_key}@example.test"),
                idempotency_key=f"quickstart-{run_key}-customer",
            )
            customer_id = required(customer.id, "customer.id")
            stage = "create payment method"
            payment_method = merchant.payment_methods().post_payment_methods(
                CreatePaymentMethodRequest(
                    customer_id=customer_id,
                    card_number="4242424242424242",
                ),
                idempotency_key=f"quickstart-{run_key}-method",
            )
            payment_method_id = required(payment_method.id, "paymentMethod.id")
            stage = "create payment intent"
            intent = merchant.payment_intents().post_payment_intents(
                CreatePaymentIntentRequest(
                    amount=4_242,
                    currency="USD",
                    customer_id=customer_id,
                    capture_method="AUTOMATIC",
                    metadata={"journey": "sdk-quickstart"},
                ),
                idempotency_key=f"quickstart-{run_key}-intent",
            )
            intent_id = required(intent.id, "paymentIntent.id")
            stage = "confirm payment intent"
            attempt = merchant.payment_intents().post_payment_intents_by_id_confirm(
                intent_id,
                ConfirmPaymentIntentRequest(payment_method_id=payment_method_id),
                idempotency_key=f"quickstart-{run_key}-confirm",
            )
            stage = "create risk operator"
            operator = merchant.operators().post_risk_operators(
                CreateRiskOperatorRequest(role="RISK_MANAGER"),
                idempotency_key=f"quickstart-{run_key}-operator",
            )
            risk_key = required(operator.credential, "riskOperator.credential")

            scenario_token = os.environ.get("THAAP_SCENARIO_RUNNER_TOKEN")
            scenario_status = "not requested"
            if scenario_token:
                worker_id = uuid.uuid4()
                trusted = ThaapClient(
                    Credentials(
                        merchant_api_key=merchant_key,
                        scenario_runner_token=scenario_token,
                    ),
                    base_url,
                )
                try:
                    stage = "create deterministic scenario"
                    run = trusted.developer().post_developer_scenario_runs(
                        CreateRunRequest(scenario_type="APPROVED_PAYMENT", seed=run_key),
                        idempotency_key=f"quickstart-{run_key}-scenario",
                    )
                    stage = "claim deterministic scenario"
                    claimed = trusted.developer().post_developer_scenario_runs_by_run_id_claims(
                        required(run.id, "scenarioRun.id"),
                        ClaimRequest(worker_id=worker_id),
                        idempotency_key=f"quickstart-{run_key}-scenario-claim",
                    )
                    scenario_status = str(claimed.lifecycle_status)
                finally:
                    trusted.close()

        with ThaapClient(Credentials(risk_operator_key=risk_key), base_url) as risk:
            stage = "read payment investigation"
            investigation = risk.investigations().get_risk_workspace_payments_by_payment_intent_id(
                intent_id
            )
            stage = "read merchant analytics"
            summary = risk.analytics().get_risk_analytics_merchant_summary()

        final_action = (
            investigation.decision_explanation.final_action
            if investigation.decision_explanation is not None
            else "unavailable"
        )
        freshness = summary.freshness.state if summary.freshness is not None else "unavailable"
        print(
            "THAAP quickstart complete:",
            f"paymentIntentId={intent_id}",
            f"attemptStatus={attempt.status}",
            f"finalAction={final_action}",
            f"analyticsFreshness={freshness}",
            f"scenarioStatus={scenario_status}",
        )
    except ThaapTransportError as exception:
        raise SystemExit(
            "Transport failed. If a mutation outcome is unknown, retry only with the same "
            "THAAP_QUICKSTART_KEY so every idempotency key and body stays identical."
        ) from exception
    except ThaapApiError as exception:
        raise SystemExit(
            f"THAAP rejected the journey at {stage}: code={exception.code} "
            f"status={exception.status} "
            f"requestId={exception.request_id or 'unavailable'} "
            f"recovery={exception.recovery_action or 'consult-error-catalog'}"
        ) from exception


if __name__ == "__main__":
    main()
