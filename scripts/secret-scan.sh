#!/usr/bin/env bash
# Runs the Cluster93 secret scan locally — the same rules CI uses (.gitleaks.toml) —
# so a locked/blocked GitHub Actions account doesn't stop you from checking.
#
#   scripts/secret-scan.sh                 # scan git history + working tree
#   scripts/secret-scan.sh --no-git        # working tree only (faster)
#   scripts/secret-scan.sh --install-hook  # add .git/hooks/pre-push that runs this
#
# gitleaks is used from PATH, or from $GITLEAKS, or downloaded (pinned) to
# ~/.cache/cluster93. Override the version with GITLEAKS_VERSION.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
GITLEAKS_VERSION="${GITLEAKS_VERSION:-8.30.1}"

install_hook() {
    local hook="$root/.git/hooks/pre-push"
    cat > "$hook" <<'HOOK'
#!/bin/sh
# Installed by scripts/secret-scan.sh --install-hook: refuse to push a secret.
exec "$(git rev-parse --show-toplevel)/scripts/secret-scan.sh"
HOOK
    chmod +x "$hook"
    printf 'installed %s\n' "$hook"
}

gitleaks_bin() {
    if [ -n "${GITLEAKS:-}" ]; then printf '%s' "$GITLEAKS"; return; fi
    if command -v gitleaks >/dev/null 2>&1; then command -v gitleaks; return; fi

    local os arch dir bin url
    case "$(uname -s)" in
        Linux) os=linux ;;
        Darwin) os=darwin ;;
        *) printf 'secret-scan: unsupported OS; install gitleaks and set GITLEAKS\n' >&2; exit 2 ;;
    esac
    case "$(uname -m)" in
        x86_64 | amd64) arch=x64 ;;
        arm64 | aarch64) arch=arm64 ;;
        *) printf 'secret-scan: unsupported arch; install gitleaks and set GITLEAKS\n' >&2; exit 2 ;;
    esac

    dir="$HOME/.cache/cluster93"
    bin="$dir/gitleaks-$GITLEAKS_VERSION"
    if [ ! -x "$bin" ]; then
        mkdir -p "$dir"
        url="https://github.com/gitleaks/gitleaks/releases/download/v${GITLEAKS_VERSION}/gitleaks_${GITLEAKS_VERSION}_${os}_${arch}.tar.gz"
        printf 'secret-scan: downloading gitleaks %s...\n' "$GITLEAKS_VERSION" >&2
        curl -fsSL "$url" | tar -xz -C "$dir" gitleaks
        mv "$dir/gitleaks" "$bin"
        chmod +x "$bin"
    fi
    printf '%s' "$bin"
}

if [ "${1:-}" = "--install-hook" ]; then
    install_hook
    exit 0
fi

exec "$(gitleaks_bin)" detect --source "$root" --config "$root/.gitleaks.toml" --redact --no-banner "$@"
