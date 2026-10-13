# Versioning

Cluster93 follows [Semantic Versioning 2.0.0](https://semver.org/). Versions are
`MAJOR.MINOR.PATCH`, with an optional pre-release suffix.

## The version line

The public line is **2.x** — this project is the 2.0 evolution of the original
Automately codebase (see [DEDICATION.md](DEDICATION.md)). `2.0.0` is a **breaking
major** relative to the 2017 edition: the Ignite SQL schema and data region were
renamed, the built-in import scheme is `c93:`, and the token prefix is `c93_`.

## Version forms

| Form | Example | Meaning |
|---|---|---|
| `-SNAPSHOT` | `2.1.0-SNAPSHOT` | Work in progress on `main`. Never released, never installed by default. |
| pre-release | `2.0.0-alpha.1`, `2.0.0-beta.1`, `2.0.0-rc.1` | A candidate, in increasing order of readiness. Published as GitHub **pre-releases**. |
| stable | `2.0.0` | Prime time. Published as a normal GitHub release. |

Stages: `alpha` → `beta` → `rc` → stable. A pre-release increments its own number
(`alpha.1`, `alpha.2`, …) and a new stage resets it (`beta.1`).

## What the numbers mean

- **MAJOR** — a breaking change: an API/contract, on-disk format, wire protocol, or
  built-in module surface that existing deployments or scripts would have to adapt
  to. (The rebrand and the `c93:` scheme are the 2.0.0 major.)
- **MINOR** — backward-compatible functionality.
- **PATCH** — backward-compatible fixes.

Everything before `2.0.0` stable is a pre-release: interfaces may still change, and
each pre-release may contain breaking changes relative to the previous one.

## Channels and `releases/latest`

- **Stable** releases are served by `releases/latest`, which is what
  `install.sh` / `install.ps1` and `c93 upgrade` use by default.
- **Pre-releases** are marked as such on GitHub, so `releases/latest` never serves
  them. Opt in explicitly: `C93_VERSION=v2.0.0-alpha.1` (installer) or
  `c93 upgrade --version v2.0.0-alpha.1`.

## Tags and the version string

A release tag is `v` + the exact version: `v2.0.0-alpha.1`, `v2.0.0`, `v2.0.1`.

The version the CLI reports comes from the Maven `project.version`
(`cluster93-cli.properties` → `${project.version}`), so **the tag and the pom must
match**. The release workflow refuses to build if they differ, and `scripts/version.sh`
keeps every pom in step.

## Tooling

```bash
scripts/version.sh show                        # the current version
scripts/version.sh set 2.0.0-alpha.1           # release version (no -SNAPSHOT)
scripts/version.sh bump minor                  # after a release: 2.1.0-SNAPSHOT
```

See [docs/guides/releasing.md](docs/guides/releasing.md) for the full cut-a-release
checklist.
