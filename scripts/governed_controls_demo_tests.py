#!/usr/bin/env python3
"""Safety tests for the public governed-controls demo."""

import importlib.util
import pathlib
import subprocess
import unittest
import io
from unittest import mock


SCRIPT_PATH = pathlib.Path(__file__).with_name("demo-governed-controls.py")
SPEC = importlib.util.spec_from_file_location("governed_controls_demo", SCRIPT_PATH)
DEMO = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(DEMO)


class GovernedControlsDemoTests(unittest.TestCase):

    @mock.patch.object(DEMO.time, "sleep")
    def test_rate_retry_preserves_exact_request_and_idempotency_identity(self, sleep):
        opener = mock.Mock()
        response = mock.MagicMock()
        response.__enter__.return_value = response
        response.status = 200
        response.read.return_value = b'{"ok":true}'
        opener.open.side_effect = [
            DEMO.urllib.error.HTTPError("http://localhost:8080/payment_intents", 429, "limit",
                                       {"Retry-After": "2"}, io.BytesIO(b'{}')), response]
        DEMO.post(opener, "http://localhost:8080", "/payment_intents", {"amount": 1200}, {}, "fixture-key")
        first = opener.open.call_args_list[0].args[0]
        second = opener.open.call_args_list[1].args[0]
        self.assertIs(first, second)
        self.assertEqual(first.data, b'{"amount":1200}')
        self.assertEqual(first.get_header("Idempotency-key"), "fixture-key")
        sleep.assert_called_once_with(2)

    @mock.patch.object(DEMO.time, "sleep")
    def test_only_bounded_numeric_rate_limit_retry_is_permitted(self, sleep):
        for status, retry in ((409, "1"), (503, "1"), (429, ""), (429, "-1"),
                              (429, "61"), (429, "1.5"), (429, "infinity"), (429, "１"), (429, "9" * 5000)):
            with self.subTest(status=status, retry=retry):
                opener = mock.Mock()
                opener.open.side_effect = DEMO.urllib.error.HTTPError("http://localhost:8080/x", status, "error",
                                                                      {"Retry-After": retry}, io.BytesIO(b'{}'))
                with self.assertRaises(DEMO.DemoFailure):
                    DEMO.request_json(opener, "http://localhost:8080", "GET", "/x")
        sleep.assert_not_called()

    def test_demo_satisfies_real_promotion_thresholds_without_overriding_controls(self):
        source = SCRIPT_PATH.read_text()
        self.assertIn("range(99)", source)
        self.assertIn('"eligible": 100', source)
        self.assertIn('if labelled < 50:', source)
        self.assertIn('excepted["status"] != "IN_REVIEW"', source)
        self.assertNotIn("THAAP_SIMULATION_GATE_MINIMUM", source)

    def test_only_loopback_http_origins_are_accepted(self):
        self.assertEqual(DEMO.require_loopback("http://localhost:8080/"), "http://localhost:8080")
        for origin in ("https://localhost:8080", "http://example.com:8080",
                       "http://user@localhost:8080", "http://localhost:8080?secret=value"):
            with self.subTest(origin=origin):
                with self.assertRaises(DEMO.DemoFailure):
                    DEMO.require_loopback(origin)

    @mock.patch.object(DEMO.subprocess, "run")
    def test_postgres_reconciliation_uses_read_only_argv(self, run):
        run.return_value = subprocess.CompletedProcess(args=[], returncode=0, stdout="1\n", stderr="")
        self.assertEqual(DEMO.postgres_scalar("SELECT 1"), "1")
        command = run.call_args.args[0]
        self.assertIsInstance(command, list)
        self.assertNotIn("sh", command)
        self.assertTrue(run.call_args.kwargs["check"])
        self.assertEqual(run.call_args.kwargs["timeout"], 10)

    @mock.patch.object(DEMO.subprocess, "run")
    def test_mutating_reconciliation_is_rejected_before_execution(self, run):
        for query in ("UPDATE authored_rule_versions SET lifecycle_status='BAD'",
                      "SELECT 1; DELETE FROM authored_rule_events"):
            with self.subTest(query=query):
                with self.assertRaises(DEMO.DemoFailure):
                    DEMO.postgres_scalar(query)
        run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
