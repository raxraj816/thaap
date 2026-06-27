#!/usr/bin/env python3
"""Unit tests for technical-debt-check.py."""

from __future__ import annotations

import importlib.util
import datetime as dt
import pathlib
import sys
import unittest


SCRIPT_PATH = pathlib.Path(__file__).with_name("technical-debt-check.py")


def load_checker_module():
    spec = importlib.util.spec_from_file_location("technical_debt_check", SCRIPT_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


checker = load_checker_module()
REVIEW_DATE = dt.date(2026, 6, 27)


def valid_register_entry(status: str = "OPEN", evidence: str = "Pending") -> str:
    return f"""
## TD-0001: Example Debt

- Status: {status}
- Debt type: IMPLEMENTATION_DEBT
- Priority: P2
- Introduced: 2026-06-27
- Origin: Chapter 16
- Resolution target: Phase 7 — Named Slice
- Primary DRI: ML/Data Platform
- Supporting owners: Payments Core
- Risk / invariant: Retries must not duplicate payment truth.
- Deferral reason: Durable run state does not exist.
- Trigger / deadline: Resolve before scheduled generation.
- Done when: Retry tests prove no duplicate durable truth.
- Resolution evidence: {evidence}
- Last reviewed: 2026-06-27
"""


class TechnicalDebtCheckTests(unittest.TestCase):

    def test_valid_open_debt_passes(self):
        entries, parse_errors = checker.parse_register(valid_register_entry())

        errors = parse_errors + checker.validate_entries(
                entries, "Owner roadmap reference TD-0001", REVIEW_DATE)

        self.assertEqual([], errors)

    def test_missing_owner_and_vague_target_fail(self):
        register = valid_register_entry().replace(
                "- Primary DRI: ML/Data Platform\n", "").replace(
                "Phase 7 — Named Slice", "Future phase")
        entries, parse_errors = checker.parse_register(register)

        errors = parse_errors + checker.validate_entries(entries, "TD-0001", REVIEW_DATE)

        self.assertTrue(any("Primary DRI" in error for error in errors))
        self.assertTrue(any("vague assignment" in error for error in errors))

    def test_resolved_debt_requires_evidence(self):
        entries, parse_errors = checker.parse_register(valid_register_entry(status="RESOLVED"))

        errors = parse_errors + checker.validate_entries(entries, "TD-0001", REVIEW_DATE)

        self.assertTrue(any("no resolution evidence" in error for error in errors))

    def test_master_plan_debt_must_be_registered(self):
        entries, parse_errors = checker.parse_register(valid_register_entry())

        errors = parse_errors + checker.validate_entries(entries, "TD-0001 TD-9999", REVIEW_DATE)

        self.assertIn("Implementation master plan references unregistered debt TD-9999", errors)

    def test_open_debt_review_cannot_go_stale(self):
        entries, parse_errors = checker.parse_register(valid_register_entry())

        errors = parse_errors + checker.validate_entries(
                entries, "TD-0001", dt.date(2026, 8, 2))

        self.assertTrue(any("has not been reviewed within 35 days" in error for error in errors))

    def test_p0_work_is_rejected_with_explicit_message(self):
        register = valid_register_entry().replace("- Priority: P2", "- Priority: P0")
        entries, parse_errors = checker.parse_register(register)

        errors = parse_errors + checker.validate_entries(entries, "TD-0001", REVIEW_DATE)

        self.assertIn("TD-0001: P0 work cannot be deferred as technical debt", errors)


if __name__ == "__main__":
    unittest.main()
