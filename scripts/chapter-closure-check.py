#!/usr/bin/env python3
"""Validate a chapter closure dossier against repository source-of-truth state."""

from __future__ import annotations

import pathlib
import re
import sys


ROOT = pathlib.Path(__file__).resolve().parents[1]
FIELD_LINE = re.compile(r"^- ([A-Za-z ]+):\s*(.+)$", re.MULTILINE)
HEADING = re.compile(r"^## (.+)$", re.MULTILINE)
ER_HEADING = re.compile(r"^## (ER-\d{4})$", re.MULTILINE)
REQUIRED_METADATA = {
    "Chapter",
    "Chapter number",
    "Status",
    "Invariant matrix",
    "Matrix provenance",
    "Promise freeze",
    "Failure inventory",
    "Traceability review",
    "Hostile review",
    "Boundary family review",
    "Custom infrastructure review",
    "Escaped review assessment",
    "Merge recommendation",
}
REQUIRED_SECTIONS = {
    "Observable promises",
    "Failure inventory",
    "Boundary family evidence",
    "Enforcement boundaries",
    "Exact proof",
    "Migration evidence",
    "Demo evidence",
    "Operational signals",
    "Known debt",
    "Custom infrastructure decision",
    "Escaped-review assessment",
    "Source-of-truth reconciliation",
    "Merge recommendation",
}
PLACEHOLDER = re.compile(r"\b(TBD|TODO|FIXME|PLACEHOLDER)\b", re.IGNORECASE)


def fields(text: str) -> dict[str, str]:
    return {name: value.strip() for name, value in FIELD_LINE.findall(text)}


def escaped_entries(text: str) -> list[tuple[str, dict[str, str]]]:
    matches = list(ER_HEADING.finditer(text))
    entries: list[tuple[str, dict[str, str]]] = []
    for index, match in enumerate(matches):
        end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        entries.append((match.group(1), fields(text[match.end():end])))
    return entries


def resolve_repo_path(value: str) -> pathlib.Path:
    path = pathlib.Path(value)
    return path.resolve() if path.is_absolute() else (ROOT / path).resolve()


def chapter_block(master_plan: str, chapter_number: str) -> str | None:
    start_pattern = re.compile(
        rf"(?m)^\d+\.\s+Chapter\s+{re.escape(chapter_number)}:")
    start = start_pattern.search(master_plan)
    if not start:
        return None
    next_chapter = re.compile(r"(?m)^\d+\.\s+Chapter\s+\d+:").search(
        master_plan, start.end())
    end = next_chapter.start() if next_chapter else len(master_plan)
    return master_plan[start.start():end]


def validate_closure(
        dossier_path: pathlib.Path,
        dossier_text: str,
        matrix_path: pathlib.Path,
        matrix_text: str,
        escaped_register_text: str,
        readme_text: str,
        master_plan_text: str) -> list[str]:
    errors: list[str] = []
    metadata = fields(dossier_text.split("##", 1)[0])
    matrix_metadata = fields(matrix_text.split("##", 1)[0])

    for name in REQUIRED_METADATA:
        if not metadata.get(name):
            errors.append(f"{dossier_path}: missing metadata field '{name}'")
    sections = set(HEADING.findall(dossier_text))
    for section in sorted(REQUIRED_SECTIONS - sections):
        errors.append(f"{dossier_path}: missing section '## {section}'")

    if metadata.get("Status") != "READY_TO_MERGE":
        errors.append(f"{dossier_path}: Status must be READY_TO_MERGE")
    if metadata.get("Promise freeze") != "CONFIRMED":
        errors.append(f"{dossier_path}: Promise freeze must be CONFIRMED")
    if metadata.get("Failure inventory") != "COMPLETE":
        errors.append(f"{dossier_path}: Failure inventory must be COMPLETE")
    if metadata.get("Traceability review") != "PASS":
        errors.append(f"{dossier_path}: Traceability review must be PASS")
    if metadata.get("Hostile review") != "PASS":
        errors.append(f"{dossier_path}: Hostile review must be PASS")
    if metadata.get("Boundary family review") != "PASS":
        errors.append(f"{dossier_path}: Boundary family review must be PASS")
    if metadata.get("Custom infrastructure review") not in {"PASS", "NOT_APPLICABLE"}:
        errors.append(
            f"{dossier_path}: Custom infrastructure review must be PASS or NOT_APPLICABLE")
    if metadata.get("Escaped review assessment") != "COMPLETE":
        errors.append(
            f"{dossier_path}: Escaped review assessment must be COMPLETE")
    if metadata.get("Merge recommendation") != "MERGE":
        errors.append(f"{dossier_path}: Merge recommendation must be MERGE")

    declared_matrix = metadata.get("Invariant matrix")
    if declared_matrix and resolve_repo_path(declared_matrix) != matrix_path.resolve():
        errors.append(
            f"{dossier_path}: declared Invariant matrix does not match the checked matrix")
    if metadata.get("Matrix provenance") != matrix_metadata.get("Matrix provenance"):
        errors.append(
            f"{dossier_path}: Matrix provenance disagrees with the invariant matrix")
    if metadata.get("Boundary family review") != matrix_metadata.get("Boundary family review"):
        errors.append(
            f"{dossier_path}: Boundary family review disagrees with the invariant matrix")
    if metadata.get("Chapter") != matrix_metadata.get("Chapter"):
        errors.append(f"{dossier_path}: Chapter disagrees with the invariant matrix")

    chapter_number = metadata.get("Chapter number", "")
    if not chapter_number.isdigit():
        errors.append(f"{dossier_path}: Chapter number must be numeric")
    elif f"Chapter {chapter_number}" not in metadata.get("Chapter", ""):
        errors.append(f"{dossier_path}: Chapter number disagrees with Chapter metadata")

    if PLACEHOLDER.search(dossier_text):
        errors.append(f"{dossier_path}: contains an unresolved placeholder")

    if metadata.get("Custom infrastructure review") == "PASS":
        adr_ids = set(re.findall(r"\bADR-(\d{4})\b", dossier_text))
        if not adr_ids:
            errors.append(
                f"{dossier_path}: custom infrastructure PASS requires an accepted ADR")
        for adr_id in sorted(adr_ids):
            candidates = list((ROOT / "docs" / "adr").glob(f"{adr_id}-*.md"))
            if len(candidates) != 1:
                errors.append(
                    f"{dossier_path}: ADR-{adr_id} does not resolve to one ADR file")
                continue
            adr_text = candidates[0].read_text(encoding="utf-8")
            if "Status: Accepted" not in adr_text:
                errors.append(f"{candidates[0]}: custom infrastructure ADR is not Accepted")
            if "| Boundary |" not in adr_text:
                errors.append(f"{candidates[0]}: custom infrastructure ADR lacks a boundary table")

    if chapter_number:
        for entry_id, entry in escaped_entries(escaped_register_text):
            if entry.get("Chapter") != chapter_number or entry.get("Status") == "RESOLVED":
                continue
            severity = entry.get("Severity")
            frozen = entry.get("Frozen promise affected")
            if severity in {"P0", "P1"} or (severity == "P2" and frozen == "YES"):
                errors.append(
                    f"{dossier_path}: blocking escaped finding {entry_id} is "
                    f"{entry.get('Status')} ({severity}, frozen promise {frozen})")

        complete_pattern = re.compile(
            rf"\bChapter\s+{re.escape(chapter_number)}\s+is\s+complete\b",
            re.IGNORECASE)
        if not complete_pattern.search(readme_text):
            errors.append(
                f"{dossier_path}: README.md does not mark Chapter {chapter_number} complete")
        block = chapter_block(master_plan_text, chapter_number)
        if block is None:
            errors.append(
                f"{dossier_path}: implementation master plan has no Chapter {chapter_number} block")
        elif not re.search(r"(?m)^\s+Status:\s*complete\b", block, re.IGNORECASE):
            errors.append(
                f"{dossier_path}: implementation master plan does not mark "
                f"Chapter {chapter_number} complete")
    return errors


def main(arguments: list[str]) -> int:
    if len(arguments) not in {2, 3}:
        print(
            "Usage: scripts/chapter-closure-check.py MATRIX DOSSIER [ESCAPED_REGISTER]",
            file=sys.stderr)
        return 2
    matrix_path = pathlib.Path(arguments[0]).resolve()
    dossier_path = pathlib.Path(arguments[1]).resolve()
    register_path = (
        pathlib.Path(arguments[2]).resolve()
        if len(arguments) == 3
        else ROOT / "docs" / "ESCAPED_REVIEW_REGISTER.md")
    paths = (matrix_path, dossier_path, register_path)
    missing = [str(path) for path in paths if not path.is_file()]
    if missing:
        for path in missing:
            print(f"{path}: required closure input does not exist", file=sys.stderr)
        return 1

    errors = validate_closure(
        dossier_path,
        dossier_path.read_text(encoding="utf-8"),
        matrix_path,
        matrix_path.read_text(encoding="utf-8"),
        register_path.read_text(encoding="utf-8"),
        (ROOT / "README.md").read_text(encoding="utf-8"),
        (ROOT / "docs" / "IMPLEMENTATION_MASTER_PLAN.md").read_text(encoding="utf-8"))
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        return 1
    chapter = fields(
        dossier_path.read_text(encoding="utf-8").split("##", 1)[0])["Chapter"]
    print(f"Chapter closure check passed: {chapter}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
