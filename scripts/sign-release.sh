#!/bin/sh
# Signs a release and publishes it (D-069). The release workflow leaves each tag's release as a draft; this downloads
# it, checks every asset against its SHA256SUMS, signs SHA256SUMS with the release key here on this machine, uploads
# SHA256SUMS.sig, and only then publishes the release. Until then `releases/latest` doesn't serve it.
#
#   scripts/sign-release.sh v2.1.0 [KEY_FILE]     default: ~/.config/cluster93/release-signing.key
#
# Needs gh (logged in, with write access) and openssl. C93_RELEASES picks another repository.

set -eu

tag="${1:?usage: scripts/sign-release.sh TAG [KEY_FILE]}"
key="${2:-${XDG_CONFIG_HOME:-$HOME/.config}/cluster93/release-signing.key}"
repo="${C93_RELEASES:-TonyRice/cluster93}"
root="$(cd "$(dirname "$0")/.." && pwd)"
pem="${root}/install/cluster93-release.pem"

fail() {
    printf 'sign-release: %s\n' "$*" >&2
    exit 1
}

for tool in gh openssl; do
    command -v "$tool" >/dev/null 2>&1 || fail "need $tool"
done
[ -r "$key" ] || fail "no release key at ${key} (scripts/release-key.sh makes one)"
[ "$(openssl pkey -in "$key" -pubout)" = "$(cat "$pem")" ] \
    || fail "${key} doesn't match install/cluster93-release.pem, the key the installers check"

[ "$(gh release view "$tag" -R "$repo" --json isDraft -q .isDraft)" = "true" ] \
    || fail "${tag} is already published; signatures go on drafts, before anyone can download them"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT INT TERM
gh release download "$tag" -R "$repo" -D "$work"
cd "$work"
rm -f SHA256SUMS.sig

# Every asset is listed and matches; nothing is listed that isn't there.
for file in *; do
    [ "$file" = SHA256SUMS ] && continue
    awk -v f="$file" '$2 == f || $2 == "*"f { found = 1 } END { exit !found }' SHA256SUMS || fail "${file} isn't in SHA256SUMS"
done
if command -v sha256sum >/dev/null 2>&1; then sha256sum -c --quiet SHA256SUMS; else shasum -a 256 -c --quiet SHA256SUMS; fi \
    || fail "an asset doesn't match SHA256SUMS"

# The installers in the release must carry the key that signs it.
for installer in install.sh install.ps1; do
    sed -n '2,3p' "$pem" | while read -r line; do
        grep -qF "$line" "$installer" || fail "${installer} in the release doesn't carry this release key"
    done
done

openssl dgst -sha256 -sign "$key" -out SHA256SUMS.sig SHA256SUMS
openssl dgst -sha256 -verify "$pem" -signature SHA256SUMS.sig SHA256SUMS >/dev/null

gh release upload "$tag" SHA256SUMS.sig -R "$repo"
gh release edit "$tag" -R "$repo" --draft=false
echo "Signed and published ${tag}"
cat SHA256SUMS
