#!/usr/bin/env python3
"""Security and read-only reconciliation tests for the review-to-label demo."""

import importlib.util
import pathlib
import subprocess
import unittest
from unittest import mock


SCRIPT_PATH = pathlib.Path(__file__).with_name("demo-review-label-loop.py")
SPEC = importlib.util.spec_from_file_location("review_label_demo", SCRIPT_PATH)
DEMO = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(DEMO)


class ReviewLabelLoopDemoTests(unittest.TestCase):

    def test_only_loopback_http_origins_are_accepted(self):
        self.assertEqual(DEMO.require_loopback("http://localhost:8080/"), "http://localhost:8080")
        for origin in (
                "https://localhost:8080", "http://example.com:8080", "http://user@localhost:8080",
                "http://localhost:8080?token=secret", "http://localhost:8080/#fragment"):
            with self.subTest(origin=origin):
                with self.assertRaises(DEMO.DemoFailure):
                    DEMO.require_loopback(origin)

    def test_sql_literal_escapes_quotes_and_rejects_nul(self):
        self.assertEqual(DEMO.sql_literal("risk'credential"), "'risk''credential'")
        with self.assertRaises(DEMO.DemoFailure):
            DEMO.sql_literal("risk\x00credential")

    @mock.patch.object(DEMO.subprocess, "run")
    def test_postgres_reconciliation_uses_read_only_argv(self, run):
        run.return_value = subprocess.CompletedProcess(
            args=[], returncode=0, stdout="CANCELED|CANCELED|RESOLVED|1|1|1|4|REVIEW\n", stderr="")

        result = DEMO.postgres_scalar("SELECT COUNT(*) FROM review_resolutions")

        self.assertEqual(result, "CANCELED|CANCELED|RESOLVED|1|1|1|4|REVIEW")
        command = run.call_args.args[0]
        self.assertIsInstance(command, list)
        self.assertNotIn("sh", command)
        self.assertIn("select", command[-1].lower())
        self.assertTrue(run.call_args.kwargs["check"])
        self.assertEqual(run.call_args.kwargs["timeout"], 10)

    @mock.patch.object(DEMO.subprocess, "run")
    def test_postgres_reconciliation_rejects_mutating_sql_before_execution(self, run):
        for query in ("UPDATE fraud_labels SET fraud_type = 'OTHER'", "SELECT 1; DELETE FROM fraud_labels"):
            with self.subTest(query=query):
                with self.assertRaises(DEMO.DemoFailure):
                    DEMO.postgres_scalar(query)
        run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
