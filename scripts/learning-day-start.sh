#!/usr/bin/env bash
set -euo pipefail

date_arg=""
create_deep_dive=false

for arg in "$@"; do
  case "$arg" in
    --deep-dive)
      create_deep_dive=true
      ;;
    [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9])
      date_arg="$arg"
      ;;
    *)
      echo "Unknown argument: $arg" >&2
      echo "Usage: scripts/learning-day-start.sh [YYYY-MM-DD] [--deep-dive]" >&2
      exit 2
      ;;
  esac
done

if [[ -z "$date_arg" ]]; then
  date_arg="$(date +%F)"
fi

daily_note="docs/learning/daily/${date_arg}.md"
deep_dive="docs/learning/daily/${date_arg}-deep-dive.md"
daily_template="docs/learning/DAILY_NOTE_TEMPLATE.md"
deep_template="docs/learning/DEEP_DIVE_TEMPLATE.md"

mkdir -p docs/learning/daily

if [[ -f "$daily_note" ]]; then
  echo "OK daily note already exists: $daily_note"
else
  sed "s/YYYY-MM-DD/${date_arg}/g" "$daily_template" > "$daily_note"
  echo "CREATED daily note: $daily_note"
fi

if [[ "$create_deep_dive" == true ]]; then
  if [[ -f "$deep_dive" ]]; then
    echo "OK deep dive already exists: $deep_dive"
  else
    sed "s/YYYY-MM-DD/${date_arg}/g" "$deep_template" > "$deep_dive"
    echo "CREATED deep dive: $deep_dive"
  fi
else
  echo "INFO deep dive not created. Use --deep-dive for non-trivial implementation work."
fi

echo
echo "Apprenticeship loop for today:"
echo "1. Explain the concept from first principles."
echo "2. Build the smallest production-shaped slice."
echo "3. Prove invariants with tests."
echo "4. Record what changed, what was learned, and what was deferred."
echo "5. Finish with explain-back questions."
