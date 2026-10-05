#!/usr/bin/env python3
"""Validate THAAP's canonical technical and implementation debt register."""

from __future__ import annotations

import argparse
import datetime as dt
import pathlib
import re
import sys
from dataclasses import dataclass
from review_calendar import review_today


DEFAULT_REGISTER = pathlib.Path("docs/TECHNICAL_DEBT_REGISTER.md")
DEFAULT_MASTER_PLAN = pathlib.Path("docs/IMPLEMENTATION_MASTER_PLAN.md")
ENTRY_HEADER = re.compile(r"^## (TD-[0-9]{4}): (.+)$", re.MULTILINE)
FIELD = re.compile(r"^- ([^:]+): (.+)$")
DEBT_REFERENCE = re.compile(r"\bTD-[0-9]{4}\b")
REQUIRED_FIELDS = (
    "Status",
    "Debt type",
    "Priority",
    "Introduced",
    "Origin",
    "Resolution target",
    "Primary DRI",
    "Supporting owners",
    "Risk / invariant",
    "Deferral reason",
    "Trigger / deadline",
    "Done when",
    "Resolution evidence",
    "Last reviewed",
)
ALLOWED_STATUSES = {"OPEN", "IN_PROGRESS", "RESOLVED"}
ALLOWED_DEBT_TYPES = {
    "TECHNICAL_DEBT",
    "IMPLEMENTATION_DEBT",
    "SECURITY_DEBT",
    "DATA_ML_DEBT",
    "OPERATIONS_DEBT",
}
ALLOWED_PRIORITIES = {"P1", "P2", "P3"}
VAGUE_ASSIGNMENTS = re.compile(r"\b(TBD|UNKNOWN|SOMEDAY|LATER|FUTURE PHASE|FUTURE CHAPTER)\b", re.IGNORECASE)
MAX_OPEN_DEBT_REVIEW_AGE_DAYS = 35


@dataclass(frozen=True)
class DebtEntry:
    debt_id: str
    title: str
    fields: dict[str, str]


def parse_register(register_text: str) -> tuple[list[DebtEntry], list[str]]:
    errors: list[str] = []
    headers = list(ENTRY_HEADER.finditer(register_text))
    entries: list[DebtEntry] = []
    if not headers:
        return [], ["Debt register contains no TD-NNNN entries"]

    for index, header in enumerate(headers):
        section_end = headers[index + 1].start() if index + 1 < len(headers) else len(register_text)
        section = register_text[header.end():section_end]
        fields: dict[str, str] = {}
        for line in section.splitlines():
            field_match = FIELD.match(line)
            if field_match is None:
                continue
            field_name = field_match.group(1).strip()
            if field_name in fields:
                errors.append(f"{header.group(1)} repeats field '{field_name}'")
                continue
            fields[field_name] = field_match.group(2).strip()
        entries.append(DebtEntry(header.group(1), header.group(2).strip(), fields))
    return entries, errors


def parse_date(debt_id: str, field_name: str, value: str, errors: list[str]) -> dt.date | None:
    try:
        return dt.date.fromisoformat(value)
    except ValueError:
        errors.append(f"{debt_id} field '{field_name}' must use YYYY-MM-DD")
        return None


def validate_entries(entries: list[DebtEntry], master_plan_text: str,
                     as_of: dt.date | None = None) -> list[str]:
    errors: list[str] = []
    seen_ids: set[str] = set()
    register_ids = {entry.debt_id for entry in entries}
    review_date = as_of if as_of is not None else review_today()
    expected_ids = [f"TD-{index:04d}" for index in range(1, len(entries) + 1)]
    actual_ids = [entry.debt_id for entry in entries]
    if actual_ids != expected_ids:
        errors.append(f"Debt IDs must be unique, ordered, and gap-free: expected {expected_ids}, got {actual_ids}")

    for entry in entries:
        if entry.debt_id in seen_ids:
            errors.append(f"Duplicate debt ID: {entry.debt_id}")
        seen_ids.add(entry.debt_id)

        missing_fields = [field for field in REQUIRED_FIELDS if not entry.fields.get(field)]
        for field_name in missing_fields:
            errors.append(f"{entry.debt_id} is missing required field '{field_name}'")

        status = entry.fields.get("Status")
        debt_type = entry.fields.get("Debt type")
        priority = entry.fields.get("Priority")
        if status is not None and status not in ALLOWED_STATUSES:
            errors.append(f"{entry.debt_id} has invalid Status '{status}'")
        if debt_type is not None and debt_type not in ALLOWED_DEBT_TYPES:
            errors.append(f"{entry.debt_id} has invalid Debt type '{debt_type}'")
        if priority == "P0":
            errors.append(f"{entry.debt_id}: P0 work cannot be deferred as technical debt")
        elif priority is not None and priority not in ALLOWED_PRIORITIES:
            errors.append(f"{entry.debt_id} has invalid Priority '{priority}'")
        introduced = None
        last_reviewed = None
        if "Introduced" in entry.fields:
            introduced = parse_date(entry.debt_id, "Introduced", entry.fields["Introduced"], errors)
        if "Last reviewed" in entry.fields:
            last_reviewed = parse_date(entry.debt_id, "Last reviewed", entry.fields["Last reviewed"], errors)
        if introduced is not None and introduced > review_date:
            errors.append(f"{entry.debt_id} introduction date is in the future")
        if last_reviewed is not None and last_reviewed > review_date:
            errors.append(f"{entry.debt_id} last-review date is in the future")
        if status != "RESOLVED" and last_reviewed is not None \
                and (review_date - last_reviewed).days > MAX_OPEN_DEBT_REVIEW_AGE_DAYS:
            errors.append(
                    f"{entry.debt_id} has not been reviewed within "
                    f"{MAX_OPEN_DEBT_REVIEW_AGE_DAYS} days")

        for field_name in ("Resolution target", "Primary DRI", "Trigger / deadline", "Done when"):
            field_value = entry.fields.get(field_name)
            if field_value is not None and VAGUE_ASSIGNMENTS.search(field_value):
                errors.append(f"{entry.debt_id} field '{field_name}' contains a vague assignment")

        resolution_evidence = entry.fields.get("Resolution evidence")
        if status == "RESOLVED" and resolution_evidence is not None and resolution_evidence.lower() == "pending":
            errors.append(f"{entry.debt_id} is RESOLVED but has no resolution evidence")
        if status is not None and status != "RESOLVED" and resolution_evidence is not None \
                and resolution_evidence.lower() != "pending":
            errors.append(f"{entry.debt_id} is not RESOLVED but resolution evidence is not Pending")
        if entry.debt_id not in master_plan_text:
            errors.append(f"{entry.debt_id} is not referenced by the implementation master plan")

    master_plan_ids = set(DEBT_REFERENCE.findall(master_plan_text))
    for dangling_id in sorted(master_plan_ids - register_ids):
        errors.append(f"Implementation master plan references unregistered debt {dangling_id}")
    return errors


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Validate THAAP's technical debt register.")
    parser.add_argument("--register", type=pathlib.Path, default=DEFAULT_REGISTER)
    parser.add_argument("--master-plan", type=pathlib.Path, default=DEFAULT_MASTER_PLAN)
    parser.add_argument("--as-of", type=dt.date.fromisoformat, default=review_today(),
            help="Review date in YYYY-MM-DD format. Defaults to today in Asia/Kolkata.")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    if not args.register.is_file():
        print(f"Missing technical debt register: {args.register}", file=sys.stderr)
        return 1
    if not args.master_plan.is_file():
        print(f"Missing implementation master plan: {args.master_plan}", file=sys.stderr)
        return 1

    register_text = args.register.read_text(encoding="utf-8")
    master_plan_text = args.master_plan.read_text(encoding="utf-8")
    entries, parse_errors = parse_register(register_text)
    errors = parse_errors + validate_entries(entries, master_plan_text, args.as_of)
    if errors:
        print("Technical debt check failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1

    open_count = sum(entry.fields["Status"] != "RESOLVED" for entry in entries)
    resolved_count = len(entries) - open_count
    print(f"Technical debt check passed: {len(entries)} entries, {open_count} open, {resolved_count} resolved.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
