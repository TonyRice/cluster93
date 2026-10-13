#!/usr/bin/env bash
# Builds and publishes a Cluster93 release locally — no GitHub Actions, and no gh.
# Linux native only: GraalVM native-image can't cross-compile, so macOS and Windows
# binaries are built on their own platforms (see docs/guides/cli.md#build-from-source).
#
#   scripts/release-local.sh <version> [--build-only | --keep-draft]
#
#     <version>     the exact project version, e.g. 2.0.0-alpha.1 (must equal the pom)
#     --build-only  build the binary and assemble dist/ + SHA256SUMS; no network
#     --keep-draft  create the draft release and upload assets, but don't publish
#
# With no flag: build, sign, create a draft release, upload the assets, and publish it.
#
# Publishing talks to the GitHub REST API with curl and a token — gh is not needed.
# Set C93_GITHUB_TOKEN (or GITHUB_TOKEN / GH_TOKEN) to a token with contents:write
# on the repository. C93_RELEASES picks another repository.
#
# Needs: GraalVM (native-image) on PATH. Publishing also needs openssl and the release
# key at ~/.config/cluster93/release-signing.key (scripts/release-key.sh makes one).
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

version="${1:?usage: scripts/release-local.sh <version> [--build-only|--keep-draft]}"
mode="${2:-publish}"
tag="v${version}"
repo="${C93_RELEASES:-TonyRice/cluster93}"

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
    printf '\nBuilt dist/ (linux x64). Publish with: scripts/release-local.sh %s\n' "$version"
    exit 0
fi

# ---- publish over the GitHub REST API (no gh) -------------------------------- #
token="${C93_GITHUB_TOKEN:-${GITHUB_TOKEN:-${GH_TOKEN:-}}}"
[ -n "$token" ] || die "set C93_GITHUB_TOKEN (a token with contents:write) to publish, or use --build-only"
command -v openssl >/dev/null 2>&1 || die "need openssl to sign"
key="${XDG_CONFIG_HOME:-$HOME/.config}/cluster93/release-signing.key"
[ -r "$key" ] || die "no release key at ${key} (scripts/release-key.sh makes one)"

api="https://api.github.com/repos/${repo}"
uploads="https://uploads.github.com/repos/${repo}"
auth=(-H "Authorization: Bearer ${token}" -H "Accept: application/vnd.github+json" -H "X-GitHub-Api-Version: 2022-11-28")
json=("${auth[@]}" -H "Content-Type: application/json")

# Sign before uploading, so SHA256SUMS.sig ships with the rest.
printf '== signing SHA256SUMS\n'
openssl dgst -sha256 -sign "$key" -out dist/SHA256SUMS.sig dist/SHA256SUMS

pre=false
case "$tag" in *-*) pre=true ;; esac

printf '== pushing tag %s\n' "$tag"
if git ls-remote --tags origin "refs/tags/${tag}" 2>/dev/null | grep -q "refs/tags/${tag}"; then
    printf '   tag already on origin\n'
else
    git push origin "$tag"
fi

printf '== creating draft release %s\n' "$tag"
release="$(curl -fsS "${json[@]}" -X POST "${api}/releases" \
    -d "{\"tag_name\":\"${tag}\",\"name\":\"${tag}\",\"draft\":true,\"prerelease\":${pre},\"generate_release_notes\":true}")"
id="$(printf '%s' "$release" | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')"
[ -n "$id" ] || die "could not read the release id from ${release}"

printf '== uploading assets\n'
for f in dist/*; do
    name="$(basename "$f")"
    printf '   %s\n' "$name"
    curl -fsS "${auth[@]}" -H "Content-Type: application/octet-stream" \
        -X POST "${uploads}/releases/${id}/assets?name=${name}" --data-binary @"$f" >/dev/null
done

if [ "$mode" = "--keep-draft" ]; then
    printf 'draft %s created (draft). Publish it when ready.\n' "$tag"
    exit 0
fi

printf '== publishing %s\n' "$tag"
curl -fsS "${json[@]}" -X PATCH "${api}/releases/${id}" -d '{"draft":false}' >/dev/null
printf 'published %s\n' "$tag"
