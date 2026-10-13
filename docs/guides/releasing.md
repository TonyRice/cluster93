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

## Checking a release by hand

```bash
openssl dgst -sha256 -verify install/cluster93-release.pem -signature SHA256SUMS.sig SHA256SUMS
sha256sum -c --ignore-missing SHA256SUMS
```

## Rotating the key

Move the old key away and run `scripts/release-key.sh` again. Then commit the new public key before the next release.

Installers and CLIs from before the rotation reject releases signed with the new key, so reinstall with the new installer.
