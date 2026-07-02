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

    def test_non_trivial_readiness_always_runs_backend_and_python_tests(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = pathlib.Path(temporary_directory)
            self.create_required_files(root)
            fake_bin = root / "fake-bin"
            fake_bin.mkdir()
            maven_marker = root / "maven-ran"
            python_marker = root / "python-ran"
            self.write_executable(
                fake_bin / "mvn",
                f"#!/usr/bin/env bash\ntouch '{maven_marker}'\n")
            self.write_executable(
                fake_bin / "python3",
                f"#!/usr/bin/env bash\ntouch '{python_marker}'\n")
            self.write_executable(fake_bin / "git", "#!/usr/bin/env bash\nexit 0\n")
            self.write_executable(fake_bin / "rg", "#!/usr/bin/env bash\nexit 1\n")
            for checker in (
                    "technical-debt-check.py",
                    "escaped-review-check.py",
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

    def create_required_files(self, root: pathlib.Path):
        for relative_path in (
                "README.md",
                "apps/payments-api/README.md",
                "docs/IMPLEMENTATION_MASTER_PLAN.md",
                "docs/COMMIT_READINESS_CHECKLIST.md",
                "docs/ESCAPED_REVIEW_REGISTER.md",
                "docs/INVARIANT_PROOF_REVIEW_PROCESS.md",
                "docs/TECHNICAL_DEBT_REGISTER.md"):
            path = root / relative_path
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("current\n", encoding="utf-8")
        (root / "scripts").mkdir(exist_ok=True)

    def write_executable(self, path: pathlib.Path, content: str):
        path.write_text(content, encoding="utf-8")
        path.chmod(path.stat().st_mode | stat.S_IXUSR)


if __name__ == "__main__":
    unittest.main()
