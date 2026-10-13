#!/usr/bin/env bash
# Builds and publishes a Cluster93 release from this machine — no GitHub Actions.
# For when the account's Actions are locked, or you just want a local cut.
#
#   scripts/release-local.sh <version> [--keep-draft]
#
#     <version>     the exact project version, e.g. 2.0.0-alpha.1 (must equal the pom)
#     --keep-draft  create the draft release and stop (sign later with scripts/sign-release.sh)
#
# It builds the native `c93` CLI for this machine (Linux x64, glibc), assembles the
# release assets with SHA256SUMS, creates a draft GitHub release, and — unless
# --keep-draft — signs and publishes it with scripts/sign-release.sh.
#
# LINUX ONLY: GraalVM native-image can't cross-compile, so macOS and Windows
# binaries still need those machines (or hosted runners once Actions is unlocked).
#
# Needs: GraalVM (native-image) on PATH, gh (authenticated), openssl, and the
# release signing key at ~/.config/cluster93/release-signing.key.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

version="${1:?usage: scripts/release-local.sh <version> [--keep-draft]}"
keep_draft=""
[ "${2:-}" = "--keep-draft" ] && keep_draft=1
tag="v${version}"

die() { printf 'release-local: %s\n' "$*" >&2; exit 1; }

for t in gh openssl; do command -v "$t" >/dev/null 2>&1 || die "need $t"; done

pom="$(./mvnw -q -B -ntp -N help:evaluate -Dexpression=project.version -DforceStdout | tail -1)"
[ "$pom" = "$version" ] || die "the pom version is ${pom}, not ${version}; run scripts/version.sh set ${version} first"

printf '== building the native c93 CLI (linux x64, glibc)\n'
./mvnw -q -B -ntp -Pnative -pl cluster93-cli -am package -Dmaven.test.skip=true \
    "-Dnative.extra.args=-march=compatibility"

src="cluster93-cli/target/c93"
[ -f "$src" ] || die "no native binary at ${src}"
mkdir -p dist
cp "$src" dist/cluster93-linux-x64
printf '== smoke test\n'
./dist/cluster93-linux-x64 --version

cp install/install.sh install/install.ps1 dist/
( cd dist && sha256sum cluster93-linux-x64 install.sh install.ps1 > SHA256SUMS && cat SHA256SUMS )

flags="--title ${tag} --generate-notes"
case "$tag" in *-*) flags="${flags} --prerelease" ;; esac
printf '== creating draft release %s\n' "$tag"
gh release create "$tag" dist/* --draft ${flags}

if [ -n "$keep_draft" ]; then
    printf 'draft created. Sign and publish with: scripts/sign-release.sh %s\n' "$tag"
else
    scripts/sign-release.sh "$tag"
fi
