#!/usr/bin/env python3
"""Proves the same commit resolves to the same dependency set on every machine.

`C60-OPS-002` claims a rebuilt image is built from the same code that produced the last
decision. That claim dies quietly if a manifest permits a range: the lockfile makes today's
install reproducible, and the first `npm install` on a deploy box that regenerates it is free to
resolve a new major.

The analyst workspace manifest previously pinned react, react-dom, typescript, vite and every dev
dependency to `"latest"`. Nothing failed, because the lockfile was doing the work — which is
exactly why nobody noticed, and exactly what this refuses to allow back in.
"""
from __future__ import annotations

import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
UI = ROOT / "apps" / "analyst-dashboard-ui"

# An exact version and nothing else. Ranges, tags and wildcards all mean "resolve it again later",
# which is the property being excluded.
EXACT = re.compile(r"^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$")


def main() -> int:
    failures: list[str] = []

    manifest = json.loads((UI / "package.json").read_text())
    lock = json.loads((UI / "package-lock.json").read_text())
    locked = lock.get("packages", {})

    for section in ("dependencies", "devDependencies"):
        for name, specifier in sorted(manifest.get(section, {}).items()):
            if not EXACT.match(specifier):
                failures.append(
                    f"{section}.{name} is '{specifier}'; an exact version is required")
                continue
            # A manifest pin that disagrees with the lockfile is worse than no pin, because
            # `npm ci` refuses to run at all and the failure surfaces in the image build.
            entry = locked.get(f"node_modules/{name}")
            if entry is None:
                failures.append(f"{section}.{name} is absent from package-lock.json")
            elif entry.get("version") != specifier:
                failures.append(
                    f"{section}.{name} pins {specifier} but the lockfile resolves "
                    f"{entry.get('version')}")

    # Optional platform-specific packages are recorded only for the platform that generated the
    # lockfile, so a lockfile written on macOS fails `npm ci` inside a Linux image on packages
    # nobody named directly. The wasm fallback entries are the marker that it was generated, or
    # regenerated, somewhere that Linux installs resolve.
    if not any("emnapi" in key for key in locked):
        failures.append(
            "package-lock.json carries no cross-platform optional dependencies; regenerate it "
            "inside the build image or `npm ci` will fail there")

    for dockerfile in (ROOT / "apps" / "payments-api" / "Dockerfile",
                       UI / "Dockerfile"):
        for line in dockerfile.read_text().splitlines():
            if line.startswith("FROM ") and "@sha256:" not in line:
                failures.append(f"{dockerfile.relative_to(ROOT)}: un-pinned base image: {line}")

    # `npm install` in a Dockerfile silently rewrites the lockfile it was given. Only build
    # instructions are examined: the comment above that RUN line explains why `npm ci` is used
    # and says the words `npm install` to do so, and a gate that cannot tell an instruction from
    # the prose describing it teaches people to write worse comments.
    for dockerfile in (UI / "Dockerfile",):
        for line in dockerfile.read_text().splitlines():
            instruction = line.strip()
            if instruction.startswith("#"):
                continue
            if re.search(r"\bnpm\s+install\b", instruction):
                failures.append(
                    f"{dockerfile.relative_to(ROOT)} runs `npm install`; use `npm ci` so the "
                    f"lockfile is honoured rather than rewritten: {instruction}")

    if failures:
        for failure in failures:
            print(f"FAIL: {failure}", file=sys.stderr)
        print(f"dependency pin check FAILED with {len(failures)} problem(s)", file=sys.stderr)
        return 1

    total = len(manifest.get("dependencies", {})) + len(manifest.get("devDependencies", {}))
    print(f"dependency pin check passed: {total} exact pins, all matching the lockfile")
    return 0


if __name__ == "__main__":
    sys.exit(main())
