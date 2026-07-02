#!/usr/bin/env python3
"""Validate chapter invariant-proof matrices before completion or handoff."""

from __future__ import annotations

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
CHAPTER_NUMBER = re.compile(r"\bChapter\s+(\d+)\b", re.IGNORECASE)


def fields(text: str) -> dict[str, str]:
    return {name: value.strip() for name, value in FIELD_LINE.findall(text)}


def validate_matrix(path: pathlib.Path, text: str) -> list[str]:
    errors: list[str] = []
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
    chapter_match = CHAPTER_NUMBER.search(metadata.get("Chapter", ""))
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
        if entry.get("Status") != "PASS":
            errors.append(f"{path}: {entry_id} Status must be PASS")
        if PLACEHOLDER.search(entry_text):
            errors.append(f"{path}: {entry_id} contains an unresolved placeholder")

    missing_lenses = sorted(REQUIRED_LENSES - covered_lenses)
    if missing_lenses:
        errors.append(f"{path}: missing required lenses {', '.join(missing_lenses)}")
    return errors


def matrix_paths(arguments: list[str]) -> list[pathlib.Path]:
    if arguments:
        return [pathlib.Path(argument).resolve() for argument in arguments]
    return sorted(ROOT.glob(DEFAULT_PATTERN))


def main(arguments: list[str]) -> int:
    paths = matrix_paths(arguments)
    if not paths:
        print(f"No invariant-proof matrices found at {DEFAULT_PATTERN}", file=sys.stderr)
        return 1

    errors: list[str] = []
    all_ids: dict[str, pathlib.Path] = {}
    for path in paths:
        if not path.is_file():
            errors.append(f"{path}: matrix file does not exist")
            continue
        text = path.read_text(encoding="utf-8")
        errors.extend(validate_matrix(path, text))
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
