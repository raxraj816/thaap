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


def drafted_matrix() -> str:
    """A matrix frozen before implementation, with nothing built yet.

    This is what the working agreement asks every chapter to produce first, and what the
    checker could not represent until `ER-0105`. Scope and failure sequences are agreed,
    the promises are frozen, the proofs are named as intentions, and no row claims to
    hold.
    """
    entries = []
    for index, lens in enumerate(sorted(checker.REQUIRED_LENSES), start=1):
        entries.append(f"""
## C98-INV-{index:03d}

- Lens: {lens}
- Invariant: Durable truth remains coherent under this review lens.
- Failure sequence: An adversarial input or interleaving attempts to violate the invariant.
- Implementation: The production boundary that will enforce the invariant is named.
- Proof: A named automated test will assert the postcondition.
- Adversarial evidence: The deliberately broken input the proof must reject is named.
- Status: PENDING
""")
    return """# Chapter 98 Invariant Proof Matrix

- Chapter: Chapter 98
- Matrix provenance: PRE_IMPLEMENTATION
- Scope status: FROZEN
- Review status: IN_PROGRESS
- Promise freeze: CONFIRMED
- Failure inventory: COMPLETE
- Traceability review: PENDING
- Fresh hostile review: PENDING
- Boundary family review: PENDING
- Custom infrastructure review: NOT_APPLICABLE
- Escaped review assessment: PENDING
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

    def test_a_verified_matrix_may_not_leave_a_review_verdict_pending(self):
        # Named for what it now asserts. `PENDING` became a legal verdict under
        # `ER-0105`, but only while the matrix says it is still under review; a matrix
        # claiming VERIFIED has by definition finished every pass.
        matrix = valid_matrix().replace(
            "- Fresh hostile review: PASS",
            "- Fresh hostile review: PENDING")

        errors = checker.validate_matrix(pathlib.Path("matrix.md"), matrix)

        self.assertTrue(any("Fresh hostile review must be PASS" in error
                            for error in errors))

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

    def test_proof_naming_a_test_class_that_does_not_exist_fails(self):
        matrix = valid_matrix().replace(
            "- Proof: Named automated test asserts the postcondition.",
            "- Proof: `NeverWrittenBoundaryTests` asserts the postcondition.",
            1)

        errors = checker.validate_matrix(
            pathlib.Path("matrix.md"), matrix, artifacts=set())

        self.assertTrue(any("names 'NeverWrittenBoundaryTests', which does not exist"
                            in error for error in errors))

    def test_proof_naming_an_existing_test_class_passes(self):
        matrix = valid_matrix().replace(
            "- Proof: Named automated test asserts the postcondition.",
            "- Proof: `RealBoundaryTests` asserts the postcondition.",
            1)

        errors = checker.validate_matrix(
            pathlib.Path("matrix.md"), matrix, artifacts={"RealBoundaryTests"})

        self.assertEqual([], errors)

    def test_proof_naming_a_script_that_does_not_exist_fails(self):
        matrix = valid_matrix().replace(
            "- Proof: Named automated test asserts the postcondition.",
            "- Proof: `scripts/never-written-check.py` proves the postcondition.",
            1)

        errors = checker.validate_matrix(
            pathlib.Path("matrix.md"), matrix, artifacts=set())

        self.assertTrue(any("scripts/never-written-check.py" in error
                            for error in errors))

    def test_a_path_reference_resolves_by_basename_when_the_matrix_omits_its_package(self):
        # Chapter 58 names SDK suites as `tests/test_secure_client.py`, which is
        # relative to the client package rather than to the repository.
        matrix = valid_matrix().replace(
            "- Proof: Named automated test asserts the postcondition.",
            "- Proof: `tests/test_secure_client.py` asserts the postcondition.",
            1)

        errors = checker.validate_matrix(
            pathlib.Path("matrix.md"), matrix, artifacts={"test_secure_client.py"})

        self.assertEqual([], errors)

    def test_backticked_prose_is_not_mistaken_for_an_artifact(self):
        # `AUDIT` and `LIMIT` are SQL keywords the matrices quote, and `ER-0096` is a
        # register coordinate. Treating them as missing files would make the gate noisy
        # enough to be switched off, which is how the first unrun gate survived.
        matrix = valid_matrix().replace(
            "- Proof: Named automated test asserts the postcondition.",
            "- Proof: `AUDIT`, `LIMIT`, `ER-0096`, `PASS`, and `SYNTHETIC_SCENARIO` hold.",
            1)

        errors = checker.validate_matrix(
            pathlib.Path("matrix.md"), matrix, artifacts=set())

        self.assertEqual([], errors)

    def test_partial_status_with_a_debt_citation_passes_at_chapter_closure(self):
        matrix = valid_matrix().replace("- Status: PASS", "- Status: PARTIAL", 1).replace(
            "- Proof: Named automated test asserts the postcondition.",
            "- Proof: The adversarial suite is unbuilt and registered as TD-0007.",
            1)

        errors = checker.validate_matrix(
            pathlib.Path("matrix.md"), matrix, artifacts=set())

        self.assertEqual([], errors)

    def test_partial_status_without_a_debt_citation_fails(self):
        # A partial row with no owner is the exact state ER-0099 was raised about.
        matrix = valid_matrix().replace("- Status: PASS", "- Status: PARTIAL", 1)

        errors = checker.validate_matrix(
            pathlib.Path("matrix.md"), matrix, artifacts=set())

        self.assertTrue(any("must cite the TD-NNNN" in error for error in errors))

    def test_partial_status_is_rejected_at_phase_closure(self):
        matrix = valid_matrix().replace("- Status: PASS", "- Status: PARTIAL", 1).replace(
            "- Proof: Named automated test asserts the postcondition.",
            "- Proof: The adversarial suite is unbuilt and registered as TD-0007.",
            1)

        errors = checker.validate_matrix(
            pathlib.Path("matrix.md"), matrix, artifacts=set(), reject_partial=True)

        self.assertTrue(any("a phase cannot close on partial proof" in error
                            for error in errors))
        self.assertTrue(any("TD-0007" in error for error in errors))

    def test_passing_rows_are_unaffected_by_phase_closure_strictness(self):
        errors = checker.validate_matrix(
            pathlib.Path("matrix.md"), valid_matrix(), artifacts=set(),
            reject_partial=True)

        self.assertEqual([], errors)

    def test_an_unknown_status_still_fails(self):
        matrix = valid_matrix().replace("- Status: PASS", "- Status: NEARLY_THERE", 1)

        errors = checker.validate_matrix(
            pathlib.Path("matrix.md"), matrix, artifacts=set())

        self.assertTrue(any("Status must be one of" in error for error in errors))

    def test_a_pre_implementation_matrix_can_be_committed_before_anything_is_built(self):
        # The state ER-0105 was raised about. The working agreement requires every matrix
        # to be drafted before implementation, and until this existed such a matrix could
        # not pass its own checker — so it lived uncommitted, where a matrix written
        # afterwards is indistinguishable from one written first.
        errors = checker.validate_matrix(
            pathlib.Path("matrix.md"), drafted_matrix(), artifacts=set())

        self.assertEqual([], errors)

    def test_a_pending_row_may_name_proof_that_does_not_exist_yet(self):
        # Naming the proof before writing it is the point of drafting. Requiring the
        # artifact to resolve would make a pre-implementation matrix unwritable.
        matrix = drafted_matrix().replace(
            "- Proof: A named automated test will assert the postcondition.",
            "- Proof: `NotWrittenYetBoundaryTests` will assert the postcondition.")

        errors = checker.validate_matrix(
            pathlib.Path("matrix.md"), matrix, artifacts=set())

        self.assertEqual([], errors)

    def test_a_closed_row_still_may_not_name_proof_that_does_not_exist(self):
        # The ER-0099 obligation is unchanged where it matters.
        matrix = drafted_matrix().replace("- Status: PENDING", "- Status: PASS").replace(
            "- Review status: IN_PROGRESS", "- Review status: VERIFIED").replace(
            "- Proof: A named automated test will assert the postcondition.",
            "- Proof: `NotWrittenYetBoundaryTests` asserts the postcondition.")

        errors = checker.validate_matrix(
            pathlib.Path("matrix.md"), matrix, artifacts=set())

        self.assertTrue(any("NotWrittenYetBoundaryTests" in error for error in errors))

    def test_a_pending_row_may_not_claim_a_verified_review(self):
        matrix = drafted_matrix().replace(
            "- Review status: IN_PROGRESS", "- Review status: VERIFIED")

        errors = checker.validate_matrix(
            pathlib.Path("matrix.md"), matrix, artifacts=set())

        self.assertTrue(any("may not claim" in error and "VERIFIED" in error
                            for error in errors))

    def test_a_pending_row_is_rejected_at_phase_closure(self):
        errors = checker.validate_matrix(
            pathlib.Path("matrix.md"), drafted_matrix(), artifacts=set(),
            reject_partial=True)

        self.assertTrue(any("a phase cannot close on an invariant nothing has proven"
                            in error for error in errors))

    def test_frozen_promises_are_required_even_while_drafting(self):
        # Promise freeze and the failure inventory are inputs to drafting, not outputs of
        # implementation. A matrix drafted without them was reconstructed from something,
        # and the only thing available to reconstruct from is the code.
        matrix = drafted_matrix().replace(
            "- Promise freeze: CONFIRMED", "- Promise freeze: PENDING")

        errors = checker.validate_matrix(
            pathlib.Path("matrix.md"), matrix, artifacts=set())

        self.assertTrue(any("Promise freeze must be CONFIRMED" in error
                            for error in errors))

    def test_draft_scope_requires_an_in_progress_review(self):
        matrix = valid_matrix().replace("- Scope status: FROZEN", "- Scope status: DRAFT")

        errors = checker.validate_matrix(
            pathlib.Path("matrix.md"), matrix, artifacts=set())

        self.assertTrue(any("Scope status DRAFT requires" in error for error in errors))

    def test_the_reject_partial_flag_is_not_treated_as_a_matrix_path(self):
        self.assertEqual(
            checker.matrix_paths(["--reject-partial"]),
            checker.matrix_paths([]))

    def test_the_repository_index_finds_a_real_committed_suite(self):
        artifacts = checker.repository_artifacts(checker.ROOT)

        self.assertIn("CanonicalEventCodecTests", artifacts)
        self.assertIn("invariant-proof-check.py", artifacts)
        self.assertNotIn("NeverWrittenBoundaryTests", artifacts)

    def test_chapter_19_recovery_provenance_is_honest_and_allowed(self):
        matrix = valid_matrix().replace("Chapter 99", "Chapter 19").replace(
            "- Matrix provenance: PRE_IMPLEMENTATION",
            "- Matrix provenance: RETROACTIVE_RECOVERY")

        errors = checker.validate_matrix(pathlib.Path("matrix.md"), matrix)

        self.assertEqual([], errors)


if __name__ == "__main__":
    unittest.main()
