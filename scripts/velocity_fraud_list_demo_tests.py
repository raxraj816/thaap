#!/usr/bin/env python3
"""Safety tests for the public velocity and fraud-list demo."""

import importlib.util
import pathlib
import subprocess
import unittest
from unittest import mock


SCRIPT_PATH = pathlib.Path(__file__).with_name("demo-velocity-fraud-lists.py")
SPEC = importlib.util.spec_from_file_location("velocity_fraud_list_demo", SCRIPT_PATH)
DEMO = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(DEMO)


class VelocityFraudListDemoTests(unittest.TestCase):

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
        for query in ("UPDATE fraud_list_entries SET action='ALLOW'",
                      "SELECT 1; DELETE FROM velocity_observations"):
            with self.subTest(query=query):
                with self.assertRaises(DEMO.DemoFailure):
                    DEMO.postgres_scalar(query)
        run.assert_not_called()

    @mock.patch.object(DEMO.subprocess, "run")
    def test_redis_outage_control_is_exact_allowlisted_argv(self, run):
        run.return_value = subprocess.CompletedProcess(args=[], returncode=0, stdout="", stderr="")
        DEMO.redis_container("pause")
        DEMO.redis_container("unpause")
        self.assertEqual(run.call_args_list[0].args[0], ["docker", "pause", "thaap-local-redis-1"])
        self.assertEqual(run.call_args_list[1].args[0], ["docker", "unpause", "thaap-local-redis-1"])
        with self.assertRaises(DEMO.DemoFailure):
            DEMO.redis_container("stop")

    @mock.patch.object(DEMO.subprocess, "run")
    def test_redis_privacy_scan_rejects_non_hmac_key(self, run):
        run.return_value = subprocess.CompletedProcess(
            args=[], returncode=0, stdout="thaap:velocity:v1:raw-card\n", stderr="")
        with self.assertRaises(DEMO.DemoFailure):
            DEMO.redis_surface()

    @mock.patch.object(DEMO.subprocess, "run")
    def test_redis_privacy_scan_accepts_hmac_owner_key_and_reads_token(self, run):
        key_digest = "a" * 64
        member_digest = "b" * 64
        owner_key = f"thaap:velocity:v1:owner:{key_digest}:{member_digest}"
        run.side_effect = [
            subprocess.CompletedProcess(args=[], returncode=0, stdout=owner_key + "\n", stderr=""),
            subprocess.CompletedProcess(args=[], returncode=0, stdout="c" * 32 + "\n", stderr=""),
        ]

        surface = DEMO.redis_surface()

        self.assertEqual(surface.splitlines(), [owner_key, "c" * 32])
        self.assertEqual(run.call_args_list[1].args[0][-2:], ["GET", owner_key])


if __name__ == "__main__":
    unittest.main()
