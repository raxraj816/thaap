#!/usr/bin/env python3
"""Reject message-only failures across every intentional public API package."""

from __future__ import annotations

import pathlib
import re


ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / "apps/payments-api/src/main/java/com/thaap/payments"
PUBLIC_PACKAGES = (
    "analytics",
    "authentication",
    "bulk",
    "controls",
    "customer",
    "developer",
    "dispute",
    "fraud",
    "fraudlist",
    "idempotency",
    "incident",
    "insights",
    "investigation",
    "ledger",
    "monitoring",
    "payment",
    "paymentattempt",
    "paymentmethod",
    "postauth",
    "ratelimit",
    "replay",
    "review",
    "risk",
    "riskcontext",
    "riskpolicy",
    "rules",
    "simulation",
    "tenant",
    "webhook",
    "workspace",
)
DIRECT_RESPONSE_STATUS = re.compile(r"(?:new|throw new)\s+ResponseStatusException\s*\(")
DESCRIPTOR_ENTRY = re.compile(
    r'^    ([A-Z][A-Z0-9_]*)\("([a-z0-9_]+)",\s*HttpStatus\.', re.M
)
HASH_SUFFIX = re.compile(r"_[0-9a-f]{10}$")
MAX_CODE_TOKENS = 7


def check_catalogue(descriptor_text: str) -> list[str]:
    """Reject message-derived, unstable, or divergent public error codes."""
    failures: list[str] = []
    entries = DESCRIPTOR_ENTRY.findall(descriptor_text)
    codes = [code for _, code in entries]
    if len(codes) < 100:
        failures.append(f"catalogue is incomplete: {len(codes)} descriptors")
    duplicates = sorted({code for code in codes if codes.count(code) > 1})
    if duplicates:
        failures.append(f"duplicate stable codes: {duplicates}")
    for name, code in entries:
        if name != code.upper():
            failures.append(f"constant {name} does not match stable code {code}")
        if HASH_SUFFIX.search(code):
            failures.append(f"hash-truncated message-derived code: {code}")
        if len(code.split("_")) > MAX_CODE_TOKENS:
            failures.append(f"code reads as a sentence rather than a failure class: {code}")
    return failures


def main() -> None:
    failures: list[str] = []
    files = [
        path
        for package in PUBLIC_PACKAGES
        for path in (SOURCE / package).rglob("*.java")
    ]
    for path in files:
        text = path.read_text(encoding="utf-8")
        if DIRECT_RESPONSE_STATUS.search(text):
            failures.append(str(path.relative_to(ROOT)))
    descriptor = SOURCE / "api/PublicApiErrorDescriptor.java"
    descriptor_text = descriptor.read_text(encoding="utf-8")
    failures.extend(check_catalogue(descriptor_text))
    codes = DESCRIPTOR_ENTRY.findall(descriptor_text)
    if failures:
        raise SystemExit(
            "Public API error contract FAIL:\n- " + "\n- ".join(failures)
        )
    print(
        f"Public API error contract PASS: {len(files)} public files, "
        f"{len(codes)} stable descriptors"
    )


if __name__ == "__main__":
    main()
