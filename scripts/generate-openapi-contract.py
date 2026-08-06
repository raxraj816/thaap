#!/usr/bin/env python3
"""Freeze the executable springdoc contract as deterministic JSON-compatible YAML."""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import tempfile
import urllib.request


ROOT = pathlib.Path(__file__).resolve().parents[1]
VERSION = "2026-08-01"
PUBLIC_ARTIFACT = ROOT / "openapi" / f"thaap-public-api-{VERSION}.yaml"
RUNTIME_ARTIFACT = (
    ROOT
    / "apps"
    / "payments-api"
    / "src"
    / "main"
    / "resources"
    / "openapi"
    / f"thaap-public-api-{VERSION}.yaml"
)
EXCLUDED_PREFIXES = ("/internal/", "/admin/", "/platform/", "/actuator/", "/v3/")


def load_contract(url: str) -> dict:
    request = urllib.request.Request(url, headers={"Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=15) as response:
        if response.status != 200:
            raise SystemExit(f"springdoc returned HTTP {response.status}")
        return json.load(response)


def validate(contract: dict) -> None:
    if not str(contract.get("openapi", "")).startswith("3."):
        raise SystemExit("contract is not OpenAPI 3.x")
    if contract.get("info", {}).get("version") != VERSION:
        raise SystemExit("contract version does not match the frozen API version")
    paths = contract.get("paths")
    if not isinstance(paths, dict) or not paths:
        raise SystemExit("contract has no public paths")
    leaked = [path for path in paths if path.startswith(EXCLUDED_PREFIXES)]
    if leaked:
        raise SystemExit(f"internal route leaked into public contract: {leaked[0]}")
    operation_ids: list[str] = []
    for path_item in paths.values():
        for method, operation in path_item.items():
            if method.lower() not in {"get", "post", "put", "patch", "delete"}:
                continue
            operation_id = operation.get("operationId")
            if not operation_id:
                raise SystemExit("public operation is missing operationId")
            operation_ids.append(operation_id)
            version_headers = [
                parameter
                for parameter in operation.get("parameters", [])
                if parameter.get("in") == "header" and parameter.get("name") == "Thaap-Version"
            ]
            if len(version_headers) != 1:
                raise SystemExit(f"{operation_id} does not have exactly one Thaap-Version header")
            if "default" not in operation.get("responses", {}):
                raise SystemExit(f"{operation_id} has no common error response")
    if len(operation_ids) != len(set(operation_ids)):
        raise SystemExit("public operationId values are not unique")
    schemes = contract.get("components", {}).get("securitySchemes", {})
    if set(schemes) != {"merchantApi", "riskOperator", "scenarioRunner"}:
        raise SystemExit("public credential schemes are incomplete or unexpected")
    serialized = json.dumps(contract, sort_keys=True).lower()
    for canary in ("test-admin-bootstrap-token", "test-platform-bootstrap-token", "password="):
        if canary in serialized:
            raise SystemExit(f"sensitive canary leaked into contract: {canary}")


def atomic_write(target: pathlib.Path, rendered: str) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        dir=target.parent, prefix=f".{target.name}.", suffix=".tmp"
    )
    temporary = pathlib.Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            output.write(rendered)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, target)
    finally:
        temporary.unlink(missing_ok=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--url", default="http://127.0.0.1:18080/v3/api-docs/public"
    )
    parser.add_argument(
        "--source",
        help=(
            "Read the runtime contract from a file instead of a live springdoc URL. "
            "VersionedPublicContractTests writes the running contract to "
            "apps/payments-api/target/openapi/runtime-public-contract.json, so the "
            "artifact can be frozen from the same JVM that serves it."
        ),
    )
    arguments = parser.parse_args()
    if arguments.source:
        contract = json.loads(pathlib.Path(arguments.source).read_text(encoding="utf-8"))
    else:
        contract = load_contract(arguments.url)
    validate(contract)
    rendered = json.dumps(contract, sort_keys=True, indent=2, ensure_ascii=False) + "\n"
    for target in (PUBLIC_ARTIFACT, RUNTIME_ARTIFACT):
        atomic_write(target, rendered)
    print(f"generated {PUBLIC_ARTIFACT.relative_to(ROOT)} with {len(contract['paths'])} paths")


if __name__ == "__main__":
    main()
