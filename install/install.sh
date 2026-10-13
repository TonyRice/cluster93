#!/bin/sh
# Installs the c93 CLI (D-049):
#   curl -fsSL https://github.com/TonyRice/cluster93/releases/latest/download/install.sh | sh
#
# Environment:
#   C93_VERSION      a release tag to install, e.g. v2.1.0 (default: the latest)
#   C93_INSTALL_DIR  where to put the binary (default: ~/.local/bin, or /usr/local/bin as root)
#   C93_RELEASES     the GitHub repository publishing releases (default: TonyRice/cluster93)
#   C93_INSTALL_URL  a base URL serving the release assets instead of GitHub
#   C93_RELEASE_PUBLIC_KEY  a PEM file to check signatures with, instead of the key below (a re-signing mirror)
#   C93_INSECURE_SKIP_SIGNATURE=1  install without checking the signature, trusting HTTPS alone
#
# Every release's SHA256SUMS is signed with the project's release key (D-069), and this checks that signature with
# openssl before trusting any checksum in it. A mirror can't change a binary without the private key, which never
# leaves the maintainer's machine.
#
# Everything happens inside main(), called on the last line, so a download cut off halfway runs nothing.

set -eu

RELEASE_PUBLIC_KEY='-----BEGIN PUBLIC KEY-----
MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEx6p8qv79K2ltKawkKzbs1xYZ1QyR
hD6NugZBZL+S2K5MijsmPijVZJI3y+kVcyioFmPTDdzKdSnWJj3916QN/Q==
-----END PUBLIC KEY-----'

main() {
    repo="${C93_RELEASES:-TonyRice/cluster93}"
    if [ -n "${C93_INSTALL_URL:-}" ]; then
        base="${C93_INSTALL_URL%/}"
    elif [ -n "${C93_VERSION:-}" ]; then
        base="https://github.com/${repo}/releases/download/${C93_VERSION}"
    else
        base="https://github.com/${repo}/releases/latest/download"
    fi

    os="$(uname -s)"
    arch="$(uname -m)"
    # Prebuilt native binaries are Linux-only for now (see docs/guides/cli.md). macOS,
    # Windows and anything else: build the CLI from source.
    case "$os" in
        Linux) os=linux ;;
        MINGW* | MSYS* | CYGWIN*) fail "Prebuilt c93 binaries are Linux-only for now. On Windows, build the CLI from source: https://github.com/${repo}/blob/main/docs/guides/cli.md#build-from-source" ;;
        *) fail "Prebuilt c93 binaries are Linux-only for now (${os} isn't covered). Build the CLI from source: https://github.com/${repo}/blob/main/docs/guides/cli.md#build-from-source" ;;
    esac
    case "$arch" in
        x86_64 | amd64) arch=x64 ;;
        arm64 | aarch64) arch=arm64 ;;
        *) fail "No c93 build for ${arch} processors. Builds exist for x64 and arm64." ;;
    esac
    asset="cluster93-${os}-${arch}"

    if [ -n "${C93_INSTALL_DIR:-}" ]; then
        dir="$C93_INSTALL_DIR"
    elif [ "$(id -u)" = "0" ]; then
        dir=/usr/local/bin
    else
        dir="${HOME}/.local/bin"
    fi

    need curl
    # HTTPS only, except for a plain-HTTP C93_INSTALL_URL you chose (a local mirror, or the tests).
    case "$base" in
        https://*) secure="--proto =https --tlsv1.2" ;;
        *) secure="" ;;
    esac
    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' EXIT INT TERM

    say "Downloading ${asset} from ${base}"
    curl -fsSL $secure "${base}/${asset}" -o "${tmp}/c93" \
        || fail "Download failed: ${base}/${asset}"
    curl -fsSL $secure "${base}/SHA256SUMS" -o "${tmp}/SHA256SUMS" \
        || fail "Download failed: ${base}/SHA256SUMS"
    check_signature

    expected="$(awk -v f="$asset" '$2 == f || $2 == "*"f { print $1 }' "${tmp}/SHA256SUMS")"
    [ -n "$expected" ] || fail "SHA256SUMS has no entry for ${asset}"
    actual="$(sha256 "${tmp}/c93")"
    [ "$expected" = "$actual" ] || fail "Checksum mismatch for ${asset}: expected ${expected}, got ${actual}. Nothing was installed."

    mkdir -p "$dir"
    chmod 755 "${tmp}/c93"
    mv "${tmp}/c93" "${dir}/c93"
    say "Installed $("${dir}/c93" --version 2>/dev/null || echo c93) to ${dir}/c93"

    case ":${PATH}:" in
        *":${dir}:"*) say "Get started: c93 login https://your-cluster" ;;
        *) say "Add ${dir} to your PATH, e.g.:  echo 'export PATH=\"${dir}:\$PATH\"' >> ~/.profile" ;;
    esac
}

# SHA256SUMS.sig is an ECDSA P-256 signature over SHA256SUMS, in the DER form `openssl dgst -sign` writes.
check_signature() {
    if [ "${C93_INSECURE_SKIP_SIGNATURE:-}" = "1" ]; then
        say "Warning: not checking the release's signature (C93_INSECURE_SKIP_SIGNATURE=1)"
        return
    fi
    command -v openssl >/dev/null 2>&1 \
        || fail "Need openssl to check the release's signature. Install it, or set C93_INSECURE_SKIP_SIGNATURE=1 to trust HTTPS alone."
    curl -fsSL $secure "${base}/SHA256SUMS.sig" -o "${tmp}/SHA256SUMS.sig" \
        || fail "Download failed: ${base}/SHA256SUMS.sig. Releases are signed; an unsigned one isn't installed."
    if [ -n "${C93_RELEASE_PUBLIC_KEY:-}" ]; then
        key="$C93_RELEASE_PUBLIC_KEY"
    else
        key="${tmp}/release.pem"
        printf '%s\n' "$RELEASE_PUBLIC_KEY" > "$key"
    fi
    openssl dgst -sha256 -verify "$key" -signature "${tmp}/SHA256SUMS.sig" "${tmp}/SHA256SUMS" >/dev/null 2>&1 \
        || fail "SHA256SUMS isn't signed by the c93 release key. Nothing was installed."
}

sha256() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{ print $1 }'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{ print $1 }'
    else
        fail "Need sha256sum or shasum to verify the download"
    fi
}

need() {
    command -v "$1" >/dev/null 2>&1 || fail "Need $1 to install c93"
}

say() {
    printf '%s\n' "$*"
}

fail() {
    printf 'c93 install: %s\n' "$*" >&2
    exit 1
}

main "$@"
