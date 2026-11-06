---
title: Verification for p2p-db incremental backup WAL archive metadata filtering
updated: "2026-08-25T09:30:00Z"
---
# Verification for p2p-db incremental backup WAL archive metadata filtering

`DsIncrementalBackup` now skips WAL archive segments whose sidecar `checkpointTimestamp` is not newer than `baseBackup.createdAt`; only newer archives are included in the incremental backup. Non-archive files continue to be selected by checksum diff.

- `mvn test -pl p2p-db "-Dtest=com.q3lives.ds.backup.DsIncrementalBackupTest#testIncrementalBackupSkipsOldWalArchivesByTimestamp" "-Dsurefire.useFile=false"`
- `mvn test -pl p2p-db "-Dtest=com.q3lives.ds.backup.DsIncrementalBackupTest,com.q3lives.ds.backup.DsFullBackupTest" "-Dsurefire.useFile=false"`

Boundaries touched: `p2p-db` (`DsIncrementalBackup`, `DsIncrementalBackupTest`), `docs` (`dsdb-design-status-and-roadmap.md`), `.brain` (`current-state.md`), `AGENTS.md`.

Notes:

- For `BackupRole.WAL_ARCHIVE` entries, the corresponding `.yaml` sidecar is parsed for `checkpointTimestamp`; if `checkpointTimestamp <= baseCreatedAt`, both the `.wal` and its sidecar are skipped.
- Non-archive files (dataFile, WAL, binlog, index, meta, etc.) are still diffed by checksum against the base backup manifest.
- In a snapshot layout, the `.wal` archive lives under `wal_archive/_wal_archive/<hash>/` while its sidecar lives under `meta/_wal_archive/<hash>/`; the skip logic resolves the sidecar by relative path.
- Added AGENTS.md invariant: incremental backup skips WAL archive segments whose sidecar `checkpointTimestamp <= baseBackup.createdAt`.
