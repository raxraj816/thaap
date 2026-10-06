#!/usr/bin/env python3
"""Unit tests for the aggregate phase closure gate."""

from __future__ import annotations

import importlib.util
import hashlib
import pathlib
import sys
import tempfile
import unittest
from unittest import mock


SCRIPT_PATH = pathlib.Path(__file__).with_name("phase-closure-check.py")


def load_checker_module():
    spec = importlib.util.spec_from_file_location("phase_closure_check", SCRIPT_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


checker = load_checker_module()


class PhaseClosureCheckTests(unittest.TestCase):

    def create_phase_artifacts(self, root: pathlib.Path, coordinates: tuple[str, ...]):
        review = root / "docs" / "reviews"
        review.mkdir(parents=True)
        for coordinate in coordinates:
            (review / f"CHAPTER_{coordinate}_INVARIANT_PROOF_MATRIX.md").write_text(
                f"valid matrix {coordinate}\n", encoding="utf-8")
            (review / f"CHAPTER_{coordinate}_CLOSURE_DOSSIER.md").write_text(
                f"valid dossier {coordinate}\n", encoding="utf-8")

    def create_validators(self, root: pathlib.Path):
        scripts = root / "scripts"
        scripts.mkdir()
        invariant_validator = scripts / "invariant-proof-check.py"
        invariant_validator.write_text(
            """#!/usr/bin/env python3
import pathlib
import sys
text = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
if "MALFORMED" in text or "PENDING" in text:
    print("matrix is malformed or pending", file=sys.stderr)
    raise SystemExit(1)
""", encoding="utf-8")
        invariant_validator.chmod(0o755)
        closure_validator = scripts / "chapter-closure-check.py"
        closure_validator.write_text(
            """#!/usr/bin/env python3
import pathlib
import sys
matrix = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
dossier = pathlib.Path(sys.argv[2]).read_text(encoding="utf-8")
if "MALFORMED" in matrix or "PENDING" in matrix or "PENDING" in dossier:
    print("closure artifact is malformed or pending", file=sys.stderr)
    raise SystemExit(1)
""", encoding="utf-8")
        closure_validator.chmod(0o755)

    def create_phase_5_repository_evidence(self, root: pathlib.Path):
        for relative_path in checker.PHASE_5_PRODUCT_ARTIFACTS:
            path = root / relative_path
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("product evidence\n", encoding="utf-8")
        for relative_path in checker.PHASE_5_COMPLETION_SOURCES:
            path = root / relative_path
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(checker.PHASE_5_COMPLETION_MARKER + "\n", encoding="utf-8")
        learning_paths = (
            root / "docs/learning/daily/2026-07-28.md",
            root / "docs/learning/daily/2026-07-28-deep-dive.md",
            root / "docs/learning/weekly/2026-W31.md",
        )
        for path in learning_paths:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(checker.PHASE_5_COMPLETION_MARKER + "\n", encoding="utf-8")

    def test_missing_inherited_artifacts_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            with mock.patch.object(checker, "ROOT", pathlib.Path(directory)):
                errors = checker.validate_phase(("36", "43R"))
        self.assertEqual(4, len(errors))
        self.assertTrue(all("missing" in error for error in errors))

    def test_phase_5_mode_selects_every_chapter_from_44_through_52(self):
        self.assertEqual(
            tuple(str(number) for number in range(44, 53)),
            checker.coordinates_from_arguments(["--phase-5"]))

    def test_default_mode_preserves_phase_4_5_coordinates(self):
        self.assertEqual(
            checker.PHASE_4_5_COORDINATES,
            checker.coordinates_from_arguments([]))

    def test_missing_phase_5_artifacts_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            with mock.patch.object(checker, "ROOT", pathlib.Path(directory)):
                errors = checker.validate_phase(checker.PHASE_5_COORDINATES)
        self.assertEqual(18, len(errors))
        self.assertTrue(all("missing" in error for error in errors))

    def test_malformed_inherited_phase_5_matrix_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            self.create_phase_artifacts(root, checker.PHASE_5_COORDINATES)
            self.create_validators(root)
            coordinate = "47"
            (root / "docs" / "reviews" /
             ("CHAPTER_" + coordinate + "_INVARIANT_PROOF_MATRIX.md")).write_text(
                "MALFORMED\n", encoding="utf-8")
            with mock.patch.object(checker, "ROOT", root):
                errors = checker.validate_phase(checker.PHASE_5_COORDINATES)
        self.assertTrue(any("Chapter " + coordinate + " invariant proof failed" in error
                            for error in errors))

    def test_pending_inherited_phase_5_dossier_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            self.create_phase_artifacts(root, checker.PHASE_5_COORDINATES)
            self.create_validators(root)
            coordinate = "50"
            (root / "docs" / "reviews" /
             ("CHAPTER_" + coordinate + "_CLOSURE_DOSSIER.md")).write_text(
                "PENDING\n", encoding="utf-8")
            with mock.patch.object(checker, "ROOT", root):
                errors = checker.validate_phase(checker.PHASE_5_COORDINATES)
        self.assertTrue(any("Chapter " + coordinate + " closure failed" in error
                            for error in errors))

    def test_all_valid_phase_5_artifacts_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            self.create_phase_artifacts(root, checker.PHASE_5_COORDINATES)
            self.create_validators(root)
            with mock.patch.object(checker, "ROOT", root):
                errors = checker.validate_phase(checker.PHASE_5_COORDINATES)
        self.assertEqual([], errors)

    def test_phase_5_repository_evidence_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            self.create_phase_5_repository_evidence(root)
            missing_artifact = root / checker.PHASE_5_PRODUCT_ARTIFACTS[0]
            missing_artifact.unlink()
            stale_source = root / checker.PHASE_5_COMPLETION_SOURCES[0]
            stale_source.write_text(
                checker.PHASE_5_COMPLETION_MARKER + "\n"
                + checker.PHASE_5_STALE_CLAIMS[0] + "\n", encoding="utf-8")
            weekly = root / "docs/learning/weekly/2026-W31.md"
            weekly.write_text("unfinished\n", encoding="utf-8")
            with mock.patch.object(checker, "ROOT", root):
                errors = checker.validate_phase_5_repository()
        self.assertTrue(any("product artifact is missing" in error for error in errors))
        self.assertTrue(any("stale Phase 5 claim" in error for error in errors))
        self.assertTrue(any("weekly" in error and "completion marker" in error
                            for error in errors))

    def test_architecture_status_is_mandatory_and_stale_detection_is_case_insensitive(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            self.create_phase_5_repository_evidence(root)
            architecture = (root / "docs/architecture" /
                            "04_DATA_EVENTS_LEDGER_AND_STORAGE_ARCHITECTURE.md")
            architecture.write_text(
                checker.PHASE_5_COMPLETION_MARKER
                + "\nPhAsE 5 Is NoW iN pRoGrEsS\n", encoding="utf-8")
            with mock.patch.object(checker, "ROOT", root):
                errors = checker.validate_phase_5_repository()
        self.assertTrue(any(str(architecture) in error
                            and "stale Phase 5 claim" in error for error in errors))

    def test_phase_5_repository_evidence_accepts_complete_sources(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            self.create_phase_5_repository_evidence(root)
            with mock.patch.object(checker, "ROOT", root):
                errors = checker.validate_phase_5_repository()
        self.assertEqual([], errors)

    def test_every_coordinate_is_independently_validated(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            self.create_phase_artifacts(root, ("36", "43R"))
            self.create_validators(root)
            with mock.patch.object(checker, "ROOT", root):
                errors = checker.validate_phase(("36", "43R"))
        self.assertEqual([], errors)

    def create_phase_6_repository_evidence(self, root: pathlib.Path):
        for relative_path in checker.PHASE_6_PRODUCT_ARTIFACTS:
            path = root / relative_path
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("product evidence\n", encoding="utf-8")
        for relative_path in checker.PHASE_6_COMPLETION_SOURCES:
            path = root / relative_path
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(checker.PHASE_6_COMPLETION_MARKER + "\n", encoding="utf-8")
        for relative in (
                "docs/learning/daily/2026-08-06.md",
                "docs/learning/daily/2026-08-06-deep-dive.md",
                "docs/learning/weekly/2026-W32.md"):
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(checker.PHASE_6_COMPLETION_MARKER + "\n", encoding="utf-8")
        contract = root / "openapi/thaap-public-api-2026-08-01.yaml"
        stamp = root / "clients/python/thaap_sdk/thaap_sdk/generated/CONTRACT_SHA256"
        stamp.parent.mkdir(parents=True, exist_ok=True)
        stamp.write_text(hashlib.sha256(contract.read_bytes()).hexdigest() + "\n",
                         encoding="utf-8")

    def test_phase_6_rejects_stale_claim_across_markdown_line_breaks(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            self.create_phase_6_repository_evidence(root)
            source = root / "docs/IMPLEMENTATION_MASTER_PLAN.md"
            for separator in (" ", "\n", "\r\n", "\n\n", "\t"):
                with self.subTest(separator=repr(separator)):
                    source.write_text(checker.PHASE_6_COMPLETION_MARKER
                                      + "\nIMPLEMENTATION AUTHORIZED, NOT"
                                      + separator + "STARTED.\n", encoding="utf-8")
                    with mock.patch.object(checker, "ROOT", root):
                        errors = checker.validate_phase_6_repository()
                    self.assertTrue(any("stale Phase 6 claim" in error for error in errors))

    def test_phase_6_rejects_contradictory_inherited_dossier(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            self.create_phase_6_repository_evidence(root)
            dossier = root / "docs/reviews/CHAPTER_55_CLOSURE_DOSSIER.md"
            dossier.parent.mkdir(parents=True)
            dossier.write_text("Phase 6 as a whole\nremains open", encoding="utf-8")
            with mock.patch.object(checker, "ROOT", root):
                errors = checker.validate_phase_6_repository()
        self.assertTrue(any(str(dossier) in error and "stale Phase 6 claim" in error
                            for error in errors))

    def test_phase_6_rejects_sdk_contract_digest_drift(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            self.create_phase_6_repository_evidence(root)
            stamp = root / "clients/python/thaap_sdk/thaap_sdk/generated/CONTRACT_SHA256"
            stamp.write_text("0" * 64 + "\n", encoding="utf-8")
            with mock.patch.object(checker, "ROOT", root):
                errors = checker.validate_phase_6_repository()
        self.assertTrue(any("regenerate the SDK" in error for error in errors))

    def test_phase_6_accepts_reconciled_repository_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            self.create_phase_6_repository_evidence(root)
            with mock.patch.object(checker, "ROOT", root):
                errors = checker.validate_phase_6_repository()
        self.assertEqual([], errors)


if __name__ == "__main__":
    unittest.main()
