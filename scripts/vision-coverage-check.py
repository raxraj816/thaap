#!/usr/bin/env python3
"""Validate THAAP's permanent product-vision coverage and roadmap ownership."""

from __future__ import annotations

import argparse
import datetime as dt
import pathlib
import re
import sys
from dataclasses import dataclass
from review_calendar import review_today


ROOT = pathlib.Path(__file__).resolve().parents[1]
DEFAULT_MATRIX = ROOT / "docs" / "THAAP_VISION_COVERAGE_MATRIX.md"
DEFAULT_MASTER_PLAN = ROOT / "docs" / "IMPLEMENTATION_MASTER_PLAN.md"
DEFAULT_VISION = ROOT / "docs" / "VISION.md"
ENTRY_HEADER = re.compile(r"^## (VP-[0-9]{3}): (.+)$", re.MULTILINE)
FIELD = re.compile(r"^- ([^:]+): (.+)$")
VISION_REFERENCE = re.compile(r"\bVP-[0-9]{3}\b")
REQUIRED_FIELDS = (
    "Status",
    "Vision promise",
    "Current evidence",
    "Remaining gap",
    "Owner phase",
    "Owner slice",
    "Primary DRI",
    "Trigger",
    "Done evidence",
    "Merchant / analyst demo",
    "Last reviewed",
)
ALLOWED_STATUSES = {
    "PROVEN_CURRENT_SCOPE",
    "PARTIAL_NEXT_OWNER",
    "PLANNED_OWNER_ASSIGNED",
    "INTENTIONALLY_EXCLUDED",
}
VAGUE_ASSIGNMENT = re.compile(
    r"\b(TBD|TODO|UNKNOWN|UNASSIGNED|SOMEDAY|EVENTUALLY|LATER|FUTURE PHASE|FUTURE CHAPTER)\b",
    re.IGNORECASE,
)
MAX_REVIEW_AGE_DAYS = 35
MINIMUM_REGISTERED_PROMISES = 23
REQUIRED_ROADMAP_MARKERS = (
    "### Phase 6: Decision Intelligence Workspace And Developer Product",
    "### Phase 7: Governed ML Baseline And Lifecycle",
    "### Phase 8: Network, Graph, Merchant, And Post-Authorization Intelligence",
    "### Phase 9: Agents, Production Trust, And Scale",
    "### Phase 10: Merchant Integration, Pilot, And Measured Validation",
)
REQUIRED_DESTINATION_MARKERS = (
    "THAAP's final destination is a real hosted merchant-facing fraud/risk platform",
    "this merchant-facing destination is confirmed",
    "the simulator is its safe proving ground",
    "### Horizon 4: Merchant Integration And Measured Validation",
)


@dataclass(frozen=True)
class VisionEntry:
    vision_id: str
    title: str
    fields: dict[str, str]


def parse_entries(matrix_text: str) -> tuple[list[VisionEntry], list[str]]:
    errors: list[str] = []
    headers = list(ENTRY_HEADER.finditer(matrix_text))
    if not headers:
        return [], ["Vision coverage matrix contains no VP-NNN entries"]

    entries: list[VisionEntry] = []
    for index, header in enumerate(headers):
        end = headers[index + 1].start() if index + 1 < len(headers) else len(matrix_text)
        body = matrix_text[header.end():end]
        fields: dict[str, str] = {}
        for line in body.splitlines():
            match = FIELD.match(line)
            if match is None:
                continue
            name = match.group(1).strip()
            if name in fields:
                errors.append(f"{header.group(1)} repeats field '{name}'")
                continue
            fields[name] = match.group(2).strip()
        entries.append(VisionEntry(header.group(1), header.group(2).strip(), fields))
    return entries, errors


def parse_date(vision_id: str, value: str, errors: list[str]) -> dt.date | None:
    try:
        return dt.date.fromisoformat(value)
    except ValueError:
        errors.append(f"{vision_id} field 'Last reviewed' must use YYYY-MM-DD")
        return None


def validate(entries: list[VisionEntry], matrix_text: str, master_plan_text: str,
             vision_text: str,
             as_of: dt.date | None = None,
             minimum_promises: int = MINIMUM_REGISTERED_PROMISES) -> list[str]:
    errors: list[str] = []
    review_date = as_of if as_of is not None else review_today()
    expected_ids = [f"VP-{number:03d}" for number in range(1, len(entries) + 1)]
    actual_ids = [entry.vision_id for entry in entries]
    if len(entries) < minimum_promises:
        errors.append(
            "Vision promises are immutable and cannot be deleted: "
            f"expected at least {minimum_promises}, found {len(entries)}")
    if actual_ids != expected_ids:
        errors.append(
            "Vision IDs must be unique, ordered, and gap-free: "
            f"expected {expected_ids}, got {actual_ids}")

    if "- Status: ACTIVE" not in matrix_text[:ENTRY_HEADER.search(matrix_text).start()]:
        errors.append("Vision coverage matrix must declare top-level Status: ACTIVE")

    matrix_ids = {entry.vision_id for entry in entries}
    for entry in entries:
        for field_name in REQUIRED_FIELDS:
            if not entry.fields.get(field_name):
                errors.append(f"{entry.vision_id} is missing required field '{field_name}'")

        status = entry.fields.get("Status")
        if status is not None and status not in ALLOWED_STATUSES:
            errors.append(f"{entry.vision_id} has invalid Status '{status}'")

        for field_name in ("Owner phase", "Owner slice", "Primary DRI", "Trigger", "Done evidence"):
            value = entry.fields.get(field_name)
            if value is not None and VAGUE_ASSIGNMENT.search(value):
                errors.append(
                    f"{entry.vision_id} field '{field_name}' contains a vague assignment")

        last_reviewed = None
        if "Last reviewed" in entry.fields:
            last_reviewed = parse_date(entry.vision_id, entry.fields["Last reviewed"], errors)
        if last_reviewed is not None:
            if last_reviewed > review_date:
                errors.append(f"{entry.vision_id} last-review date is in the future")
            elif (review_date - last_reviewed).days > MAX_REVIEW_AGE_DAYS:
                errors.append(
                    f"{entry.vision_id} has not been reviewed within "
                    f"{MAX_REVIEW_AGE_DAYS} days")

        for evidence_field in ("Current evidence", "Done evidence", "Merchant / analyst demo"):
            value = entry.fields.get(evidence_field, "")
            if len(value) < 30:
                errors.append(
                    f"{entry.vision_id} field '{evidence_field}' lacks concrete evidence")

        if entry.vision_id not in master_plan_text:
            errors.append(
                f"{entry.vision_id} is not referenced by the implementation master plan")

    master_ids = set(VISION_REFERENCE.findall(master_plan_text))
    for dangling_id in sorted(master_ids - matrix_ids):
        errors.append(
            f"Implementation master plan references unregistered vision promise {dangling_id}")

    for marker in REQUIRED_ROADMAP_MARKERS:
        if marker not in master_plan_text:
            errors.append(f"Implementation master plan is missing rebaselined roadmap marker '{marker}'")
    destination_sources = re.sub(
        r"\s+", " ", master_plan_text + "\n" + vision_text)
    for marker in REQUIRED_DESTINATION_MARKERS:
        if re.sub(r"\s+", " ", marker) not in destination_sources:
            errors.append(
                "Canonical product sources are missing merchant-platform "
                f"destination marker '{marker}'")
    return errors


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate THAAP's product-vision coverage and roadmap ownership.")
    parser.add_argument("--matrix", type=pathlib.Path, default=DEFAULT_MATRIX)
    parser.add_argument("--master-plan", type=pathlib.Path, default=DEFAULT_MASTER_PLAN)
    parser.add_argument("--vision", type=pathlib.Path, default=DEFAULT_VISION)
    parser.add_argument(
        "--as-of", type=dt.date.fromisoformat, default=review_today(),
        help="Review date in YYYY-MM-DD format. Defaults to today in Asia/Kolkata.")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    if not args.matrix.is_file():
        print(f"Missing vision coverage matrix: {args.matrix}", file=sys.stderr)
        return 1
    if not args.master_plan.is_file():
        print(f"Missing implementation master plan: {args.master_plan}", file=sys.stderr)
        return 1
    if not args.vision.is_file():
        print(f"Missing canonical product vision: {args.vision}", file=sys.stderr)
        return 1

    matrix_text = args.matrix.read_text(encoding="utf-8")
    master_plan_text = args.master_plan.read_text(encoding="utf-8")
    vision_text = args.vision.read_text(encoding="utf-8")
    entries, parse_errors = parse_entries(matrix_text)
    errors = parse_errors + validate(
        entries, matrix_text, master_plan_text, vision_text, args.as_of,
        MINIMUM_REGISTERED_PROMISES)
    if errors:
        print("Vision coverage check failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1

    counts: dict[str, int] = {status: 0 for status in ALLOWED_STATUSES}
    for entry in entries:
        counts[entry.fields["Status"]] += 1
    summary = ", ".join(
        f"{status.lower()}={counts[status]}" for status in sorted(counts))
    print(f"Vision coverage check passed: {len(entries)} promises, {summary}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
