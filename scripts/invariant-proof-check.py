#!/usr/bin/env python3
"""Validate chapter invariant-proof matrices before completion or handoff."""

from __future__ import annotations

import os
import pathlib
import re
import sys


ROOT = pathlib.Path(__file__).resolve().parents[1]
DEFAULT_PATTERN = "docs/reviews/*_INVARIANT_PROOF_MATRIX.md"
ENTRY_HEADING = re.compile(r"^## ([A-Z][A-Z0-9]*(?:-[A-Z0-9]+){2,})$", re.MULTILINE)
FIELD_LINE = re.compile(r"^- ([A-Za-z ]+):\s*(.+)$", re.MULTILINE)
REQUIRED_METADATA = {
    "Chapter",
    "Matrix provenance",
    "Scope status",
    "Review status",
    "Promise freeze",
    "Failure inventory",
    "Traceability review",
    "Fresh hostile review",
    "Boundary family review",
    "Custom infrastructure review",
    "Escaped review assessment",
}
REQUIRED_ENTRY_FIELDS = {
    "Lens",
    "Invariant",
    "Failure sequence",
    "Implementation",
    "Proof",
    "Adversarial evidence",
    "Status",
}
# `PASS` means the invariant is held and proven. `PARTIAL` means it is held on the
# ordinary path and its adversarial proof is unbuilt — the state a matrix previously had
# no way to express, which is why rows sat at `PASS` naming suites nobody had written
# (`ER-0099`). A `PARTIAL` row must name the debt that owns the missing proof, and a
# phase cannot close while one exists: honest at chapter closure, blocking at phase
# closure.
ALLOWED_ENTRY_STATUSES = {"PASS", "PARTIAL"}
REQUIRED_LENSES = {
    "PAYMENT_FRAUD",
    "TENANT_ISOLATION",
    "SECURITY_PRIVACY",
    "TRANSACTION_CONCURRENCY",
    "DATA_ML_LINEAGE",
    "API_PRODUCT",
    "OBSERVABILITY",
    "OPERATIONS_RESILIENCE",
}
PLACEHOLDER = re.compile(r"\b(TBD|TODO|FIXME|PLACEHOLDER)\b", re.IGNORECASE)
CHAPTER_COORDINATE = re.compile(r"\bChapter\s+(\d+)([A-Z]?)\b", re.IGNORECASE)

# A `Proof` field names the artifacts that hold the invariant. Anything in backticks
# that is shaped like a test class or an executable file is treated as a claim that
# the artifact exists, because that is how a reader uses the field: they run it.
ARTIFACT_REFERENCE = re.compile(r"`([^`]+)`")
DEBT_REFERENCE = re.compile(r"\bTD-\d{4}\b")
REJECT_PARTIAL_FLAG = "--reject-partial"
CLASS_ARTIFACT = re.compile(r"^[A-Z][A-Za-z0-9]*(?:Tests|Test)$")
FILE_ARTIFACT = re.compile(r"^[A-Za-z0-9_./-]+\.(?:py|sh|ts|tsx|java|kt)$")
SOURCE_SUFFIXES = (".java", ".kt", ".ts", ".tsx", ".py", ".sh")
IGNORED_DIRECTORIES = {
    ".git", ".m2", "__pycache__", "build", "dist", "node_modules", "target", "venv",
    ".venv",
}


def fields(text: str) -> dict[str, str]:
    return {name: value.strip() for name, value in FIELD_LINE.findall(text)}


def repository_artifacts(root: pathlib.Path) -> set[str]:
    """Every source basename in the repository, plus its extension-free stem.

    The stem is what lets a `Proof` field name a test class the way an engineer says
    it out loud, while the basename resolves a file named with its extension.
    """
    names: set[str] = set()
    # `os.walk` rather than `Path.walk`, which needs Python 3.12; the repository floor
    # declared for the supported SDK is 3.11.
    for _, subdirectories, files in os.walk(root):
        subdirectories[:] = [
            name for name in subdirectories if name not in IGNORED_DIRECTORIES]
        for name in files:
            if name.endswith(SOURCE_SUFFIXES):
                names.add(name)
                names.add(name.rsplit(".", 1)[0])
    return names


def unresolved_artifacts(proof: str, root: pathlib.Path, names: set[str]) -> list[str]:
    """Artifact names a `Proof` field claims that nothing in the repository provides.

    `ER-0096` and `ER-0098` both closed a row on a gate that was never executed. The
    weaker version of the same failure is a row that closes on an artifact that was
    never written: the planned name survives into a `PASS` row, reads as evidence in
    every summary, and resolves to nothing when an auditor goes looking for it.

    A row that cites a `TD-NNNN` coordinate is exempt, because the gap has then been
    moved into the technical debt register where it has an owner, a trigger and done
    criteria. The exemption is deliberately not silent: it requires an author to name
    the debt, and `technical-debt-check.py` holds that entry to the register's rules.
    """
    if DEBT_REFERENCE.search(proof):
        return []
    unresolved: list[str] = []
    for reference in ARTIFACT_REFERENCE.findall(proof):
        candidate = reference.strip()
        if CLASS_ARTIFACT.match(candidate):
            if candidate not in names:
                unresolved.append(candidate)
            continue
        if not FILE_ARTIFACT.match(candidate):
            continue
        # A reference may be repository-relative, or relative to a package root that
        # the matrix does not spell out; the basename settles the second case.
        if (root / candidate).is_file():
            continue
        if candidate.rsplit("/", 1)[-1] in names:
            continue
        unresolved.append(candidate)
    return unresolved


def validate_matrix(
        path: pathlib.Path,
        text: str,
        artifacts: set[str] | None = None,
        root: pathlib.Path = ROOT,
        reject_partial: bool = False) -> list[str]:
    errors: list[str] = []
    if artifacts is None:
        artifacts = repository_artifacts(root)
    matches = list(ENTRY_HEADING.finditer(text))
    metadata_text = text[:matches[0].start()] if matches else text
    metadata = fields(metadata_text)

    for name in REQUIRED_METADATA:
        if not metadata.get(name):
            errors.append(f"{path}: missing metadata field '{name}'")
    provenance = metadata.get("Matrix provenance")
    if provenance not in {"PRE_IMPLEMENTATION", "RETROACTIVE_RECOVERY"}:
        errors.append(
            f"{path}: Matrix provenance must be PRE_IMPLEMENTATION or RETROACTIVE_RECOVERY")
    chapter_match = CHAPTER_COORDINATE.search(metadata.get("Chapter", ""))
    if not chapter_match:
        errors.append(f"{path}: Chapter metadata must contain a numeric chapter")
    elif provenance == "RETROACTIVE_RECOVERY" and chapter_match.group(1) != "19":
        errors.append(
            f"{path}: RETROACTIVE_RECOVERY is allowed only for the documented Chapter 19 recovery")
    if metadata.get("Scope status") != "FROZEN":
        errors.append(f"{path}: Scope status must be FROZEN")
    if metadata.get("Review status") != "VERIFIED":
        errors.append(f"{path}: Review status must be VERIFIED")
    if metadata.get("Promise freeze") != "CONFIRMED":
        errors.append(f"{path}: Promise freeze must be CONFIRMED")
    if metadata.get("Failure inventory") != "COMPLETE":
        errors.append(f"{path}: Failure inventory must be COMPLETE")
    if metadata.get("Traceability review") != "PASS":
        errors.append(f"{path}: Traceability review must be PASS")
    if metadata.get("Fresh hostile review") != "PASS":
        errors.append(f"{path}: Fresh hostile review must be PASS")
    if metadata.get("Boundary family review") != "PASS":
        errors.append(f"{path}: Boundary family review must be PASS")
    if metadata.get("Custom infrastructure review") not in {"PASS", "NOT_APPLICABLE"}:
        errors.append(
            f"{path}: Custom infrastructure review must be PASS or NOT_APPLICABLE")
    if metadata.get("Escaped review assessment") != "COMPLETE":
        errors.append(f"{path}: Escaped review assessment must be COMPLETE")
    if PLACEHOLDER.search(metadata_text):
        errors.append(f"{path}: metadata contains an unresolved placeholder")
    if not matches:
        errors.append(f"{path}: no invariant entries found")
        return errors

    seen_ids: set[str] = set()
    covered_lenses: set[str] = set()
    for index, match in enumerate(matches):
        entry_id = match.group(1)
        end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        entry_text = text[match.end():end]
        entry = fields(entry_text)
        if entry_id in seen_ids:
            errors.append(f"{path}: duplicate invariant ID {entry_id}")
        seen_ids.add(entry_id)
        for name in REQUIRED_ENTRY_FIELDS:
            if not entry.get(name):
                errors.append(f"{path}: {entry_id} missing field '{name}'")
        lens = entry.get("Lens")
        if lens:
            covered_lenses.add(lens)
            if lens not in REQUIRED_LENSES:
                errors.append(f"{path}: {entry_id} uses unknown lens '{lens}'")
        status = entry.get("Status")
        if status not in ALLOWED_ENTRY_STATUSES:
            errors.append(
                f"{path}: {entry_id} Status must be one of "
                f"{', '.join(sorted(ALLOWED_ENTRY_STATUSES))}")
        elif status == "PARTIAL":
            debt = DEBT_REFERENCE.search(entry_text)
            if not debt:
                errors.append(
                    f"{path}: {entry_id} is PARTIAL and must cite the TD-NNNN that owns "
                    "the unbuilt proof")
            elif reject_partial:
                errors.append(
                    f"{path}: {entry_id} is PARTIAL ({debt.group(0)}); a phase cannot "
                    "close on partial proof")
        if PLACEHOLDER.search(entry_text):
            errors.append(f"{path}: {entry_id} contains an unresolved placeholder")
        for artifact in unresolved_artifacts(entry.get("Proof", ""), root, artifacts):
            errors.append(
                f"{path}: {entry_id} Proof names '{artifact}', which does not exist")

    missing_lenses = sorted(REQUIRED_LENSES - covered_lenses)
    if missing_lenses:
        errors.append(f"{path}: missing required lenses {', '.join(missing_lenses)}")
    return errors


def matrix_paths(arguments: list[str]) -> list[pathlib.Path]:
    paths = [argument for argument in arguments if argument != REJECT_PARTIAL_FLAG]
    if paths:
        return [pathlib.Path(argument).resolve() for argument in paths]
    return sorted(ROOT.glob(DEFAULT_PATTERN))


def main(arguments: list[str]) -> int:
    reject_partial = REJECT_PARTIAL_FLAG in arguments
    paths = matrix_paths(arguments)
    if not paths:
        print(f"No invariant-proof matrices found at {DEFAULT_PATTERN}", file=sys.stderr)
        return 1

    errors: list[str] = []
    all_ids: dict[str, pathlib.Path] = {}
    artifacts = repository_artifacts(ROOT)
    for path in paths:
        if not path.is_file():
            errors.append(f"{path}: matrix file does not exist")
            continue
        text = path.read_text(encoding="utf-8")
        errors.extend(
            validate_matrix(path, text, artifacts, reject_partial=reject_partial))
        for entry_id in ENTRY_HEADING.findall(text):
            if entry_id in all_ids:
                errors.append(
                    f"{path}: invariant ID {entry_id} is already used in {all_ids[entry_id]}")
            all_ids[entry_id] = path

    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        return 1
    print(f"Invariant proof check passed: {len(paths)} matrix file(s), {len(all_ids)} invariants")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
