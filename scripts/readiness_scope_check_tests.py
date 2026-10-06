"""Adversarial manifest and selector partitions for the current readiness boundary."""
import importlib.util
import json
import pathlib
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("readiness_scope", pathlib.Path(__file__).with_name("readiness-scope-check.py"))
CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECK)


class ReadinessScopeTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = pathlib.Path(self.directory.name).resolve()
        self.matrix = "docs/reviews/LOCAL_RELEASE_INVARIANT_PROOF_MATRIX.md"
        self.dossier = "docs/reviews/LOCAL_RELEASE_CLOSURE_DOSSIER.md"
        self.document = {"schemaVersion": 1, "learningDay": "2026-10-06",
                         "invariantMatrix": self.matrix, "closureDossier": self.dossier}
        for name in (self.matrix, self.dossier):
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("fixture\n")
        self.manifest = self.root / CHECK.MANIFEST
        self.save()

    def save(self):
        self.manifest.write_text(json.dumps(self.document))

    def resolve(self, matrix="", dossier="", day="2026-10-06"):
        return CHECK.resolve_scope(self.root, day, matrix, dossier)

    def test_no_selectors_resolve_current_pair_and_exact_selectors_pass(self):
        self.assertEqual((self.matrix, self.dossier), self.resolve())
        self.assertEqual((self.matrix, self.dossier), self.resolve(self.matrix, self.dossier))

    def test_stale_or_partial_selectors_cannot_replace_current_proof(self):
        for pair in [(self.matrix, ""), ("", self.dossier), ("old.md", "old-dossier.md"),
                     (self.matrix, "old.md"), (str(self.root / self.matrix), self.dossier)]:
            with self.subTest(pair=pair), self.assertRaises(CHECK.ScopeFailure):
                self.resolve(*pair)

    def test_missing_malformed_duplicate_key_and_non_object_manifest_fail(self):
        self.manifest.unlink()
        with self.assertRaises(CHECK.ScopeFailure):
            self.resolve()
        for content in ("{", "[]", "null", '"scope"', '{"schemaVersion":1,"schemaVersion":1}'):
            self.manifest.write_text(content)
            with self.subTest(content=content), self.assertRaises(CHECK.ScopeFailure):
                self.resolve()

    def test_missing_or_unexpected_fields_fail(self):
        original = dict(self.document)
        for key in original:
            self.document = {name: value for name, value in original.items() if name != key}
            self.save()
            with self.subTest(missing=key), self.assertRaises(CHECK.ScopeFailure):
                self.resolve()
        self.document = {**original, "skipClosure": True}
        self.save()
        with self.assertRaises(CHECK.ScopeFailure):
            self.resolve()

    def test_schema_types_and_calendar_date_partitions_fail_closed(self):
        for version in (True, False, 1.0, "1", 0, 2, None):
            self.document["schemaVersion"] = version
            self.save()
            with self.subTest(version=version), self.assertRaises(CHECK.ScopeFailure):
                self.resolve()
        self.document["schemaVersion"] = 1
        for day in ("2026-02-30", "2026-1-06", "２０２６-１０-０６", None, 20261006):
            self.document["learningDay"] = day
            self.save()
            with self.subTest(day=day), self.assertRaises(CHECK.ScopeFailure):
                self.resolve(day=day)

    def test_other_learning_day_cannot_use_current_scope(self):
        with self.assertRaisesRegex(CHECK.ScopeFailure, "learning day"):
            self.resolve(day="2026-08-09")

    def test_path_representation_and_missing_file_partitions_fail(self):
        for path in ("/tmp/outside.md", "docs/reviews/../LOCAL_RELEASE_INVARIANT_PROOF_MATRIX.md",
                     "docs/reviews/LOCAL_RELEASE_CLOSURE_DOSSIER.md", "docs/reviews/MISSING_INVARIANT_PROOF_MATRIX.md",
                     "docs\\reviews\\LOCAL_RELEASE_INVARIANT_PROOF_MATRIX.md", self.matrix + "\n",
                     "docs/reviews/a b_INVARIANT_PROOF_MATRIX.md", "docs/reviews/A\tB_INVARIANT_PROOF_MATRIX.md",
                     1, None):
            self.document["invariantMatrix"] = path
            self.save()
            with self.subTest(path=path), self.assertRaises(CHECK.ScopeFailure):
                self.resolve()

    def test_mixed_existing_review_coordinates_fail(self):
        other = "docs/reviews/OTHER_RELEASE_CLOSURE_DOSSIER.md"
        (self.root / other).write_text("fixture")
        self.document["closureDossier"] = other
        self.save()
        with self.assertRaisesRegex(CHECK.ScopeFailure, "same review coordinate"):
            self.resolve()

    def test_manifest_and_artifact_symlinks_cannot_change_identity(self):
        for selected in (self.manifest, self.root / self.matrix):
            original = selected.read_text()
            target = self.root / "indirection.md"
            target.write_text(original)
            selected.unlink()
            selected.symlink_to(target)
            with self.subTest(selected=selected), self.assertRaises(CHECK.ScopeFailure):
                self.resolve()
            selected.unlink()
            selected.write_text(original)


if __name__ == "__main__":
    unittest.main()
