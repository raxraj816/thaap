#!/usr/bin/env python3
"""Reject roadmap chapter identifiers from durable executable artifacts."""

from __future__ import annotations

import re
import sys
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parent.parent
EXECUTABLE_SUFFIXES = {".java", ".lua", ".py", ".sh", ".sql"}
IGNORED_DIRECTORY_NAMES = {".git", ".m2", "__pycache__", "target"}
CHAPTER_IDENTIFIER = re.compile(r"chapters?(?:[ _-]?\d+)", re.IGNORECASE)

# These files genuinely operate on chapter-governance artifacts rather than on
# payment-product behavior. Their vocabulary is therefore the domain vocabulary.
GOVERNANCE_CONTENT_EXCLUSIONS = {
    Path("scripts/chapter-closure-check.py"),
    Path("scripts/chapter_closure_check_tests.py"),
    Path("scripts/commit-readiness-check.sh"),
    Path("scripts/commit_readiness_check_tests.py"),
    Path("scripts/executable-artifact-naming-check.py"),
    Path("scripts/executable_artifact_naming_check_tests.py"),
    Path("scripts/invariant-proof-check.py"),
    Path("scripts/invariant_proof_check_tests.py"),
    Path("scripts/phase-closure-check.py"),
    Path("scripts/phase_closure_check_tests.py"),
    Path("scripts/slice-review-check.py"),
    Path("scripts/technical_debt_check_tests.py"),
}

# Applied Flyway migrations are immutable. These two historical files predate
# the naming rule and cannot be rewritten without falsifying migration history.
IMMUTABLE_MIGRATION_CONTENT_EXCLUSIONS = {
    Path("apps/payments-api/src/main/resources/db/migration/V6__add_authorization_voids.sql"),
    Path("apps/payments-api/src/main/resources/db/migration/V8__add_refunds_and_refund_ledger_lineage.sql"),
}

# Two upgrade tests must assert the exact historical V8 constraint identifier.
ALLOWED_HISTORICAL_TOKENS = {
    Path("apps/payments-api/src/test/java/com/thaap/payments/database/DatabaseMigrationTests.java"):
        {"ck_chapter18_no_legacy_refunded_payment_intents"},
    Path("apps/payments-api/src/test/java/com/thaap/payments/database/PostgreSqlMigrationTests.java"):
        {"ck_chapter18_no_legacy_refunded_payment_intents"},
}


def is_executable_source(relative_path: Path) -> bool:
    if relative_path.suffix not in EXECUTABLE_SUFFIXES:
        return False
    if relative_path.parts[0] == "scripts":
        return True
    return (
        relative_path.parts[0] == "apps"
        and "src" in relative_path.parts
        and ("main" in relative_path.parts or "test" in relative_path.parts)
    )


def discover_executable_sources(repository_root: Path) -> list[Path]:
    sources: list[Path] = []
    for top_level in ("apps", "scripts"):
        search_root = repository_root / top_level
        if not search_root.exists():
            continue
        for path in search_root.rglob("*"):
            if not path.is_file():
                continue
            relative_path = path.relative_to(repository_root)
            if any(part in IGNORED_DIRECTORY_NAMES for part in relative_path.parts):
                continue
            if is_executable_source(relative_path):
                sources.append(relative_path)
    return sorted(sources)


def allowed_historical_match(relative_path: Path, line: str, match: re.Match[str]) -> bool:
    allowed_tokens = ALLOWED_HISTORICAL_TOKENS.get(relative_path, set())
    return any(
        token in line and match.start() >= line.index(token)
        and match.end() <= line.index(token) + len(token)
        for token in allowed_tokens
    )


def find_violations(repository_root: Path) -> list[str]:
    violations: list[str] = []
    content_exclusions = GOVERNANCE_CONTENT_EXCLUSIONS | IMMUTABLE_MIGRATION_CONTENT_EXCLUSIONS
    for relative_path in discover_executable_sources(repository_root):
        path_match = CHAPTER_IDENTIFIER.search(relative_path.as_posix())
        if path_match:
            violations.append(
                f"{relative_path}: executable path contains temporary chapter identity "
                f"'{path_match.group(0)}'")

        if relative_path in content_exclusions:
            continue

        absolute_path = repository_root / relative_path
        for line_number, line in enumerate(
                absolute_path.read_text(encoding="utf-8").splitlines(), start=1):
            for match in CHAPTER_IDENTIFIER.finditer(line):
                if allowed_historical_match(relative_path, line, match):
                    continue
                violations.append(
                    f"{relative_path}:{line_number}: executable content contains temporary "
                    f"chapter identity '{match.group(0)}'")
    return violations


def main(arguments: list[str]) -> int:
    repository_root = Path(arguments[0]).resolve() if arguments else REPOSITORY_ROOT
    violations = find_violations(repository_root)
    if violations:
        print("Executable artifact naming check failed:", file=sys.stderr)
        for violation in violations:
            print(f"- {violation}", file=sys.stderr)
        return 1
    print("Executable artifact naming check passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
