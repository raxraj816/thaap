#!/usr/bin/env python3
"""Security and read-only reconciliation tests for the Chapter 22 demo."""

import importlib.util
import pathlib
import subprocess
import unittest
import uuid
from unittest import mock


SCRIPT_PATH = pathlib.Path(__file__).with_name("demo-chapter-22-feature-lineage.py")
SPEC = importlib.util.spec_from_file_location("chapter22_demo", SCRIPT_PATH)
DEMO = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(DEMO)


class Chapter22FeatureLineageDemoTests(unittest.TestCase):

    def test_only_loopback_http_origins_are_accepted(self):
        self.assertEqual(DEMO.require_loopback("http://localhost:8080/"), "http://localhost:8080")
        for origin in (
                "https://localhost:8080", "http://example.com:8080", "http://user@localhost:8080",
                "http://localhost:8080?token=secret", "http://localhost:8080/#fragment"):
            with self.subTest(origin=origin):
                with self.assertRaises(DEMO.DemoFailure):
                    DEMO.require_loopback(origin)

    @mock.patch.object(DEMO.subprocess, "run")
    def test_postgres_reconciliation_is_read_only_argv_and_parses_exact_rows(self, run):
        run.return_value = subprocess.CompletedProcess(
            args=[], returncode=0,
            stdout="card.country|STRING|US|true\npayment.amount_minor|LONG|4200|true\n",
            stderr="")
        attempt_id = uuid.UUID("11111111-1111-1111-1111-111111111111")

        rows = DEMO.postgres_rows(attempt_id)

        self.assertEqual(rows, [
            ("card.country", "STRING", "US", "true"),
            ("payment.amount_minor", "LONG", "4200", "true"),
        ])
        command = run.call_args.args[0]
        self.assertIsInstance(command, list)
        self.assertNotIn("sh", command)
        query = command[-1].lower()
        self.assertIn("select", query)
        self.assertNotIn("insert", query)
        self.assertNotIn("update", query)
        self.assertNotIn("delete", query)
        self.assertIn(str(attempt_id), query)


if __name__ == "__main__":
    unittest.main()
