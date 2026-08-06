#!/usr/bin/env python3
"""Reject public contract changes that break an already-integrated client.

The committed contract is compared against the reviewed baseline in
``openapi/baselines/``. Additive change is the promise THAAP makes to
integrators: new operations, new optional fields, and new error codes are
always allowed. Anything that can break code a merchant already shipped -- a
removed operation, a renamed SDK method, a narrowed type, a unit change, a
newly mandatory field, or a tightened credential -- fails here so that it has
to be a deliberate, reviewed act rather than a side effect of a refactor.

Updating the baseline is that deliberate act, and it is intentionally a
separate command so the change appears in review as a baseline diff.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys

REPOSITORY_ROOT = pathlib.Path(__file__).resolve().parent.parent
CONTRACT = REPOSITORY_ROOT / "openapi" / "thaap-public-api-2026-08-01.yaml"
BASELINE = REPOSITORY_ROOT / "openapi" / "baselines" / "thaap-public-api-2026-08-01.yaml"

HTTP_METHODS = ("get", "post", "put", "patch", "delete")


def load(path: pathlib.Path) -> dict:
    if not path.exists():
        raise SystemExit(f"missing contract artifact: {path}")
    # The artifact is emitted as JSON, which is a valid YAML document, so it is
    # readable without a third-party parser on a clean machine.
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def operations(contract: dict) -> dict:
    result = {}
    for path, item in contract.get("paths", {}).items():
        for method, operation in item.items():
            if method.lower() in HTTP_METHODS:
                result[f"{method.upper()} {path}"] = operation
    return result


def schema_properties(schema: dict) -> dict:
    return schema.get("properties") or {}


def type_signature(schema: dict) -> tuple:
    """The part of a property a generated client compiles against."""
    return (
        schema.get("type"),
        schema.get("format"),
        schema.get("$ref"),
        tuple(sorted(schema.get("enum", []))) if schema.get("enum") else None,
        json.dumps(schema.get("items"), sort_keys=True) if schema.get("items") else None,
    )


def security_requirements(operation: dict) -> list:
    return sorted(
        tuple(sorted(requirement.keys()))
        for requirement in operation.get("security", [])
    )


def required_parameters(operation: dict) -> set:
    return {
        (parameter.get("in"), parameter.get("name"))
        for parameter in operation.get("parameters", [])
        if parameter.get("required")
    }


def compare_schemas(baseline: dict, current: dict, breaks: list) -> None:
    baseline_schemas = baseline.get("components", {}).get("schemas", {})
    current_schemas = current.get("components", {}).get("schemas", {})

    for name, baseline_schema in baseline_schemas.items():
        current_schema = current_schemas.get(name)
        if current_schema is None:
            breaks.append(f"schema removed: {name}")
            continue

        baseline_properties = schema_properties(baseline_schema)
        current_properties = schema_properties(current_schema)
        for property_name, baseline_property in baseline_properties.items():
            current_property = current_properties.get(property_name)
            if current_property is None:
                breaks.append(f"property removed: {name}.{property_name}")
                continue
            if type_signature(baseline_property) != type_signature(current_property):
                breaks.append(
                    f"property type narrowed or changed: {name}.{property_name} "
                    f"{type_signature(baseline_property)} -> {type_signature(current_property)}"
                )

        newly_required = set(current_schema.get("required", [])) - set(
            baseline_schema.get("required", [])
        )
        for property_name in sorted(newly_required):
            breaks.append(f"property became required: {name}.{property_name}")


def compare_security(baseline: dict, current: dict, breaks: list) -> None:
    baseline_schemes = baseline.get("components", {}).get("securitySchemes", {})
    current_schemes = current.get("components", {}).get("securitySchemes", {})

    for name, baseline_scheme in baseline_schemes.items():
        current_scheme = current_schemes.get(name)
        if current_scheme is None:
            breaks.append(f"security scheme removed: {name}")
            continue
        for field in ("type", "in", "name"):
            if baseline_scheme.get(field) != current_scheme.get(field):
                breaks.append(
                    f"security scheme changed: {name}.{field} "
                    f"{baseline_scheme.get(field)!r} -> {current_scheme.get(field)!r}"
                )


def compare_operations(baseline: dict, current: dict, breaks: list) -> None:
    baseline_operations = operations(baseline)
    current_operations = operations(current)

    for key, baseline_operation in baseline_operations.items():
        current_operation = current_operations.get(key)
        if current_operation is None:
            breaks.append(f"operation removed: {key}")
            continue

        baseline_id = baseline_operation.get("operationId")
        current_id = current_operation.get("operationId")
        if baseline_id != current_id:
            breaks.append(
                f"operationId renamed: {key} {baseline_id!r} -> {current_id!r}"
            )

        baseline_security = security_requirements(baseline_operation)
        current_security = security_requirements(current_operation)
        # A client holding the credentials for some baseline requirement must still satisfy at
        # least one current requirement, so demanding a subset is a relaxation and demanding
        # anything not already covered is a break.
        for requirement in current_security:
            if not any(
                set(requirement) <= set(baseline_requirement)
                for baseline_requirement in baseline_security
            ):
                breaks.append(
                    f"credential requirement tightened: {key} "
                    f"{baseline_security} -> {current_security}"
                )
                break

        added_required = required_parameters(current_operation) - required_parameters(
            baseline_operation
        )
        for location, name in sorted(added_required):
            breaks.append(f"parameter became required: {key} {location} {name}")

        baseline_statuses = set(baseline_operation.get("responses", {}))
        current_statuses = set(current_operation.get("responses", {}))
        for status in sorted(baseline_statuses - current_statuses):
            breaks.append(f"response status removed: {key} {status}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--update-baseline",
        action="store_true",
        help="accept the current contract as the reviewed baseline",
    )
    arguments = parser.parse_args()

    if arguments.update_baseline:
        BASELINE.parent.mkdir(parents=True, exist_ok=True)
        BASELINE.write_text(CONTRACT.read_text(encoding="utf-8"), encoding="utf-8")
        print(f"baseline updated from {CONTRACT.relative_to(REPOSITORY_ROOT)}")
        return 0

    baseline = load(BASELINE)
    current = load(CONTRACT)

    breaks: list[str] = []
    compare_schemas(baseline, current, breaks)
    compare_security(baseline, current, breaks)
    compare_operations(baseline, current, breaks)

    if breaks:
        print("OpenAPI compatibility check failed:", file=sys.stderr)
        for entry in sorted(breaks):
            print(f"  - {entry}", file=sys.stderr)
        print(
            "\nIf the change is intended, update the baseline deliberately:\n"
            "  python3 scripts/openapi-compatibility-check.py --update-baseline",
            file=sys.stderr,
        )
        return 1

    added = len(set(operations(current)) - set(operations(baseline)))
    print(
        f"OpenAPI compatibility check passed: {len(operations(current))} operations, "
        f"{added} added since the reviewed baseline"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
