#!/usr/bin/env python3
"""Unit tests for escaped-review-check.py."""

from __future__ import annotations

import importlib.util
import pathlib
import sys
import unittest


SCRIPT_PATH = pathlib.Path(__file__).with_name("escaped-review-check.py")


def load_checker_module():
    spec = importlib.util.spec_from_file_location("escaped_review_check", SCRIPT_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


checker = load_checker_module()


def entry(identifier: str = "ER-0001", status: str = "RESOLVED") -> str:
    return f"""# Escaped Review Register

## {identifier}

- Status: {status}
- Severity: P1
- Chapter: 19
- Category: WEAK_PROOF
- Frozen promise affected: YES
- Date found: 2026-06-30
- Escaped gate: Earlier review
- Invariant: Durable state has one coherent winner.
- Finding: A timing-only test did not force the dangerous transaction ordering.
- Why prior review missed it: The review treated concurrent start as deterministic evidence.
- Resolution evidence: Forced PostgreSQL ordering proves the intended lock and durable postcondition.
- Process correction: The completion process now rejects timing-only concurrency evidence.
- Primary DRI: Founding Staff Engineer
- Last reviewed: 2026-06-30
"""


class EscapedReviewCheckTests(unittest.TestCase):

    def test_complete_resolved_entry_passes(self):
        errors = checker.validate_register(pathlib.Path("register.md"), entry())

        self.assertEqual([], errors)

    def test_gap_in_immutable_ids_fails(self):
        errors = checker.validate_register(
            pathlib.Path("register.md"), entry(identifier="ER-0002"))

        self.assertTrue(any("gap-free" in error for error in errors))

    def test_unknown_root_cause_fails(self):
        register = entry().replace(
            "- Category: WEAK_PROOF", "- Category: CARELESSNESS")

        errors = checker.validate_register(pathlib.Path("register.md"), register)

        self.assertTrue(any("invalid Category" in error for error in errors))

    def test_resolved_entry_requires_product_and_process_evidence(self):
        register = entry().replace(
            "- Resolution evidence: Forced PostgreSQL ordering proves the intended lock and durable postcondition.",
            "- Resolution evidence: fixed")

        errors = checker.validate_register(pathlib.Path("register.md"), register)

        self.assertTrue(any("require product evidence" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
