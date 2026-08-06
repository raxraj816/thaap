#!/usr/bin/env python3
"""Require every inherited chapter closure before an integrated phase can close."""

from __future__ import annotations

import hashlib
import pathlib
import subprocess
import sys


ROOT = pathlib.Path(__file__).resolve().parents[1]
PHASE_4_5_COORDINATES = tuple(str(number) for number in range(36, 44)) + ("43R",)
PHASE_5_COORDINATES = tuple(str(number) for number in range(44, 53))
PHASE_6_COORDINATES = tuple(str(number) for number in range(53, 60))
DEFAULT_COORDINATES = PHASE_4_5_COORDINATES
PHASE_MODES = {
    "--phase-4-5": PHASE_4_5_COORDINATES,
    "--phase-5": PHASE_5_COORDINATES,
    "--phase-6": PHASE_6_COORDINATES,
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
        # `--reject-partial` is what makes a phase gate stricter than a chapter gate: a
        # chapter may close honestly with a `PARTIAL` row that names the debt owning its
        # unbuilt proof, but a phase cannot be declared complete while one remains.
        invariant_result = subprocess.run(
            [str(ROOT / "scripts" / "invariant-proof-check.py"), str(matrix),
             "--reject-partial"],
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


PHASE_6_COMPLETION_MARKER = "Phase 6 implementation status: COMPLETE"
PHASE_6_COMPLETION_SOURCES = (
    pathlib.Path("README.md"),
    pathlib.Path("apps/payments-api/README.md"),
    pathlib.Path("docs/README.md"),
    pathlib.Path("docs/IMPLEMENTATION_MASTER_PLAN.md"),
    pathlib.Path("docs/PHASE_6_DETAILED_IMPLEMENTATION_PLAN.md"),
    pathlib.Path("docs/THAAP_VISION_COVERAGE_MATRIX.md"),
)
# The executable surfaces a Phase 6 completion claim depends on. A closure document
# that says "complete" while one of these is absent is describing a product that does
# not exist, which is the exact failure C59-OPS-001 exists to prevent.
PHASE_6_PRODUCT_ARTIFACTS = (
    pathlib.Path("openapi/thaap-public-api-2026-08-01.yaml"),
    pathlib.Path("clients/python/thaap_sdk/pyproject.toml"),
    pathlib.Path("clients/python/thaap_sdk/thaap_sdk/client.py"),
    pathlib.Path("clients/python/thaap_sdk/examples/sandbox_quickstart.py"),
    pathlib.Path("docs/DEVELOPER_SANDBOX_GUIDE.md"),
    pathlib.Path("scripts/demo-payment-investigation.py"),
    pathlib.Path("scripts/demo-saved-views-and-bulk-review.py"),
    pathlib.Path("scripts/demo-workspace-insights.py"),
    pathlib.Path("scripts/openapi-contract-check.py"),
    pathlib.Path("scripts/python-sdk-scope-check.py"),
    pathlib.Path("scripts/python-sdk-package-check.py"),
    pathlib.Path("apps/analyst-dashboard-ui/src/Phase6Workspace.tsx"),
    pathlib.Path("apps/analyst-dashboard-ui/src/DeveloperPortal.tsx"),
    pathlib.Path("apps/analyst-dashboard-ui/src/developerQuickstart.test.tsx"),
    pathlib.Path("docs/operations/DECISION_INTELLIGENCE_WORKSPACE_OPERATIONS_RUNBOOK.md"),
)
PHASE_6_STALE_CLAIMS = (
    "IMPLEMENTATION AUTHORIZED, NOT STARTED",
    "No Phase 6 implementation chapter is complete",
    "Phase 6 implementation has not started",
    "Phase 6 is not implemented",
    "Chapters 54-56 wait on their declared dependencies",
)


def validate_phase_6_repository() -> list[str]:
    """Fail closed when Phase 6 closure artifacts disagree with the shipped surface."""
    errors: list[str] = []
    for relative_path in PHASE_6_PRODUCT_ARTIFACTS:
        path = ROOT / relative_path
        if not path.is_file():
            errors.append(f"{path}: required Phase 6 product artifact is missing")

    for relative_path in PHASE_6_COMPLETION_SOURCES:
        path = ROOT / relative_path
        if not path.is_file():
            errors.append(f"{path}: required Phase 6 source of truth is missing")
            continue
        text = path.read_text(encoding="utf-8")
        if PHASE_6_COMPLETION_MARKER not in text:
            errors.append(
                f"{path}: missing exact completion marker "
                f"'{PHASE_6_COMPLETION_MARKER}'")
        normalized_text = text.casefold()
        for stale_claim in PHASE_6_STALE_CLAIMS:
            if stale_claim.casefold() in normalized_text:
                errors.append(f"{path}: stale Phase 6 claim remains: '{stale_claim}'")

    # The SDK must be generated from the contract that is actually committed. Comparing
    # the stamped digest here means a closure run cannot pass while the published client
    # and the published contract describe different APIs.
    contract = ROOT / "openapi" / "thaap-public-api-2026-08-01.yaml"
    stamp = ROOT / "clients" / "python" / "thaap_sdk" / "thaap_sdk" / "generated" / "CONTRACT_SHA256"
    if contract.is_file() and stamp.is_file():
        expected = hashlib.sha256(contract.read_bytes()).hexdigest()
        recorded = stamp.read_text(encoding="utf-8").strip()
        if expected != recorded:
            errors.append(
                f"{stamp}: the committed SDK was generated from contract {recorded[:12]}… "
                f"but the committed contract hashes to {expected[:12]}…; regenerate the SDK")
    elif contract.is_file():
        errors.append(f"{stamp}: the generated SDK records no source contract digest")

    for pattern in (
            "docs/learning/daily/[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9].md",
            "docs/learning/daily/[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]-deep-dive.md",
            "docs/learning/weekly/[0-9][0-9][0-9][0-9]-W[0-9][0-9].md"):
        matching = [path for path in ROOT.glob(pattern)
                    if PHASE_6_COMPLETION_MARKER in path.read_text(encoding="utf-8")]
        if not matching:
            errors.append(
                f"{ROOT / pattern}: no learning artifact carries the exact Phase 6 "
                "completion marker")
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
            "[--phase-4-5 | --phase-5 | --phase-6 | CHAPTER ...]")
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
    if coordinates == PHASE_6_COORDINATES:
        errors.extend(validate_phase_6_repository())
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print("Phase closure check passed: " + ", ".join(f"Chapter {value}" for value in coordinates))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
