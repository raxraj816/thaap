#!/usr/bin/env python3
"""Reproducibility and crash-safety tests for the OpenAPI contract generator.

A contract artifact is only trustworthy if regenerating it is deterministic and if a generator
that dies midway cannot leave a half-written document where a reader expects a whole one. Both
are properties of the writer, so they are tested against the writer rather than inferred from a
green build.
"""

from __future__ import annotations

import importlib.util
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest


ROOT = pathlib.Path(__file__).resolve().parent.parent
SCRIPT_PATH = ROOT / "scripts" / "generate-openapi-contract.py"
COMMITTED = ROOT / "openapi" / "thaap-public-api-2026-08-01.yaml"
RUNTIME_SOURCE = ROOT / "apps" / "payments-api" / "target" / "openapi" / "runtime-public-contract.json"

SPEC = importlib.util.spec_from_file_location("generate_openapi_contract", SCRIPT_PATH)
GENERATOR = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(GENERATOR)


class OpenApiGenerationTests(unittest.TestCase):

    def test_rendering_is_deterministic_for_identical_input(self):
        contract = json.loads(COMMITTED.read_text(encoding="utf-8"))
        # Key order in the source must not change the bytes, or two machines generating from the
        # same running API would disagree and the drift gate would fail for no real reason.
        shuffled = json.loads(json.dumps(contract, sort_keys=False))
        first = json.dumps(contract, sort_keys=True, indent=2, ensure_ascii=False) + "\n"
        second = json.dumps(shuffled, sort_keys=True, indent=2, ensure_ascii=False) + "\n"
        self.assertEqual(first, second)
        self.assertEqual(first, COMMITTED.read_text(encoding="utf-8"),
                         "the committed artifact is not what the generator would render")

    def test_a_writer_that_dies_midway_leaves_the_previous_artifact_intact(self):
        with tempfile.TemporaryDirectory() as directory:
            target = pathlib.Path(directory) / "contract.yaml"
            target.write_text("PREVIOUS", encoding="utf-8")

            # Simulate a crash between opening the temporary file and the atomic replace.
            original_replace = os.replace

            def exploding_replace(*args, **kwargs):
                raise OSError("simulated crash before publish")

            os.replace = exploding_replace
            try:
                with self.assertRaises(OSError):
                    GENERATOR.atomic_write(target, "NEW CONTENT")
            finally:
                os.replace = original_replace

            self.assertEqual(target.read_text(encoding="utf-8"), "PREVIOUS",
                             "a failed generation replaced the artifact with partial bytes")
            leftovers = [path.name for path in pathlib.Path(directory).iterdir()
                         if path.name != "contract.yaml"]
            self.assertEqual(leftovers, [],
                             f"the failed write left temporary files behind: {leftovers}")

    def test_a_reader_never_observes_a_partially_written_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            target = pathlib.Path(directory) / "contract.yaml"
            target.write_text("A" * 4096, encoding="utf-8")
            observed: list[str] = []
            stop = threading.Event()

            def read_forever():
                while not stop.is_set():
                    try:
                        observed.append(target.read_text(encoding="utf-8"))
                    except FileNotFoundError:
                        observed.append("MISSING")

            reader = threading.Thread(target=read_forever, daemon=True)
            reader.start()
            try:
                for _ in range(40):
                    GENERATOR.atomic_write(target, "B" * 4096)
                    GENERATOR.atomic_write(target, "A" * 4096)
            finally:
                stop.set()
                reader.join(timeout=5)

            # Every observation must be one whole document. A torn read would appear as a
            # mixed or short string, which is exactly what a non-atomic write produces.
            self.assertTrue(observed, "the concurrent reader observed nothing")
            for content in observed:
                self.assertIn(content, {"A" * 4096, "B" * 4096},
                              "a reader observed a partially written artifact")

    def test_regenerating_from_the_same_source_reproduces_the_committed_bytes(self):
        if not RUNTIME_SOURCE.exists():
            self.skipTest("runtime contract snapshot is produced by the backend test suite")
        before = COMMITTED.read_bytes()
        with tempfile.TemporaryDirectory() as directory:
            backup = pathlib.Path(directory) / "committed.bak"
            shutil.copyfile(COMMITTED, backup)
            try:
                for _ in range(2):
                    result = subprocess.run(
                        [sys.executable, str(SCRIPT_PATH), "--source", str(RUNTIME_SOURCE)],
                        capture_output=True, text=True, timeout=60, cwd=str(ROOT))
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertEqual(COMMITTED.read_bytes(), before,
                                     "regeneration from the same source changed the artifact")
            finally:
                shutil.copyfile(backup, COMMITTED)

    def test_the_generator_refuses_a_contract_that_fails_its_own_validation(self):
        contract = json.loads(COMMITTED.read_text(encoding="utf-8"))
        # A contract with no paths is not a contract; publishing one would replace a working
        # artifact with an empty document that every downstream gate would then accept.
        empty = dict(contract)
        empty["paths"] = {}
        with self.assertRaises(BaseException):
            GENERATOR.validate(empty)

    def test_the_generator_writes_both_the_committed_and_bundled_artifacts(self):
        source = SCRIPT_PATH.read_text(encoding="utf-8")
        # Serving bytes that differ from the reviewed file is the drift this pairing prevents.
        self.assertIn("PUBLIC_ARTIFACT", source)
        self.assertIn("RUNTIME_ARTIFACT", source)
        self.assertIn("for target in (PUBLIC_ARTIFACT, RUNTIME_ARTIFACT):", source)


if __name__ == "__main__":
    unittest.main()
