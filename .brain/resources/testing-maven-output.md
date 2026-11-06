---
updated: "2026-09-01T03:01:26Z"
---
# Maven Surefire Test Output Notes

- Project build system: Maven (multi-module, Java)
- Each module's test command: `mvn test -pl <module> [-Dtest=<Class>[#method]]`
- Test artifact output: under each module's build output, the Surefire plugin produces:
  - `TEST-*.xml` (JUnit XML format — consumed by CI quality gates, IDE JUnit runners, and offline failure triage)
  - `*.txt` (plain text per-test stdout/stderr dumps — for local flaky-test replay and stack trace inspection)
- Concrete per-module report path examples:
  - Core module reports: p2p-core/target/surefire-reports/
  - Database module reports: p2p-db/target/surefire-reports/
  - Sync module reports: p2p-sync/target/surefire-reports/
- Focused test surface for touched modules is the first filter before running a full module regression.
- Flaky / timing-sensitive performance tests are excluded from CI gates by Surefire profiles; they still write reports so performance drift can be detected off-line.
