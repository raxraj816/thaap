#!/usr/bin/env bash
set -euo pipefail

# Prints the payments-api project version from pom.xml.
#
# One place, because three callers need it — the release identity check, the compose stack check
# and the release workflow — and three copies of "parse the pom" is three chances to read the
# wrong <version>. The first one in the file belongs to the spring-boot-starter-parent
# declaration; the project's own is the first after </parent>.
#
# Deliberately sed rather than an embedded Python heredoc: bash 3.2, which is what macOS ships,
# fails to parse a here-document containing an apostrophe inside a command substitution. That is
# a real failure that happened here, and a gate that cannot run on the maintainer's own machine
# is a gate that gets worked around.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

sed -n '/<\/parent>/,$p' "$ROOT_DIR/apps/payments-api/pom.xml" \
    | grep -m1 -o '<version>[^<]*</version>' \
    | sed 's/<[^>]*>//g'
