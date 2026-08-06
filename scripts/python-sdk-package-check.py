#!/usr/bin/env python3
"""Prove the supported SDK installs and runs as a package, not just as a source tree.

Importing from the repository working directory proves almost nothing about what a
merchant receives. The failure this catches is a package that is missing generated
modules, or that depends on something the manifest never declared, so it works for
whoever built it and fails on the first clean machine.

Three properties are checked:

1. Clean-environment installation with no package index reachable, followed by the
   real test suite executing from the installed package. `--no-index` is the point:
   an install that silently reaches a package host is not evidence of a complete
   declared dependency set.
2. Build determinism, compared over the archive's member names and content digests
   rather than its bytes, because archive metadata carries build timestamps that say
   nothing about whether the shipped code is the same.
3. The declared runtime range genuinely refuses the versions outside it, so an
   unsupported interpreter fails at install time rather than at the first payment.
"""

from __future__ import annotations

import argparse
import hashlib
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile


REPOSITORY_ROOT = pathlib.Path(__file__).resolve().parent.parent
SDK_ROOT = REPOSITORY_ROOT / "clients" / "python" / "thaap_sdk"
UNSUPPORTED_RUNTIMES = ("3.9", "3.10", "3.15")
SUPPORTED_RUNTIMES = ("3.11", "3.12", "3.13", "3.14")


def build_wheel(destination: pathlib.Path) -> pathlib.Path:
    # Build from a pristine copy rather than the working tree. `pip wheel` leaves a
    # `build/` directory behind and reuses it on the next run, which would both litter
    # the repository and let a stale artifact hide a real determinism difference.
    sources = destination.parent / f"{destination.name}-sources"
    shutil.copytree(
        SDK_ROOT, sources,
        ignore=shutil.ignore_patterns(
            ".venv", "__pycache__", ".pytest_cache", "build", "dist", "*.egg-info"))
    subprocess.run(
        [sys.executable, "-m", "pip", "wheel", "--no-deps", "--wheel-dir",
         str(destination), str(sources)],
        check=True, capture_output=True, text=True)
    wheels = sorted(destination.glob("thaap_risk_sdk-*.whl"))
    if len(wheels) != 1:
        raise SystemExit(f"expected exactly one built wheel, found {len(wheels)}")
    return wheels[0]


def wheel_contents(wheel: pathlib.Path) -> dict[str, str]:
    """Member name to content digest, ignoring archive timestamps and ordering."""
    with zipfile.ZipFile(wheel) as archive:
        return {
            name: hashlib.sha256(archive.read(name)).hexdigest()
            for name in sorted(archive.namelist())
            # RECORD lists every other member's digest, so comparing it as content
            # would double count, and its own ordering is not meaningful.
            if not name.endswith("RECORD")
        }


def check_offline_clean_install(workspace: pathlib.Path) -> list[str]:
    failures: list[str] = []
    wheelhouse = workspace / "wheelhouse"
    wheelhouse.mkdir()
    wheel = build_wheel(wheelhouse)

    # Stage the declared dependencies once, then install with the index switched off.
    download = subprocess.run(
        [sys.executable, "-m", "pip", "download", "--dest", str(wheelhouse),
         str(wheelhouse.parent / "wheelhouse-sources"), "pytest"],
        capture_output=True, text=True, check=False)
    if download.returncode != 0:
        return ["could not stage dependencies for the offline install "
                f"(pip download failed: {download.stderr.strip().splitlines()[-1:]})"]

    environment = workspace / "clean-env"
    subprocess.run([sys.executable, "-m", "venv", str(environment)], check=True)
    python = environment / "bin" / "python"

    install = subprocess.run(
        [str(python), "-m", "pip", "install", "--no-index",
         "--find-links", str(wheelhouse), f"{wheel}[test]", "pytest"],
        capture_output=True, text=True, check=False)
    if install.returncode != 0:
        return [f"offline clean-environment install failed: {install.stderr.strip()[-800:]}"]

    # The suite must pass from the installed package, using the repository only for
    # the test sources themselves.
    suite = subprocess.run(
        [str(python), "-m", "pytest", "tests", "-q"],
        cwd=SDK_ROOT, capture_output=True, text=True, check=False)
    if suite.returncode != 0:
        failures.append(
            f"installed package failed its suite: {suite.stdout.strip()[-800:]}")

    imported = subprocess.run(
        [str(python), "-c",
         "import thaap_sdk, thaap_sdk.generated.api_client as a; "
         "print(thaap_sdk.ThaapClient.__name__, a.ApiClient.__name__)"],
        cwd=workspace, capture_output=True, text=True, check=False)
    if imported.returncode != 0:
        failures.append(
            "the installed package could not be imported outside the source tree: "
            f"{imported.stderr.strip()[-400:]}")
    return failures


def check_build_determinism(workspace: pathlib.Path) -> list[str]:
    first = build_wheel(workspace / "build-a")
    second = build_wheel(workspace / "build-b")
    if wheel_contents(first) == wheel_contents(second):
        return []
    left, right = wheel_contents(first), wheel_contents(second)
    differing = sorted(
        set(left) ^ set(right)
        | {name for name in set(left) & set(right) if left[name] != right[name]})
    return [f"repeated builds produced different package contents: {differing[:10]}"]


def release_tuple(version: str) -> tuple[int, ...]:
    return tuple(int(part) for part in version.split("."))


def accepts_runtime(specifier: str, version: str) -> bool:
    """Evaluate a `requires-python` specifier without depending on `packaging`.

    A gate that cannot run unless the host happens to have a third-party package
    installed is the failure this whole audit is about: it reports FAIL for an
    environment reason and nobody can tell that from a real one. `requires-python`
    only ever carries release comparisons, so it is evaluated directly here and
    `packaging` is used when present purely as the more precise implementation.
    """
    candidate = release_tuple(version)
    for clause in (part.strip() for part in specifier.split(",")):
        if not clause:
            continue
        match = re.fullmatch(r"(>=|<=|==|!=|>|<)\s*([0-9]+(?:\.[0-9]+)*)", clause)
        if match is None:
            raise ValueError(f"unsupported requires-python clause {clause!r}")
        operator, bound_text = match.group(1), match.group(2)
        bound = release_tuple(bound_text)
        # Pad to equal length so 3.11 compares against 3.11.0 as the same release.
        width = max(len(candidate), len(bound))
        left = candidate + (0,) * (width - len(candidate))
        right = bound + (0,) * (width - len(bound))
        satisfied = {
            ">=": left >= right,
            "<=": left <= right,
            "==": left == right,
            "!=": left != right,
            ">": left > right,
            "<": left < right,
        }[operator]
        if not satisfied:
            return False
    return True


def check_declared_runtime_range() -> list[str]:
    text = (SDK_ROOT / "pyproject.toml").read_text(encoding="utf-8")
    match = re.search(r'requires-python\s*=\s*"([^"]+)"', text)
    if match is None:
        return ["pyproject.toml declares no requires-python"]
    declared = match.group(1)

    try:
        from packaging.specifiers import SpecifierSet
    except ModuleNotFoundError:
        accepts = lambda version: accepts_runtime(declared, version)
    else:
        specifier = SpecifierSet(declared)
        accepts = lambda version: version in specifier

    failures = [
        f"declared runtime range {declared} rejects supported Python {version}"
        for version in SUPPORTED_RUNTIMES
        if not accepts(version)
    ]
    failures += [
        f"declared runtime range {declared} accepts untested Python {version}"
        for version in UNSUPPORTED_RUNTIMES
        if accepts(version)
    ]
    return failures


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--skip-install",
        action="store_true",
        help="skip the clean-environment install (which needs one dependency staging pass)")
    arguments = parser.parse_args()

    failures: list[str] = []
    with tempfile.TemporaryDirectory() as temporary:
        workspace = pathlib.Path(temporary)
        failures += check_declared_runtime_range()
        failures += check_build_determinism(workspace)
        if not arguments.skip_install:
            failures += check_offline_clean_install(workspace)
        shutil.rmtree(workspace / "build-a", ignore_errors=True)

    if failures:
        print("Python SDK package check FAIL")
        for failure in failures:
            print(f"  {failure}")
        return 1
    print("Python SDK package check PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
