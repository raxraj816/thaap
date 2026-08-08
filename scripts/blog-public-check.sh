#!/usr/bin/env bash
set -euo pipefail

# Literal search across markdown that does not require ripgrep. See the equivalent note in
# scripts/commit-readiness-check.sh: a gate that cannot run because a tool is missing reports the
# same failure as a gate that found something, which makes it unreadable exactly when it matters.
search_markdown_literal() {
  local pattern="$1"
  shift
  if command -v rg >/dev/null 2>&1; then
    rg -n --glob '*.md' --fixed-strings "$pattern" "$@"
  else
    grep -rnF --include='*.md' -- "$pattern" "$@"
  fi
}

if [[ "$#" -eq 0 ]]; then
  targets=(docs/blog/drafts docs/blog/published)
else
  targets=("$@")
fi

missing=0

for target in "${targets[@]}"; do
  if [[ ! -e "$target" ]]; then
    echo "MISSING target: $target" >&2
    missing=1
  fi
done

if [[ "$missing" -ne 0 ]]; then
  exit "$missing"
fi

patterns=(
  "THAAP"
  "/Users/"
  "apps/"
  "docs/"
  "scripts/"
  "feature/"
  "develop"
  "weekly review"
  "source file"
  "repository"
  "case study"
  "what we built"
  "This week I built"
  "What THAAP"
  "Week:"
  "Status: Draft"
  "Public concept:"
  "Target reader:"
  "Opening scene:"
  "Reader promise:"
  "Private Editorial Brief"
  "Public Article Draft"
  "Concept:"
  "Reader pain:"
  "One-line thesis:"
  "Concrete artifact:"
  "Main diagram:"
  "What makes this non-obvious:"
  "Naive design to challenge:"
  "Better design:"
  "What not to cover:"
  "Memorable takeaway:"
  "## Thesis"
  "Request path:"
  "Data model or records:"
  "Decision logic:"
  "Human review path:"
)

echo "Public blog check"
echo

failed=0

for pattern in "${patterns[@]}"; do
  if search_markdown_literal "$pattern" "${targets[@]}"; then
    failed=1
  fi
done

if [[ "$failed" -ne 0 ]]; then
  echo
  echo "FAIL public blog draft contains private/project-status language."
  echo "Keep published articles concept-led and free of product names, repo paths, branches, commits, and build status."
  exit 1
fi

echo "OK public blog drafts do not contain blocked private/project-status terms."
