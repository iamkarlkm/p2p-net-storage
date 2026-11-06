---
title: Verification for p2p-db timeline restore by WAL entry offset
updated: "2026-08-25T01:30:00Z"
---
# Verification for p2p-db timeline restore by WAL entry offset

Fine-grained timeline restore: truncate an individual WAL archive segment at a specific entry count, so a single `syncStore()` that produced multiple block WAL entries can be partially replayed.

- `mvn test -pl p2p-db "-Dtest=com.q3lives.ds.backup.DsIncrementalBackupTest#testTimelineRestoreToWalEntryOffset" "-Dsurefire.useFile=false"`
- `mvn test -pl p2p-db "-Dtest=com.q3lives.ds.backup.DsIncrementalBackupTest,com.q3lives.ds.backup.DsFullBackupTest" "-Dsurefire.useFile=false"`
- `mvn test -pl p2p-db "-Dtest=com.q3lives.ds.backup.DsIncrementalBackupTest,com.q3lives.ds.backup.DsFullBackupTest,com.q3lives.ds.core.DsWALCrashRecoveryTest" "-Dsurefire.useFile=false"`

Boundaries touched: `p2p-db` (`DsMemory`, `DsTimelineRestore`, `DsIncrementalBackupTest`).

Notes:
- `DsTimelineRestore.WalStopPoint { checkpointTimestamp, walEntryOffset }` is the new recovery target; old `restoreTimeline(chain, targetDbHome, long checkpointTimestamp)` delegates to it with `Integer.MAX_VALUE` entries for the matching checkpoint.
- WAL archive sidecar (`.yaml`) now records `walEntryCount`; legacy sidecars without it derive the count from `walSize / (ENTRY_HEAD_SIZE + BLOCK_SIZE + TAIL_CRC32_LEN)`.
- `DsFullBackup.restore(snapshotDir, workHome)` copies the baseline `_wal_archive/` into `workHome`; tests that continue writing new archives after restoring a snapshot should delete `workHome/_wal_archive` to avoid mixing baseline archives with new ones.
