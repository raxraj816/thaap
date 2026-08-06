#!/usr/bin/env python3
"""Stamp generated Python sources with their immutable OpenAPI digest."""

from __future__ import annotations

import argparse
import hashlib
import pathlib


ROOT = pathlib.Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "openapi/thaap-public-api-2026-08-01.yaml"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("generated_root", type=pathlib.Path)
    arguments = parser.parse_args()
    digest = hashlib.sha256(CONTRACT.read_bytes()).hexdigest()
    marker = f"# THAAP OpenAPI SHA-256: {digest}\n"
    generated_root = arguments.generated_root.resolve()
    for source in sorted(generated_root.rglob("*.py")):
        text = source.read_text(encoding="utf-8")
        lines = text.splitlines(keepends=True)
        if lines and lines[0].startswith("# THAAP OpenAPI SHA-256:"):
            text = "".join(lines[1:])
        source.write_text(marker + text, encoding="utf-8")
    (generated_root / "py.typed").write_bytes(b"")
    (generated_root / "CONTRACT_SHA256").write_text(digest + "\n", encoding="ascii")
    print(f"stamped {generated_root} with {digest}")


if __name__ == "__main__":
    main()
