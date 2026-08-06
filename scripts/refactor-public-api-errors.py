#!/usr/bin/env python3
"""Emit an apply_patch diff that types one legacy public error package."""

from __future__ import annotations

import argparse
import difflib
import hashlib
import json
import pathlib
import re


ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / "apps/payments-api/src/main/java/com/thaap/payments"
DESCRIPTORS = SOURCE / "api/PublicApiErrorDescriptor.java"
NEEDLE = "new ResponseStatusException("
ALIASES = {
    "payment_intent_is_not_confirmable": "payment_intent_not_confirmable",
    "risk_operator_credential_is_stale": "risk_operator_stale",
    "saved_search_requires_an_explicit_schema_upgrade": "saved_search_upgrade_required",
    "payment_saved_view_requires_an_explicit_schema_upgrade": "saved_view_upgrade_required",
}


def constructor_ranges(text: str) -> list[tuple[int, int, str]]:
    result: list[tuple[int, int, str]] = []
    position = 0
    while True:
        start = text.find(NEEDLE, position)
        if start < 0:
            return result
        cursor = start + len(NEEDLE)
        depth = 1
        quoted = character = escaped = False
        while depth:
            value = text[cursor]
            if quoted:
                if escaped:
                    escaped = False
                elif value == "\\":
                    escaped = True
                elif value == '"':
                    quoted = False
            elif character:
                if escaped:
                    escaped = False
                elif value == "\\":
                    escaped = True
                elif value == "'":
                    character = False
            elif value == '"':
                quoted = True
            elif value == "'":
                character = True
            elif value == "(":
                depth += 1
            elif value == ")":
                depth -= 1
            cursor += 1
        result.append((start, cursor, text[start:cursor]))
        position = cursor


def split_arguments(expression: str) -> list[str]:
    inner = expression[expression.index("(") + 1 : -1]
    result: list[str] = []
    start = depth = 0
    quoted = character = escaped = False
    for index, value in enumerate(inner):
        if quoted:
            if escaped:
                escaped = False
            elif value == "\\":
                escaped = True
            elif value == '"':
                quoted = False
        elif character:
            if escaped:
                escaped = False
            elif value == "\\":
                escaped = True
            elif value == "'":
                character = False
        elif value == '"':
            quoted = True
        elif value == "'":
            character = True
        elif value in "([{":
            depth += 1
        elif value in ")]}":
            depth -= 1
        elif value == "," and depth == 0:
            result.append(inner[start:index].strip())
            start = index + 1
    result.append(inner[start:].strip())
    return result


def words(value: str) -> str:
    value = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", value)
    value = re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1_\2", value)
    value = re.sub(r"[^A-Za-z0-9]+", "_", value).strip("_").lower()
    value = re.sub(r"_+", "_", value)
    return value


def literal_message(reason: str) -> tuple[str, bool]:
    tokens = re.findall(r'"(?:\\.|[^"\\])*"', reason)
    values: list[str] = []
    for token in tokens:
        try:
            values.append(json.loads(token))
        except json.JSONDecodeError:
            values.append(token[1:-1])
    message = re.sub(r"\s+", " ", " ".join(values)).strip(" :-")
    remainder = reason
    for token in tokens:
        remainder = remainder.replace(token, "")
    dynamic = bool(re.sub(r"[+()\s]", "", remainder))
    return message, dynamic


def domain_name(path: pathlib.Path) -> str:
    stem = re.sub(
        r"(Management|Snapshot|ActivationGate|Identity|Operations|Endpoint|Content)?"
        r"(Service|Controller|Codec|Validator|Evaluator)$",
        "",
        path.stem,
    )
    return words(stem) or words(path.parent.name)


def generic_message(domain: str, status: str) -> str:
    title = domain.replace("_", " ").capitalize()
    if status == "BAD_REQUEST":
        return f"{title} request is invalid"
    if status == "CONFLICT":
        return f"{title} request conflicts with authoritative state"
    if status == "NOT_FOUND":
        return f"{title} resource was not found"
    if status == "FORBIDDEN":
        return f"{title} request requires an authorized role"
    if status == "UNAUTHORIZED":
        return f"{title} request requires a valid credential"
    return f"{title} dependency is temporarily unavailable"


def safe_message(reason: str, domain: str, status: str) -> str:
    message, dynamic = literal_message(reason)
    if not message:
        return generic_message(domain, status)
    if dynamic and not re.search(
        r"\b(cannot|must|invalid|not found|required|unavailable|unsupported|stale|expired|"
        r"positive|match|exceeds|predate|future|before|after|disabled|active|terminal)\b",
        message,
        re.IGNORECASE,
    ):
        return generic_message(domain, status)
    return message[:240]


def code_for(reason: str, path: pathlib.Path, status: str) -> str:
    message, _ = literal_message(reason)
    domain = domain_name(path)
    code = words(message) if message else f"{domain}_{status.lower()}"
    code = ALIASES.get(code, code)
    if len(code) > 110:
        digest = hashlib.sha256(f"{path}:{status}:{reason}".encode()).hexdigest()[:10]
        code = f"{code[:98].rstrip('_')}_{digest}"
    if not code or not code[0].isalpha():
        code = f"{domain}_{code}"
    return code


def java_string(value: str) -> str:
    return json.dumps(value, ensure_ascii=False)


def recovery(status: str, code: str) -> str:
    if "idempotency" in code and ("different" in code or "mismatch" in code):
        return "reuse_the_key_only_with_the_identical_request_body"
    return {
        "BAD_REQUEST": "correct_the_request_before_retry",
        "UNAUTHORIZED": "replace_or_refresh_the_applicable_credential",
        "FORBIDDEN": "request_an_authorized_role_without_changing_credential_type",
        "NOT_FOUND": "verify_the_tenant_scoped_identifier",
        "CONFLICT": "refresh_authoritative_state_before_retry",
        "SERVICE_UNAVAILABLE": "retry_with_the_same_request_identity_after_retry_after",
    }[status]


def descriptors(text: str) -> tuple[dict[str, str], dict[str, tuple[str, str]]]:
    names: dict[str, str] = {}
    meanings: dict[str, tuple[str, str]] = {}
    pattern = re.compile(
        r"\b([A-Z][A-Z0-9_]*)\(\"([a-z][a-z0-9_]+)\",\s*HttpStatus\.([A-Z_]+),"
    )
    for name, code, status in pattern.findall(text):
        names[code] = name
        meanings[code] = (status, name)
    return names, meanings


def transform_file(
    path: pathlib.Path,
    known_names: dict[str, str],
    known_meanings: dict[str, tuple[str, str]],
    additions: dict[str, tuple[str, str, str]],
) -> tuple[str, str]:
    original = path.read_text(encoding="utf-8")
    ranges = constructor_ranges(original)
    if not ranges:
        return original, original
    transformed = original
    for start, end, expression in reversed(ranges):
        arguments = split_arguments(expression)
        if len(arguments) not in {2, 3} or not arguments[0].startswith("HttpStatus."):
            raise SystemExit(f"Unsupported ResponseStatusException shape in {path}: {expression}")
        status = arguments[0].removeprefix("HttpStatus.")
        reason = arguments[1]
        domain = domain_name(path)
        code = code_for(reason, path.relative_to(SOURCE), status)
        descriptor_message = safe_message(reason, domain, status)
        if code in known_meanings and known_meanings[code][0] != status:
            code = f"{domain}_{code}_{status.lower()}"
        if code in additions and additions[code][:2] != (status, descriptor_message):
            digest = hashlib.sha256(f"{path}:{status}:{reason}".encode()).hexdigest()[:8]
            code = f"{code}_{digest}"
        if code not in known_names and code not in additions:
            additions[code] = (status, descriptor_message, recovery(status, code))
        name = known_names.get(code, code.upper())
        replacement_arguments = ", ".join(arguments[1:])
        replacement = (
            f"new PublicApiException(PublicApiErrorDescriptor.{name}, "
            f"{replacement_arguments})"
        )
        transformed = transformed[:start] + replacement + transformed[end:]
    imports = (
        "import com.thaap.payments.api.PublicApiErrorDescriptor;\n"
        "import com.thaap.payments.api.PublicApiException;\n"
    )
    if "import com.thaap.payments.api.PublicApiException;" not in transformed:
        package_end = transformed.index(";", transformed.index("package ")) + 1
        transformed = transformed[:package_end] + "\n\n" + imports + transformed[package_end + 2 :]
    return original, transformed


def append_descriptors(
    original: str, additions: dict[str, tuple[str, str, str]]
) -> str:
    if not additions:
        return original
    marker = "\n\n    private final String code;"
    boundary = original.index(marker)
    prefix = original[:boundary]
    suffix = original[boundary:]
    semicolon = prefix.rfind(";")
    prefix = prefix[:semicolon] + "," + prefix[semicolon + 1 :]
    rendered: list[str] = []
    for code, (status, message, action) in sorted(additions.items()):
        rendered.append(
            f"    {code.upper()}({java_string(code)}, HttpStatus.{status}, "
            f"{'true' if status == 'SERVICE_UNAVAILABLE' else 'false'},\n"
            f"            {java_string(message)}, {java_string(action)})"
        )
    return prefix + "\n" + ",\n".join(rendered) + ";" + suffix


def patch_for(package: str) -> str:
    descriptor_original = DESCRIPTORS.read_text(encoding="utf-8")
    known_names, known_meanings = descriptors(descriptor_original)
    additions: dict[str, tuple[str, str, str]] = {}
    changes: list[tuple[pathlib.Path, str, str]] = []
    package_root = SOURCE / package
    for path in sorted(package_root.rglob("*.java")):
        original, transformed = transform_file(
            path, known_names, known_meanings, additions
        )
        if transformed != original:
            changes.append((path, original, transformed))
    descriptor_transformed = append_descriptors(descriptor_original, additions)
    if descriptor_transformed != descriptor_original:
        changes.append((DESCRIPTORS, descriptor_original, descriptor_transformed))
    output: list[str] = ["*** Begin Patch\n"]
    for path, original, transformed in changes:
        relative = path.relative_to(ROOT)
        diff = list(
            difflib.unified_diff(
                original.splitlines(keepends=True),
                transformed.splitlines(keepends=True),
                fromfile=str(relative),
                tofile=str(relative),
                n=3,
            )
        )
        output.append(f"*** Update File: {ROOT / relative}\n")
        output.extend("@@\n" if line.startswith("@@") else line for line in diff[2:])
    output.append("*** End Patch\n")
    return "".join(output)


def shortening_patch() -> str:
    descriptor_text = DESCRIPTORS.read_text(encoding="utf-8")
    rows = re.findall(
        r'\b([A-Z][A-Z0-9_]*)\("([a-z][a-z0-9_]+)"', descriptor_text
    )
    replacements: dict[str, tuple[str, str]] = {}
    for old_name, old_code in rows:
        if len(old_code) <= 63:
            continue
        digest = hashlib.sha256(old_code.encode()).hexdigest()[:10]
        new_code = f"{old_code[:52].rstrip('_')}_{digest}"
        replacements[old_name] = (new_code.upper(), new_code)
    changes: list[tuple[pathlib.Path, str, str]] = []
    for path in sorted(SOURCE.rglob("*.java")):
        original = path.read_text(encoding="utf-8")
        transformed = original
        for old_name, (new_name, new_code) in replacements.items():
            transformed = transformed.replace(
                f"PublicApiErrorDescriptor.{old_name}",
                f"PublicApiErrorDescriptor.{new_name}",
            )
            if path == DESCRIPTORS:
                transformed = transformed.replace(
                    f'{old_name}("', f'{new_name}("'
                ).replace(f'("{old_code_for_name(rows, old_name)}",', f'("{new_code}",')
        if transformed != original:
            changes.append((path, original, transformed))
    output: list[str] = ["*** Begin Patch\n"]
    for path, original, transformed in changes:
        diff = list(
            difflib.unified_diff(
                original.splitlines(keepends=True),
                transformed.splitlines(keepends=True),
                fromfile=str(path.relative_to(ROOT)),
                tofile=str(path.relative_to(ROOT)),
                n=3,
            )
        )
        output.append(f"*** Update File: {path}\n")
        output.extend("@@\n" if line.startswith("@@") else line for line in diff[2:])
    output.append("*** End Patch\n")
    return "".join(output)


def old_code_for_name(rows: list[tuple[str, str]], name: str) -> str:
    return next(code for candidate, code in rows if candidate == name)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("package", nargs="?")
    parser.add_argument("--shorten-existing", action="store_true")
    arguments = parser.parse_args()
    if arguments.shorten_existing:
        print(shortening_patch(), end="")
        return
    if arguments.package is None:
        parser.error("package is required unless --shorten-existing is used")
    print(patch_for(arguments.package), end="")


if __name__ == "__main__":
    main()
