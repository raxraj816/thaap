#!/usr/bin/env python3
"""Behavior tests for python-sdk-scope-check.py.

A scope gate that only ever passes proves nothing. Each test plants exactly one
violation of a supported-SDK scope exclusion and asserts the gate fails on it, so
the gate itself is evidence rather than decoration.
"""

from __future__ import annotations

import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest


SCRIPT_PATH = pathlib.Path(__file__).with_name("python-sdk-scope-check.py")
REPOSITORY_ROOT = SCRIPT_PATH.parent.parent


class PythonSdkScopeCheckTests(unittest.TestCase):

    def test_the_committed_sdk_passes(self):
        result = self.run_check(REPOSITORY_ROOT)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("PASS", result.stdout)

    def test_a_package_index_claim_fails(self):
        with self.staged_repository() as root:
            readme = root / "clients" / "python" / "thaap_sdk" / "README.md"
            readme.write_text(readme.read_text() + "\nInstall from PyPI.\n")
            result = self.run_check(root)
        self.assertEqual(1, result.returncode)
        self.assertIn("package-index claim", result.stdout)

    def test_a_production_readiness_claim_fails(self):
        with self.staged_repository() as root:
            readme = root / "clients" / "python" / "thaap_sdk" / "README.md"
            readme.write_text(readme.read_text() + "\nThis client is production-ready.\n")
            result = self.run_check(root)
        self.assertEqual(1, result.returncode)
        self.assertIn("support and audit obligation", result.stdout)

    def test_an_example_contacting_a_remote_host_fails(self):
        with self.staged_repository() as root:
            example = root / "clients" / "python" / "thaap_sdk" / "examples" / "remote.py"
            example.write_text('BASE_URL = "https://api.thaap.io"\n')
            result = self.run_check(root)
        self.assertEqual(1, result.returncode)
        self.assertIn("non-loopback host 'api.thaap.io'", result.stdout)

    def test_a_hostile_url_with_userinfo_resolves_to_its_real_host(self):
        """http://loopback@evil.test targets evil.test, not the reassuring prefix."""
        with self.staged_repository() as root:
            example = root / "clients" / "python" / "thaap_sdk" / "examples" / "userinfo.py"
            example.write_text('BASE_URL = "http://127.0.0.1@evil.test"\n')
            result = self.run_check(root)
        self.assertEqual(1, result.returncode)
        self.assertIn("non-loopback host 'evil.test'", result.stdout)

    def test_removing_the_loopback_guard_fails(self):
        with self.staged_repository() as root:
            client = root / "clients" / "python" / "thaap_sdk" / "thaap_sdk" / "client.py"
            client.write_text(
                client.read_text().replace(
                    "Plain HTTP is allowed only for the loopback sandbox", "relaxed"
                )
            )
            result = self.run_check(root)
        self.assertEqual(1, result.returncode)
        self.assertIn("loopback enforcement", result.stdout)

    def test_an_unbounded_supported_runtime_range_fails(self):
        with self.staged_repository() as root:
            manifest = root / "clients" / "python" / "thaap_sdk" / "pyproject.toml"
            manifest.write_text(
                manifest.read_text().replace(
                    'requires-python = ">=3.11,<3.15"', 'requires-python = ">=3.11"'
                )
            )
            result = self.run_check(root)
        self.assertEqual(1, result.returncode)
        self.assertIn("no upper bound", result.stdout)

    # -- helpers ----------------------------------------------------------

    def run_check(self, root: pathlib.Path) -> subprocess.CompletedProcess:
        return subprocess.run(
            [sys.executable, str(root / "scripts" / "python-sdk-scope-check.py")],
            cwd=root,
            capture_output=True,
            text=True,
            check=False)

    class _StagedRepository:
        def __init__(self):
            self.temporary = tempfile.TemporaryDirectory()

        def __enter__(self) -> pathlib.Path:
            root = pathlib.Path(self.temporary.name) / "repository"
            (root / "scripts").mkdir(parents=True)
            shutil.copy2(SCRIPT_PATH, root / "scripts" / SCRIPT_PATH.name)
            shutil.copytree(
                REPOSITORY_ROOT / "clients",
                root / "clients",
                ignore=shutil.ignore_patterns(
                    ".venv", "__pycache__", ".pytest_cache", "*.egg-info"))
            docs = root / "docs"
            docs.mkdir()
            shutil.copy2(
                REPOSITORY_ROOT / "docs" / "DEVELOPER_SANDBOX_GUIDE.md",
                docs / "DEVELOPER_SANDBOX_GUIDE.md")
            return root

        def __exit__(self, *_exception: object) -> None:
            self.temporary.cleanup()

    def staged_repository(self) -> "PythonSdkScopeCheckTests._StagedRepository":
        return self._StagedRepository()


if __name__ == "__main__":
    unittest.main()
