#!/usr/bin/env python3
"""Safety tests for the public payment investigation demo."""

import importlib.util
import pathlib
import re
import subprocess
import unittest
from unittest import mock


SCRIPT_PATH = pathlib.Path(__file__).with_name("demo-payment-investigation.py")
SPEC = importlib.util.spec_from_file_location("payment_investigation_demo", SCRIPT_PATH)
DEMO = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(DEMO)


class PaymentInvestigationDemoTests(unittest.TestCase):

    def test_only_loopback_http_origins_are_accepted(self):
        self.assertEqual(DEMO.require_loopback("http://localhost:8080/"), "http://localhost:8080")
        for origin in ("https://localhost:8080", "http://example.com:8080",
                       "http://user@localhost:8080", "http://localhost:8080?secret=value"):
            with self.subTest(origin=origin):
                with self.assertRaises(DEMO.DemoFailure):
                    DEMO.require_loopback(origin)

    @mock.patch.object(DEMO.subprocess, "run")
    def test_source_reconciliation_uses_read_only_argv(self, run):
        run.return_value = subprocess.CompletedProcess(args=[], returncode=0,
                                                       stdout="1\n", stderr="")
        self.assertEqual(DEMO.postgres_scalar("SELECT 1"), "1")
        command = run.call_args.args[0]
        self.assertIsInstance(command, list)
        self.assertNotIn("sh", command)
        self.assertTrue(run.call_args.kwargs["check"])
        self.assertEqual(run.call_args.kwargs["timeout"], 10)

    @mock.patch.object(DEMO.subprocess, "run")
    def test_mutating_reconciliation_is_rejected_before_execution(self, run):
        for query in ("UPDATE payment_intents SET status='SUCCEEDED'",
                      "SELECT 1; DELETE FROM fraud_decisions",
                      "DROP TABLE rule_execution_results"):
            with self.subTest(query=query):
                with self.assertRaises(DEMO.DemoFailure):
                    DEMO.postgres_scalar(query)
        run.assert_not_called()

    @mock.patch.object(DEMO, "postgres_scalar")
    def test_source_fingerprint_covers_every_authoritative_table_the_read_touches(self, scalar):
        scalar.return_value = "0"
        DEMO.source_fingerprint("11111111-1111-1111-1111-111111111111")
        query = scalar.call_args.args[0]
        for table in ("payment_intents", "payment_attempts", "fraud_decisions",
                      "rule_execution_results", "manual_review_cases", "fraud_labels",
                      "ledger_entries", "domain_events", "idempotency_records"):
            with self.subTest(table=table):
                self.assertIn(table, query)

    def test_the_demo_never_declares_a_route_outside_the_investigation_surface(self):
        source = SCRIPT_PATH.read_text(encoding="utf-8")
        allowed = ("/actuator/health/readiness", "/admin/merchants", "/customers",
                   "/payment_methods", "/payment_intents", "/risk/operators",
                   "/risk/workspace/payments")
        for literal in re.findall(r'"(/[a-z][a-z0-9/_{}\[\]\'-]*)', source):
            with self.subTest(route=literal):
                self.assertTrue(literal.startswith(allowed),
                                f"demo declares out-of-scope route {literal}")


if __name__ == "__main__":
    unittest.main()
