#!/usr/bin/env python3
"""Unit tests for generate-synthetic-scenarios.py."""

from __future__ import annotations

import importlib.util
import datetime as dt
import pathlib
import sys
import tempfile
import unittest


SCRIPT_PATH = pathlib.Path(__file__).with_name("generate-synthetic-scenarios.py")


def load_generator_module():
    spec = importlib.util.spec_from_file_location("generate_synthetic_scenarios", SCRIPT_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


generator = load_generator_module()


class SyntheticScenarioGeneratorTests(unittest.TestCase):

    def test_same_seed_produces_same_public_scenario_plan(self):
        first_plan = generator.build_scenario_plan("chapter-16-demo")
        second_plan = generator.build_scenario_plan("chapter-16-demo")

        self.assertEqual(generator.plan_to_public_dict(first_plan), generator.plan_to_public_dict(second_plan))
        self.assertEqual(generator.plan_fingerprint(first_plan), generator.plan_fingerprint(second_plan))

    def test_scenario_labels_and_expected_outcomes_are_stable(self):
        plan = generator.build_scenario_plan("chapter-16-demo")
        public_plan = generator.plan_to_public_dict(plan)

        self.assertEqual([
            "happy_path_approved_authorization",
            "retryable_processor_decline",
            "basic_card_testing_pattern",
        ], [scenario["label"] for scenario in public_plan])
        self.assertEqual("APPROVED", public_plan[0]["payments"][0]["expectedProcessorOutcomeCode"])
        self.assertEqual("ISSUER_DECLINE", public_plan[1]["payments"][0]["expectedProcessorOutcomeCode"])
        self.assertEqual([
            "SUSPECTED_FRAUD",
            "CVC_FAILURE",
            "AVS_LINE1_FAILURE",
        ], [payment["expectedProcessorOutcomeCode"] for payment in public_plan[2]["payments"]])

    def test_generated_customer_phones_are_valid_deterministic_e164_values(self):
        first_plan = generator.build_scenario_plan("chapter-16-demo")
        second_plan = generator.build_scenario_plan("chapter-16-demo")

        self.assertEqual(
                [scenario.customer_phone for scenario in first_plan],
                [scenario.customer_phone for scenario in second_plan])
        for scenario in first_plan:
            self.assertRegex(scenario.customer_phone, r"^\+1[0-9]{10}$")

    def test_public_plan_contains_card_fixture_labels_not_raw_card_numbers(self):
        public_plan = generator.plan_to_public_dict(generator.build_scenario_plan("chapter-16-demo"))
        public_plan_text = repr(public_plan)

        self.assertIn("499999:0001", public_plan_text)
        self.assertNotIn("4999990000000001", public_plan_text)
        self.assertNotIn("4999990000000002", public_plan_text)
        self.assertNotIn("4999990000000004", public_plan_text)

    def test_sensitive_data_scan_fails_for_blocked_values_or_terms(self):
        summary = {
            "apiKey": "thaap_live_secret",
            "nested": {
                "card": "4999990000000001",
            },
        }

        result = generator.sensitive_data_scan(summary, ["thaap_live_secret", "4999990000000001"])

        self.assertEqual("FAIL", result["status"])
        self.assertGreaterEqual(len(result["findings"]), 2)

    def test_sensitive_data_scan_passes_for_safe_summary(self):
        summary = {
            "scenario": "happy_path_approved_authorization",
            "paymentAttemptId": "00000000-0000-0000-0000-000000000001",
            "processorOutcomeCode": "APPROVED",
        }

        result = generator.sensitive_data_scan(summary, ["4999990000000001", "secret_key"])

        self.assertEqual({"status": "PASS", "findings": []}, result)

    def test_non_local_or_credential_bearing_base_urls_are_rejected(self):
        self.assertEqual("http://localhost:8080",
                generator.validate_local_base_url("http://localhost:8080/"))
        self.assertEqual("https://127.0.0.1:8443",
                generator.validate_local_base_url("https://127.0.0.1:8443"))
        self.assertEqual("http://[::1]:8080",
                generator.validate_local_base_url("http://[::1]:8080"))

        for unsafe_url in (
                "https://payments.example.com",
                "http://user:secret@localhost:8080",
                "http://localhost:8080/api",
                "http://localhost:8080?token=secret",
        ):
            with self.subTest(unsafe_url=unsafe_url):
                with self.assertRaises(ValueError):
                    generator.validate_local_base_url(unsafe_url)

    def test_admin_token_is_read_from_environment_and_is_required(self):
        self.assertEqual("local-secret",
                generator.admin_token_from_environment({"THAAP_ADMIN_BOOTSTRAP_TOKEN": "local-secret"}))
        with self.assertRaises(ValueError):
            generator.admin_token_from_environment({})

    def test_run_ids_are_collision_resistant_even_at_the_same_instant(self):
        instant = dt.datetime(2026, 6, 27, 9, 30, 0, tzinfo=dt.timezone.utc)

        first_run_id = generator.new_run_id("chapter-16-demo", instant)
        second_run_id = generator.new_run_id("chapter-16-demo", instant)

        self.assertNotEqual(first_run_id, second_run_id)
        self.assertRegex(first_run_id, r"^20260627093000000000-[0-9a-f]{8}-[0-9a-f]{12}$")

    def test_non_empty_or_malformed_ledger_truth_fails_the_scenario(self):
        generator.assert_no_ledger_truth([], "decline")

        with self.assertRaisesRegex(RuntimeError, "unexpectedly created 1 ledger entries"):
            generator.assert_no_ledger_truth([{"id": "ledger-entry"}], "decline")
        with self.assertRaisesRegex(RuntimeError, "was not a list"):
            generator.assert_no_ledger_truth({"entries": []}, "decline")

    def test_summary_write_is_atomic_and_leaves_no_temporary_file(self):
        summary = {"runId": "run-1", "status": "PASS"}
        with tempfile.TemporaryDirectory() as temporary_directory:
            output = pathlib.Path(temporary_directory) / "summary.json"

            written_path = generator.write_summary(summary, output)

            self.assertEqual(output, written_path)
            self.assertEqual('{\n  "runId": "run-1",\n  "status": "PASS"\n}\n', output.read_text())
            self.assertEqual([], list(pathlib.Path(temporary_directory).glob("*.tmp")))


if __name__ == "__main__":
    unittest.main()
