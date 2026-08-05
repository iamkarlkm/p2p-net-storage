---
created: "2026-08-05T15:00:00Z"
title: Add eviction policy to DsMemory to prevent OOM
type: change_verification
updated: "2026-08-05T15:00:00Z"
---
# Add eviction policy to DsMemory to prevent OOM

## Outcome

- `p2p-db/src/main/java/com/q3lives/ds/core/DsMemory.java` now enforces a bounded in-memory block cache (default 2048 blocks = 128 MB, tune via `ds.memory.maxCachedBlocks`) plus a 16-slot LRU-style approximate eviction with blocking locks to keep `activeCachedBlocks <= maxCachedBlocks` under write-heavy and reload paths.
- Dirty evictees are unconditionally flushed back to the backing RAF file (`writeBlockToFile`) so correctness does not depend on `markDirty` coverage.
- A new `CacheStats` record exposes `maxCachedBlocks`, `cachedBytes`, `activeCachedBlocks`, `dirtyBuffers`, `highestIndex`, `evictionAttempts`, `evictionSuccess`, `evictionBytes`, `evictionDirtyCount`, plus `getAndResetCacheStats() / resetCacheStats() / getActiveCachedBlocks() / getCachedBytes() / getMaxCachedBlocks() / getMaxCachedBytes()`.
- `syncLoad()` was switched to lazy mode: it resets all buffers / counts / stats and restores only `highestBufferIndexEverSeen`; subsequent block accesses rehydrate via `loadBuffer` and trigger the normal `ensureCapacity` eviction loop, which matches the eviction contract.
- Three regression tests were added under `p2p-sync/src/test/java/com/q3lives/ds/core/DsMemoryEvictionTest.java` (p2p-sync hosts it because its classpath already has a downloaded JUnit4 offline):
  - `shouldKeepCachedBlocksBelowLimit`
  - `shouldPersistAndReloadCorrectlyThroughEvictions`
  - `shouldTrimWhenMaxShrinks`

## Touched boundaries

- `p2p-db/`
- `p2p-sync/`
- `p2p-core/` (via `-am` install, no source edits)

## Verification

- Build and install offline:
  - `mvn -pl p2p-db -am -Dmaven.test.skip=true -Dmaven.repo.local=C:/Users/karl/.m2/repository -o install`
- Regression suite (3 tests):
  - `mvn -pl p2p-sync -Dmaven.repo.local=C:/Users/karl/.m2/repository -o -Dtest=DsMemoryEvictionTest -Dsurefire.failIfNoSpecifiedTests=false test`
- Expected results: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`; reload-path `CacheStats` must show `evictionAttempts > 0` and `activeCachedBlocks <= maxCachedBlocks` even after loading and scanning 600+ blocks with `maxCachedBlocks=2`.
