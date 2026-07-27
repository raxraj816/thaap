#!/usr/bin/env python3
"""Unit tests for invariant-proof-check.py."""

from __future__ import annotations

import importlib.util
import pathlib
import sys
import unittest


SCRIPT_PATH = pathlib.Path(__file__).with_name("invariant-proof-check.py")


def load_checker_module():
    spec = importlib.util.spec_from_file_location("invariant_proof_check", SCRIPT_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


checker = load_checker_module()


def valid_matrix() -> str:
    entries = []
    for index, lens in enumerate(sorted(checker.REQUIRED_LENSES), start=1):
        entries.append(f"""
## C99-INV-{index:03d}

- Lens: {lens}
- Invariant: Durable truth remains coherent under this review lens.
- Failure sequence: An adversarial input or interleaving attempts to violate the invariant.
- Implementation: Named production boundary enforces the invariant.
- Proof: Named automated test asserts the postcondition.
- Adversarial evidence: Boundary and negative-path assertions pass.
- Status: PASS
""")
    return """# Chapter 99 Invariant Proof Matrix

- Chapter: Chapter 99
- Matrix provenance: PRE_IMPLEMENTATION
- Scope status: FROZEN
- Review status: VERIFIED
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PASS
- Fresh hostile review: PASS
- Boundary family review: PASS
- Custom infrastructure review: NOT_APPLICABLE
- Escaped review assessment: COMPLETE
""" + "".join(entries)


class InvariantProofCheckTests(unittest.TestCase):

    def test_complete_matrix_passes(self):
        errors = checker.validate_matrix(pathlib.Path("matrix.md"), valid_matrix())

        self.assertEqual([], errors)

    def test_letter_suffixed_recovery_coordinate_passes(self):
        matrix = valid_matrix().replace("Chapter 99", "Chapter 43R")

        errors = checker.validate_matrix(pathlib.Path("matrix.md"), matrix)

        self.assertEqual([], errors)

    def test_missing_lens_fails(self):
        matrix = valid_matrix().replace(
            "- Lens: PAYMENT_FRAUD", "- Lens: API_PRODUCT", 1)

        errors = checker.validate_matrix(pathlib.Path("matrix.md"), matrix)

        self.assertTrue(any("missing required lenses PAYMENT_FRAUD" in error for error in errors))

    def test_unverified_matrix_fails(self):
        matrix = valid_matrix().replace(
            "- Fresh hostile review: PASS",
            "- Fresh hostile review: PENDING")

        errors = checker.validate_matrix(pathlib.Path("matrix.md"), matrix)

        self.assertTrue(any("must be PASS" in error for error in errors))

    def test_incomplete_boundary_family_review_fails(self):
        matrix = valid_matrix().replace(
            "- Boundary family review: PASS",
            "- Boundary family review: PENDING")

        errors = checker.validate_matrix(pathlib.Path("matrix.md"), matrix)

        self.assertTrue(any("Boundary family review must be PASS" in error
                            for error in errors))

    def test_missing_proof_and_placeholder_fail(self):
        matrix = valid_matrix().replace(
            "- Proof: Named automated test asserts the postcondition.",
            "- Proof: TODO",
            1)

        errors = checker.validate_matrix(pathlib.Path("matrix.md"), matrix)

        self.assertTrue(any("unresolved placeholder" in error for error in errors))

    def test_missing_preimplementation_provenance_fails(self):
        matrix = valid_matrix().replace(
            "- Matrix provenance: PRE_IMPLEMENTATION\n", "")

        errors = checker.validate_matrix(pathlib.Path("matrix.md"), matrix)

        self.assertTrue(any("missing metadata field 'Matrix provenance'" in error
                            for error in errors))

    def test_retroactive_recovery_cannot_be_reused_by_future_chapter(self):
        matrix = valid_matrix().replace(
            "- Matrix provenance: PRE_IMPLEMENTATION",
            "- Matrix provenance: RETROACTIVE_RECOVERY")

        errors = checker.validate_matrix(pathlib.Path("matrix.md"), matrix)

        self.assertTrue(any("allowed only" in error for error in errors))

    def test_chapter_19_recovery_provenance_is_honest_and_allowed(self):
        matrix = valid_matrix().replace("Chapter 99", "Chapter 19").replace(
            "- Matrix provenance: PRE_IMPLEMENTATION",
            "- Matrix provenance: RETROACTIVE_RECOVERY")

        errors = checker.validate_matrix(pathlib.Path("matrix.md"), matrix)

        self.assertEqual([], errors)


if __name__ == "__main__":
    unittest.main()
