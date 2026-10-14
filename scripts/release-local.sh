#!/usr/bin/env bash
# Builds and publishes a Cluster93 release locally — no GitHub Actions, and no gh.
#
# A release is the whole platform, not just the client:
#   cluster93-linux-x64   the native `c93` CLI (Linux x64, glibc)
#   cluster93-cli.jar     the same CLI as a portable jar (any OS with Java 21+)
#   c93.jar               the server (portable; run a node with `java -jar c93.jar`)
#   worker-javet.gz       the Linux native workers the server spawns in process mode
#   worker-graaljs.gz     (gunzip them into bin/ beside the server before running a node)
#   install.sh / install.ps1, SHA256SUMS, SHA256SUMS.sig
#
#   scripts/release-local.sh <version> [--build-only | --keep-draft] [--no-build]
#
#     <version>     the exact project version, e.g. 2.0.0-alpha.2 (must equal the pom)
#     --build-only  build everything and assemble dist/ + SHA256SUMS; no network
#     --keep-draft  create the draft release and upload assets, but don't publish
#     --no-build    reuse the existing build; skip maven and the worker build
#
# With no flag: build, sign, create a draft release, upload the assets, and publish it.
#
# NOTES
#   - Linux native, x64 only: native-image can't cross-compile. `c93.jar` (server) and
#     `cluster93-cli.jar` are portable and cover macOS/Windows.
#   - The workers are only built if bin/worker-* are missing (they take a few minutes).
#   - SIGNING: the release key is passphrase-protected. Run in a terminal and openssl
#     prompts; C93_RELEASE_PASSPHRASE supplies it non-interactively. A signature that
#     already verifies is reused, so a retry needn't re-enter it.
#   - Publishing talks to the GitHub REST API with curl — gh is not required. It uses a
#     token from C93_GITHUB_TOKEN / GITHUB_TOKEN / GH_TOKEN, or an authenticated `gh`
#     session (`gh auth token`). C93_RELEASES picks another repository.
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
    printf '== building the native CLI, the portable CLI jar, and the server jar\n'
    # -DskipTests (not -Dmaven.test.skip=true): the reactor's other modules need
    # cluster93-core's tests jar, which is only produced when tests are compiled.
    ./mvnw -q -B -ntp -Pnative -pl cli,server -am package -DskipTests \
        "-Dnative.extra.args=-march=compatibility"
    if [ ! -x bin/worker-javet ] || [ ! -x bin/worker-graaljs ]; then
        printf '== building the native workers (first time; a few minutes)\n'
        scripts/build-workers.sh all
    fi
else
    printf '== reusing the existing build (--no-build)\n'
fi

# ---- assemble dist/ ---------------------------------------------------------- #
rm -rf dist
mkdir -p dist
cp cli/target/c93 dist/cluster93-linux-x64
cp cli/target/cluster93-cli.jar dist/cluster93-cli.jar
cp server/target/c93.jar dist/c93.jar
chmod +x dist/cluster93-linux-x64
# The native workers are huge; ship them gzipped.
gzip -9 -c bin/worker-javet > dist/worker-javet.gz
gzip -9 -c bin/worker-graaljs > dist/worker-graaljs.gz
cp install/install.sh install/install.ps1 dist/

printf '== smoke test\n'
./dist/cluster93-linux-x64 --version
java -jar dist/cluster93-cli.jar --version

( cd dist && sha256sum cluster93-linux-x64 cluster93-cli.jar c93.jar worker-javet.gz worker-graaljs.gz install.sh install.ps1 > SHA256SUMS && cat SHA256SUMS )

if [ "$mode" = "build-only" ]; then
    printf '\nBuilt dist/ (full bundle). Publish with: scripts/release-local.sh %s\n' "$version"
    exit 0
fi

# ---- publish over the GitHub REST API (no gh required) ----------------------- #
token="${C93_GITHUB_TOKEN:-${GITHUB_TOKEN:-${GH_TOKEN:-}}}"
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

# A signature that already verifies is reused, so a retry needn't re-enter the passphrase.
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
