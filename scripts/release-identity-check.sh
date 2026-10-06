#!/usr/bin/env bash
set -euo pipefail

# Proves that a release artifact can state what it is.
#
# The promise is narrow and the failure it prevents is not: during an incident, the only reliable
# answer to "what is running?" must come from the artifact itself. A tag on a task definition
# records what somebody intended to deploy. A registry digest records what was pulled. Neither
# survives a rollback performed at 3am by someone reading a different runbook.
#
# So this builds the images the way a release builds them, and then interrogates them:
#
#   1. the working tree is clean, so the commit being stamped is the commit being built
#   2. both images carry the version and revision as OCI labels
#   3. the API artifact carries the same pair in build-info.properties, which is what
#      /actuator/info serves to a caller who has no shell access to the task
#   4. the image is addressable by semantic version and by commit SHA, and both names resolve
#      to one image rather than to two builds that happen to share a moment
#
# What this deliberately does not do is start the application: that needs PostgreSQL, Redis and
# Kafka, and proving the HTTP surface is compose-stack-check.sh's job, where the stack already
# exists. Here the question is what the artifact contains, which is answerable without running it.
#
#   scripts/release-identity-check.sh [--allow-dirty]

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ALLOW_DIRTY=false
for argument in "$@"; do
    case "$argument" in
        --allow-dirty) ALLOW_DIRTY=true ;;
        *) echo "Unknown argument: $argument" >&2; exit 2 ;;
    esac
done

failures=0

fail() {
    echo "FAIL: $*" >&2
    failures=$((failures + 1))
}

pass() {
    echo "ok: $*"
}

# ---------------------------------------------------------------------------------------------
# The inputs being stamped
# ---------------------------------------------------------------------------------------------

VERSION="$(bash "$ROOT_DIR/scripts/project-version.sh")"
COMMIT="$(git rev-parse HEAD)"

# A dirty tree means the source that produced the image is not the source at that commit, so the
# stamp would be a lie told by a build that had every opportunity to notice.
if [[ -n "$(git status --porcelain)" ]]; then
    if [[ "$ALLOW_DIRTY" == true ]]; then
        echo "warning: working tree is dirty; --allow-dirty was passed, so $COMMIT is stamped anyway"
    else
        echo "FAIL: working tree is dirty — the image would claim a commit it was not built from" >&2
        echo "      commit the change, or pass --allow-dirty to rehearse the check" >&2
        exit 1
    fi
fi

echo "building release images for $VERSION at $COMMIT"

API_IMAGE="thaap/payments-api"
UI_IMAGE="thaap/analyst-dashboard-ui"

for target in "$API_IMAGE:apps/payments-api" "$UI_IMAGE:apps/analyst-dashboard-ui"; do
    image="${target%%:*}"
    context="${target#*:}"
    docker build \
        --platform linux/arm64 \
        --build-arg "THAAP_BUILD_COMMIT=$COMMIT" \
        --build-arg "THAAP_BUILD_VERSION=$VERSION" \
        --tag "$image:$VERSION" \
        --tag "$image:$COMMIT" \
        "$context"
done

# ---------------------------------------------------------------------------------------------
# 1. Both images carry the identity as OCI labels
# ---------------------------------------------------------------------------------------------

for image in "$API_IMAGE" "$UI_IMAGE"; do
    architecture="$(docker image inspect --format '{{.Architecture}}' "$image:$VERSION")"
    if [[ "$architecture" != "arm64" ]]; then
        fail "$image architecture '$architecture' cannot run on the ARM64 ECS task"
    else
        pass "$image architecture agrees with the ARM64 deployment"
    fi
    labelled_version="$(docker image inspect --format \
        '{{index .Config.Labels "org.opencontainers.image.version"}}' "$image:$VERSION")"
    labelled_revision="$(docker image inspect --format \
        '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$image:$VERSION")"

    if [[ "$labelled_version" != "$VERSION" ]]; then
        fail "$image labels version '$labelled_version', expected '$VERSION'"
    elif [[ "$labelled_revision" != "$COMMIT" ]]; then
        fail "$image labels revision '$labelled_revision', expected '$COMMIT'"
    else
        pass "$image is labelled $VERSION at $COMMIT"
    fi
done

# ---------------------------------------------------------------------------------------------
# 2. The API artifact carries the same pair where /actuator/info reads it
# ---------------------------------------------------------------------------------------------

# The label is metadata a registry shows. This is the file the running process reads, and the two
# being equal is the whole point: an image whose label and whose application disagree is worse
# than one that reports nothing, because it answers the incident question confidently and wrongly.
build_info="$(docker run --rm --entrypoint sh "$API_IMAGE:$VERSION" -c \
    'cat "$(find /app -name build-info.properties | head -1)"' 2>/dev/null || true)"

if [[ -z "$build_info" ]]; then
    fail "$API_IMAGE carries no build-info.properties — /actuator/info would report nothing"
else
    reported_version="$(grep '^build.version=' <<<"$build_info" | cut -d= -f2- | tr -d '\r')"
    reported_commit="$(grep '^build.commit=' <<<"$build_info" | cut -d= -f2- | tr -d '\r')"

    if [[ "$reported_version" != "$VERSION" ]]; then
        fail "build-info reports version '$reported_version', expected '$VERSION'"
    elif [[ "$reported_commit" != "$COMMIT" ]]; then
        fail "build-info reports commit '$reported_commit', expected '$COMMIT'"
    else
        pass "$API_IMAGE reports $VERSION at $COMMIT to /actuator/info"
    fi

    # `unknown` is the honest default for a developer build and an unacceptable value for a
    # release. This is the assertion that keeps the default from silently becoming the norm.
    if grep -qE '^build\.(version|commit)=unknown$' <<<"$build_info"; then
        fail "$API_IMAGE was released carrying the placeholder identity 'unknown'"
    else
        pass "$API_IMAGE carries no placeholder identity"
    fi

    # A build timestamp would make the same commit produce a different artifact every time.
    if grep -q '^build.time=' <<<"$build_info"; then
        fail "$API_IMAGE carries a build timestamp, which breaks reproducibility per commit"
    else
        pass "$API_IMAGE carries no build timestamp"
    fi
fi

# ---------------------------------------------------------------------------------------------
# 3. Addressable by version and by commit, as one image
# ---------------------------------------------------------------------------------------------

# Deployments reference the SHA because it is unambiguous; humans reference the version because it
# is memorable. If those two names ever resolved to different builds, every rollback conversation
# would be about which one was real.
for image in "$API_IMAGE" "$UI_IMAGE"; do
    by_version="$(docker image inspect --format '{{.Id}}' "$image:$VERSION")"
    by_commit="$(docker image inspect --format '{{.Id}}' "$image:$COMMIT")"
    if [[ "$by_version" != "$by_commit" ]]; then
        fail "$image:$VERSION and $image:$COMMIT are different images"
    else
        pass "$image is addressable by version and by commit as one image"
    fi
done

# ---------------------------------------------------------------------------------------------
# 4. The version has a changelog entry
# ---------------------------------------------------------------------------------------------

if grep -q "^## \[$VERSION\]" CHANGELOG.md; then
    pass "CHANGELOG.md describes $VERSION"
else
    fail "CHANGELOG.md has no entry for $VERSION — a release nobody can describe"
fi

if (( failures > 0 )); then
    echo "release identity check FAILED with $failures problem(s)" >&2
    exit 1
fi

echo "release identity check passed for $VERSION at $COMMIT"
