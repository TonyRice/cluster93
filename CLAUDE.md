# Working on Cluster93

- **Document every change and why.**
  - Add an entry under `[Unreleased]` in `CHANGELOG.md`, saying what changed and why.
  - For any choice with trade-offs, add a numbered entry to `docs/DECISIONS.md`. Never delete or rewrite old entries; supersede them with new ones.
- **Author:** Adam & Lynn Incorporated <tony@adamnlynn.com>. Use this in POM metadata, docs and commits.
- **Ideas aren't tasks.** `NOTES.md` holds ideas that are deliberately parked. Don't implement them unless asked.
- **Build:** run `./mvnw verify` (JDK 21). Surefire runs the unit, cluster and chaos tests and the ArchUnit rules (`server/.../ArchitectureTest.java`). Failsafe then runs `DockerClusterIT` against the packaged jar in Docker; use `-DskipITs` to skip it.
- **Module tests:** core's store contracts ship as a test-jar, so run a single module with `./mvnw verify -pl <module> -am` (the `test` phase alone doesn't build the test-jar). Don't install snapshots into `~/.m2`.
- **Native crashes:** V8 is native code, and a crash can happen after tests have reported success. After test runs, check `find . -name 'hs_err_pid*.log'`; any hit is a bug.
- **No CI for now** (D-017). `./mvnw verify` locally is the gate.
- **Boundaries:**
  - `cluster93-core` depends only on `vertx-core`.
  - Ignite (the cluster manager and database, D-030) is used only in `cluster93-cluster-ignite`.
  - No mutable statics and no hand-rolled threads.
- **Plans and designs:** the roadmap is in `README.md`, and designs are in `docs/design/`.
