#!/usr/bin/env python3
"""Fail if the supported Python SDK starts claiming something Phase 6 does not deliver.

Scope creep in a client library is not a documentation problem. "Install from PyPI"
in a README sends an integrator to a package nobody publishes — or worse, to a name
an attacker registered. A relaxed loopback guard turns a sandbox client into one
that will happily send an API key to an arbitrary host. A "production ready" line
creates a support obligation THAAP has not built.

Every rule here maps to a frozen exclusion in the supported-SDK scope boundary, and
each carries the reason it exists so a future reader can tell a real violation from
a false positive.
"""

from __future__ import annotations

import pathlib
import re
import sys
from urllib.parse import urlsplit


REPOSITORY_ROOT = pathlib.Path(__file__).resolve().parent.parent
SDK_ROOT = REPOSITORY_ROOT / "clients" / "python" / "thaap_sdk"
GUIDE = REPOSITORY_ROOT / "docs" / "DEVELOPER_SANDBOX_GUIDE.md"

# Generated operations and models are owned by OpenAPI Generator and are reconciled
# by the regeneration gate; scanning them would flag contract vocabulary, not claims.
GENERATED = SDK_ROOT / "thaap_sdk" / "generated"

PROSE_RULES: list[tuple[str, str]] = [
    (
        r"\bpypi\b|pip\s+install\s+thaap|upload\s+to\s+(?:the\s+)?index|twine",
        "The SDK is distributed from this repository only. A package-index "
        "claim points integrators at a name THAAP does not control.",
    ),
    (
        r"\bproduction[- ]ready\b|\bproduction\s+use\b|\bSLA\b|\bPCI[- ]compliant\b",
        "This is a local sandbox client. A production or compliance claim "
        "creates a support and audit obligation that Phase 9 owns.",
    ),
    (
        r"\basync(?:io)?\s+client\b|\bawait\s+client\b",
        "One synchronous client is supported. An async surface described as supported "
        "would be an untested second transport with its own secret-handling rules.",
    ),
    (
        r"\blive\s+card\b|\breal\s+card\b|\bproduction\s+credential\b",
        "Only sandbox test instruments are supported; a real-card example invites a "
        "real PAN into a repository with no PCI boundary.",
    ),
]

# A base URL an example could actually contact. Loopback and the RFC 2606 reserved
# example domains are safe; anything else is a live host in copyable code.
HOST_PATTERN = re.compile(r"https?://[^\s\"'`)<>]+")
ALLOWED_HOSTS = {"127.0.0.1", "localhost", "::1", "example.com", "example.test"}

# Reference links in comments and tooling configuration. These are read by humans and
# are never request targets, but they are listed one by one rather than waived by a
# "skip comments" rule, which an example could hide behind.
DOCUMENTATION_HOSTS = {"mypy.readthedocs.io", "errors.pydantic.dev"}

# The transport guarantees that make the sandbox client safe to hand to a merchant.
REQUIRED_TRANSPORT_GUARDS: list[tuple[str, str]] = [
    ("Plain HTTP is allowed only for the loopback sandbox", "loopback enforcement"),
    ("must be an origin without credentials, path, or query", "base URL validation"),
]


def scanned_files() -> list[pathlib.Path]:
    paths: list[pathlib.Path] = []
    for path in sorted(SDK_ROOT.rglob("*")):
        if not path.is_file():
            continue
        if GENERATED in path.parents or path == GENERATED:
            continue
        # Build outputs are transient copies of the sources already scanned. A gate that
        # failed because somebody built a wheel would be turned off within a week.
        if any(part in {".venv", "__pycache__", ".pytest_cache", "build", "dist"}
               or part.endswith(".egg-info") for part in path.parts):
            continue
        if path.suffix in {".py", ".md", ".toml", ".cfg", ".txt"}:
            paths.append(path)
    if GUIDE.is_file():
        paths.append(GUIDE)
    return paths


def check_prose(paths: list[pathlib.Path]) -> list[str]:
    failures: list[str] = []
    for path in paths:
        if path.name == "python-sdk-scope-check.py":
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        for pattern, reason in PROSE_RULES:
            for match in re.finditer(pattern, text, re.IGNORECASE):
                line = text.count("\n", 0, match.start()) + 1
                relative = path.relative_to(REPOSITORY_ROOT)
                failures.append(f"{relative}:{line} claims '{match.group(0)}'. {reason}")
    return failures


def check_hosts(paths: list[pathlib.Path]) -> list[str]:
    failures: list[str] = []
    for path in paths:
        text = path.read_text(encoding="utf-8", errors="ignore")
        for match in HOST_PATTERN.finditer(text):
            # urlsplit, not a regex group: "http://user:secret@127.0.0.1" must resolve
            # to the loopback host it really targets, not to the userinfo before the @.
            host = urlsplit(match.group(0)).hostname or ""
            if host in ALLOWED_HOSTS or host in DOCUMENTATION_HOSTS:
                continue
            if host.endswith((".example.com", ".example.test")):
                continue
            line = text.count("\n", 0, match.start()) + 1
            relative = path.relative_to(REPOSITORY_ROOT)
            failures.append(
                f"{relative}:{line} references non-loopback host '{host}'. Copyable SDK "
                "examples must not contact a host outside the local sandbox."
            )
    return failures


def check_transport_guards() -> list[str]:
    client = SDK_ROOT / "thaap_sdk" / "client.py"
    if not client.is_file():
        return [f"missing {client.relative_to(REPOSITORY_ROOT)}"]
    text = client.read_text(encoding="utf-8")
    return [
        f"{client.relative_to(REPOSITORY_ROOT)} no longer enforces {label}; the sandbox "
        "client would accept an arbitrary remote origin."
        for needle, label in REQUIRED_TRANSPORT_GUARDS
        if needle not in text
    ]


def check_supported_runtimes() -> list[str]:
    manifest = SDK_ROOT / "pyproject.toml"
    if not manifest.is_file():
        return [f"missing {manifest.relative_to(REPOSITORY_ROOT)}"]
    text = manifest.read_text(encoding="utf-8")
    match = re.search(r'requires-python\s*=\s*"([^"]+)"', text)
    if match is None:
        return ["pyproject.toml does not declare requires-python"]
    specifier = match.group(1)
    if "<" not in specifier:
        return [
            f"requires-python '{specifier}' has no upper bound, so the package claims "
            "support for Python releases this suite has never run on."
        ]
    return []


def main() -> int:
    if not SDK_ROOT.is_dir():
        print(f"FAIL missing SDK at {SDK_ROOT}")
        return 1
    paths = scanned_files()
    failures = (
        check_prose(paths)
        + check_hosts(paths)
        + check_transport_guards()
        + check_supported_runtimes()
    )
    if failures:
        print("Python SDK scope check FAIL")
        for failure in failures:
            print(f"  {failure}")
        return 1
    print(f"Python SDK scope check PASS ({len(paths)} files scanned)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
