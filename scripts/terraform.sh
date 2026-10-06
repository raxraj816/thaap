#!/usr/bin/env bash
set -euo pipefail

# Runs Terraform at one pinned version, in a container, against infra/terraform.
#
# Containerised rather than installed for the same reason the application is: a version that
# differs between a laptop and CI produces a plan that differs between a laptop and CI, and the
# whole claim of this chapter is that the topology in version control is the topology that exists.
# Nobody has to install anything, and nobody can be on a different version by accident.
#
#   scripts/terraform.sh fmt -check -recursive
#   scripts/terraform.sh init -backend=false
#   scripts/terraform.sh validate
#
# Credentials are passed through when present, so the same entry point serves plan and apply once
# an account exists. It mounts the repository read-write because Terraform writes .terraform/ and
# lock files, and nothing else.

TERRAFORM_VERSION="1.9.8"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKDIR="${THAAP_TERRAFORM_DIR:-infra/terraform}"

credential_flags=()
# A named profile needs the shared config directory; an env-var identity needs the variables.
if [[ -n "${AWS_PROFILE:-}" ]]; then
    credential_flags+=(--env "AWS_PROFILE=$AWS_PROFILE" --volume "$HOME/.aws:/root/.aws:ro")
fi
for name in AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN AWS_REGION \
            AWS_DEFAULT_REGION TF_VAR_region; do
    if [[ -n "${!name:-}" ]]; then
        credential_flags+=(--env "$name")
    fi
done

# `${array[@]+"${array[@]}"}` rather than `"${array[@]}"`: bash 3.2, which is what macOS ships,
# treats an empty array as an unbound variable under `set -u`. Without credentials configured the
# array is empty, so the plain form fails on exactly the machine this is developed on — the same
# class of portability trap as the here-document that broke release-identity-check.sh.
exec docker run --rm \
    --volume "$ROOT_DIR:/repo" \
    --workdir "/repo/$WORKDIR" \
    ${credential_flags[@]+"${credential_flags[@]}"} \
    "hashicorp/terraform:$TERRAFORM_VERSION" "$@"
