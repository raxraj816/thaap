#!/usr/bin/env python3
"""Safety tests for the public saved-view and bulk review assignment demo.

A demo runs against a real sandbox with an admin bootstrap token, so its own safety properties
matter as much as what it demonstrates. These tests hold the script to the same boundaries the
product enforces: loopback only, read-only reconciliation, and no route outside the chapter's
own surface.
"""

import importlib.util
import pathlib
import re
import subprocess
import unittest
from unittest import mock


SCRIPT_PATH = pathlib.Path(__file__).with_name("demo-saved-views-and-bulk-review.py")
SPEC = importlib.util.spec_from_file_location("saved_views_and_bulk_review_demo", SCRIPT_PATH)
DEMO = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(DEMO)

SOURCE = SCRIPT_PATH.read_text(encoding="utf-8")


class SavedViewsAndBulkReviewDemoTests(unittest.TestCase):

    def test_only_loopback_http_origins_are_accepted(self):
        self.assertEqual(DEMO.require_loopback("http://localhost:8080/"), "http://localhost:8080")
        self.assertEqual(DEMO.require_loopback("http://127.0.0.1:8080"), "http://127.0.0.1:8080")
        for origin in ("https://localhost:8080", "http://example.com:8080",
                       "http://user@localhost:8080", "http://localhost:8080?secret=value",
                       "http://localhost:8080#fragment", "file:///etc/passwd"):
            with self.subTest(origin=origin):
                with self.assertRaises(DEMO.DemoFailure):
                    DEMO.require_loopback(origin)

    def test_mutating_reconciliation_is_rejected_before_execution(self):
        # The guard must fire before any subprocess starts, not be caught afterwards.
        with mock.patch.object(DEMO.subprocess, "run") as run:
            for query in ("UPDATE manual_review_cases SET status = 'OPEN'",
                          "SELECT 1; DELETE FROM review_bulk_action_items",
                          "SELECT 1 FROM x; DROP TABLE payment_intents",
                          "INSERT INTO fraud_labels VALUES (1)",
                          "SELECT 1; TRUNCATE ledger_entries"):
                with self.subTest(query=query):
                    with self.assertRaises(DEMO.DemoFailure):
                        DEMO.postgres_scalar(query)
            run.assert_not_called()

    def test_reconciliation_uses_read_only_argv_without_a_shell(self):
        completed = subprocess.CompletedProcess(args=[], returncode=0, stdout="1", stderr="")
        with mock.patch.object(DEMO.subprocess, "run", return_value=completed) as run:
            DEMO.postgres_scalar("SELECT COUNT(*) FROM review_bulk_action_items")
        command = run.call_args.args[0]
        self.assertIsInstance(command, list)
        self.assertNotIn("shell", run.call_args.kwargs)
        # -X refuses psqlrc so a local file cannot change what the reconciliation runs.
        self.assertIn("-X", command)

    def test_the_prohibited_fingerprint_covers_every_table_bulk_assignment_must_not_touch(self):
        with mock.patch.object(DEMO, "postgres_scalar", return_value="0") as scalar:
            DEMO.prohibited_fingerprint("11111111-1111-1111-1111-111111111111")
        query = scalar.call_args.args[0]
        for table in ("review_resolutions", "fraud_labels", "chargebacks", "ledger_entries",
                      "authored_rule_versions", "fraud_lists"):
            with self.subTest(table=table):
                self.assertIn(table, query,
                              f"{table} is a prohibited effect the fingerprint does not watch")

    def test_the_demo_never_declares_a_route_outside_its_own_chapter_surface(self):
        declared = set(re.findall(r'"(/[a-z0-9/_.{}$-]+)"', SOURCE))
        allowed_prefixes = (
            "/admin/merchants", "/risk/operators", "/customers", "/payment_methods",
            "/payment_intents", "/risk/workspace/payment-views",
            "/risk/workspace/review-bulk-actions", "/risk/workspace/payments/",
            "/actuator/health/readiness",
        )
        for route in declared:
            with self.subTest(route=route):
                self.assertTrue(
                    any(route.startswith(prefix) for prefix in allowed_prefixes),
                    f"demo reaches {route}, which is outside the saved-view/bulk surface")

    def test_the_demo_reaches_no_insight_or_high_risk_route(self):
        # Insights belong to a different chapter; resolution, refund, dispute, and control
        # activation are rejected scope for bulk work entirely.
        for prohibited in ("/risk/workspace/insights", "/resolve", "/refunds", "/disputes",
                           "/risk/controls", "/risk/simulations", "/labels"):
            with self.subTest(route=prohibited):
                self.assertNotIn(f'"{prohibited}', SOURCE)

    def test_the_demo_asserts_a_mixed_result_rather_than_only_success(self):
        # A bulk demo that only ever shows every item succeeding has not demonstrated the
        # per-item truth that is the whole point of the durable item table.
        self.assertIn("PARTIAL", SOURCE)
        self.assertIn("STALE", SOURCE)
        self.assertIn("NOT_FOUND", SOURCE)
        self.assertIn("SUCCEEDED", SOURCE)

    def test_the_demo_proves_idempotent_replay_and_conflict(self):
        self.assertIn("bulk_idempotency_mismatch", SOURCE)
        self.assertIn("idempotent replay started a second bulk run", SOURCE)

    def test_redirects_are_refused_by_the_opener(self):
        handler = DEMO.RejectRedirects()
        with self.assertRaises(DEMO.DemoFailure):
            handler.redirect_request(None, None, 302, "Found", {}, "http://evil.example")

    def test_no_credential_or_token_is_hard_coded(self):
        # Markers are anchored so the URL guard's own `parsed.password` check does not match.
        for secret_marker in ("sk_live", "sk_test_", "Bearer ", 'password="', "password = \""):
            with self.subTest(marker=secret_marker):
                self.assertNotIn(secret_marker, SOURCE)
        # The bootstrap token must come from the environment, never a literal.
        self.assertIn('os.environ.get("THAAP_ADMIN_BOOTSTRAP_TOKEN")', SOURCE)


if __name__ == "__main__":
    unittest.main()
