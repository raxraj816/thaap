#!/usr/bin/env python3
"""Reject literal AWS region strings outside the files allowed to declare them.

THAAP is a global, multi-rail product. The first deployment lands in one region, and every
region reference is a variable so that standing up a second is a `terraform apply` rather than a
refactor. Infrastructure that names one region in fifty places is the first thing a second region
forces somebody to rewrite, and by then it is load-bearing.

The rule is narrow on purpose: a region literal is allowed in `variables.tf`, where a default
belongs, and nowhere else. Two literals are legitimate and both live there —

  region                          the deployment region
  cloudfront_control_plane_region the region CloudFront requires for a viewer certificate and a
                                  CLOUDFRONT-scoped WebACL, which is an AWS API constraint rather
                                  than a deployment choice

Anything else is drift, and the check names the file and line so it can be fixed rather than
argued about.
"""

from __future__ import annotations

import pathlib
import re
import sys


ROOT = pathlib.Path(__file__).resolve().parents[1]
TERRAFORM_ROOT = ROOT / "infra" / "terraform"

# Every current AWS partition region shape: two lowercase letters, a hyphen, a word, a hyphen,
# a digit. Matches ap-south-1, us-east-1, eu-central-1, us-gov-west-1, cn-north-1.
REGION_LITERAL = re.compile(r"\b[a-z]{2}(?:-gov)?-[a-z]+-\d\b")

# Files permitted to contain one, by basename. A default has to be written down somewhere.
ALLOWED_BASENAMES = {"variables.tf"}

# Documentation and lock files are not configuration. A README naming ap-south-1 in a worked
# example is helpful; a module doing it is the defect.
SCANNED_SUFFIXES = (".tf",)


def offending_lines(path: pathlib.Path) -> list[tuple[int, str]]:
    findings: list[tuple[int, str]] = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        # A comment explaining why a region is fixed is not a hardcoded region. The check is
        # about what Terraform evaluates, not about what a reader is told.
        code = line.split("#", 1)[0]
        if REGION_LITERAL.search(code):
            findings.append((number, line.strip()))
    return findings


def main() -> int:
    if not TERRAFORM_ROOT.is_dir():
        print(f"{TERRAFORM_ROOT} does not exist", file=sys.stderr)
        return 1

    scanned = 0
    errors: list[str] = []
    for path in sorted(TERRAFORM_ROOT.rglob("*")):
        if not path.is_file() or path.suffix not in SCANNED_SUFFIXES:
            continue
        # .terraform/ holds vendored provider code that is not ours to hold to this rule.
        if ".terraform" in path.parts:
            continue
        scanned += 1
        if path.name in ALLOWED_BASENAMES:
            continue
        for number, text in offending_lines(path):
            errors.append(
                f"{path.relative_to(ROOT)}:{number}: hardcoded region — "
                f"use a variable instead\n    {text}")

    if scanned == 0:
        print("No Terraform files found; the check is not reading what it thinks it is",
              file=sys.stderr)
        return 1

    if errors:
        for error in errors:
            print(f"FAIL: {error}", file=sys.stderr)
        print(
            f"terraform region parameterisation check FAILED with {len(errors)} problem(s)",
            file=sys.stderr)
        return 1

    print(
        f"Terraform region parameterisation check passed: {scanned} file(s), "
        "every region derived from a variable")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
