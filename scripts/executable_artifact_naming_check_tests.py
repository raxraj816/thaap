#!/usr/bin/env python3
"""Behavior tests for the executable-artifact naming policy."""

from __future__ import annotations

import importlib.util
import pathlib
import tempfile
import unittest


SCRIPT_PATH = pathlib.Path(__file__).with_name("executable-artifact-naming-check.py")
SPEC = importlib.util.spec_from_file_location("executable_artifact_naming_check", SCRIPT_PATH)
NAMING_CHECK = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(NAMING_CHECK)


class ExecutableArtifactNamingCheckTests(unittest.TestCase):

    def test_capability_named_executables_pass(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = pathlib.Path(temporary_directory)
            self.write(root, "apps/api/src/test/java/FraudDecisionContractTests.java",
                       "class FraudDecisionContractTests {}\n")
            self.write(root, "scripts/demo-fraud-decision-loop.py", "RUN_ID = 'fraud-decision'\n")

            self.assertEqual([], NAMING_CHECK.find_violations(root))

    def test_chapter_named_java_path_fails(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = pathlib.Path(temporary_directory)
            self.write(root, "apps/api/src/test/java/Chapter26ReviewTests.java",
                       "class ReviewTests {}\n")

            violations = NAMING_CHECK.find_violations(root)

            self.assertTrue(any("Chapter26" in violation for violation in violations))

    def test_chapter_named_script_path_fails(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = pathlib.Path(temporary_directory)
            self.write(root, "scripts/demo-chapter-26-review.py", "print('review')\n")

            violations = NAMING_CHECK.find_violations(root)

            self.assertTrue(any("chapter-26" in violation for violation in violations))

    def test_chapter_class_and_fixture_content_fail(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = pathlib.Path(temporary_directory)
            self.write(
                root,
                "apps/api/src/test/java/ReviewTests.java",
                "class Chapter26ReviewTests { String fixture = \"chapter26-review\"; }\n")

            violations = NAMING_CHECK.find_violations(root)

            self.assertEqual(2, len(violations))

    def test_chapter_governance_checker_content_is_allowed(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = pathlib.Path(temporary_directory)
            self.write(
                root,
                "scripts/chapter-closure-check.py",
                "EXPECTED = 'Chapter 26 closure dossier'\n")

            self.assertEqual([], NAMING_CHECK.find_violations(root))

    def test_phase_governance_checker_content_is_allowed(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = pathlib.Path(temporary_directory)
            self.write(
                root,
                "scripts/phase-closure-check.py",
                "STALE_CLAIM = 'Chapter 44 is still active'\n")

            self.assertEqual([], NAMING_CHECK.find_violations(root))

    def test_only_exact_historical_constraint_reference_is_allowed(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = pathlib.Path(temporary_directory)
            test_path = (
                "apps/payments-api/src/test/java/com/thaap/payments/database/"
                "DatabaseMigrationTests.java")
            self.write(
                root,
                test_path,
                "String expected = \"ck_chapter18_no_legacy_refunded_payment_intents\";\n")

            self.assertEqual([], NAMING_CHECK.find_violations(root))

            self.write(
                root,
                test_path,
                "String expected = \"ck_chapter18_no_legacy_refunded_payment_intents\"; "
                "String fixture = \"chapter26-review\";\n")

            violations = NAMING_CHECK.find_violations(root)
            self.assertEqual(1, len(violations))
            self.assertIn("chapter26", violations[0])

    def write(self, root: pathlib.Path, relative_path: str, content: str):
        path = root / relative_path
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
