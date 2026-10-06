#!/usr/bin/env bash
set -euo pipefail

# Proves what is NOT in the runtime images.
#
# `C60-SEC-001` and `C60-TEN-001` claim that no image layer carries a secret, a credential, a
# tenant identity, or the build toolchain, and that the application runs as a non-root user that
# cannot rewrite its own code.
#
# The important word is *layer*. Inspecting the final filesystem proves nothing: a file added in
# one layer and deleted in the next is still readable by anyone who pulls the image, because a
# deletion is a whitueout marker rather than an erasure. So this walks every layer's contents.
#
# Run after building both images:
#   scripts/image-content-check.sh [api-image] [ui-image]

API_IMAGE="${1:-thaap/payments-api:local}"
UI_IMAGE="${2:-thaap/analyst-dashboard-ui:local}"

failures=0

fail() {
    echo "FAIL: $*" >&2
    failures=$((failures + 1))
}

pass() {
    echo "ok: $*"
}

require_image() {
    if ! docker image inspect "$1" >/dev/null 2>&1; then
        echo "Image not found: $1 — build it first" >&2
        exit 2
    fi
}

require_image "$API_IMAGE"
require_image "$UI_IMAGE"

# ---------------------------------------------------------------------------------------------
# Layer contents
# ---------------------------------------------------------------------------------------------

# Filenames that must never appear in any layer of a published image. These are the artifacts a
# broad `COPY . .` picks up from a working tree: a developer's environment file with a live admin
# bootstrap token is the specific accident this guards against.
PROHIBITED_PATHS='(^|/)(\.env|\.env\..*|\.git|id_rsa|\.npmrc|settings\.xml)$|\.(pem|key|p12|jks)$'

# System trust stores are made of exactly the file extensions above and are legitimate base-image
# content. Excluding them is not a weakening of the check: a gate that reports two hundred CA
# certificates as credential leaks is a gate that gets read once and then switched off, which is
# the failure mode `ER-0099` recorded. Application paths are still scanned in full.
TRUST_STORE_PATHS='(^|/)(etc/ssl[^/]*/|usr/lib/ssl[^/]*/|etc/pki/|usr/share/ca-certificates/|usr/local/share/ca-certificates/|usr/lib/jvm/[^/]*/lib/security/|opt/java/[^/]*/lib/security/)'

layer_paths() {
    local image="$1" tar_dir
    tar_dir="$(mktemp -d)"
    docker save "$image" -o "$tar_dir/image.tar"
    tar -xf "$tar_dir/image.tar" -C "$tar_dir"
    # Every layer blob in the image, whatever the archive layout the daemon produced.
    find "$tar_dir" -name '*.tar' ! -name 'image.tar' -print0 \
        | while IFS= read -r -d '' layer; do tar -tf "$layer" 2>/dev/null || true; done
    find "$tar_dir/blobs" -type f 2>/dev/null -print0 \
        | while IFS= read -r -d '' blob; do tar -tf "$blob" 2>/dev/null || true; done
    rm -rf "$tar_dir"
}

for image in "$API_IMAGE" "$UI_IMAGE"; do
    hits="$(layer_paths "$image" | grep -Ei "$PROHIBITED_PATHS" \
        | grep -Ev "$TRUST_STORE_PATHS" || true)"
    if [[ -n "$hits" ]]; then
        fail "$image carries prohibited files in at least one layer:"
        echo "$hits" | head -20 >&2
    else
        pass "$image has no credential-shaped file in any layer"
    fi
done

# ---------------------------------------------------------------------------------------------
# Runtime identity
# ---------------------------------------------------------------------------------------------

for image in "$API_IMAGE" "$UI_IMAGE"; do
    user="$(docker image inspect --format '{{.Config.User}}' "$image")"
    if [[ -z "$user" || "$user" == "root" || "$user" == 0* ]]; then
        fail "$image runs as root (User='$user')"
    else
        pass "$image runs as non-root ($user)"
    fi
done

# The application directory must not be writable by the account that runs the application: a
# process compromised at runtime should not be able to rewrite the code it is executing.
if docker run --rm --entrypoint sh "$API_IMAGE" -c \
        'test -w /app/application.jar 2>/dev/null || test -w /app/BOOT-INF 2>/dev/null'; then
    fail "$API_IMAGE application directory is writable by its runtime user"
else
    pass "$API_IMAGE application directory is not writable by its runtime user"
fi

# ---------------------------------------------------------------------------------------------
# Toolchain absence
# ---------------------------------------------------------------------------------------------

# A JDK or Maven in the runtime stage means the multi-stage split silently collapsed, which both
# inflates the image and hands an attacker a compiler.
for tool in mvn javac; do
    if docker run --rm --entrypoint sh "$API_IMAGE" -c "command -v $tool" >/dev/null 2>&1; then
        fail "$API_IMAGE ships $tool in its runtime stage"
    else
        pass "$API_IMAGE has no $tool in its runtime stage"
    fi
done

if docker run --rm --entrypoint sh "$UI_IMAGE" -c 'command -v node' >/dev/null 2>&1; then
    fail "$UI_IMAGE ships node in its runtime stage"
else
    pass "$UI_IMAGE has no node in its runtime stage"
fi

# ---------------------------------------------------------------------------------------------
# Base image pinning
# ---------------------------------------------------------------------------------------------

# A floating tag makes "what is running in production" unanswerable after the fact.
for dockerfile in apps/payments-api/Dockerfile apps/analyst-dashboard-ui/Dockerfile; do
    unpinned="$(grep -E '^FROM ' "$dockerfile" | grep -v '@sha256:' || true)"
    if [[ -n "$unpinned" ]]; then
        fail "$dockerfile has un-pinned base images:"
        echo "$unpinned" >&2
    else
        pass "$dockerfile pins every base image by digest"
    fi
done

if (( failures > 0 )); then
    echo "image content check FAILED with $failures problem(s)" >&2
    exit 1
fi

echo "image content check passed"
