---
title: "Add real interrupted resumable upload E2E and validate resumed upload observability"
updated: "2026-08-03T00:00:00+08:00"
---
# Add real interrupted resumable upload E2E and validate resumed upload observability

## Verification

- `mvn -pl p2p-sync -Dmaven.repo.local=C:/Users/karl/.m2/repository -Dtest=P2PDirectorySyncE2ETest -Dsurefire.failIfNoSpecifiedTests=false test`
- `mvn -pl p2p-sync -Dmaven.repo.local=C:/Users/karl/.m2/repository -Dtest=P2PDirectorySyncE2ETest#shouldResumeSegmentedUploadAfterInterruptedFirstAttemptOverTcp -Dsurefire.failIfNoSpecifiedTests=false test`

