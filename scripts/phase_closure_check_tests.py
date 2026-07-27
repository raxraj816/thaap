#!/usr/bin/env python3
"""Unit tests for the aggregate phase closure gate."""

from __future__ import annotations

import importlib.util
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

    def test_missing_inherited_artifacts_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            with mock.patch.object(checker, "ROOT", pathlib.Path(directory)):
                errors = checker.validate_phase(("36", "43R"))
        self.assertEqual(4, len(errors))
        self.assertTrue(all("missing" in error for error in errors))

    def test_every_coordinate_is_independently_validated(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            review = root / "docs" / "reviews"
            review.mkdir(parents=True)
            scripts = root / "scripts"
            scripts.mkdir()
            validator = scripts / "chapter-closure-check.py"
            validator.write_text("#!/usr/bin/env python3\nimport sys\nsys.exit(0)\n", encoding="utf-8")
            validator.chmod(0o755)
            for coordinate in ("36", "43R"):
                (review / f"CHAPTER_{coordinate}_INVARIANT_PROOF_MATRIX.md").write_text(
                    "matrix", encoding="utf-8")
                (review / f"CHAPTER_{coordinate}_CLOSURE_DOSSIER.md").write_text(
                    "dossier", encoding="utf-8")
            with mock.patch.object(checker, "ROOT", root):
                errors = checker.validate_phase(("36", "43R"))
        self.assertEqual([], errors)


if __name__ == "__main__":
    unittest.main()
