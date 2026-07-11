import importlib.util
import pathlib
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "slice_review_check", ROOT / "scripts" / "slice-review-check.py")
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def packet(status="NOT_READY", implementation="PENDING_COMMIT",
           review="PENDING", hostile="PENDING_LATER_COMMIT"):
    rows = "\n".join(
        f"| state {index} | event {index} | {index}s | result {index} | proof {index} |"
        for index in range(6)
    )
    return f"""# Packet

- Parent chapter: 21
- Review provenance: RETROACTIVE_RECOVERY
- Transition table timing: WRITTEN_BEFORE_FIX
- Product implementation checkpoint: {implementation}
- Independent implementation review: {review}
- Hostile review checkpoint: {hostile}
- Status: {status}

## Transition table

{MODULE.EXPECTED_HEADER}
| --- | --- | --- | --- | --- |
{rows}

## Mandatory closure questions

### What can change after this check and before use?

State can change.

### What makes a new lifecycle epoch different from a replay?

The durable epoch identifier.

## Primary deterministic proof

- forced interleaving
"""


class SliceReviewCheckTests(unittest.TestCase):

    def write(self, contents):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        path = pathlib.Path(temporary.name) / "packet.md"
        path.write_text(contents, encoding="utf-8")
        return path

    def test_structural_packet_passes_before_verification(self):
        self.assertEqual([], MODULE.validate(self.write(packet()), False))

    def test_requires_exact_transition_columns(self):
        path = self.write(packet().replace("Elapsed time", "Delay"))
        errors = MODULE.validate(path, False)
        self.assertTrue(any("transition table" in error for error in errors))

    def test_requires_both_closure_questions(self):
        path = self.write(packet().replace(
            "### What can change after this check and before use?", "### Timing"))
        errors = MODULE.validate(path, False)
        self.assertTrue(any("missing mandatory closure question" in error for error in errors))

    def test_rejects_aggregate_count_rhetoric(self):
        path = self.write(packet() + "\nThe 271 tests prove closure.\n")
        errors = MODULE.validate(path, False)
        self.assertTrue(any("aggregate test count" in error for error in errors))

    def test_verified_packet_requires_separate_commit_hashes(self):
        path = self.write(packet(status="VERIFIED", implementation="abcdef1",
                                 review="PASS", hostile="abcdef1"))
        errors = MODULE.validate(path, True)
        self.assertTrue(any("later commit" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
