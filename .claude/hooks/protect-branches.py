#!/usr/bin/env python3
"""Refuses git history writes while master or develop is checked out.

`master` and `develop` are integration branches: everything that lands on them is supposed to
arrive through a reviewed pull request, which is the only place the repository gates run against
the change as a whole. A direct commit or push bypasses that, and the bypass is silent — the
branch simply moves and nobody is told which review it skipped.

This runs as a Claude Code PreToolUse hook on every Bash call, so it sees the command before it
executes rather than after. It reads the hook payload on stdin and answers with a deny decision
when the command would write history on a protected branch, and with nothing at all otherwise.

The check is deliberately about *branch mutation*, not about git in general: reading, staging,
branching, fetching and switching are all untouched, because the point is to route work through a
feature branch, not to make git unusable on develop.
"""

import json
import os
import re
import shlex
import subprocess
import sys

PROTECTED = {"master", "develop"}

# Subcommands that move the protected branch's own history. `push` is handled separately because
# it can target a protected branch from anywhere, and is the only one that leaves this machine.
HISTORY_WRITING = {
    "commit", "merge", "rebase", "cherry-pick", "revert", "am", "citool", "commit-tree",
}

BRANCH_DELETE_FLAGS = {"-d", "-D", "--delete", "--force-delete"}


def payload():
    try:
        return json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError):
        return {}


def deny(reason):
    """Blocks the tool call and tells the caller why, in the shape PreToolUse expects."""
    json.dump({
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": reason,
        }
    }, sys.stdout)
    sys.exit(0)


def segments(command):
    """Splits a compound command into the individual commands a shell would run.

    A guard that only inspects the first word is bypassed by `cd apps && git push`, so the
    separators that chain commands are split on before anything is matched.
    """
    for part in re.split(r"&&|\|\||;|\||\n", command):
        part = part.strip()
        if part:
            yield part


def tokenize(segment):
    try:
        return shlex.split(segment)
    except ValueError:
        # Unbalanced quotes: fall back to whitespace so a malformed command is still inspected
        # rather than silently skipped.
        return segment.split()


def git_invocation(tokens):
    """Returns (subcommand, arguments, repository_path) for a git call, or None.

    Leading `VAR=value` assignments and the global `-C <path>` / `-c key=value` options sit
    between the executable and the subcommand, so they are stepped over rather than assumed away.
    """
    index = 0
    while index < len(tokens) and re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*=.*", tokens[index]):
        index += 1
    if index >= len(tokens) or os.path.basename(tokens[index]) != "git":
        return None

    index += 1
    repository_path = None
    while index < len(tokens) and tokens[index].startswith("-"):
        if tokens[index] == "-C" and index + 1 < len(tokens):
            repository_path = tokens[index + 1]
            index += 2
        elif tokens[index] in {"-c", "--namespace", "--git-dir", "--work-tree"}:
            index += 2
        else:
            index += 1
    if index >= len(tokens):
        return None
    return tokens[index], tokens[index + 1:], repository_path


def current_branch(repository_path):
    try:
        result = subprocess.run(
            ["git", "-C", repository_path or ".", "rev-parse", "--abbrev-ref", "HEAD"],
            capture_output=True, text=True, timeout=5, check=False)
    except (OSError, subprocess.SubprocessError):
        return None
    return result.stdout.strip() or None


def protected_push_target(arguments):
    """Names the protected branch a push would land on, or None.

    A refspec may be `develop`, `HEAD:develop`, `+develop`, or `refs/heads/develop`, and a delete
    may be spelled `--delete develop` or `:develop`. All of them end at the same destination.
    """
    for argument in arguments:
        if argument.startswith("-"):
            continue
        candidate = argument.split(":")[-1].lstrip("+")
        candidate = re.sub(r"^refs/heads/", "", candidate)
        if candidate in PROTECTED:
            return candidate
    return None


def inspect(segment):
    invocation = git_invocation(tokenize(segment))
    if invocation is None:
        return None
    subcommand, arguments, repository_path = invocation
    branch = current_branch(repository_path)

    if subcommand == "push":
        target = protected_push_target(arguments)
        if target:
            return f"`git push` targets the protected branch `{target}`"
        # A bare `git push` pushes the current branch to its upstream of the same name.
        if branch in PROTECTED and not any(not a.startswith("-") for a in arguments[1:]):
            return f"`git push` would push the protected branch `{branch}`"
        return None

    if subcommand == "branch":
        if any(flag in arguments for flag in BRANCH_DELETE_FLAGS):
            for argument in arguments:
                if argument in PROTECTED:
                    return f"`git branch` would delete the protected branch `{argument}`"
        return None

    if branch not in PROTECTED:
        return None

    if subcommand in HISTORY_WRITING:
        return f"`git {subcommand}` would write history directly on `{branch}`"

    if subcommand == "reset":
        moves_head = any(not a.startswith("-") for a in arguments) or "--hard" in arguments
        if moves_head:
            return f"`git reset` would move or discard history on `{branch}`"

    return None


def main():
    tool_input = payload().get("tool_input") or {}
    command = tool_input.get("command")
    if not isinstance(command, str):
        return

    for segment in segments(command):
        reason = inspect(segment)
        if reason:
            deny(
                f"Blocked by .claude/hooks/protect-branches.py: {reason}. "
                "master and develop only move through a reviewed pull request. "
                "Create or switch to a feature branch, commit there, and open a PR. "
                "If this specific command really has to run, the user must run it themselves."
            )


if __name__ == "__main__":
    main()
