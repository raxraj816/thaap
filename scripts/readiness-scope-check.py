#!/usr/bin/env python3
"""Resolve the current governed readiness scope, never a stale or implicit historical pair."""
from __future__ import annotations

import datetime
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
MANIFEST = "docs/reviews/CURRENT_READINESS_SCOPE.json"
KEYS = {"schemaVersion", "learningDay", "invariantMatrix", "closureDossier"}


class ScopeFailure(ValueError):
    pass


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ScopeFailure("duplicate scope manifest key")
        result[key] = value
    return result


def governed_path(root: pathlib.Path, value, suffix: str) -> str:
    if not isinstance(value, str) or not re.fullmatch(
            rf"docs/reviews/[A-Z0-9_]+{re.escape(suffix)}", value):
        raise ScopeFailure("scope path must be a canonical repository review artifact")
    candidate = root / value
    if candidate.resolve() != candidate.absolute() or not candidate.is_file():
        raise ScopeFailure("scope artifact must exist without symlink indirection")
    return value


def resolve_scope(root: pathlib.Path, learning_day: str, requested_matrix="", requested_dossier=""):
    root = root.resolve()
    manifest = root / MANIFEST
    if manifest.resolve() != manifest.absolute():
        raise ScopeFailure("scope manifest must not be symlinked")
    try:
        document = json.loads(manifest.read_text(encoding="utf-8"), object_pairs_hook=unique_object)
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ScopeFailure("current readiness scope manifest is missing or malformed") from error
    if not isinstance(document, dict) or set(document) != KEYS:
        raise ScopeFailure("scope manifest must contain exactly the four versioned fields")
    if type(document["schemaVersion"]) is not int or document["schemaVersion"] != 1:
        raise ScopeFailure("unsupported scope schemaVersion")
    day = document["learningDay"]
    if not isinstance(day, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", day, re.ASCII):
        raise ScopeFailure("scope learningDay must be an ISO calendar date")
    try:
        datetime.date.fromisoformat(day)
    except ValueError as error:
        raise ScopeFailure("scope learningDay is not a valid calendar date") from error
    if learning_day != day:
        raise ScopeFailure("requested learning day differs from the registered current scope")
    matrix = governed_path(root, document["invariantMatrix"], "_INVARIANT_PROOF_MATRIX.md")
    dossier = governed_path(root, document["closureDossier"], "_CLOSURE_DOSSIER.md")
    if matrix.removesuffix("_INVARIANT_PROOF_MATRIX.md") != dossier.removesuffix("_CLOSURE_DOSSIER.md"):
        raise ScopeFailure("scope matrix and dossier must belong to the same review coordinate")
    if bool(requested_matrix) != bool(requested_dossier):
        raise ScopeFailure("explicit readiness selectors require both matrix and dossier")
    if requested_matrix and (requested_matrix, requested_dossier) != (matrix, dossier):
        raise ScopeFailure("explicit readiness selectors differ from the registered current scope")
    return matrix, dossier


def main(arguments):
    if len(arguments) != 3:
        print("Usage: readiness-scope-check.py DAY MATRIX_OR_EMPTY DOSSIER_OR_EMPTY", file=sys.stderr)
        return 2
    try:
        matrix, dossier = resolve_scope(ROOT, *arguments)
    except ScopeFailure as error:
        print(f"Readiness scope check failed: {error}", file=sys.stderr)
        return 1
    print(f"{matrix}\t{dossier}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
