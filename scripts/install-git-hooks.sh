#!/usr/bin/env bash
#
# Points this clone's git hooks at the versioned .githooks directory.
#
# Hooks under .git/hooks are not version controlled, so a hook that only exists there protects
# exactly one clone and disappears on the next fresh checkout. Setting core.hooksPath makes the
# hooks part of the repository, reviewable in a diff like anything else.
#
# Run once per clone:
#
#     scripts/install-git-hooks.sh

set -euo pipefail

repository_root="$(git rev-parse --show-toplevel)"
cd "$repository_root"

if [[ ! -d .githooks ]]; then
  echo "No .githooks directory found at $repository_root" >&2
  exit 1
fi

chmod +x .githooks/*
git config core.hooksPath .githooks

echo "OK git hooks path set to .githooks"
echo "Installed hooks:"
for hook in .githooks/*; do
  [[ -f "$hook" ]] || continue
  echo "  - $(basename "$hook")"
done
echo
echo "The pre-push hook runs the repository gates. The backend and Python suites run in CI on"
echo "every push, so a local bypass with --no-verify saves time rather than skipping the gate."
