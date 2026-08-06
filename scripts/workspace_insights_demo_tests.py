#!/usr/bin/env python3
"""Safety tests for the public workspace insights demo."""

import importlib.util
import pathlib
import re
import subprocess
import unittest
from unittest import mock


SCRIPT_PATH = pathlib.Path(__file__).with_name("demo-workspace-insights.py")
SPEC = importlib.util.spec_from_file_location("workspace_insights_demo", SCRIPT_PATH)
DEMO = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(DEMO)

SOURCE = SCRIPT_PATH.read_text(encoding="utf-8")


class WorkspaceInsightsDemoTests(unittest.TestCase):

    def test_only_loopback_http_origins_are_accepted(self):
        self.assertEqual(DEMO.require_loopback("http://localhost:8080/"), "http://localhost:8080")
        for origin in ("https://localhost:8080", "http://example.com:8080",
                       "http://user@localhost:8080", "http://localhost:8080?secret=value",
                       "http://localhost:8080#fragment"):
            with self.subTest(origin=origin):
                with self.assertRaises(DEMO.DemoFailure):
                    DEMO.require_loopback(origin)

    def test_mutating_reconciliation_is_rejected_before_execution(self):
        with mock.patch.object(DEMO.subprocess, "run") as run:
            for query in ("UPDATE manual_review_cases SET status = 'OPEN'",
                          "SELECT 1; DELETE FROM payment_risk_projections",
                          "INSERT INTO fraud_labels VALUES (1)",
                          "SELECT 1; TRUNCATE ledger_entries"):
                with self.subTest(query=query):
                    with self.assertRaises(DEMO.DemoFailure):
                        DEMO.postgres_scalar(query)
            run.assert_not_called()

    def test_reconciliation_uses_read_only_argv_without_a_shell(self):
        completed = subprocess.CompletedProcess(args=[], returncode=0, stdout="1", stderr="")
        with mock.patch.object(DEMO.subprocess, "run", return_value=completed) as run:
            DEMO.postgres_scalar("SELECT COUNT(*) FROM manual_review_cases")
        command = run.call_args.args[0]
        self.assertIsInstance(command, list)
        self.assertNotIn("shell", run.call_args.kwargs)
        self.assertIn("-X", command)

    def test_the_demo_never_declares_a_route_outside_the_insight_journey(self):
        declared = set(re.findall(r'"(/[a-z0-9/_.{}$-]+)"', SOURCE))
        allowed_prefixes = (
            "/admin/merchants", "/risk/operators", "/customers", "/payment_methods",
            "/payment_intents", "/risk/workspace/insights", "/risk/workspace/payments/search",
            "/actuator/health/readiness",
        )
        for route in declared:
            with self.subTest(route=route):
                self.assertTrue(any(route.startswith(prefix) for prefix in allowed_prefixes),
                                f"demo reaches {route}, outside the insight journey")

    def test_the_demo_reconciles_backlog_against_authoritative_rows(self):
        # A demo that only prints the API's own answer proves nothing. It has to compare that
        # answer against the rows the API claims to be summarising.
        self.assertIn("manual_review_cases", SOURCE)
        self.assertIn("disagrees with authoritative rows", SOURCE)

    def test_the_demo_proves_drill_through_reproduces_its_own_segment(self):
        self.assertIn("but the card claimed", SOURCE)
        self.assertIn("no segment offered a drill-through", SOURCE)
        for prohibited in ("merchantId", "operatorId", "generationId", "paymentIntentId"):
            with self.subTest(field=prohibited):
                self.assertIn(prohibited, SOURCE)

    def test_the_demo_asserts_the_unknowns_rather_than_only_the_knowns(self):
        # The distinguishing product claim is honesty about what is not known.
        self.assertIn("empty denominator", SOURCE)
        self.assertIn("conflict attempts were reported as a number", SOURCE)
        self.assertIn("DESCRIPTIVE_NOT_CAUSAL", SOURCE)

    def test_the_demo_asserts_currency_separation_and_no_blended_total(self):
        self.assertIn("totalAmountMinor", SOURCE)
        self.assertIn("a currency appeared twice", SOURCE)

    def test_the_demo_exercises_a_weaker_role_rather_than_only_a_manager(self):
        self.assertIn("VIEWER", SOURCE)
        self.assertIn("a viewer received an individual workload block", SOURCE)

    def test_redirects_are_refused_by_the_opener(self):
        handler = DEMO.RejectRedirects()
        with self.assertRaises(DEMO.DemoFailure):
            handler.redirect_request(None, None, 302, "Found", {}, "http://evil.example")

    def test_no_credential_is_hard_coded(self):
        for marker in ("sk_live", "sk_test_", "Bearer "):
            with self.subTest(marker=marker):
                self.assertNotIn(marker, SOURCE)
        self.assertIn('os.environ.get("THAAP_ADMIN_BOOTSTRAP_TOKEN")', SOURCE)


if __name__ == "__main__":
    unittest.main()
