#!/usr/bin/env python3
"""Validate independent Chapter 21 state-machine recovery packets."""

from __future__ import annotations

import argparse
import pathlib
import re
import subprocess
import sys


ROOT = pathlib.Path(__file__).resolve().parents[1]
DEFAULT_PATTERN = "docs/reviews/CHAPTER_21_SLICE_*.md"
FIELD = re.compile(r"^- ([^:]+):\s*(.+)$", re.MULTILINE)
COMMIT = re.compile(r"^[0-9a-f]{7,40}$")
AGGREGATE_RHETORIC = re.compile(
    r"\b\d+\s+(?:Java|backend|Python|repository|total)?\s*tests?\b",
    re.IGNORECASE,
)
EXPECTED_HEADER = (
    "| Initial state | Concurrent event | Elapsed time | "
    "Expected durable result | Exact proof boundary |"
)
REQUIRED_QUESTIONS = (
    "### What can change after this check and before use?",
    "### What makes a new lifecycle epoch different from a replay?",
)


def fields(text: str) -> dict[str, str]:
    return {name.strip(): value.strip() for name, value in FIELD.findall(text)}


def transition_rows(text: str) -> list[str]:
    header_index = text.find(EXPECTED_HEADER)
    if header_index < 0:
        return []
    lines = text[header_index:].splitlines()
    rows: list[str] = []
    for line in lines[2:]:
        if not line.startswith("|"):
            break
        rows.append(line)
    return rows


def is_ancestor(older: str, newer: str) -> bool:
    result = subprocess.run(
        ["git", "merge-base", "--is-ancestor", older, newer],
        cwd=ROOT,
        check=False,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    return result.returncode == 0


def validate(path: pathlib.Path, require_verified: bool) -> list[str]:
    text = path.read_text(encoding="utf-8")
    metadata = fields(text.split("##", 1)[0])
    errors: list[str] = []
    if metadata.get("Parent chapter") != "21":
        errors.append(f"{path}: Parent chapter must be 21")
    if metadata.get("Review provenance") not in {
        "PRE_IMPLEMENTATION", "RETROACTIVE_RECOVERY"
    }:
        errors.append(f"{path}: invalid Review provenance")
    timing = metadata.get("Transition table timing", "")
    if not timing:
        errors.append(f"{path}: missing Transition table timing")
    if EXPECTED_HEADER not in text:
        errors.append(f"{path}: transition table has the wrong or missing columns")
    rows = transition_rows(text)
    if len(rows) < 6:
        errors.append(f"{path}: transition table needs at least six state partitions")
    for index, row in enumerate(rows, start=1):
        cells = [cell.strip() for cell in row.strip().strip("|").split("|")]
        if len(cells) != 5 or any(not cell for cell in cells):
            errors.append(f"{path}: transition row {index} must have five non-empty cells")
    for question in REQUIRED_QUESTIONS:
        if question not in text:
            errors.append(f"{path}: missing mandatory closure question '{question[4:]}'")
    if "## Primary deterministic proof" not in text:
        errors.append(f"{path}: missing primary deterministic proof section")
    if AGGREGATE_RHETORIC.search(text):
        errors.append(f"{path}: aggregate test count cannot be persuasive slice evidence")

    if require_verified:
        if metadata.get("Status") != "VERIFIED":
            errors.append(f"{path}: Status must be VERIFIED")
        if metadata.get("Independent implementation review") != "PASS":
            errors.append(f"{path}: Independent implementation review must be PASS")
        implementation = metadata.get("Product implementation checkpoint", "")
        hostile = metadata.get("Hostile review checkpoint", "")
        if not COMMIT.fullmatch(implementation):
            errors.append(f"{path}: Product implementation checkpoint must be a commit hash")
        if not COMMIT.fullmatch(hostile):
            errors.append(f"{path}: Hostile review checkpoint must be a commit hash")
        if COMMIT.fullmatch(implementation) and COMMIT.fullmatch(hostile):
            if implementation == hostile:
                errors.append(f"{path}: hostile review must use a later commit")
            elif not is_ancestor(implementation, hostile):
                errors.append(
                    f"{path}: implementation checkpoint must be an ancestor of hostile review")
    return errors


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("paths", nargs="*", type=pathlib.Path)
    parser.add_argument("--require-verified", action="store_true")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    paths = [path.resolve() for path in args.paths]
    if not paths:
        paths = sorted(ROOT.glob(DEFAULT_PATTERN))
    if len(paths) != 5:
        print(f"Slice review check requires exactly five packets; found {len(paths)}", file=sys.stderr)
        return 1
    errors: list[str] = []
    for path in paths:
        if not path.is_file():
            errors.append(f"{path}: packet does not exist")
        else:
            errors.extend(validate(path, args.require_verified))
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        return 1
    state = "verified" if args.require_verified else "structurally complete"
    print(f"Slice review check passed: five independent packets are {state}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
