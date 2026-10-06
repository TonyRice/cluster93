# Working on Automately

- **Document every change and why.**
  - Add an entry under `[Unreleased]` in `CHANGELOG.md`, saying what changed and why.
  - For any choice with trade-offs, add a numbered entry to `docs/DECISIONS.md`. Never delete or rewrite old entries; supersede them with new ones.
- **Author:** Adam & Lynn Incorporated <tony@adamnlynn.com>. Use this in POM metadata, docs and commits.
- **Ideas aren't tasks.** `NOTES.md` holds ideas that are deliberately parked. Don't implement them unless asked.
- **Build:** run `./mvnw verify` (JDK 21). It includes a 2-node Hazelcast cluster test and ArchUnit rules in `automately-server/src/test/java/automately/server/ArchitectureTest.java`.
- **Boundaries:**
  - `automately-core` depends only on `vertx-core`.
  - Hazelcast is used only in `automately.server.cluster`.
  - No mutable statics and no hand-rolled threads.
- **Plans and designs:** the roadmap is in `README.md`, and designs are in `docs/design/`.
