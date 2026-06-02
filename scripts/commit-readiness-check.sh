#!/usr/bin/env bash
set -euo pipefail

date_arg=""
deep_dive_required=false
run_tests=false

for arg in "$@"; do
  case "$arg" in
    --deep-dive-required)
      deep_dive_required=true
      ;;
    --run-tests)
      run_tests=true
      ;;
    [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9])
      date_arg="$arg"
      ;;
    *)
      echo "Unknown argument: $arg" >&2
      echo "Usage: scripts/commit-readiness-check.sh [YYYY-MM-DD] [--deep-dive-required] [--run-tests]" >&2
      exit 2
      ;;
  esac
done

if [[ -z "$date_arg" ]]; then
  date_arg="$(date +%F)"
fi

missing=0

echo "Commit readiness check for ${date_arg}"
echo

required_files=(
  "README.md"
  "apps/payments-api/README.md"
  "docs/IMPLEMENTATION_MASTER_PLAN.md"
  "docs/COMMIT_READINESS_CHECKLIST.md"
)

for file in "${required_files[@]}"; do
  if [[ -f "$file" ]]; then
    echo "OK required doc exists: $file"
  else
    echo "MISSING required doc: $file"
    missing=1
  fi
done

echo
echo "Checking for obviously stale root README status..."
if rg -n "Day 2 is complete|Day 3 is complete" README.md >/dev/null; then
  echo "STALE README.md status found:"
  rg -n "Day 2 is complete|Day 3 is complete" README.md
  missing=1
else
  echo "OK README.md does not advertise stale Day 2/Day 3 status."
fi

echo
if [[ "$deep_dive_required" == true ]]; then
  scripts/learning-day-check.sh "$date_arg" --deep-dive-required
else
  scripts/learning-day-check.sh "$date_arg"
fi

echo
echo "Git status summary:"
git status --short

echo
echo "Diff stat:"
git diff --stat

if [[ "$run_tests" == true ]]; then
  echo
  echo "Running backend tests..."
  mvn -f apps/payments-api/pom.xml -Dmaven.repo.local=.m2/repository test
fi

echo
echo "Manual review reminders:"
echo "- Check root README and service README for truthful current status."
echo "- Check master plan if scope, sequencing, or invariants changed."
echo "- Check runtime smoke test evidence if local startup behavior changed."
echo "- Check that unrelated files are not mixed into the commit."

exit "$missing"
