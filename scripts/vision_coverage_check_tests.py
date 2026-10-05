#!/usr/bin/env python3
"""Unit tests for vision-coverage-check.py."""

from __future__ import annotations

import datetime as dt
import importlib.util
import pathlib
import sys
import unittest
from unittest.mock import patch


SCRIPT_PATH = pathlib.Path(__file__).with_name("vision-coverage-check.py")


def load_checker_module():
    spec = importlib.util.spec_from_file_location("vision_coverage_check", SCRIPT_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


checker = load_checker_module()
REVIEW_DATE = dt.date(2026, 7, 27)
ROADMAP = "\n".join(checker.REQUIRED_ROADMAP_MARKERS)
VISION = "\n".join(checker.REQUIRED_DESTINATION_MARKERS)


def valid_matrix(status: str = "PARTIAL_NEXT_OWNER", owner: str = "Phase 7") -> str:
    return f"""
# THAAP Vision Coverage Matrix

- Status: ACTIVE

## VP-001: Example Promise

- Status: {status}
- Vision promise: A payment-risk product promise remains explicit and testable.
- Current evidence: Exact PostgreSQL and public API evidence proves the current useful boundary.
- Remaining gap: A named capability remains to be implemented and demonstrated.
- Owner phase: {owner}
- Owner slice: Governed Model Lifecycle
- Primary DRI: ML Architecture
- Trigger: Start after the point-in-time dataset contract passes.
- Done evidence: Exact integration, tenant, rollback, privacy, and public demo evidence all pass.
- Merchant / analyst demo: An analyst operates the capability end to end without reading source code.
- Last reviewed: 2026-07-27
"""


class VisionCoverageCheckTests(unittest.TestCase):

    def test_default_cli_and_validation_use_india_calendar_at_actual_ci_failure(self):
        import review_calendar
        matrix = valid_matrix().replace("2026-07-27", "2026-10-06")
        entries, errors = checker.parse_entries(matrix)
        self.assertEqual([], errors)
        with patch.object(review_calendar, "datetime") as clock:
            clock.now.return_value = dt.datetime(2026, 10, 5, 21, 13, tzinfo=dt.timezone.utc)
            self.assertEqual(dt.date(2026, 10, 6), checker.parse_args([]).as_of)
            self.assertEqual([], checker.validate(entries, matrix, f"VP-001\n{ROADMAP}", VISION,
                                                  minimum_promises=1))
            self.assertEqual(REVIEW_DATE, checker.parse_args(["--as-of", "2026-07-27"]).as_of)

    def test_calendar_fix_preserves_future_and_exact_staleness_boundary(self):
        matrix = valid_matrix()
        entries, _ = checker.parse_entries(matrix)
        for age, expected in ((-1, "in the future"), (0, None), (35, None),
                              (36, "reviewed within 35 days")):
            with self.subTest(age=age):
                errors = checker.validate(entries, matrix, f"VP-001\n{ROADMAP}", VISION,
                                           REVIEW_DATE + dt.timedelta(days=age), 1)
                if expected:
                    self.assertTrue(any(expected in error for error in errors), errors)
                else:
                    self.assertEqual([], errors)

    def test_valid_matrix_passes(self):
        matrix = valid_matrix()
        entries, parse_errors = checker.parse_entries(matrix)

        errors = parse_errors + checker.validate(
            entries, matrix, f"VP-001\n{ROADMAP}", VISION, REVIEW_DATE, 1)

        self.assertEqual([], errors)

    def test_gap_or_vague_owner_fails(self):
        matrix = valid_matrix(owner="Future phase")
        entries, parse_errors = checker.parse_entries(matrix)

        errors = parse_errors + checker.validate(
            entries, matrix, f"VP-001\n{ROADMAP}", VISION, REVIEW_DATE, 1)

        self.assertTrue(any("vague assignment" in error for error in errors))

    def test_missing_product_demo_fails(self):
        matrix = valid_matrix().replace(
            "- Merchant / analyst demo: An analyst operates the capability end to end without reading source code.\n",
            "")
        entries, parse_errors = checker.parse_entries(matrix)

        errors = parse_errors + checker.validate(
            entries, matrix, f"VP-001\n{ROADMAP}", VISION, REVIEW_DATE, 1)

        self.assertTrue(any("Merchant / analyst demo" in error for error in errors))

    def test_review_cannot_go_stale(self):
        matrix = valid_matrix()
        entries, parse_errors = checker.parse_entries(matrix)

        errors = parse_errors + checker.validate(
            entries, matrix, f"VP-001\n{ROADMAP}", VISION,
            dt.date(2026, 9, 1), 1)

        self.assertTrue(any("reviewed within 35 days" in error for error in errors))

    def test_master_plan_must_reference_every_promise(self):
        matrix = valid_matrix()
        entries, parse_errors = checker.parse_entries(matrix)

        errors = parse_errors + checker.validate(
            entries, matrix, ROADMAP, VISION, REVIEW_DATE, 1)

        self.assertIn(
            "VP-001 is not referenced by the implementation master plan", errors)

    def test_master_plan_must_keep_rebaselined_phase_markers(self):
        matrix = valid_matrix()
        entries, parse_errors = checker.parse_entries(matrix)

        errors = parse_errors + checker.validate(
            entries, matrix, "VP-001", VISION, REVIEW_DATE, 1)

        self.assertTrue(any("rebaselined roadmap marker" in error for error in errors))

    def test_registered_promises_cannot_be_deleted(self):
        matrix = valid_matrix()
        entries, parse_errors = checker.parse_entries(matrix)

        errors = parse_errors + checker.validate(
            entries, matrix, f"VP-001\n{ROADMAP}", VISION, REVIEW_DATE, 2)

        self.assertTrue(any("cannot be deleted" in error for error in errors))

    def test_merchant_platform_destination_cannot_be_removed(self):
        matrix = valid_matrix()
        entries, parse_errors = checker.parse_entries(matrix)

        errors = parse_errors + checker.validate(
            entries, matrix, f"VP-001\n{ROADMAP}", "simulator only",
            REVIEW_DATE, 1)

        self.assertTrue(any(
            "merchant-platform destination marker" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
