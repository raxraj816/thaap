#!/usr/bin/env bash
set -euo pipefail

deep_dive_required=false
date_arg=""

for arg in "$@"; do
  case "$arg" in
    --deep-dive-required)
      deep_dive_required=true
      ;;
    [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9])
      date_arg="$arg"
      ;;
    *)
      echo "Unknown argument: $arg" >&2
      echo "Usage: scripts/learning-day-check.sh [YYYY-MM-DD] [--deep-dive-required]" >&2
      exit 2
      ;;
  esac
done

if [[ -z "$date_arg" ]]; then
  date_arg="$(date +%F)"
fi

daily_note="docs/learning/daily/${date_arg}.md"
deep_dive="docs/learning/daily/${date_arg}-deep-dive.md"
checklist="docs/learning/DAILY_APPRENTICESHIP_CHECKLIST.md"

missing=0

echo "Learning day check for ${date_arg}"
echo

if [[ -f "$daily_note" ]]; then
  echo "OK daily note exists: $daily_note"
else
  echo "MISSING daily note: $daily_note"
  echo "Create it from docs/learning/DAILY_NOTE_TEMPLATE.md before ending the day."
  missing=1
fi

if [[ "$deep_dive_required" == true ]]; then
  if [[ -f "$deep_dive" ]]; then
    echo "OK deep dive exists: $deep_dive"
  else
    echo "MISSING deep dive: $deep_dive"
    echo "Create it from docs/learning/DEEP_DIVE_TEMPLATE.md for non-trivial implementation work."
    missing=1
  fi
else
  if [[ -f "$deep_dive" ]]; then
    echo "OK optional deep dive exists: $deep_dive"
  else
    echo "INFO no deep dive found. That is acceptable only for trivial or non-implementation days."
  fi
fi

if [[ -f "$checklist" ]]; then
  echo "OK checklist exists: $checklist"
else
  echo "MISSING checklist: $checklist"
  missing=1
fi

echo
echo "Remember the completion rule:"
echo "No non-trivial implementation day is complete until the daily note, deep dive, tests, and explain-back questions are handled."

exit "$missing"
