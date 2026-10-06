#!/usr/bin/env python3
"""Hold the runtime configuration reference equal to the configuration it describes.

A frozen Phase 6.5 promise says every environment variable the system reads is
documented in one place. A promise of that shape decays the moment it is written:
the next variable goes into `application.yml`, nobody remembers the document, and
the reference becomes a description of a system that used to exist — which is
worse than no reference at all, because an operator trusts it.

So the document is checked rather than maintained. Three ways it can drift, all
of them fatal here:

  undocumented  the application reads a variable the reference does not mention
  phantom       the reference names a variable the application does not read
  stale default the reference states a default the application no longer uses

The eight required secrets are additionally held equal to `RequiredRuntimeSecret`,
because that enum is what actually refuses a deployment, and a reference that
disagreed with it would send an operator to fix the wrong thing.
"""

from __future__ import annotations

import pathlib
import re
import sys


ROOT = pathlib.Path(__file__).resolve().parents[1]
APPLICATION_YAML = ROOT / "apps/payments-api/src/main/resources/application.yml"
REFERENCE = ROOT / "docs/references/RUNTIME_CONFIGURATION_REFERENCE.md"
SECRET_ENUM = ROOT / (
    "apps/payments-api/src/main/java/com/thaap/payments/config/RequiredRuntimeSecret.java")

# ${THAAP_NAME:default} — the default is everything up to the closing brace, and is the empty
# string when the placeholder ends at the colon.
PLACEHOLDER = re.compile(r"\$\{(THAAP_[A-Z0-9_]+):([^}]*)}")

# | `THAAP_NAME` | `default` | Deployment | Purpose |
REFERENCE_ROW = re.compile(
    r"^\|\s*`(THAAP_[A-Z0-9_]+)`\s*\|\s*`([^`]*)`\s*\|\s*([A-Za-z ]+?)\s*\|", re.MULTILINE)

# The build arguments are documented in their own section and are not runtime configuration.
BUILD_ARGUMENTS = {"THAAP_BUILD_COMMIT", "THAAP_BUILD_VERSION"}

EMPTY_DEFAULT = "(empty)"
REQUIRED = "Required"

ENUM_ENVIRONMENT_VARIABLE = re.compile(r'"(THAAP_[A-Z0-9_]+)"')


def application_variables() -> dict[str, str]:
    """Every variable the application reads, with the default it falls back to."""
    text = APPLICATION_YAML.read_text(encoding="utf-8")
    variables: dict[str, str] = {}
    for name, default in PLACEHOLDER.findall(text):
        # First occurrence wins; a variable read twice with two defaults would be a defect in
        # application.yml rather than in this document, and the drift check below would expose it
        # as a stale default on whichever occurrence disagreed.
        variables.setdefault(name, default)
    return variables


def documented_variables() -> dict[str, tuple[str, str]]:
    """Every variable the reference documents, with its stated default and deployment column."""
    text = REFERENCE.read_text(encoding="utf-8")
    return {
        name: (default, deployment)
        for name, default, deployment in REFERENCE_ROW.findall(text)
    }


def enforced_secrets() -> set[str]:
    """The environment variables RequiredRuntimeSecret actually refuses a deployment over."""
    text = SECRET_ENUM.read_text(encoding="utf-8")
    start = text.index("public enum RequiredRuntimeSecret")
    end = text.index("MINIMUM_OPAQUE_TOKEN_LENGTH")
    return set(ENUM_ENVIRONMENT_VARIABLE.findall(text[start:end]))


def main() -> int:
    configured = application_variables()
    documented = documented_variables()
    required_in_reference = {
        name for name, (_, deployment) in documented.items() if deployment == REQUIRED}
    enforced = enforced_secrets()

    errors: list[str] = []

    if not configured:
        errors.append("no THAAP_ placeholders found in application.yml — the parser is broken")
    if not documented:
        errors.append("no variable rows found in the reference — the parser is broken")

    for name in sorted(set(configured) - set(documented)):
        errors.append(
            f"{name} is read by application.yml and documented nowhere in the reference")

    for name in sorted(set(documented) - set(configured) - BUILD_ARGUMENTS):
        errors.append(
            f"{name} is documented in the reference and read by nothing in application.yml")

    for name in sorted(set(configured) & set(documented)):
        stated, _ = documented[name]
        actual = configured[name]
        expected = EMPTY_DEFAULT if actual == "" else actual
        if stated != expected:
            errors.append(
                f"{name} defaults to '{actual}' in application.yml but the reference states "
                f"'{stated}'")

    for name in sorted(enforced - required_in_reference):
        errors.append(
            f"{name} refuses a deployment when unset but the reference does not mark it Required")

    for name in sorted(required_in_reference - enforced):
        errors.append(
            f"{name} is marked Required in the reference but RuntimeSecretContractGate does not "
            "enforce it")

    if errors:
        for error in errors:
            print(f"FAIL: {error}", file=sys.stderr)
        print(
            f"runtime configuration reference check FAILED with {len(errors)} problem(s)",
            file=sys.stderr)
        return 1

    print(
        f"Runtime configuration reference check passed: {len(configured)} variables documented, "
        f"{len(enforced)} enforced at startup")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
