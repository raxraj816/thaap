#!/usr/bin/env python3
"""Behavior tests for the supported-SDK package gate.

The gate previously reported `FAIL` on any host without `packaging` installed.
A gate that fails for an environment reason is indistinguishable in a summary
from one that fails for a real reason, which is the failure shape recorded in
`ER-0096`. These tests hold the fallback comparator to the same answers the
`packaging` implementation gives.
"""

from __future__ import annotations

import importlib.util
import pathlib
import unittest


SCRIPT_PATH = pathlib.Path(__file__).with_name("python-sdk-package-check.py")
SPEC = importlib.util.spec_from_file_location("python_sdk_package_check", SCRIPT_PATH)
CHECK = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(CHECK)


class DeclaredRuntimeRangeTests(unittest.TestCase):

    def test_the_declared_range_accepts_every_supported_runtime(self):
        for version in CHECK.SUPPORTED_RUNTIMES:
            with self.subTest(version=version):
                self.assertTrue(CHECK.accepts_runtime(">=3.11,<3.15", version))

    def test_the_declared_range_rejects_every_untested_runtime(self):
        for version in CHECK.UNSUPPORTED_RUNTIMES:
            with self.subTest(version=version):
                self.assertFalse(CHECK.accepts_runtime(">=3.11,<3.15", version))

    def test_bounds_compare_by_release_not_by_string(self):
        # "3.9" > "3.11" lexically, which is the mistake this gate must not make.
        self.assertTrue(CHECK.accepts_runtime(">=3.11", "3.11"))
        self.assertFalse(CHECK.accepts_runtime(">=3.11", "3.9"))
        self.assertTrue(CHECK.accepts_runtime("<3.15", "3.14"))
        self.assertFalse(CHECK.accepts_runtime("<3.15", "3.15"))

    def test_unequal_precision_compares_as_the_same_release(self):
        self.assertTrue(CHECK.accepts_runtime(">=3.11.0", "3.11"))
        self.assertTrue(CHECK.accepts_runtime("<=3.11", "3.11.0"))
        self.assertFalse(CHECK.accepts_runtime("<3.11", "3.11.0"))

    def test_every_supported_operator_is_evaluated(self):
        self.assertTrue(CHECK.accepts_runtime("==3.12", "3.12"))
        self.assertFalse(CHECK.accepts_runtime("==3.12", "3.13"))
        self.assertTrue(CHECK.accepts_runtime("!=3.12", "3.13"))
        self.assertFalse(CHECK.accepts_runtime("!=3.12", "3.12"))
        self.assertTrue(CHECK.accepts_runtime(">3.11", "3.12"))
        self.assertFalse(CHECK.accepts_runtime(">3.11", "3.11"))

    def test_an_unrecognised_clause_fails_loudly_rather_than_silently_accepting(self):
        for clause in ("~=3.11", "3.11", ">=3.11.*", "not-a-clause"):
            with self.subTest(clause=clause):
                with self.assertRaises(ValueError):
                    CHECK.accepts_runtime(clause, "3.12")

    def test_the_fallback_matches_pep_440_over_the_declared_range(self):
        # Pinned as an explicit table rather than compared against `packaging`, so the
        # assertion holds identically on a host that does not have it installed.
        expected = {
            (">=3.11,<3.15", "3.10"): False, (">=3.11,<3.15", "3.11"): True,
            (">=3.11,<3.15", "3.14"): True, (">=3.11,<3.15", "3.15"): False,
            (">3.11,<=3.14", "3.11"): False, (">3.11,<=3.14", "3.12"): True,
            (">3.11,<=3.14", "3.14"): True, (">3.11,<=3.14", "3.15"): False,
        }
        for (specifier, version), accepted in expected.items():
            with self.subTest(specifier=specifier, version=version):
                self.assertEqual(accepted, CHECK.accepts_runtime(specifier, version))

    def test_the_repository_declaration_passes_on_a_host_without_packaging(self):
        self.assertEqual([], CHECK.check_declared_runtime_range())


if __name__ == "__main__":
    unittest.main()
