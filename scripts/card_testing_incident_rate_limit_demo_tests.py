#!/usr/bin/env python3
"""Safety tests for the public card-testing incident and rate-limit demo."""

import importlib.util
import pathlib
import subprocess
import unittest
from unittest import mock


SCRIPT_PATH = pathlib.Path(__file__).with_name("demo-card-testing-incidents-rate-limits.py")
SPEC = importlib.util.spec_from_file_location("card_testing_incident_demo", SCRIPT_PATH)
DEMO = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(DEMO)


class CardTestingIncidentRateLimitDemoTests(unittest.TestCase):

    def test_only_loopback_http_origins_are_accepted(self):
        self.assertEqual(DEMO.require_loopback("http://localhost:8080/"), "http://localhost:8080")
        for origin in ("https://localhost:8080", "http://example.com:8080",
                       "http://user@localhost:8080", "http://localhost:8080?secret=value"):
            with self.subTest(origin=origin):
                with self.assertRaises(DEMO.DemoFailure):
                    DEMO.require_loopback(origin)

    @mock.patch.object(DEMO.subprocess, "run")
    def test_reconciliation_uses_bounded_argv_without_a_shell(self, run):
        run.return_value = subprocess.CompletedProcess(args=[], returncode=0, stdout="0\n", stderr="")
        self.assertEqual(DEMO.postgres_scalar("SELECT 0"), "0")
        self.assertIsInstance(run.call_args.args[0], list)
        self.assertNotIn("sh", run.call_args.args[0])
        self.assertTrue(run.call_args.kwargs["check"])
        self.assertEqual(run.call_args.kwargs["timeout"], 10)

    @mock.patch.object(DEMO.subprocess, "run")
    def test_mutating_sql_is_rejected_before_execution(self, run):
        for query in ("DELETE FROM card_testing_incidents",
                      "SELECT 1; UPDATE merchant_risk_policy_versions SET status='ACTIVE'"):
            with self.subTest(query=query):
                with self.assertRaises(DEMO.DemoFailure):
                    DEMO.postgres_scalar(query)
        run.assert_not_called()

    @mock.patch.object(DEMO.subprocess, "run")
    def test_redis_scan_rejects_non_hmac_keys(self, run):
        run.return_value = subprocess.CompletedProcess(
            args=[], returncode=0, stdout="thaap:rate-limit:v1:203.0.113.1\n", stderr="")
        with self.assertRaises(DEMO.DemoFailure):
            DEMO.redis_rate_limit_keys()


if __name__ == "__main__":
    unittest.main()
