#!/usr/bin/env bash
# Cluster93 version tooling (SemVer; the 2.x line with pre-releases).
#
#   scripts/version.sh show
#   scripts/version.sh set <version>              # e.g. 2.0.0-alpha.1  (no -SNAPSHOT)
#   scripts/version.sh snapshot <base>            # e.g. 2.0.0 -> 2.0.0-SNAPSHOT
#   scripts/version.sh bump <major|minor|patch>   # the next -SNAPSHOT after a release
#
# Sets the version on the parent pom and every module at once (versions:set), so
# the version the CLI reports (${project.version}) always matches the git tag.
#
# Release flow (see VERSIONING.md and docs/guides/releasing.md):
#   scripts/version.sh set 2.0.0-alpha.1
#   $EDITOR CHANGELOG.md                       # date the section
#   git commit -am "release: 2.0.0-alpha.1"
#   git tag v2.0.0-alpha.1 && git push origin main v2.0.0-alpha.1
#   scripts/version.sh bump minor              # back to 2.1.0-SNAPSHOT on main
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

die() { printf 'version.sh: %s\n' "$*" >&2; exit 1; }

current() {
    ./mvnw -q -B -ntp -N help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null | tail -1
}

valid() { printf '%s' "$1" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$'; }

apply() {
    local v="$1"
    valid "$v" || die "not a valid SemVer version: $v"
    ./mvnw -q -B -ntp versions:set -DnewVersion="$v" -DprocessAllModules=true -DgenerateBackupPoms=false
    printf 'version -> %s\n' "$v"
}

bump() {
    local part="$1" cur core major minor patch
    cur="$(current)"
    core="${cur%%-*}"                       # drop -SNAPSHOT / -alpha.N / -rc.N
    IFS=. read -r major minor patch <<<"$core"
    case "$part" in
        major) major=$((major + 1)); minor=0; patch=0 ;;
        minor) minor=$((minor + 1)); patch=0 ;;
        patch) patch=$((patch + 1)) ;;
        *) die "bump: expected major|minor|patch, got '$part'" ;;
    esac
    apply "${major}.${minor}.${patch}-SNAPSHOT"
}

case "${1:-}" in
    show)
        current
        ;;
    set)
        [ $# -eq 2 ] || die "usage: version.sh set <version>"
        apply "$2"
        ;;
    snapshot)
        [ $# -eq 2 ] || die "usage: version.sh snapshot <base>"
        apply "${2%-SNAPSHOT}-SNAPSHOT"
        ;;
    bump)
        [ $# -eq 2 ] || die "usage: version.sh bump <major|minor|patch>"
        bump "$2"
        ;;
    *)
        sed -n '2,20p' "$0"
        exit 1
        ;;
esac
