#!/usr/bin/env bash
# Builds and publishes a Cluster93 release locally — no GitHub Actions, and no gh.
# Linux native only: GraalVM native-image can't cross-compile, so macOS and Windows
# binaries are built on their own platforms (see docs/guides/cli.md#build-from-source).
#
#   scripts/release-local.sh <version> [--build-only | --keep-draft] [--no-build]
#
#     <version>     the exact project version, e.g. 2.0.0-alpha.2 (must equal the pom)
#     --build-only  build the binary and assemble dist/ + SHA256SUMS; no network
#     --keep-draft  create the draft release and upload assets, but don't publish
#     --no-build    reuse the existing build (cluster93-cli/target/c93); skip maven
#
# With no flag: build, sign, create a draft release, upload the assets, and publish it.
#
# SIGNING: the release key is passphrase-protected. Run this in a terminal and openssl
# prompts for the passphrase. For a non-interactive run, set C93_RELEASE_PASSPHRASE.
#
# Publishing talks to the GitHub REST API with curl — gh is not required. It uses a
# token from C93_GITHUB_TOKEN / GITHUB_TOKEN / GH_TOKEN, or an authenticated `gh`
# session (`gh auth token`) if one exists. C93_RELEASES picks another repository.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

version="${1:?usage: scripts/release-local.sh <version> [--build-only|--keep-draft] [--no-build]}"
shift
mode=publish
build=1
for a in "$@"; do
    case "$a" in
        --build-only) mode=build-only ;;
        --keep-draft) mode=keep-draft ;;
        --no-build) build=0 ;;
        *) printf 'release-local: unknown flag: %s\n' "$a" >&2; exit 1 ;;
    esac
done
tag="v${version}"
repo="${C93_RELEASES:-TonyRice/cluster93}"

die() { printf 'release-local: %s\n' "$*" >&2; exit 1; }

pom="$(./mvnw -q -B -ntp -N help:evaluate -Dexpression=project.version -DforceStdout | tail -1)"
[ "$pom" = "$version" ] || die "the pom version is ${pom}, not ${version}; run scripts/version.sh set ${version} first"

if [ "$build" = 1 ]; then
    printf '== building the native c93 CLI (linux x64, glibc)\n'
    # -DskipTests (not -Dmaven.test.skip=true): the reactor's other modules need
    # cluster93-core's tests jar, which is only produced when tests are compiled.
    ./mvnw -q -B -ntp -Pnative -pl cluster93-cli -am package -DskipTests \
        "-Dnative.extra.args=-march=compatibility"
else
    printf '== reusing the existing native build (--no-build)\n'
fi

src="cluster93-cli/target/c93"
[ -f "$src" ] || die "no native binary at ${src} (drop --no-build to build one)"
mkdir -p dist
cp "$src" dist/cluster93-linux-x64
printf '== smoke test\n'
./dist/cluster93-linux-x64 --version

cp install/install.sh install/install.ps1 dist/
( cd dist && sha256sum cluster93-linux-x64 install.sh install.ps1 > SHA256SUMS && cat SHA256SUMS )

if [ "$mode" = "build-only" ]; then
    printf '\nBuilt dist/ (linux x64). Publish with: scripts/release-local.sh %s\n' "$version"
    exit 0
fi

# ---- publish over the GitHub REST API (no gh required) ----------------------- #
token="${C93_GITHUB_TOKEN:-${GITHUB_TOKEN:-${GH_TOKEN:-}}}"
# Reuse an authenticated gh session if there is one and no token was given.
if [ -z "$token" ] && command -v gh >/dev/null 2>&1; then
    token="$(gh auth token 2>/dev/null || true)"
fi
[ -n "$token" ] || die "set C93_GITHUB_TOKEN (a token with contents:write), or run 'gh auth login', or use --build-only"
command -v openssl >/dev/null 2>&1 || die "need openssl to sign"
key="${XDG_CONFIG_HOME:-$HOME/.config}/cluster93/release-signing.key"
[ -r "$key" ] || die "no release key at ${key} (scripts/release-key.sh makes one)"

api="https://api.github.com/repos/${repo}"
uploads="https://uploads.github.com/repos/${repo}"
auth=(-H "Authorization: Bearer ${token}" -H "Accept: application/vnd.github+json" -H "X-GitHub-Api-Version: 2022-11-28")
json=("${auth[@]}" -H "Content-Type: application/json")

# The tag must exist locally before it can be pushed.
if ! git rev-parse -q --verify "refs/tags/${tag}" >/dev/null; then
    printf '== creating tag %s at %s\n' "$tag" "$(git rev-parse --short HEAD)"
    git tag "$tag"
fi

# Sign before uploading, so SHA256SUMS.sig ships with the rest. The key is
# passphrase-protected: C93_RELEASE_PASSPHRASE (or an interactive prompt) supplies it.
# A signature that already verifies is reused, so a retry needn't re-enter it.
if [ -s dist/SHA256SUMS.sig ] && openssl dgst -sha256 -verify "${root}/install/cluster93-release.pem" \
        -signature dist/SHA256SUMS.sig dist/SHA256SUMS >/dev/null 2>&1; then
    printf '== reusing the existing SHA256SUMS.sig\n'
else
    printf '== signing SHA256SUMS\n'
    passin=()
    [ -n "${C93_RELEASE_PASSPHRASE:-}" ] && passin=(-passin env:C93_RELEASE_PASSPHRASE)
    openssl dgst -sha256 "${passin[@]}" -sign "$key" -out dist/SHA256SUMS.sig dist/SHA256SUMS
fi

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

if [ "$mode" = "keep-draft" ]; then
    printf 'draft %s created (draft). Publish it when ready.\n' "$tag"
    exit 0
fi

printf '== publishing %s\n' "$tag"
curl -fsS "${json[@]}" -X PATCH "${api}/releases/${id}" -d '{"draft":false}' >/dev/null
printf 'published %s\n' "$tag"
