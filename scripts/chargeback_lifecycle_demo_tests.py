#!/usr/bin/env python3
"""Safety tests for the public chargeback and dispute demo."""

import importlib.util
import pathlib
import subprocess
import unittest
from unittest import mock


SCRIPT_PATH = pathlib.Path(__file__).with_name("demo-chargeback-lifecycle.py")
SPEC = importlib.util.spec_from_file_location("chargeback_lifecycle_demo", SCRIPT_PATH)
DEMO = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(DEMO)


class ChargebackLifecycleDemoTests(unittest.TestCase):

    def test_only_loopback_http_origins_are_accepted(self):
        self.assertEqual(DEMO.require_loopback("http://localhost:8080/"), "http://localhost:8080")
        for origin in ("https://localhost:8080", "http://example.com:8080",
                       "http://user@localhost:8080", "http://localhost:8080?secret=value"):
            with self.subTest(origin=origin):
                with self.assertRaises(DEMO.DemoFailure):
                    DEMO.require_loopback(origin)

    @mock.patch.object(DEMO.subprocess, "run")
    def test_postgres_reconciliation_uses_read_only_argv(self, run):
        run.return_value = subprocess.CompletedProcess(args=[], returncode=0, stdout="0\n", stderr="")
        self.assertEqual(DEMO.postgres_scalar("SELECT 0"), "0")
        command = run.call_args.args[0]
        self.assertIsInstance(command, list)
        self.assertNotIn("sh", command)
        self.assertTrue(run.call_args.kwargs["check"])
        self.assertEqual(run.call_args.kwargs["timeout"], 10)

    @mock.patch.object(DEMO.subprocess, "run")
    def test_mutating_reconciliation_is_rejected_before_execution(self, run):
        for query in ("UPDATE chargebacks SET amount=1",
                      "SELECT 1; DELETE FROM dispute_offset_allocations"):
            with self.subTest(query=query):
                with self.assertRaises(DEMO.DemoFailure):
                    DEMO.postgres_scalar(query)
        run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
