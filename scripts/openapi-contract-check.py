#!/usr/bin/env python3
"""Check committed/runtime OpenAPI equality and stable public-contract invariants."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import urllib.request


ROOT = pathlib.Path(__file__).resolve().parents[1]
VERSION = "2026-08-01"
PUBLIC = ROOT / "openapi" / f"thaap-public-api-{VERSION}.yaml"
RUNTIME = ROOT / "apps/payments-api/src/main/resources/openapi" / PUBLIC.name
METHODS = {"get", "post", "put", "patch", "delete"}

# Every /developer/scenario-runs operation is guarded: DeveloperScenarioService
# .requireSynthetic rejects a context whose TrafficOrigin is not SYNTHETIC_SCENARIO, and
# only a valid runner token produces that origin. So a merchant key alone is insufficient
# for all of them, and the contract must say so — otherwise a generated client omits the
# token and every scenario call fails with SCENARIO_RUNNER_CREDENTIAL_REQUIRED.


def check_scenario_credentials(method: str, path: str, operation: dict) -> None:
    security = operation.get("security", [])
    schemes = set(security[0]) if len(security) == 1 else set()
    operation_key = f"{method.upper()} {path}"

    if schemes != {"merchantApi", "scenarioRunner"}:
        raise SystemExit(
            f"scenario route must require merchantApi AND scenarioRunner: {operation_key} "
            f"declares {sorted(schemes) or 'nothing'}")

    if any(parameter.get("in") == "header"
           and parameter.get("name") == "X-Thaap-Scenario-Runner-Token"
           for parameter in operation.get("parameters", [])):
        raise SystemExit(
            f"{operation_key} publishes the runner token as both a security scheme and a "
            "bare header; a client could send the header and believe it satisfied the scheme")



def read(path: pathlib.Path) -> tuple[bytes, dict]:
    if not path.is_file():
        raise SystemExit(f"missing OpenAPI artifact: {path.relative_to(ROOT)}")
    raw = path.read_bytes()
    return raw, json.loads(raw)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--served-url")
    arguments = parser.parse_args()
    public_raw, contract = read(PUBLIC)
    runtime_raw, _ = read(RUNTIME)
    if public_raw != runtime_raw:
        raise SystemExit("committed and runtime OpenAPI artifacts differ")
    if arguments.served_url:
        with urllib.request.urlopen(arguments.served_url, timeout=15) as response:
            served = response.read()
        if served != public_raw:
            raise SystemExit("served and committed OpenAPI bytes differ")
    operation_ids: list[str] = []
    public_operations = 0
    for path, path_item in contract["paths"].items():
        if path.startswith(("/internal/", "/admin/", "/platform/", "/actuator/", "/v3/")):
            raise SystemExit(f"internal route exposed: {path}")
        for method, operation in path_item.items():
            if method not in METHODS:
                continue
            public_operations += 1
            operation_ids.append(operation.get("operationId", ""))
            if not operation.get("security") and not path.startswith("/developer/"):
                raise SystemExit(f"unsecured non-developer route: {method.upper()} {path}")
            if path.startswith("/developer/scenario-runs"):
                check_scenario_credentials(method, path, operation)
    if not operation_ids or "" in operation_ids or len(operation_ids) != len(set(operation_ids)):
        raise SystemExit("operationId inventory is empty, missing, or duplicated")
    schemas = contract.get("components", {}).get("schemas", {})
    if schemas.get("StepView", {}).get("properties", {}).get("body", {}).get(
        "additionalProperties"
    ) is not True:
        raise SystemExit("arbitrary JSON map values are not represented honestly")
    if "type" in schemas.get("FeatureValueView", {}).get("properties", {}).get("value", {}):
        raise SystemExit("arbitrary JSON feature values are incorrectly narrowed")
    digest = hashlib.sha256(public_raw).hexdigest()
    print(
        f"OpenAPI contract PASS: {len(contract['paths'])} paths, "
        f"{public_operations} operations, sha256={digest}"
    )


if __name__ == "__main__":
    main()
