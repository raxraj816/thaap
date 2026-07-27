#!/usr/bin/env python3
"""Require every inherited chapter closure before an integrated phase can close."""

from __future__ import annotations

import pathlib
import subprocess
import sys


ROOT = pathlib.Path(__file__).resolve().parents[1]
DEFAULT_COORDINATES = tuple(str(number) for number in range(36, 44)) + ("43R",)


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
        result = subprocess.run(
            [str(ROOT / "scripts" / "chapter-closure-check.py"), str(matrix), str(dossier)],
            cwd=ROOT, capture_output=True, text=True, check=False)
        if result.returncode != 0:
            detail = result.stderr.strip() or result.stdout.strip() or "closure validator failed"
            errors.append(f"Chapter {coordinate} closure failed:\n{detail}")
    return errors


def main(arguments: list[str]) -> int:
    coordinates = tuple(arguments) if arguments else DEFAULT_COORDINATES
    errors = validate_phase(coordinates)
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print("Phase closure check passed: " + ", ".join(f"Chapter {value}" for value in coordinates))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
