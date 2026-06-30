#!/usr/bin/env python3
"""Unit tests for chapter-closure-check.py."""

from __future__ import annotations

import importlib.util
import pathlib
import sys
import unittest


SCRIPT_PATH = pathlib.Path(__file__).with_name("chapter-closure-check.py")


def load_checker_module():
    spec = importlib.util.spec_from_file_location("chapter_closure_check", SCRIPT_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


checker = load_checker_module()


def dossier(status: str = "READY_TO_MERGE", recommendation: str = "MERGE") -> str:
    sections = "\n".join(
        f"## {section}\n\nConcrete reviewed evidence for {section}."
        for section in sorted(checker.REQUIRED_SECTIONS))
    return f"""# Chapter 19 Closure Dossier

- Chapter: Chapter 19
- Chapter number: 19
- Status: {status}
- Invariant matrix: matrix.md
- Matrix provenance: RETROACTIVE_RECOVERY
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Hostile review: PASS
- Custom infrastructure review: NOT_APPLICABLE
- Escaped review assessment: COMPLETE
- Merge recommendation: {recommendation}

{sections}
"""


def matrix() -> str:
    return """# Chapter 19 Matrix

- Chapter: Chapter 19
- Matrix provenance: RETROACTIVE_RECOVERY
"""


def register(status: str = "RESOLVED", severity: str = "P1") -> str:
    return f"""# Register

## ER-0001

- Status: {status}
- Severity: {severity}
- Chapter: 19
- Frozen promise affected: YES
"""


class ChapterClosureCheckTests(unittest.TestCase):

    def validate(self, dossier_text: str, register_text: str | None = None):
        return checker.validate_closure(
            pathlib.Path("dossier.md"),
            dossier_text,
            pathlib.Path("matrix.md").resolve(),
            matrix(),
            register_text or register(),
            "Chapter 19 is complete.",
            "4. Chapter 19: Name.\n\n   Status: complete.\n\n5. Chapter 20: Next.")

    def test_complete_closure_passes(self):
        errors = self.validate(dossier())

        self.assertEqual([], errors)

    def test_non_merge_dossier_fails(self):
        errors = self.validate(dossier("IN_PROGRESS", "DO_NOT_MERGE"))

        self.assertTrue(any("Status must be READY_TO_MERGE" in error for error in errors))
        self.assertTrue(any("recommendation must be MERGE" in error for error in errors))

    def test_unresolved_blocking_escaped_finding_fails(self):
        errors = self.validate(dossier(), register("IN_PROGRESS", "P2"))

        self.assertTrue(any("blocking escaped finding ER-0001" in error
                            for error in errors))

    def test_source_of_truth_status_contradiction_fails(self):
        errors = checker.validate_closure(
            pathlib.Path("dossier.md"),
            dossier(),
            pathlib.Path("matrix.md").resolve(),
            matrix(),
            register(),
            "Chapter 18 is complete.",
            "4. Chapter 19: Name.\n\n   Status: in progress.")

        self.assertTrue(any("README.md does not mark" in error for error in errors))
        self.assertTrue(any("master plan does not mark" in error for error in errors))

    def test_matrix_provenance_disagreement_fails(self):
        errors = checker.validate_closure(
            pathlib.Path("dossier.md"),
            dossier(),
            pathlib.Path("matrix.md").resolve(),
            matrix().replace("RETROACTIVE_RECOVERY", "PRE_IMPLEMENTATION"),
            register(),
            "Chapter 19 is complete.",
            "4. Chapter 19: Name.\n\n   Status: complete.")

        self.assertTrue(any("Matrix provenance disagrees" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
