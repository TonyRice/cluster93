# Working on Automately

- **Document every change and why.**
  - Add an entry under `[Unreleased]` in `CHANGELOG.md`, saying what changed and why.
  - For any choice with trade-offs, add a numbered entry to `docs/DECISIONS.md`. Never delete or rewrite old entries; supersede them with new ones.
- **Author:** Adam & Lynn Incorporated <tony@adamnlynn.com>. Use this in POM metadata, docs and commits.
- **Ideas aren't tasks.** `NOTES.md` holds ideas that are deliberately parked. Don't implement them unless asked.
- **Build:** run `./mvnw verify` (JDK 21). Surefire runs the unit, cluster and chaos tests and the ArchUnit rules (`automately-server/.../ArchitectureTest.java`). Failsafe then runs `DockerClusterIT` against the packaged jar in Docker; use `-DskipITs` to skip it.
- **Native crashes:** V8 is native code. After test runs, check for `hs_err_pid*.log` files; CI fails on them.
- **Boundaries:**
  - `automately-core` depends only on `vertx-core`.
  - Hazelcast is used only in `automately.server.cluster`.
  - No mutable statics and no hand-rolled threads.
- **Plans and designs:** the roadmap is in `README.md`, and designs are in `docs/design/`.
