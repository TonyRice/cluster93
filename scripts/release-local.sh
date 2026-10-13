#!/usr/bin/env bash
# Builds a Cluster93 release locally — no GitHub Actions. Linux native only:
# GraalVM native-image can't cross-compile, so macOS and Windows binaries are built
# on their own platforms (see docs/guides/cli.md#build-from-source).
#
#   scripts/release-local.sh <version> [--build-only | --keep-draft]
#
#     <version>     the exact project version, e.g. 2.0.0-alpha.1 (must equal the pom)
#     --build-only  build the binary and assemble dist/ + SHA256SUMS; no gh, no publish
#     --keep-draft  create a draft GitHub release and stop (sign later with sign-release.sh)
#
# With no flag: build, create a draft release, then sign and publish it.
#
# Needs GraalVM (native-image) on PATH. Publishing also needs gh (authenticated) and
# openssl, plus the release key at ~/.config/cluster93/release-signing.key.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

version="${1:?usage: scripts/release-local.sh <version> [--build-only|--keep-draft]}"
mode="${2:-publish}"
tag="v${version}"

die() { printf 'release-local: %s\n' "$*" >&2; exit 1; }

pom="$(./mvnw -q -B -ntp -N help:evaluate -Dexpression=project.version -DforceStdout | tail -1)"
[ "$pom" = "$version" ] || die "the pom version is ${pom}, not ${version}; run scripts/version.sh set ${version} first"

printf '== building the native c93 CLI (linux x64, glibc)\n'
# -DskipTests (not -Dmaven.test.skip=true): the reactor's other modules need
# cluster93-core's tests jar, which is only produced when tests are compiled.
./mvnw -q -B -ntp -Pnative -pl cluster93-cli -am package -DskipTests \
    "-Dnative.extra.args=-march=compatibility"

src="cluster93-cli/target/c93"
[ -f "$src" ] || die "no native binary at ${src}"
mkdir -p dist
cp "$src" dist/cluster93-linux-x64
printf '== smoke test\n'
./dist/cluster93-linux-x64 --version

cp install/install.sh install/install.ps1 dist/
( cd dist && sha256sum cluster93-linux-x64 install.sh install.ps1 > SHA256SUMS && cat SHA256SUMS )

if [ "$mode" = "--build-only" ]; then
    printf '\nBuilt dist/ (linux x64). Sign and publish with scripts/sign-release.sh %s\n' "$tag"
    printf 'or create the draft release here: scripts/release-local.sh %s\n' "$version"
    exit 0
fi

for t in gh openssl; do command -v "$t" >/dev/null 2>&1 || die "need $t to publish (or use --build-only)"; done

flags="--title ${tag} --generate-notes"
case "$tag" in *-*) flags="${flags} --prerelease" ;; esac
printf '== creating draft release %s\n' "$tag"
gh release create "$tag" dist/* --draft ${flags}

if [ "$mode" = "--keep-draft" ]; then
    printf 'draft created. Sign and publish with: scripts/sign-release.sh %s\n' "$tag"
else
    scripts/sign-release.sh "$tag"
fi
