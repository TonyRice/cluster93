#!/bin/sh
# Installs the automately CLI (D-049):
#   curl -fsSL https://github.com/adamnlynn/automately-v2/releases/latest/download/install.sh | sh
#
# Environment:
#   AUTOMATELY_VERSION      a release tag to install, e.g. v2.1.0 (default: the latest)
#   AUTOMATELY_INSTALL_DIR  where to put the binary (default: ~/.local/bin, or /usr/local/bin as root)
#   AUTOMATELY_RELEASES     the GitHub repository publishing releases (default: adamnlynn/automately-v2)
#   AUTOMATELY_INSTALL_URL  a base URL serving the release assets instead of GitHub
#
# Everything happens inside main(), called on the last line, so a download cut off halfway runs nothing.

set -eu

main() {
    repo="${AUTOMATELY_RELEASES:-adamnlynn/automately-v2}"
    if [ -n "${AUTOMATELY_INSTALL_URL:-}" ]; then
        base="${AUTOMATELY_INSTALL_URL%/}"
    elif [ -n "${AUTOMATELY_VERSION:-}" ]; then
        base="https://github.com/${repo}/releases/download/${AUTOMATELY_VERSION}"
    else
        base="https://github.com/${repo}/releases/latest/download"
    fi

    os="$(uname -s)"
    arch="$(uname -m)"
    case "$os" in
        Linux) os=linux ;;
        Darwin) os=macos ;;
        MINGW* | MSYS* | CYGWIN*) fail "On Windows, use PowerShell: irm ${base}/install.ps1 | iex" ;;
        *) fail "No automately build for ${os}. Builds exist for Linux, macOS and Windows." ;;
    esac
    case "$arch" in
        x86_64 | amd64) arch=x64 ;;
        arm64 | aarch64) arch=arm64 ;;
        *) fail "No automately build for ${arch} processors. Builds exist for x64 and arm64." ;;
    esac
    asset="automately-${os}-${arch}"

    if [ -n "${AUTOMATELY_INSTALL_DIR:-}" ]; then
        dir="$AUTOMATELY_INSTALL_DIR"
    elif [ "$(id -u)" = "0" ]; then
        dir=/usr/local/bin
    else
        dir="${HOME}/.local/bin"
    fi

    need curl
    # HTTPS only, except for a plain-HTTP AUTOMATELY_INSTALL_URL you chose (a local mirror, or the tests).
    case "$base" in
        https://*) secure="--proto =https --tlsv1.2" ;;
        *) secure="" ;;
    esac
    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' EXIT INT TERM

    say "Downloading ${asset} from ${base}"
    curl -fsSL $secure "${base}/${asset}" -o "${tmp}/automately" \
        || fail "Download failed: ${base}/${asset}"
    curl -fsSL $secure "${base}/SHA256SUMS" -o "${tmp}/SHA256SUMS" \
        || fail "Download failed: ${base}/SHA256SUMS"

    expected="$(awk -v f="$asset" '$2 == f || $2 == "*"f { print $1 }' "${tmp}/SHA256SUMS")"
    [ -n "$expected" ] || fail "SHA256SUMS has no entry for ${asset}"
    actual="$(sha256 "${tmp}/automately")"
    [ "$expected" = "$actual" ] || fail "Checksum mismatch for ${asset}: expected ${expected}, got ${actual}. Nothing was installed."

    mkdir -p "$dir"
    chmod 755 "${tmp}/automately"
    mv "${tmp}/automately" "${dir}/automately"
    say "Installed $("${dir}/automately" --version 2>/dev/null || echo automately) to ${dir}/automately"

    case ":${PATH}:" in
        *":${dir}:"*) say "Get started: automately login https://your-cluster" ;;
        *) say "Add ${dir} to your PATH, e.g.:  echo 'export PATH=\"${dir}:\$PATH\"' >> ~/.profile" ;;
    esac
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
    command -v "$1" >/dev/null 2>&1 || fail "Need $1 to install automately"
}

say() {
    printf '%s\n' "$*"
}

fail() {
    printf 'automately install: %s\n' "$*" >&2
    exit 1
}

main "$@"
