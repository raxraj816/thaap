#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: scripts/blog-week-start.sh [YYYY-WNN] short-slug" >&2
  echo "Example: scripts/blog-week-start.sh 2026-W25 why-fraud-systems-need-explainable-intelligence" >&2
}

week_arg=""
slug_arg=""

if [[ "$#" -eq 1 ]]; then
  week_arg="$(date +%G-W%V)"
  slug_arg="$1"
elif [[ "$#" -eq 2 ]]; then
  week_arg="$1"
  slug_arg="$2"
else
  usage
  exit 2
fi

if [[ ! "$week_arg" =~ ^[0-9]{4}-W[0-9]{2}$ ]]; then
  echo "Invalid week: $week_arg" >&2
  usage
  exit 2
fi

if [[ ! "$slug_arg" =~ ^[a-z0-9]+(-[a-z0-9]+)*$ ]]; then
  echo "Invalid slug: $slug_arg" >&2
  echo "Use lowercase letters, numbers, and hyphens only." >&2
  exit 2
fi

template="docs/blog/POST_TEMPLATE.md"
draft_dir="docs/blog/drafts"
draft="${draft_dir}/${week_arg}-${slug_arg}.md"

mkdir -p "$draft_dir"

if [[ ! -f "$template" ]]; then
  echo "Missing template: $template" >&2
  exit 1
fi

if [[ -f "$draft" ]]; then
  echo "OK blog draft already exists: $draft"
else
  sed "s/YYYY-WNN/${week_arg}/g" "$template" > "$draft"
  echo "CREATED blog draft: $draft"
fi

echo
echo "Next steps:"
echo "1. Fill the Private Editorial Brief."
echo "2. Write the Public Article Draft with a scenario, artifact, diagram, tradeoff, and failure modes."
echo "3. Delete the private brief and all template prompt text before publishing."
echo "4. Score the article with docs/blog/EDITORIAL_RUBRIC.md."
echo "5. Run scripts/blog-public-check.sh and docs/blog/WEEKLY_BLOG_CHECKLIST.md."
