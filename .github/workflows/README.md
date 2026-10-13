# Workflows (currently disabled)

These workflows are named `*.yml.disabled`, so GitHub Actions does **not** queue them.
They were disabled because the account's Actions are locked by a billing issue, and
every run would fail before starting ("The job was not started because your account is
locked due to a billing issue"). GitHub only runs files that end in `.yml` or `.yaml`
directly in this directory, so the `.disabled` suffix parks them without deleting them.

| Workflow | What it does | Runs on |
|---|---|---|
| `release.yml` | Builds the five platform native binaries and leaves a draft release | a `v*` tag |
| `secret-scan.yml` | Runs gitleaks over history with `.gitleaks.toml` | every push / PR |

## Local equivalents (use these while Actions is locked)

- **Secret scan:** `scripts/secret-scan.sh` — the same `.gitleaks.toml`.
  `scripts/secret-scan.sh --install-hook` adds a `pre-push` hook.
- **Release (Linux native):** `scripts/release-local.sh <version>` — builds
  `cluster93-linux-x64`, assembles `SHA256SUMS`, creates a draft release, and signs and
  publishes it (`--build-only` skips `gh`/signing). macOS and Windows still need their
  own platforms, since native-image can't cross-compile.

## Re-enabling

Rename the file back (drop `.disabled`) once Actions is available again:

```bash
git mv .github/workflows/release.yml.disabled .github/workflows/release.yml
git mv .github/workflows/secret-scan.yml.disabled .github/workflows/secret-scan.yml
```
