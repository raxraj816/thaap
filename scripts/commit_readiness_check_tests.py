#!/usr/bin/env python3
"""Behavior tests for commit-readiness-check.sh."""

from __future__ import annotations

import os
import pathlib
import stat
import subprocess
import tempfile
import unittest


SCRIPT_PATH = pathlib.Path(__file__).with_name("commit-readiness-check.sh")


class CommitReadinessCheckTests(unittest.TestCase):

    def test_no_selector_run_validates_current_pair_and_propagates_dossier_failure(self):
        for gate_exit in (0, 7):
            with self.subTest(gate_exit=gate_exit), tempfile.TemporaryDirectory() as directory:
                root = pathlib.Path(directory)
                self.create_required_files(root)
                fake_bin = root / "fake-bin"
                fake_bin.mkdir()
                self.write_executable(fake_bin / "git", "#!/usr/bin/env bash\nexit 0\n")
                self.write_executable(fake_bin / "rg", "#!/usr/bin/env bash\nexit 1\n")
                for name in ("technical-debt-check.py", "vision-coverage-check.py", "escaped-review-check.py",
                             "executable-artifact-naming-check.py", "runtime-configuration-reference-check.py",
                             "terraform-region-parameterisation-check.py", "routing-contract-check.py",
                             "public-api-error-contract-check.py", "openapi-contract-check.py",
                             "openapi-compatibility-check.py", "python-sdk-scope-check.py",
                             "python-sdk-package-check.py", "learning-day-check.sh"):
                    self.write_executable(root / "scripts" / name, "#!/usr/bin/env bash\nexit 0\n")
                self.write_executable(root / "scripts/invariant-proof-check.py",
                    "#!/usr/bin/env bash\nprintf '%s' \"$*\" > invariant-args\n")
                self.write_executable(root / "scripts/chapter-closure-check.py",
                    f"#!/usr/bin/env bash\nprintf '%s' \"$*\" > closure-args\nexit {gate_exit}\n")
                result = subprocess.run([str(SCRIPT_PATH), "2026-10-06"], cwd=root,
                    env={**os.environ, "PATH": str(fake_bin) + os.pathsep + os.environ["PATH"]},
                    capture_output=True, text=True, timeout=30)
                self.assertEqual(gate_exit, result.returncode, result.stderr)
                expected = "docs/reviews/CHAPTER_19_INVARIANT_PROOF_MATRIX.md"
                self.assertEqual(expected, (root / "invariant-args").read_text())
                self.assertEqual(expected + " docs/reviews/CHAPTER_19_CLOSURE_DOSSIER.md",
                                 (root / "closure-args").read_text())
                self.assertNotIn("SKIP closure dossier", result.stdout)

    def test_real_invalid_scope_stops_before_any_readiness_gate(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            self.create_required_files(root)
            self.write_executable(root / "scripts/readiness-scope-check.py",
                SCRIPT_PATH.with_name("readiness-scope-check.py").read_text())
            self.write_executable(root / "scripts/technical-debt-check.py",
                "#!/usr/bin/env bash\ntouch downstream-gate-ran\n")
            result = subprocess.run([str(SCRIPT_PATH), "2026-10-06"], cwd=root,
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(1, result.returncode)
            self.assertIn("Readiness scope check failed", result.stderr)
            self.assertFalse((root / "downstream-gate-ran").exists())

    def test_empty_scope_output_cannot_skip_closure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            self.create_required_files(root)
            self.write_executable(root / "scripts/readiness-scope-check.py", "#!/usr/bin/env bash\nexit 0\n")
            result = subprocess.run([str(SCRIPT_PATH), "2026-10-06"], cwd=root,
                                    capture_output=True, text=True, timeout=10)
            self.assertNotEqual(0, result.returncode)


    def test_non_trivial_readiness_always_runs_backend_and_python_tests(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = pathlib.Path(temporary_directory)
            self.create_required_files(root)
            fake_bin = root / "fake-bin"
            fake_bin.mkdir()
            maven_marker = root / "maven-ran"
            python_marker = root / "python-ran"
            vision_marker = root / "vision-coverage-ran"
            self.write_executable(
                fake_bin / "mvn",
                f"#!/usr/bin/env bash\ntouch '{maven_marker}'\n")
            self.write_executable(
                fake_bin / "python3",
                f"#!/usr/bin/env bash\ntouch '{python_marker}'\n")
            self.write_executable(fake_bin / "git", "#!/usr/bin/env bash\nexit 0\n")
            self.write_executable(fake_bin / "rg", "#!/usr/bin/env bash\nexit 1\n")
            self.write_executable(
                root / "scripts" / "vision-coverage-check.py",
                f"#!/usr/bin/env bash\ntouch '{vision_marker}'\n")
            for checker in (
                    "technical-debt-check.py",
                    "runtime-configuration-reference-check.py",
                    "terraform-region-parameterisation-check.py",
                    "routing-contract-check.py",
                    "escaped-review-check.py",
                    "executable-artifact-naming-check.py",
                    "public-api-error-contract-check.py",
                    "openapi-contract-check.py",
                    "openapi-compatibility-check.py",
                    "python-sdk-scope-check.py",
                    "python-sdk-package-check.py",
                    "invariant-proof-check.py",
                    "chapter-closure-check.py",
                    "learning-day-check.sh"):
                self.write_executable(
                    root / "scripts" / checker,
                    "#!/usr/bin/env bash\nexit 0\n")

            environment = os.environ.copy()
            environment["PATH"] = str(fake_bin) + os.pathsep + environment["PATH"]
            result = subprocess.run(
                [
                    str(SCRIPT_PATH),
                    "2026-07-02",
                    "--deep-dive-required",
                    "--invariant-matrix=docs/reviews/CHAPTER_19_INVARIANT_PROOF_MATRIX.md",
                    "--closure-dossier=docs/reviews/CHAPTER_19_CLOSURE_DOSSIER.md",
                ],
                cwd=root,
                env=environment,
                capture_output=True,
                text=True,
                check=False)

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertTrue(maven_marker.is_file())
            self.assertTrue(python_marker.is_file())
            self.assertTrue(vision_marker.is_file())

    def test_every_required_gate_script_is_actually_invoked(self):
        # Stubbing a checker and asserting exit 0 does not prove the readiness run
        # calls it: an unreferenced stub is silently never executed. `ER-0098` records
        # the correction that a gate script not invoked by this run is not a gate, so
        # the invocation itself is what has to be asserted. Deleting any line below
        # from commit-readiness-check.sh fails this test.
        required_gates = (
            "technical-debt-check.py",
            "runtime-configuration-reference-check.py",
            "terraform-region-parameterisation-check.py",
            "routing-contract-check.py",
            "vision-coverage-check.py",
            "escaped-review-check.py",
            "executable-artifact-naming-check.py",
            "public-api-error-contract-check.py",
            "openapi-contract-check.py",
            "openapi-compatibility-check.py",
            "python-sdk-scope-check.py",
            "python-sdk-package-check.py",
            "invariant-proof-check.py",
            "readiness-scope-check.py",
            "learning-day-check.sh",
        )
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = pathlib.Path(temporary_directory)
            self.create_required_files(root)
            fake_bin = root / "fake-bin"
            fake_bin.mkdir()
            self.write_executable(fake_bin / "git", "#!/usr/bin/env bash\nexit 0\n")
            self.write_executable(fake_bin / "rg", "#!/usr/bin/env bash\nexit 1\n")
            markers = {gate: root / f"ran-{gate}" for gate in required_gates}
            for gate, marker in markers.items():
                self.write_executable(
                    root / "scripts" / gate,
                    f"#!/usr/bin/env bash\ntouch '{marker}'\n")
            self.write_executable(root / "scripts" / "readiness-scope-check.py",
                f"#!/usr/bin/env bash\ntouch '{markers['readiness-scope-check.py']}'\n"
                "printf '%s\\t%s\\n' docs/reviews/CHAPTER_19_INVARIANT_PROOF_MATRIX.md docs/reviews/CHAPTER_19_CLOSURE_DOSSIER.md\n")
            self.write_executable(
                root / "scripts" / "chapter-closure-check.py",
                "#!/usr/bin/env bash\nexit 0\n")

            environment = os.environ.copy()
            environment["PATH"] = str(fake_bin) + os.pathsep + environment["PATH"]
            result = subprocess.run(
                [str(SCRIPT_PATH), "2026-08-06"],
                cwd=root,
                env=environment,
                capture_output=True,
                text=True,
                check=False)

            self.assertEqual(0, result.returncode, result.stderr)
            never_invoked = sorted(
                gate for gate, marker in markers.items() if not marker.is_file())
            self.assertEqual([], never_invoked)

    def test_chapter_52_runs_phase_5_aggregate_closure(self):
        self.assert_phase_aggregate_closure(52, 5, 0)

    def test_chapter_59_runs_phase_6_aggregate_closure_and_propagates_failure(self):
        for gate_exit_code in (0, 9):
            with self.subTest(gate_exit_code=gate_exit_code):
                self.assert_phase_aggregate_closure(59, 6, gate_exit_code)

    def test_local_release_recovery_retains_inherited_phase_closure(self):
        for gate_exit_code in (0, 9):
            with self.subTest(gate_exit_code=gate_exit_code):
                self.assert_phase_aggregate_closure("60R", 6, gate_exit_code)

    def assert_phase_aggregate_closure(self, chapter, phase, gate_exit_code):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = pathlib.Path(temporary_directory)
            self.create_required_files(root)
            fake_bin = root / "fake-bin"
            fake_bin.mkdir()
            phase_marker = root / f"phase-{phase}-ran"
            self.write_executable(fake_bin / "git", "#!/usr/bin/env bash\nexit 0\n")
            self.write_executable(fake_bin / "rg", "#!/usr/bin/env bash\nexit 1\n")
            for checker in (
                    "technical-debt-check.py",
                    "runtime-configuration-reference-check.py",
                    "terraform-region-parameterisation-check.py",
                    "routing-contract-check.py",
                    "vision-coverage-check.py",
                    "escaped-review-check.py",
                    "executable-artifact-naming-check.py",
                    "public-api-error-contract-check.py",
                    "openapi-contract-check.py",
                    "openapi-compatibility-check.py",
                    "python-sdk-scope-check.py",
                    "python-sdk-package-check.py",
                    "invariant-proof-check.py",
                    "chapter-closure-check.py",
                    "learning-day-check.sh"):
                self.write_executable(
                    root / "scripts" / checker,
                    "#!/usr/bin/env bash\nexit 0\n")
            self.write_executable(
                root / "scripts" / "phase-closure-check.py",
                "#!/usr/bin/env bash\n"
                f"[[ \"$#\" -eq 1 && \"$1\" == \"--phase-{phase}\" ]] || exit 7\n"
                f"touch '{phase_marker}'\n"
                f"exit {gate_exit_code}\n")

            environment = os.environ.copy()
            environment["PATH"] = str(fake_bin) + os.pathsep + environment["PATH"]
            result = subprocess.run(
                [
                    str(SCRIPT_PATH),
                    "2026-07-27",
                    f"--invariant-matrix=docs/reviews/CHAPTER_{chapter}_INVARIANT_PROOF_MATRIX.md",
                    f"--closure-dossier=docs/reviews/CHAPTER_{chapter}_CLOSURE_DOSSIER.md",
                ],
                cwd=root,
                env=environment,
                capture_output=True,
                text=True,
                check=False)

            self.assertEqual(gate_exit_code, result.returncode, result.stderr)
            self.assertTrue(phase_marker.is_file())

    def create_required_files(self, root: pathlib.Path):
        for relative_path in (
                "README.md",
                "apps/payments-api/README.md",
                "docs/IMPLEMENTATION_MASTER_PLAN.md",
                "docs/VISION.md",
                "docs/THAAP_VISION_COVERAGE_MATRIX.md",
                "docs/COMMIT_READINESS_CHECKLIST.md",
                "docs/ESCAPED_REVIEW_REGISTER.md",
                "docs/INVARIANT_PROOF_REVIEW_PROCESS.md",
                "docs/TECHNICAL_DEBT_REGISTER.md"):
            path = root / relative_path
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("current\n", encoding="utf-8")
        (root / "scripts").mkdir(exist_ok=True)
        scope = root / "docs/reviews/CURRENT_READINESS_SCOPE.json"
        scope.parent.mkdir(parents=True, exist_ok=True)
        scope.write_text("fixture\n")
        self.write_executable(root / "scripts/readiness-scope-check.py",
            "#!/usr/bin/env bash\n"
            "matrix=\"${2:-docs/reviews/CHAPTER_19_INVARIANT_PROOF_MATRIX.md}\"\n"
            "dossier=\"${3:-docs/reviews/CHAPTER_19_CLOSURE_DOSSIER.md}\"\n"
            "printf '%s\\t%s\\n' \"$matrix\" \"$dossier\"\n")

    def write_executable(self, path: pathlib.Path, content: str):
        path.write_text(content, encoding="utf-8")
        path.chmod(path.stat().st_mode | stat.S_IXUSR)


if __name__ == "__main__":
    unittest.main()
