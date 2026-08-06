#!/usr/bin/env python3
"""Unit tests for openapi-compatibility-check.py.

A compatibility gate is only worth the breaks it actually rejects. Each test
here plants one specific way a shipped merchant integration can be broken and
requires the gate to name it, and the final test requires the additive changes
THAAP promises are always safe to pass untouched.
"""

from __future__ import annotations

import copy
import importlib.util
import pathlib
import sys
import unittest


SCRIPT_PATH = pathlib.Path(__file__).with_name("openapi-compatibility-check.py")


def load_checker_module():
    spec = importlib.util.spec_from_file_location("openapi_compatibility_check", SCRIPT_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


checker = load_checker_module()


def contract() -> dict:
    return {
        "openapi": "3.1.0",
        "info": {"version": "2026-08-01"},
        "components": {
            "schemas": {
                "PaymentIntentView": {
                    "required": ["id", "amount"],
                    "properties": {
                        "id": {"type": "string", "format": "uuid"},
                        "amount": {"type": "integer", "format": "int64"},
                        "currency": {"type": "string"},
                        "descriptor": {"type": "string"},
                    },
                }
            },
            "securitySchemes": {
                "merchantApi": {"type": "apiKey", "in": "header", "name": "X-Api-Key"},
                "scenarioRunner": {
                    "type": "apiKey",
                    "in": "header",
                    "name": "X-Thaap-Scenario-Runner-Token",
                },
            },
        },
        "paths": {
            "/payment_intents": {
                "post": {
                    "operationId": "postPaymentIntents",
                    "security": [{"merchantApi": []}],
                    "parameters": [
                        {"in": "header", "name": "Idempotency-Key", "required": True},
                        {"in": "header", "name": "Thaap-Version", "required": False},
                    ],
                    "responses": {"201": {}, "default": {}},
                }
            },
            "/developer/error-catalog": {
                "get": {
                    "operationId": "getDeveloperErrorCatalog",
                    "security": [],
                    "responses": {"200": {}, "default": {}},
                }
            },
        },
    }


def breaks(baseline: dict, current: dict) -> list:
    found: list = []
    checker.compare_schemas(baseline, current, found)
    checker.compare_security(baseline, current, found)
    checker.compare_operations(baseline, current, found)
    return found


class CompatibilityGateTests(unittest.TestCase):
    def setUp(self) -> None:
        self.baseline = contract()
        self.current = copy.deepcopy(self.baseline)

    def assert_rejected(self, fragment: str) -> None:
        found = breaks(self.baseline, self.current)
        self.assertTrue(
            any(fragment in entry for entry in found),
            f"expected a break mentioning {fragment!r}, got {found}",
        )

    def test_an_unchanged_contract_reports_no_break(self) -> None:
        self.assertEqual(breaks(self.baseline, self.current), [])

    def test_a_removed_operation_is_rejected(self) -> None:
        del self.current["paths"]["/payment_intents"]["post"]
        self.assert_rejected("operation removed: POST /payment_intents")

    def test_a_removed_path_is_rejected(self) -> None:
        del self.current["paths"]["/payment_intents"]
        self.assert_rejected("operation removed: POST /payment_intents")

    def test_a_renamed_operation_id_is_rejected(self) -> None:
        # Every generated SDK names its method after this value.
        self.current["paths"]["/payment_intents"]["post"]["operationId"] = "createPayment"
        self.assert_rejected("operationId renamed")

    def test_a_removed_response_status_is_rejected(self) -> None:
        del self.current["paths"]["/payment_intents"]["post"]["responses"]["201"]
        self.assert_rejected("response status removed")

    def test_a_removed_schema_is_rejected(self) -> None:
        del self.current["components"]["schemas"]["PaymentIntentView"]
        self.assert_rejected("schema removed: PaymentIntentView")

    def test_a_removed_property_is_rejected(self) -> None:
        del self.current["components"]["schemas"]["PaymentIntentView"]["properties"]["descriptor"]
        self.assert_rejected("property removed: PaymentIntentView.descriptor")

    def test_a_money_unit_change_is_rejected(self) -> None:
        # Minor units silently becoming a decimal is the single most expensive change this
        # gate exists to stop: every amount a client parses would be wrong by a factor of 100.
        self.current["components"]["schemas"]["PaymentIntentView"]["properties"]["amount"] = {
            "type": "number",
            "format": "double",
        }
        self.assert_rejected("property type narrowed or changed: PaymentIntentView.amount")

    def test_an_identifier_format_change_is_rejected(self) -> None:
        self.current["components"]["schemas"]["PaymentIntentView"]["properties"]["id"] = {
            "type": "string"
        }
        self.assert_rejected("property type narrowed or changed: PaymentIntentView.id")

    def test_a_newly_required_property_is_rejected(self) -> None:
        self.current["components"]["schemas"]["PaymentIntentView"]["required"].append("currency")
        self.assert_rejected("property became required: PaymentIntentView.currency")

    def test_a_newly_required_parameter_is_rejected(self) -> None:
        self.current["paths"]["/payment_intents"]["post"]["parameters"][1]["required"] = True
        self.assert_rejected("parameter became required: POST /payment_intents header Thaap-Version")

    def test_a_removed_security_scheme_is_rejected(self) -> None:
        del self.current["components"]["securitySchemes"]["scenarioRunner"]
        self.assert_rejected("security scheme removed: scenarioRunner")

    def test_a_renamed_credential_header_is_rejected(self) -> None:
        self.current["components"]["securitySchemes"]["merchantApi"]["name"] = "Authorization"
        self.assert_rejected("security scheme changed: merchantApi.name")

    def test_requiring_an_additional_credential_is_rejected(self) -> None:
        self.current["paths"]["/payment_intents"]["post"]["security"] = [
            {"merchantApi": [], "scenarioRunner": []}
        ]
        self.assert_rejected("credential requirement tightened")

    def test_closing_a_previously_open_operation_is_rejected(self) -> None:
        self.current["paths"]["/developer/error-catalog"]["get"]["security"] = [
            {"merchantApi": []}
        ]
        self.assert_rejected("credential requirement tightened")

    def test_dropping_one_of_two_required_credentials_is_accepted(self) -> None:
        # A client already holding both credentials still satisfies the narrower requirement,
        # so this direction is a relaxation and must not be reported as a break.
        self.baseline["paths"]["/payment_intents"]["post"]["security"] = [
            {"merchantApi": [], "scenarioRunner": []}
        ]
        self.assertEqual(breaks(self.baseline, self.current), [])

    def test_additive_change_is_accepted(self) -> None:
        self.current["paths"]["/refunds"] = {
            "post": {"operationId": "postRefunds", "security": [{"merchantApi": []}],
                     "responses": {"201": {}, "default": {}}}
        }
        self.current["components"]["schemas"]["PaymentIntentView"]["properties"]["statementNote"] = {
            "type": "string"
        }
        self.current["components"]["schemas"]["RefundView"] = {"properties": {}}
        self.current["paths"]["/payment_intents"]["post"]["responses"]["202"] = {}
        self.current["paths"]["/payment_intents"]["post"]["parameters"].append(
            {"in": "query", "name": "expand", "required": False}
        )
        self.assertEqual(breaks(self.baseline, self.current), [])


if __name__ == "__main__":
    unittest.main()
