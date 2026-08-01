#!/usr/bin/env python3
"""Require every inherited chapter closure before an integrated phase can close."""

from __future__ import annotations

import pathlib
import subprocess
import sys


ROOT = pathlib.Path(__file__).resolve().parents[1]
PHASE_4_5_COORDINATES = tuple(str(number) for number in range(36, 44)) + ("43R",)
PHASE_5_COORDINATES = tuple(str(number) for number in range(44, 53))
DEFAULT_COORDINATES = PHASE_4_5_COORDINATES
PHASE_MODES = {
    "--phase-4-5": PHASE_4_5_COORDINATES,
    "--phase-5": PHASE_5_COORDINATES,
}
PHASE_5_COMPLETION_MARKER = "Phase 5 implementation status: COMPLETE"
PHASE_5_COMPLETION_SOURCES = (
    pathlib.Path("README.md"),
    pathlib.Path("apps/payments-api/README.md"),
    pathlib.Path("docs/README.md"),
    pathlib.Path("docs/IMPLEMENTATION_MASTER_PLAN.md"),
    pathlib.Path("docs/PHASE_5_DETAILED_IMPLEMENTATION_PLAN.md"),
    pathlib.Path("docs/THAAP_VISION_COVERAGE_MATRIX.md"),
    pathlib.Path("docs/architecture/04_DATA_EVENTS_LEDGER_AND_STORAGE_ARCHITECTURE.md"),
)
PHASE_5_PRODUCT_ARTIFACTS = (
    pathlib.Path("scripts/demo-payment-risk-analytics.py"),
    pathlib.Path("scripts/payment_risk_analytics_demo_tests.py"),
    pathlib.Path("docs/operations/EVENT_PIPELINE_AND_PROJECTION_OPERATIONS_RUNBOOK.md"),
    pathlib.Path("apps/analyst-dashboard-ui/src/App.tsx"),
    pathlib.Path("apps/analyst-dashboard-ui/src/App.test.tsx"),
)
PHASE_5_STALE_CLAIMS = (
    "No Phase 5 implementation chapter is complete",
    "Phase 5 implementation as not started",
    "Phase 5 implementation has not started",
    "Phase 5 is not implemented",
    "Phase 5 is now in progress",
    "Chapter 44 as the active event/outbox foundation slice",
    "Chapters 45–52 wait on their declared dependencies",
    "Every proof row remains `PENDING`",
    "Every dossier remains `IN_PROGRESS`/`DO_NOT_MERGE`",
)


def validate_phase(coordinates: tuple[str, ...] = DEFAULT_COORDINATES) -> list[str]:
    errors: list[str] = []
    for coordinate in coordinates:
        matrix = ROOT / "docs" / "reviews" / f"CHAPTER_{coordinate}_INVARIANT_PROOF_MATRIX.md"
        dossier = ROOT / "docs" / "reviews" / f"CHAPTER_{coordinate}_CLOSURE_DOSSIER.md"
        for path in (matrix, dossier):
            if not path.is_file():
                errors.append(f"{path}: required inherited phase-closure artifact is missing")
        if not matrix.is_file() or not dossier.is_file():
            continue
        invariant_result = subprocess.run(
            [str(ROOT / "scripts" / "invariant-proof-check.py"), str(matrix)],
            cwd=ROOT, capture_output=True, text=True, check=False)
        if invariant_result.returncode != 0:
            detail = (
                invariant_result.stderr.strip()
                or invariant_result.stdout.strip()
                or "invariant validator failed"
            )
            errors.append(f"Chapter {coordinate} invariant proof failed:\n{detail}")
        result = subprocess.run(
            [str(ROOT / "scripts" / "chapter-closure-check.py"), str(matrix), str(dossier)],
            cwd=ROOT, capture_output=True, text=True, check=False)
        if result.returncode != 0:
            detail = result.stderr.strip() or result.stdout.strip() or "closure validator failed"
            errors.append(f"Chapter {coordinate} closure failed:\n{detail}")
    return errors


def validate_phase_5_repository() -> list[str]:
    """Fail closed when closure artifacts disagree with the shipped product surface."""
    errors: list[str] = []
    for relative_path in PHASE_5_PRODUCT_ARTIFACTS:
        path = ROOT / relative_path
        if not path.is_file():
            errors.append(f"{path}: required Phase 5 product artifact is missing")

    for relative_path in PHASE_5_COMPLETION_SOURCES:
        path = ROOT / relative_path
        if not path.is_file():
            errors.append(f"{path}: required Phase 5 source of truth is missing")
            continue
        text = path.read_text(encoding="utf-8")
        if PHASE_5_COMPLETION_MARKER not in text:
            errors.append(
                f"{path}: missing exact completion marker "
                f"'{PHASE_5_COMPLETION_MARKER}'")
        normalized_text = text.casefold()
        for stale_claim in PHASE_5_STALE_CLAIMS:
            if stale_claim.casefold() in normalized_text:
                errors.append(f"{path}: stale Phase 5 claim remains: '{stale_claim}'")

    learning_patterns = (
        "docs/learning/daily/[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9].md",
        "docs/learning/daily/[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]-deep-dive.md",
        "docs/learning/weekly/[0-9][0-9][0-9][0-9]-W[0-9][0-9].md",
    )
    for pattern in learning_patterns:
        matching = [path for path in ROOT.glob(pattern)
                    if PHASE_5_COMPLETION_MARKER in path.read_text(encoding="utf-8")]
        if not matching:
            errors.append(
                f"{ROOT / pattern}: no learning artifact carries the exact Phase 5 "
                "completion marker")
    return errors


def coordinates_from_arguments(arguments: list[str]) -> tuple[str, ...]:
    if not arguments:
        return DEFAULT_COORDINATES
    if len(arguments) == 1 and arguments[0] in PHASE_MODES:
        return PHASE_MODES[arguments[0]]
    unknown_modes = [argument for argument in arguments if argument.startswith("--")]
    if unknown_modes:
        raise ValueError(
            "Usage: scripts/phase-closure-check.py "
            "[--phase-4-5 | --phase-5 | CHAPTER ...]")
    return tuple(arguments)


def main(arguments: list[str]) -> int:
    try:
        coordinates = coordinates_from_arguments(arguments)
    except ValueError as error:
        print(error, file=sys.stderr)
        return 2
    errors = validate_phase(coordinates)
    if coordinates == PHASE_5_COORDINATES:
        errors.extend(validate_phase_5_repository())
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print("Phase closure check passed: " + ", ".join(f"Chapter {value}" for value in coordinates))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
