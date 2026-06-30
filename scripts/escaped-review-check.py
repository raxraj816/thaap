#!/usr/bin/env python3
"""Validate THAAP's immutable escaped-review quality register."""

from __future__ import annotations

import datetime as dt
import pathlib
import re
import sys


ROOT = pathlib.Path(__file__).resolve().parents[1]
DEFAULT_REGISTER = ROOT / "docs" / "ESCAPED_REVIEW_REGISTER.md"
ENTRY_HEADING = re.compile(r"^## (ER-(\d{4}))$", re.MULTILINE)
FIELD_LINE = re.compile(r"^- ([A-Za-z ]+):\s*(.+)$", re.MULTILINE)
REQUIRED_FIELDS = {
    "Status",
    "Severity",
    "Chapter",
    "Category",
    "Frozen promise affected",
    "Date found",
    "Escaped gate",
    "Invariant",
    "Finding",
    "Why prior review missed it",
    "Resolution evidence",
    "Process correction",
    "Primary DRI",
    "Last reviewed",
}
ALLOWED_STATUS = {"OPEN", "IN_PROGRESS", "RESOLVED"}
ALLOWED_SEVERITY = {"P0", "P1", "P2"}
ALLOWED_CATEGORY = {
    "MISSING_PROMISE",
    "MISSING_FAILURE_SEQUENCE",
    "WEAK_PROOF",
    "IMPLEMENTATION_DEFECT",
    "DOCUMENTATION_OVERCLAIM",
}
PLACEHOLDER = re.compile(r"\b(TBD|TODO|FIXME|PLACEHOLDER)\b", re.IGNORECASE)
VAGUE_OWNER = re.compile(r"^(TBD|later|future|team|unassigned)$", re.IGNORECASE)


def fields(text: str) -> dict[str, str]:
    return {name: value.strip() for name, value in FIELD_LINE.findall(text)}


def parse_entries(text: str) -> list[tuple[str, dict[str, str], str]]:
    matches = list(ENTRY_HEADING.finditer(text))
    result: list[tuple[str, dict[str, str], str]] = []
    for index, match in enumerate(matches):
        end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        body = text[match.end():end]
        result.append((match.group(1), fields(body), body))
    return result


def valid_date(value: str) -> bool:
    try:
        dt.date.fromisoformat(value)
        return True
    except ValueError:
        return False


def validate_register(path: pathlib.Path, text: str) -> list[str]:
    errors: list[str] = []
    entries = parse_entries(text)
    if not entries:
        return [f"{path}: no ER-NNNN entries found"]

    expected_numbers = list(range(1, len(entries) + 1))
    actual_numbers = [int(entry_id.removeprefix("ER-")) for entry_id, _, _ in entries]
    if actual_numbers != expected_numbers:
        errors.append(
            f"{path}: escaped-review IDs must be ordered, gap-free, and immutable; "
            f"expected {expected_numbers}, found {actual_numbers}")

    for entry_id, entry, body in entries:
        for name in REQUIRED_FIELDS:
            if not entry.get(name):
                errors.append(f"{path}: {entry_id} missing field '{name}'")
        if entry.get("Status") not in ALLOWED_STATUS:
            errors.append(f"{path}: {entry_id} has invalid Status")
        if entry.get("Severity") not in ALLOWED_SEVERITY:
            errors.append(f"{path}: {entry_id} has invalid Severity")
        if entry.get("Category") not in ALLOWED_CATEGORY:
            errors.append(f"{path}: {entry_id} has invalid Category")
        if entry.get("Frozen promise affected") not in {"YES", "NO"}:
            errors.append(
                f"{path}: {entry_id} Frozen promise affected must be YES or NO")
        for date_field in ("Date found", "Last reviewed"):
            value = entry.get(date_field)
            if value and not valid_date(value):
                errors.append(
                    f"{path}: {entry_id} {date_field} must be YYYY-MM-DD")
        if VAGUE_OWNER.fullmatch(entry.get("Primary DRI", "")):
            errors.append(f"{path}: {entry_id} has a vague Primary DRI")
        if entry.get("Status") == "RESOLVED":
            evidence = entry.get("Resolution evidence", "")
            correction = entry.get("Process correction", "")
            if len(evidence) < 20 or len(correction) < 20:
                errors.append(
                    f"{path}: {entry_id} resolved entries require product evidence "
                    "and a process correction")
        if PLACEHOLDER.search(body):
            errors.append(f"{path}: {entry_id} contains an unresolved placeholder")
    return errors


def main(arguments: list[str]) -> int:
    if len(arguments) > 1:
        print("Usage: scripts/escaped-review-check.py [REGISTER]", file=sys.stderr)
        return 2
    path = pathlib.Path(arguments[0]).resolve() if arguments else DEFAULT_REGISTER
    if not path.is_file():
        print(f"{path}: escaped-review register does not exist", file=sys.stderr)
        return 1
    text = path.read_text(encoding="utf-8")
    errors = validate_register(path, text)
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        return 1
    print(f"Escaped review check passed: {len(parse_entries(text))} immutable finding(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
