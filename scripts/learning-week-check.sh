#!/usr/bin/env bash
set -euo pipefail

week_arg="${1:-$(date +%Y-W%U)}"
weekly_note="docs/learning/weekly/${week_arg}.md"
rubric="docs/learning/SENIOR_STAFF_ML_ARCHITECT_RUBRIC.md"
spaced_repetition="docs/learning/SPACED_REPETITION_TRACKER.md"
template="docs/learning/WEEKLY_ARCHITECTURE_REVIEW_TEMPLATE.md"

missing=0

echo "Learning week check for ${week_arg}"
echo

if [[ -f "$weekly_note" ]]; then
  echo "OK weekly review exists: $weekly_note"
else
  echo "MISSING weekly review: $weekly_note"
  echo "Create it from $template."
  missing=1
fi

if [[ -f "$rubric" ]]; then
  echo "OK rubric exists: $rubric"
else
  echo "MISSING rubric: $rubric"
  missing=1
fi

if [[ -f "$spaced_repetition" ]]; then
  echo "OK spaced repetition tracker exists: $spaced_repetition"
else
  echo "MISSING spaced repetition tracker: $spaced_repetition"
  missing=1
fi

echo
echo "Remember the weekly rule:"
echo "Daily notes build memory; weekly reviews build Staff-level judgment."

exit "$missing"
