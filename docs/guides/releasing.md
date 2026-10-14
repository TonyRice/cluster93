# Releasing the CLI

**For maintainers.** Decisions: D-049, D-055, D-069.

Releases are built by GitHub Actions and signed on your machine. The signing key never goes to GitHub.

## Once: the signing key

```bash
scripts/release-key.sh
```

This asks for a passphrase and writes:
- the private key, to `~/.config/cluster93/release-signing.key`. It's owner-only and encrypted with that passphrase. **Back up the key file and the passphrase offline.** If you lose either, you'll need a new key and new installers.
- the public key, to `install/cluster93-release.pem`, and copied into `install/install.sh` and `install/install.ps1`. Commit these. The CLI bundles the `.pem` when it's built.

## Each release

Versions are SemVer — see [VERSIONING.md](../../VERSIONING.md). `main` carries the next
`-SNAPSHOT`; a release is cut from a non-snapshot version whose tag matches the pom.

```bash
scripts/version.sh set 2.0.0-alpha.1     # or 2.0.0 for a stable release
# edit CHANGELOG.md: move [Unreleased] entries under ## [2.0.0-alpha.1] - <date>
git commit -am "release: 2.0.0-alpha.1"
git tag v2.0.0-alpha.1
git push origin main v2.0.0-alpha.1      # builds every platform and leaves a draft release
scripts/sign-release.sh v2.0.0-alpha.1   # needs gh and openssl; asks for the passphrase
scripts/version.sh bump minor            # back to 2.1.0-SNAPSHOT on main
```

- The release workflow **fails if the tag doesn't match `project.version`**, so the
  CLI never reports the wrong version.
- A tag with a hyphen (`-alpha.1`, `-beta.1`, `-rc.1`) is published as a GitHub
  **pre-release**, so `releases/latest` never serves it; users opt in with
  `C93_VERSION=v2.0.0-alpha.1`.

`sign-release.sh` downloads the draft and checks:
- every asset is listed in `SHA256SUMS` and matches it
- both installers carry the release key

Then it signs `SHA256SUMS`, uploads `SHA256SUMS.sig` and publishes the release (keeping
the pre-release flag). Until then the release is a draft, which `releases/latest`
doesn't serve and the installers wouldn't accept.

## When GitHub Actions is unavailable

If the account's Actions are locked (billing) or you just want a local cut, both
steps have local equivalents:

- **Secret scan:** `scripts/secret-scan.sh` — the same `.gitleaks.toml` CI uses.
  `scripts/secret-scan.sh --install-hook` wires it as a `pre-push` hook so a secret
  is caught before it leaves your machine.
- **Release (Linux only):** `scripts/release-local.sh <version>` builds
  `cluster93-linux-x64`, assembles `SHA256SUMS`, creates a draft release, and signs +
  publishes it (add `--keep-draft` to stop before signing). macOS and Windows still
  need hosted runners, because native-image can't cross-compile.

### The platform matrix

One release carries the whole platform: a portable `c93.jar` (server), a portable
`cluster93-cli.jar` (CLI), and the Linux native workers. Native CLI binaries are built
per platform by `.github/workflows/release.yml`; `release-local.sh` builds the Linux x64
one locally.

| Asset | Built by | Notes |
|---|---|---|
| `cluster93-linux-x64` | workflow or `release-local.sh` | static (musl): any distro, Alpine included |
| `cluster93-linux-arm64` | workflow (arm64 runner) | glibc, `-march=compatibility` |
| `cluster93-macos-arm64` / `-x64` | workflow (macOS runners) | |
| `cluster93-windows-x64.exe` | workflow (Windows runner) | |
| `cluster93-cli.jar` | workflow or `release-local.sh` | portable; the Windows installer uses it |
| `c93.jar` | workflow or `release-local.sh` | the server, portable |
| `worker-javet.gz` / `worker-graaljs.gz` | workflow or `release-local.sh` | **Linux only** (x64/arm64); a node elsewhere runs `C93_WORKER_MODE=embedded` |

`c93 update` refreshes both halves of an install from this bundle: `--cli` replaces the
native binary (or the jar it runs from) and `--node` re-fetches the node.

## Checking a release by hand

```bash
openssl dgst -sha256 -verify install/cluster93-release.pem -signature SHA256SUMS.sig SHA256SUMS
sha256sum -c --ignore-missing SHA256SUMS
```

## Rotating the key

Move the old key away and run `scripts/release-key.sh` again. Then commit the new public key before the next release.

Installers and CLIs from before the rotation reject releases signed with the new key, so reinstall with the new installer.
