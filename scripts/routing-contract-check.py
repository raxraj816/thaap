#!/usr/bin/env python3
"""Hold the four routers that carry the API prefixes equal to one another.

The workspace reaches the API through a different router depending on where it runs:

  vite.config.ts        the dev proxy, on a developer machine
  nginx.conf            the workspace container, locally and in compose
  infra/terraform       CloudFront behaviors, in AWS
  compose-stack-check   the list the stack proof actually requests

`src/routingContract.test.ts` already holds the first two together. Nothing held the third or the
fourth, which is the half of Section 9's routing contract that only existed as a paragraph.

Drift here is worth machinery because of how it fails. A prefix missing from CloudFront is not a
502 or a 404 — it is the default behavior serving `index.html` with a `200`. The API call
succeeds, returns HTML, and the workspace reports something unrelated three screens away. No
status-code check can see it, and it appears only in the environment nobody runs locally.
"""

from __future__ import annotations

import pathlib
import json
import re
import sys


ROOT = pathlib.Path(__file__).resolve().parents[1]
VITE_CONFIG = ROOT / "apps/analyst-dashboard-ui/vite.config.ts"
NGINX_CONF = ROOT / "apps/analyst-dashboard-ui/nginx.conf"
TERRAFORM_LOCALS = ROOT / "infra/terraform/locals.tf"
STACK_CHECK = ROOT / "scripts/compose-stack-check.sh"

VITE_PREFIX = re.compile(r'^\s*"(/[a-z0-9_]+)":\s*"http', re.MULTILINE)
NGINX_PREFIX = re.compile(r"^\s*location\s+~\s+\^(/[a-z0-9_]+)\(\?:/\|\$\)\s*\{[^}]*proxy_pass", re.MULTILINE)
STACK_PREFIXES = re.compile(r"^PREFIXES=\((.*)\)", re.MULTILINE)


def vite_prefixes() -> set[str]:
    return set(VITE_PREFIX.findall(VITE_CONFIG.read_text(encoding="utf-8")))


def nginx_prefixes() -> set[str]:
    return set(NGINX_PREFIX.findall(NGINX_CONF.read_text(encoding="utf-8")))


def terraform_prefixes() -> set[str]:
    """The `api_path_prefixes` list CloudFront behaviors are generated from."""
    text = TERRAFORM_LOCALS.read_text(encoding="utf-8")
    blocks = re.findall(r"(?:api|operational)_path_prefixes\s*=\s*\[(.*?)\]", text, re.S)
    if len(blocks) != 2:
        return set()
    return set(re.findall(r'"(/[a-z0-9_]+)"', " ".join(blocks)))


def stack_check_prefixes() -> set[str]:
    match = STACK_PREFIXES.search(STACK_CHECK.read_text(encoding="utf-8"))
    if not match:
        return set()
    return {entry for entry in match.group(1).split() if entry.startswith("/")}


def main() -> int:
    sources = {
        "vite.config.ts": vite_prefixes(),
        "nginx.conf": nginx_prefixes(),
        "infra/terraform/locals.tf": terraform_prefixes(),
        "scripts/compose-stack-check.sh": stack_check_prefixes(),
    }

    errors: list[str] = []
    for name, prefixes in sources.items():
        if not prefixes:
            errors.append(
                f"{name}: no API prefixes found — the parser is no longer reading this file, "
                "which silently disables the comparison")

    if errors:
        for error in errors:
            print(f"FAIL: {error}", file=sys.stderr)
        return 1

    canonical = sources["vite.config.ts"]
    for name, prefixes in sources.items():
        if prefixes == canonical:
            continue
        missing = sorted(canonical - prefixes)
        extra = sorted(prefixes - canonical)
        detail = []
        if missing:
            detail.append(f"missing {', '.join(missing)}")
        if extra:
            detail.append(f"unexpected {', '.join(extra)}")
        errors.append(f"{name} disagrees with vite.config.ts: {'; '.join(detail)}")

    # A CloudFront behaviour is a pattern, not a prefix, and each prefix needs both the bare path
    # and everything under it. Checking the generation rule as well as the list is what stops a
    # correct list producing behaviors that match nothing.
    terraform_text = TERRAFORM_LOCALS.read_text(encoding="utf-8")
    if 'for prefix in concat(local.api_path_prefixes, local.operational_path_prefixes) : [prefix, "${prefix}/*"]' not in terraform_text:
        errors.append(
            "infra/terraform/locals.tf: api_cache_behaviors no longer generates both the bare "
            "prefix and its subtree from api_path_prefixes")

    contract = json.loads((ROOT / "openapi/thaap-public-api-2026-08-01.yaml").read_text())
    public_prefixes = {"/" + path.split("/")[1] for path in contract["paths"]}
    if not public_prefixes <= canonical:
        errors.append("published API prefixes absent from routers: " + ", ".join(sorted(public_prefixes - canonical)))

    if errors:
        for error in errors:
            print(f"FAIL: {error}", file=sys.stderr)
        print(f"routing contract check FAILED with {len(errors)} problem(s)", file=sys.stderr)
        return 1

    print(
        f"Routing contract check passed: {len(canonical)} prefixes identical across "
        f"{len(sources)} routers")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
