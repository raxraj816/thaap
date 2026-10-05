#!/usr/bin/env python3
"""Security and read-only reconciliation tests for the Phase 3 demo."""

import importlib.util
import pathlib
import subprocess
import unittest
from unittest import mock


SCRIPT_PATH = pathlib.Path(__file__).with_name("demo-fraud-decision-loop.py")
SPEC = importlib.util.spec_from_file_location("fraud_decision_demo", SCRIPT_PATH)
DEMO = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(DEMO)


class FraudDecisionLoopDemoTests(unittest.TestCase):

    @mock.patch.object(DEMO, "request_json")
    def test_every_feature_omission_and_unexpected_feature_is_rejected(self, request):
        detail = {"decision": {"action": "ALLOW", "rulesetVersion": "rules_v1"},
                  "ruleExecutionResults": [{"ruleCode": str(i), "matched": False} for i in range(3)],
                  "featureSnapshot": {"features": dict.fromkeys(DEMO.BASE_FEATURE_CODES)}}
        request.return_value = b"", detail
        DEMO.assert_decision(None, "unused", {}, {"fraudDecisionId": "fixture"}, "ALLOW", [])
        for key in DEMO.BASE_FEATURE_CODES:
            with self.subTest(feature=key):
                detail["featureSnapshot"]["features"] = dict.fromkeys(DEMO.BASE_FEATURE_CODES - {key})
                with self.assertRaises(DEMO.DemoFailure):
                    DEMO.assert_decision(None, "unused", {}, {"fraudDecisionId": "fixture"}, "ALLOW", [])
        detail["featureSnapshot"]["features"] = dict.fromkeys(DEMO.BASE_FEATURE_CODES | {"unsafe.extra"})
        with self.assertRaises(DEMO.DemoFailure):
            DEMO.assert_decision(None, "unused", {}, {"fraudDecisionId": "fixture"}, "ALLOW", [])

    def test_repeated_decline_setup_uses_its_own_customer_without_disabling_velocity(self):
        source = SCRIPT_PATH.read_text()
        self.assertIn('{"customerId": review_customer["id"], "cardNumber": "4999990000000003"}', source)
        self.assertIn('headers, review_customer["id"], 99_999', source)
        self.assertNotIn("THAAP_VELOCITY_ENABLED", source)

    def test_only_loopback_http_origins_are_accepted(self):
        self.assertEqual(DEMO.require_loopback("http://localhost:8080/"), "http://localhost:8080")
        for origin in (
                "https://localhost:8080", "http://example.com:8080", "http://user@localhost:8080",
                "http://localhost:8080?token=secret", "http://localhost:8080/#fragment"):
            with self.subTest(origin=origin):
                with self.assertRaises(DEMO.DemoFailure):
                    DEMO.require_loopback(origin)

    @mock.patch.object(DEMO.subprocess, "run")
    def test_postgres_reconciliation_uses_read_only_argv(self, run):
        run.return_value = subprocess.CompletedProcess(
            args=[], returncode=0, stdout="6|42|18|3|1\n", stderr="")

        result = DEMO.postgres_scalar("SELECT COUNT(*) FROM fraud_decisions")

        self.assertEqual(result, "6|42|18|3|1")
        command = run.call_args.args[0]
        self.assertIsInstance(command, list)
        self.assertNotIn("sh", command)
        query = command[-1].lower()
        self.assertIn("select", query)
        self.assertNotIn("insert", query)
        self.assertNotIn("update", query)
        self.assertNotIn("delete", query)
        self.assertTrue(run.call_args.kwargs["check"])
        self.assertEqual(run.call_args.kwargs["timeout"], 10)


if __name__ == "__main__":
    unittest.main()
