#!/usr/bin/env python3
"""Safety tests for the public payment-risk analytics demo."""

import importlib.util
import pathlib
import subprocess
import unittest
from unittest import mock


SCRIPT_PATH = pathlib.Path(__file__).with_name("demo-payment-risk-analytics.py")
SPEC = importlib.util.spec_from_file_location("payment_risk_analytics_demo", SCRIPT_PATH)
DEMO = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(DEMO)


class PaymentRiskAnalyticsDemoTests(unittest.TestCase):

    def test_only_loopback_http_origins_are_accepted(self):
        self.assertEqual(DEMO.require_loopback("http://127.0.0.1:8080/"),
                         "http://127.0.0.1:8080")
        for origin in ("https://localhost:8080", "http://example.com:8080",
                       "http://user@localhost:8080", "http://localhost:8080?key=value"):
            with self.subTest(origin=origin):
                with self.assertRaises(DEMO.DemoFailure):
                    DEMO.require_loopback(origin)

    def test_dataset_response_requires_public_dataset_id_contract(self):
        dataset_id = "00000000-0000-0000-0000-000000000123"
        self.assertEqual(DEMO.require_uuid_field({"datasetId": dataset_id}, "datasetId"),
                         DEMO.uuid.UUID(dataset_id))
        with self.assertRaisesRegex(DEMO.DemoFailure, "datasetId"):
            DEMO.require_uuid_field({"id": dataset_id}, "datasetId")

    def test_durable_evidence_requires_every_pipeline_hop(self):
        self.assertEqual(DEMO.require_durable_evidence("10|12|10|5|1"),
                         [10, 12, 10, 5, 1])
        for evidence in ("10|9|10|5|1", "10|10|0|5|1", "10|10|10|4|1",
                         "10|10|10|5|0", "invalid"):
            with self.subTest(evidence=evidence):
                with self.assertRaises(DEMO.DemoFailure):
                    DEMO.require_durable_evidence(evidence)

    @mock.patch.object(DEMO.subprocess, "run")
    def test_postgres_reconciliation_uses_read_only_argv(self, run):
        run.return_value = subprocess.CompletedProcess(args=[], returncode=0,
                                                       stdout="1\n", stderr="")
        self.assertEqual(DEMO.postgres_scalar("SELECT 1"), "1")
        command = run.call_args.args[0]
        self.assertIsInstance(command, list)
        self.assertNotIn("sh", command)
        self.assertTrue(run.call_args.kwargs["check"])
        self.assertEqual(run.call_args.kwargs["timeout"], 12)

    @mock.patch.object(DEMO.subprocess, "run")
    def test_mutating_reconciliation_is_rejected_before_execution(self, run):
        for query in ("UPDATE projection_rebuilds SET lifecycle_status='BAD'",
                      "SELECT 1; DELETE FROM domain_events",
                      "CREATE TABLE leaked(value text)"):
            with self.subTest(query=query):
                with self.assertRaises(DEMO.DemoFailure):
                    DEMO.postgres_scalar(query)
        run.assert_not_called()

    @mock.patch.object(DEMO.subprocess, "run")
    def test_broker_control_is_allowlisted_argv_without_shell(self, run):
        run.return_value = subprocess.CompletedProcess(args=[], returncode=0,
                                                       stdout="", stderr="")
        DEMO.broker_command("stop")
        DEMO.broker_command("start")
        self.assertEqual(run.call_args_list[0].args[0], ["docker", "stop", "thaap-kafka"])
        self.assertEqual(run.call_args_list[1].args[0], ["docker", "start", "thaap-kafka"])
        for call in run.call_args_list:
            self.assertNotIn("shell", call.kwargs)
        with self.assertRaises(DEMO.DemoFailure):
            DEMO.broker_command("rm")

    @mock.patch.object(DEMO, "broker_command")
    def test_broker_outage_restores_broker_after_failure(self, broker_command):
        with self.assertRaisesRegex(RuntimeError, "forced"):
            with DEMO.broker_outage():
                raise RuntimeError("forced")
        self.assertEqual(broker_command.call_args_list,
                         [mock.call("stop"), mock.call("start")])


if __name__ == "__main__":
    unittest.main()
