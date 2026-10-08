#!/bin/sh
# Creates the release signing key (D-069): an ECDSA P-256 key pair. The private key stays on this machine, encrypted
# with a passphrase you type; the public
# key goes into install/automately-release.pem and the copies inside install.sh and install.ps1, which the
# installers and `automately upgrade` check every release against.
#
#   scripts/release-key.sh [KEY_FILE]     default: ~/.config/automately/release-signing.key
#
# It refuses to overwrite an existing key. To rotate, move the old one away, run this, and commit the new public key
# before the next release; releases signed with the old key then stop verifying with new installers.

set -eu

repo="$(cd "$(dirname "$0")/.." && pwd)"
key="${1:-${XDG_CONFIG_HOME:-$HOME/.config}/automately/release-signing.key}"
pem="${repo}/install/automately-release.pem"

command -v openssl >/dev/null 2>&1 || { echo "release-key: need openssl" >&2; exit 1; }
[ ! -e "$key" ] || { echo "release-key: ${key} already exists; move it away first to rotate" >&2; exit 1; }

mkdir -p "$(dirname "$key")"
# Encrypted with a passphrase openssl asks for at the terminal, so the file alone can't sign anything.
(umask 077 && openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -aes-256-cbc -out "$key")
chmod 600 "$key"
openssl pkey -in "$key" -pubout -out "$pem"

# Replace the key between the BEGIN and END lines in each installer with the new one.
for installer in "${repo}/install/install.sh" "${repo}/install/install.ps1"; do
    # Whatever surrounds the markers on their lines (install.sh's variable and quotes) is kept.
    awk -v pem="$pem" '
        /-----BEGIN PUBLIC KEY-----/ { before = substr($0, 1, index($0, "-----BEGIN") - 1); skip = 1; next }
        skip && /-----END PUBLIC KEY-----/ {
            after = substr($0, index($0, "-----END PUBLIC KEY-----") + 24)
            n = 0
            while ((getline line < pem) > 0) lines[++n] = line
            close(pem)
            lines[1] = before lines[1]
            lines[n] = lines[n] after
            for (i = 1; i <= n; i++) print lines[i]
            skip = 0
            next
        }
        !skip { print }
    ' "$installer" > "${installer}.new"
    cat "${installer}.new" > "$installer" && rm "${installer}.new"
done

echo "Private key: ${key}   (back it up somewhere offline; it never goes to GitHub)"
echo "Public key:  ${pem}, and copied into install.sh and install.ps1. Commit them."
