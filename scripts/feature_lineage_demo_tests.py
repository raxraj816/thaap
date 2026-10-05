#!/usr/bin/env python3
"""Security and read-only reconciliation tests for the feature-lineage demo."""

import importlib.util
import pathlib
import subprocess
import unittest
import uuid
from unittest import mock


SCRIPT_PATH = pathlib.Path(__file__).with_name("demo-feature-lineage.py")
SPEC = importlib.util.spec_from_file_location("feature_lineage_demo", SCRIPT_PATH)
DEMO = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(DEMO)


class FeatureLineageDemoTests(unittest.TestCase):

    def test_no_context_contract_matches_production_always_present_and_velocity_types(self):
        catalogue = SCRIPT_PATH.parents[1] / "apps/payments-api/src/main/java/com/thaap/payments/fraud/FeatureCatalogue.java"
        import re
        types = dict(re.findall(r'types.put\("([^"]+)", FeatureValueType.([A-Z]+)\)', catalogue.read_text()))
        expected_codes = {code for code in types if not code.startswith(("risk.", "velocity."))}
        expected_codes.add("risk.context_present")
        expected_codes.update({"velocity.card_attempts_10m", "velocity.customer_attempts_1h", "velocity.merchant_attempts_1m"})
        self.assertEqual(DEMO.BASE_FEATURE_CODES, expected_codes)
        for code, (kind, _) in DEMO.EXPECTED_FEATURES.items():
            self.assertEqual(types[code], kind)

    def test_current_catalogue_is_exact_and_lexical_not_a_subset(self):
        valid = dict.fromkeys(sorted(DEMO.EXPECTED_FEATURES))
        DEMO.require_feature_catalogue(valid)
        for key in valid:
            with self.subTest(missing=key):
                with self.assertRaises(DEMO.DemoFailure):
                    DEMO.require_feature_catalogue({k: None for k in valid if k != key})
        for invalid in (dict.fromkeys(reversed(valid)), {**valid, "unsafe.extra": None}):
            with self.assertRaises(DEMO.DemoFailure):
                DEMO.require_feature_catalogue(invalid)

    def test_every_typed_value_and_observation_time_is_reconciled_without_duplicate_rows(self):
        valid = [(key, value_type, value, "true")
                 for key, (value_type, value) in sorted(DEMO.EXPECTED_FEATURES.items())]
        DEMO.require_durable_lineage(valid)
        for index, row in enumerate(valid):
            for column, wrong in ((0, "unknown.feature"), (1, "WRONG_TYPE"),
                                  (2, "wrong_value"), (3, "false")):
                changed = list(row)
                changed[column] = wrong
                invalid = valid[:index] + [tuple(changed)] + valid[index + 1:]
                with self.subTest(feature=row[0], column=column):
                    with self.assertRaises(DEMO.DemoFailure):
                        DEMO.require_durable_lineage(invalid)
        for invalid in (valid[:-1], valid + [valid[0]]):
            with self.assertRaises(DEMO.DemoFailure):
                DEMO.require_durable_lineage(invalid)

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
