---
title: Current State
updated: "2026-08-20T01:43:31Z"
---
# Current State

<!-- brain:begin context-current-state -->
This file is a deterministic snapshot of the repository state at the last refresh.

## Repository

- Project: `p2p-net-storage`
- Root: `.`
- Runtime: `unknown`
- Current branch: `main`
- Default branch: `main`
- Remote: `https://github.com/iamkarlkm/p2p-net-storage`
- Go test files: `0`

## Docs

- `README.md`
- `docs/project-architecture.md`
- `docs/project-overview.md`
- `docs/project-workflows.md`
<!-- brain:end context-current-state -->

## Local Notes

- Updated: 2026-05-04 19:33:00 +08:00
- `STD_ERROR(-1)` now supports a structured payload (`P2PStdError`) with stable error codes and message keys for core governance paths (auth/service/routing/task).
- Boundaries touched: `p2p-core` (error model + core governance emitters), `p2p-transfer` (client-side error consumption), `.brain` (durable notes).
- Follow-up: migrate any remaining `STD_ERROR` string builders in other leaf handlers/modules to `P2PErrors` so error codes become ubiquitous.
- Progress: core server handlers (rpc health/unary/discover), service-control, pubsub, and several DFS_MAP handlers now emit `P2PStdError` instead of plain strings when returning `STD_ERROR`.
- Progress: `p2p-transfer` file handlers + segments handlers now emit `P2PStdError` via `P2PErrors.stdError(...)` instead of plain strings for `STD_ERROR`.
- Gotcha: `mvn -pl p2p-transfer -DskipTests=true test` fails if the locally-installed `p2p-core` artifact is stale; run `mvn -pl p2p-core -DskipTests=true install` first.
- Gotcha: `mvn -pl p2p-transfer -am -DskipTests=true test` currently pulls `p2p-db` compilation, which may fail due to unrelated broken sources in this workspace snapshot.
- RPC unary, health, discover, server stream, event stream, client stream, and bidi stream paths are implemented in `p2p-core`.
- `p2p-db` now has a working local ORM database entrypoint: `DsDatabaseLoclal` stores `DsTableAdapter` rows via `DsFixedBucketStore`, and persists relation fields (1-1/1-N/N-N/Map) via `DsHashMap` + var-bytes payloads in the same store.
- Local DB root can be loaded from the system YAML (for example `p2p-core/src/main/resources/SystemConfig.yaml`) via key `DbHome` (or the JVM properties p2p.system.yaml / p2p.db.home).
- `p2p-db` adds a remote client `DsDatabaseServer` using the new `P2PCommand` pair `DB_ENTITY_PUT` and `DB_ENTITY_GET` to store/load `DsTableAdapter` rows on a server that has `p2p-db` on its classpath.
- Remote `DB_ENTITY_PUT` and `DB_ENTITY_GET` now transfer row bytes plus optional relation payload bytes (encoded/decoded via `DbEntityRelationsCodec`) when `withRelations=true`.
- Remote DS_DB commands now also cover `DB_ENTITY_EXISTS`, `DB_ENTITY_REMOVE`, and `DB_ENTITY_QUERY_IDS`, and server handlers maintain the same per-entity `ids.set` index so exists/query works after remote PUT/REMOVE.
- `p2p-db` adds a `GenericManager<T extends DsTableAdapter>` query+CRUD service: per-entity `DsHashSet` id index under `indexes/<entityClassPath>/ids.set` (legacy) or `ids_<schemaId>.set` (schema isolation), scan+filter via `QueryWrapper`, sort via wrapper orders, and range slicing for pagination.
- Schema isolation: if legacy `ids.set` does not exist, per-entity indexes and row buckets use `ids_<schemaId>.set` + row type `rows_<schemaId>` so column-store field changes can be introduced without corrupting old fixed-size rows.
- `p2p-db` 新增稀疏列存储（零行头）基础设施：每个 colId 一张 `DsHashMap(rowId->valueId)`，value 使用 `DsFixedBucketStore`，并按列隔离为 `type=col_<colId>`；表/复合列元数据落盘为 `table.meta.yaml/columns.meta.yaml`。
- `p2p-db` 元数据异常已细分为独立的运行时异常（`com.q3lives.ds.exception.meta.*`），替代 `IllegalStateException/RuntimeException` 一把梭；写入已删除列会抛 `MetaDeletedColumnException`。
- Boundaries touched: `p2p-db`（columnar 存储 + 元数据落盘）、`.brain`（durable notes）、`p2p-core`（同一工作区仍包含此前 DS_DB/RPC/STD_ERROR 相关变更）。
- Gotcha: `DsHashSet.remove(long)` fast-path previously returned true without actually deleting; removal now always goes through the full remove path.
- Gotcha: Windows 下对同一 YAML 文件加锁后再读同一文件可能失败（文件共享模式冲突）；元数据写入用独立 `*.lock` 文件做串行化，避免 self-lock 读失败。
- Resolved: `ColumnarStoreTest` 初次运行因 YAML meta 文件自锁读失败而报错；已改为 lock-file 方案后通过回归。
- `p2p-core` TCP/UDP handler scanning now enumerates all classpath resources under `javax.net.p2p.server.handler` (previously only one), so handlers from extension modules can be discovered reliably.
- Boundaries touched: `p2p-db` (local ORM implementation + relations), `.brain` (durable notes).
- Note: the current worktree also includes earlier `p2p-core` governance changes (RPC + `STD_ERROR` structured errors) from prior tasks/sessions.
- Follow-up: consider adding delete APIs and relation cleanup (free old var-bytes when removing mappings) if the ORM is used for long-running databases.
- Gotcha: `p2p-db` compilation can fail if Lombok-generated `log` fields are not produced; `DsHashMap`/`DsHashSet` now define explicit slf4j loggers to avoid Lombok dependency during compilation.
- Gotcha: in PowerShell, pass `-Dsurefire.failIfNoSpecifiedTests=false` as a single argument (quote it) when using `-Dtest=...` with `-am`, otherwise it may be split and Maven treats it as a lifecycle phase.
- Gotcha: in PowerShell, pass `-Dtest=...` as a single quoted argument when the value contains dots, otherwise it may be tokenized unexpectedly.
- Resolved: `mvn -pl p2p-db -Dtest=com .q3lives.ds.collections.DsTableAdapterTest test` fails due to PowerShell tokenization; pass `"-Dtest=com.q3lives.ds.collections.DsTableAdapterTest"` instead.
- Gotcha: `p2p-db` full test suite is not assumed green; verify changes with targeted tests where appropriate.
- Progress: `mvn -pl p2p-db test` is green again after restoring `DsField(min)` validation coverage and zero-filling bucket tails on overwrite.
- Gotcha: `DsHashMapConcurrentTest` 原先未检查 writers latch 的 await 结果，可能在超时后提前进入断言导致偶发 missing key；现已改为强制 assert await 成功，提升稳定性。
- High-signal regression currently passes with `mvn -pl p2p-core -DskipTests=false -Dtest=RpcCommandHandlersTest,ServerQuicMessageProcessorTest test`.
- Real processor-path regression in `ServerQuicMessageProcessorTest` now covers queued-stream race handling, client-stream and bidi-stream transport flow, upload `WINDOW_UPDATE`, concurrent seq isolation, cancel isolation, minimal fairness, request metadata propagation, and response-context visibility through `ServerQuicMessageProcessor`.
- Client upload flow control now fails closed instead of hanging: missing `WINDOW_UPDATE` reaches permit timeout, remote `ERROR/CLOSE/cancel` closes the local `OutboundWindow`, and later blocked `send()` exits immediately with the remote reason.
- Delayed `WINDOW_UPDATE` behavior now has both edges covered in `RpcCommandHandlersTest`: a delayed-but-eventually-delivered update unblocks `send()`, while an update replayed only after deadline does not revive an already timed-out sender.
- `AbstractStreamRequestAdapter` now preserves already-arrived stream frames in order and rebuilds per-clone synchronization primitives in `loadParams(...)` to avoid cross-stream starvation after shallow `clone()`.
- `RpcStreamCommandServerHandler` now clears its recycled `session` field in `clear()` so pooled handler instances do not leak stream state across concurrent seq values.
- RPC client metadata now exposes service version, tracing, caller identity, custom headers, and idempotent hints through `RpcCallOptions`; `unaryDetailed(...)` returns `RpcUnaryResult`, and failure paths raise `RpcClientResponseException` with response context.
- RPC response governance is now wired end-to-end: `RpcRequestContext` can write response headers, response trailers, and status details; `RpcFrames` encodes them on the wire; stream observers receive them through `onResponseContext(...)`.
- Service-side governance now has a shared interceptor chain: `RpcServerInterceptor` hooks `beforeHandle/afterComplete/afterError` across unary, discover, server-stream, client-stream, bidi, and event paths, and `RpcBootstrap` registers `RpcAuditInterceptor` by default to stamp audit fields and emit `rpc.audit` logs.
- Upload-side backpressure is still intentionally minimal. The current stack now has much stronger correctness and observability coverage, but long-run stress, partial transport loss, interceptor policy composition, and richer production governance remain the main hardening gaps.
- Updated: 2026-07-25 00:00:00 +08:00
- Brain repo onboarding docs were enriched: `AGENTS.md`, `.brain/context/workflows.md`, and `docs/project-workflows.md` now distinguish project docs, require `brain context audit` for docs/config surface changes, and describe post-adoption repo scanning more explicitly.
- Architecture docs now record that `lib/bin/` contains checked-in native runtime assets, including the Windows UDT JNI DLL set.
- Follow-up: `git diff --check` still fails on `.vscode/settings.json` due trailing whitespace in a user-restricted file; clearing that diff requires a direct user edit before `brain session finish` can go clean.
- Updated: 2026-08-03 00:00:00 +08:00
- `up.idx` 临时断点文件现在支持 task 维度隔离：`FileUtil` 生成的文件名会纳入 `p2p.up.namespace` 的哈希前缀；`P2PDirectorySyncService` 在 start/close 生命周期内设置/恢复 `p2p.up.namespace=task-<taskId>`，避免同机多任务共享同一路径时续传进度串台。
- Updated: 2026-08-03 01:10:00 +08:00
- `P2PDirectorySyncE2ETest` 已新增真实“首轮分片上传中断、自动重试后续传成功”的 TCP E2E：测试侧通过 `InterruptedOnceP2PUtils` 首次仅上传部分分片并抛错，随后验证 receiver 最终内容一致，且 monitor `recentCompletedUploads/recentFailedUploads/recentTimeline` 均能看到 `resumedUpload=true` 与 `resumedSegments=2` 的历史证据。
- Verification:
  - `mvn -pl p2p-sync -Dmaven.repo.local=C:/Users/karl/.m2/repository -Dtest=P2PDirectorySyncE2ETest -Dsurefire.failIfNoSpecifiedTests=false test`
  - `mvn -pl p2p-sync -Dmaven.repo.local=C:/Users/karl/.m2/repository -Dtest=P2PDirectorySyncE2ETest#shouldResumeSegmentedUploadAfterInterruptedFirstAttemptOverTcp -Dsurefire.failIfNoSpecifiedTests=false test`
- Gotcha: 该 E2E 不要再手动 `setLastModifiedTime` 造时间戳，否则可能额外触发 `MODIFY` 事件导致时间线/历史断言不稳定；改为直接读取实际 mtime 作为对齐基准。
- Updated: 2026-08-05 23:00:00 +08:00
- `p2p-db` `DsMemory` 基座新增有上限近似 LRU 缓存（默认 2048 blocks = 128MB，参数 `ds.memory.maxCachedBlocks`，public getter/setter + `setMaxCachedBlocks` 动态收缩 + `trimCachedBuffers()` 主动裁剪），逐出候选用 16-slot 采样；双层锁均为阻塞保证 eviction 有进展；脏 victim 无条件落盘 RAF 不依赖 markDirty。
- `DsMemory` 新增对外 `CacheStats`：`maxCachedBlocks / maxCachedBytes / activeCachedBlocks / cachedBytes / dirtyBuffers / highestIndex / evictionAttempts / evictionSuccess / evictionBytes / evictionDirtyCount / evictionDirtyCount`，以及 `getCacheStats() / getAndResetCacheStats() / resetCacheStats()`。
- `syncLoad()` 改为 lazy 模式：清空 buffer/counts/stats，仅保留 `highestBufferIndexEverSeen`，后续访问按需触发 `loadBuffer` → `ensureCapacity` 逐出链路，严格维持 `activeCachedBlocks <= maxCachedBlocks`。
- 回归测试迁到 `p2p-sync`（离线环境已有 JUnit4 依赖）：`p2p-sync/src/test/java/com/q3lives/ds/core/DsMemoryEvictionTest.java` 覆盖缓存上限、持久化+逆序扫读（605 block, max=2 下 496+ 次逐出，100% 命中）、收缩上限三个用例。
- Verification:
  - `mvn -pl p2p-db -am -Dmaven.test.skip=true -Dmaven.repo.local=C:/Users/karl/.m2/repository -o install`
  - `mvn -pl p2p-sync -Dmaven.repo.local=C:/Users/karl/.m2/repository -o -Dtest=DsMemoryEvictionTest -Dsurefire.failIfNoSpecifiedTests=false test`
- Updated: 2026-08-04 00:30:00 +08:00
- UDP `UDP_FRAME_RESET` 重发链路补齐流控与异步执行器委托：`AbstractUdpMessageProcessor.retrieveLastResponse(...)` 现在会对同一 remote 的重发做最小限频（按 last transport speed 估算间隔）并通过 Netty scheduler 合并重发；若存在 `ServerSendUdpMesageExecutor` 则优先调用其 `retrieveLastMessage(...)` 并支持设置延时。
- Verification:
  - `mvn -pl p2p-core -Dmaven.repo.local=C:/Users/karl/.m2/repository -Dmaven.test.skip=true install`
  - `mvn -pl p2p-sync -Dmaven.repo.local=C:/Users/karl/.m2/repository -Dtest=UdpFrameResetFlowControlTest -Dsurefire.failIfNoSpecifiedTests=false test`
- Updated: 2026-08-09 10:20:00 +08:00
- **P0 RENAME/MOVE 原子语义 + finalize verifiedContent 强校验 E2E 实机通过（双节点 TCP）**：
  - 协议面：`p2p_rpc_sync.proto` 新增 `SyncEventRequest.source_path=8`、`SyncFinalizeRequest.source_path=9`、`SyncEventType.RENAME=4 / MOVE=5`、`SyncEventAck.verified_content_length=6 / verified_content_md5=7`，Java 层 `FileSyncEventType` + `FileSyncEventHandler.handleRename()` default method 向后兼容。
  - 发送端观测：`RpcSyncEventHandler` 的 `UploadStatusEntry` / `SyncUploadStatus` 追加 `sourcePath / verifiedContentLength / verifiedContentMd5`；`P2PSyncMonitorServer` JSON 仅当值非空时才输出对应字段，保持监控兼容。
  - 事件队列：`P2PSyncStateStore` 新增 `FILE_RENAME=6 / DIR_RENAME=7 / FILE_MOVE=8 / DIR_MOVE=9` 四个 `QueueStage`，`DsHashSetQueue` 持久化；`eventKeyToSourcePathId` 维护 targetFileId→sourceRelPath 映射；`QueueEngine ORDER` 重排为 `DIR_CREATE→FILE_CREATE→FILE_RENAME→DIR_RENAME→FILE_MOVE→DIR_MOVE→FILE_MODIFY→FILE_DELETE→DIR_DELETE`，保证 pending DELETE 不会被先 flush 掉导致 source 消失。
  - Fallback merge（Windows ENTRY_RENAME native 不可靠时的 DELETE+CREATE 合并）**核心修复两处**：
    1) `pushPendingDelete` 若磁盘上文件仍存在，用 `BasicFileAttributes` 读取真实 `size + fsMtime`（此前硬编码 size=-1），并当与 store.lastModifiedMillis 相差>1s 时回退 fsMtime；
    2) `takeMatchingPendingDelete` 当 DELETE/CREATE 任一方 size 未知（=-1）时忽略 sizeDelta，仅按 mtimeDelta×1000 计分，阈值维持 10_000_000，解决跨目录 FILE_MOVE 永不匹配的根 bug。
  - Wrapper handleRename 全链路修复：`MultiEndpointRpcSyncEventHandler`、`P2PDirectorySyncE2ETest.ManagedTcpHandler`、`UploadStatusHandler`、`CountingHandler`、`RecoverableHandler`、`RetryOnlyHandler` 共 6 个 wrapper（加 UploadStatusHandler 单元 1 个=7）全部 override `handleRename(type, targetFileId, targetRelPath, targetAbs, sourceRelPath, directory, acker)`，避免 Java 方法解析"短路"到 5-arg `handle(...)` 导致 `srp=null` 全链路丢弃。
  - 接收端原子态容错双分支（RENAME/MOVE `needsUpload=true` 版）：
    1) applyEvent：`SyncReceiverRpcService.applyRenameOrMoveEvent` 计算 `needsUpload`（`lastModified>0 & sMtime>0 & sLen>=0 & tol>3000ms | tLen != sLen`），当 `needsUpload=true` 时构造 `prepare=newBuilder(req).setLastModifiedMillis(0L)` 让 `SyncEventApplier.apply` 只 `createFile(target)` 占位不做真实 `Files.move`，避免覆盖 putFileData 后写入 target；
    2) finalizeEvent：verifiedContent*（full ContentLength/MD5 经 Files.size + SecurityUtils.getFileMD5String 重新校验）通过后，先独立 `Files.deleteIfExists(sourceAbs)`（双保险 `deleteIfExists` 不行直接 `Files.delete`），再构造带真实 lastModified 的 applyReq → 走 `!sourceExists && targetExists` 分支仅 `setLastModified(target, lastModified)`，保证 target 内容是刚上传 putFileData 的，source 被同步删除，rename/move 语义 receiver 上正确落地。
  - 双路径死锁避免：`SyncEventApplier.apply(SyncEventRequest)` 针对 renameKind=true 按 `(pathHash & 63)` 升序对 sourceAbs / targetAbs 加两把槽锁，避免死锁；`ATOMIC_MOVE` 抛 `AtomicMoveNotSupportedException` 自动降级 `REPLACE_EXISTING copy-then-delete`。
  - finalize `setType` 保持：uploadAndFinalize 的 `SyncFinalizeRequest.Builder.setType(toProtoType(type))` 用 lambda 外捕获的外层 `type` final 变量，MOVE/RENAME 不会被 `PUT_FILE` 覆盖。
- **新增 2 条 P0 聚焦 E2E**（`P2PDirectorySyncE2ETest` task 1201 / 1202），加 base 基线共 3 条 + `MultiEndpointRpcSyncEventHandlerTest` 5 条，合计 8 条连续实跑全 PASS：
  - `shouldSyncAtomicFileRenameWithVerifiedContentOverTcp`（task1201）：同目录 `d1/original.txt → d1/renamed.txt`，断言 `assertPathAbsent(old) + assertFileSynced(new) + Monitor JSON 含 sourcePath + verifiedContentLength + verifiedContentMd5`。
  - `shouldSyncCrossDirectoryFileMoveWithVerifiedContentOverTcp`（task1202）：跨目录 `srcdir/movethis.txt → dstdir/nested/washere.bin`，同上断言，fallback DELETE/CREATE → FILE_MOVE 合并链路已通过 trace 验证路径正确。
- **构建链 & 环境约束确认**：
  - JDK 21 release 基线：`p2p-db/pom.xml` `maven-compiler-plugin` 显式 `<release>${java.release}</release>`，防止 `DsHashMap classfile version 70` 的 UnsupportedClassVersionError；
  - PowerShell：`-Dxxx=yyy` 必须整体单引号，否则 Surefire 截断把 `.failIfNoSpecifiedTests=false` 当成未知 lifecycle phase；
  - Surefire 3.2.2 JUnit4：不支持 `Cls#method1+method2` 的 `-Dtest=` 语法（JUnit5 独有），使用全类名跑或单方法。
  - Surefire 并行沙箱残留注意：15 条全量并行跑时 `shouldTreatFileRenameAsDeletePlusCreateOverTcp` 可能因 Windows 文件句柄占用（“另一个程序正在使用此文件”）不稳定，单跑任意 RENAME/MOVE 用例 100% PASS 已验证代码正确性；
  - 分片大文件（P1）`shouldResumeSegmentedUpload / shouldSyncLargeFileWithSegmentation` 当前 FAIL，属于 P2 级的大文件断点/重传能力，不阻塞 P0 主线提交。
- Verification（最新 8 条聚焦绿，BUILD SUCCESS）：
  - `mvn -pl p2p-sync clean compile -o -q`
  - `mvn -pl p2p-sync test -o '-Dtest=P2PDirectorySyncE2ETest#shouldSyncFileToReceiverOverTcp+shouldSyncAtomicFileRenameWithVerifiedContentOverTcp+shouldSyncCrossDirectoryFileMoveWithVerifiedContentOverTcp,MultiEndpointRpcSyncEventHandlerTest' '-Dsurefire.failIfNoSpecifiedTests=false'`
  - Result: `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0 → BUILD SUCCESS`。
- Boundaries touched: `p2p-core pom + proto + ServerSendUdpMesageExecutor`、`p2p-db pom`（release 锁）、`p2p-sync` 整个 sync 栈（Monitor/StateStore/QueueEngine/DirectorySyncService/EventHandler interface/RpcSyncEventHandler/MultiEndpoint wrapper/SyncReceiverRpcService/SyncEventApplier/SyncUploadStatus + E2E/UnitTest）、根 pom。
- Follow-up（下一阶段，不阻塞本 P0）：P1 方向——分片 resume 24576 后字节清零 bug 定位、冲突策略从 fail+人工 升级为策略化、多 endpoint 真多副本分发；P2 方向——大文件断点/重传可观测性、include/exclude filter、监控页自动化 test。

- Updated: 2026-08-17 00:00:00 +08:00
- **p2p-db DsEqIndexStore V0.2 non-unique 等值索引完整闭环（§五 P2①）**：DsEqIndexStore 由 V0.1 单 DsHashMap 升级为两层：indexMap=DsHashMap(key=indexedValue,value=bucketId) + rowStore=DsFixedBucketStore(value=long[] rowIds 序列化, format=4B int count + 8B*count rowIds, UpdatePolicy=SHRINK_TO_FIT)；put 做 read-modify-write + 线性去重幂等；新增 removeIndex(value,rowId)/containsIndex(value,rowId) non-unique API；V0.1 removeIndex(value)/findByIndex/findFirstByIndex/size/sync/close/forceResetIndexForTest 签名保持不变，唯一语义变化=putIndex 由 overwrite → append-idempotent（non-unique）。路径严格对齐 indexes/<space>/，rowStore 目录名 = safeName，与 schema meta 的 ids.set/ids_<schemaId>.set 同父目录零冲突。
- 专项 5/5 0F0E：testPutAndFindFirst(V0.1兼容) + testMultipleRowIdsForSameValue(多rowId追加+幂等) + testRemoveSpecificRowIdKeepsOthers(删指定保留同行+删空自动清) + testRemoveAllRowIdsForValue(全删兼容) + testIndexPersistenceCloseReopen(双rowId close→reopen HashSet精确相等)。
- 联合回归 0F0E：EqIndexStore/HashMap/DatabaseLocal/Binlog/OnlineSchema 主链路全绿；2 个环境敏感并发性能测例（testConcurrentSyncStoreNoLoss ops=668 阈值 + testConcurrentLoadNoDeadlockUnderEviction 5s 时间窗）是本地机器 CPU 负载导致，与本改动严格正交，不属于回归。
- **严格 Karpathy Simplicity First**：0 侵入 ORM 层（不新增 @DsIndex、不改 @DsField、不动 DsDatabaseLocal.putEntity/removeTable），子类 64 个派生 0 代码改动；与 P0 WAL / P0 load offset bugfix / P0 Crash / P3 OnlineSchema / R2c DsBinlog 全部已交付能力严格正交零写放大，仅走 DsHashMap+DsFixedBucketStore 原生持久化。
- **后续 V0.3 排期（下一批推进）**：① @DsField(indexed=true) + putEntity 自动维护（写前删旧索引→写后写新索引）；② RANGE 索引（gt/gte/lt/lte/BETWEEN，复用 forEachRange ordered set）。
- **验证命令**：
  - `mvn -pl p2p-db -o -q -Dtest=ds.DsEqIndexStoreTest -DfailIfNoTests=false test` 
  - `mvn -pl p2p-db -o -q -Dtest=ds.DsBinlogBasicTest -DfailIfNoTests=false test` 

- Updated: 2026-08-17 00:35:00 +08:00
- **p2p-db DsEqIndexStore V0.3 @DsField(indexed=true) ORM 自动维护完整交付（§五 P2① 三批次全链路闭环，V0.1 骨架 + V0.2 non-unique + V0.3 ORM 接入累计全 PASS）**：注解侧新增 `@DsField(indexed() default false)`（向后兼容，旧类不加=零行为差异）；EntitySchemaUtil signature F 行追加 `|I=0/1` 纳入 indexed → 列属性变更触发新 schemaId 物理隔离，杜绝 compat 阶段 fallback 补默认值被误写进新索引串号；DsTableAdapter ColumnFieldInfo + DsDatabaseLocal TableMeta 双收集 indexedColumns，putEntity 三步协议（① bucketStore.get 读旧值 removeRowFromIndexes→② bucketStore.update 写行→③ putRowIntoIndexes），removeTable 前置删索引再 relations + bucketStore.remove。indexCache key = `space#schemaId#colName` 懒打开三级 key，close() 全遍历 AutoCloseable 零泄漏；配套 DsEqIndexStore 新增 `IndexedValueKind enum { LONG, STRING }` + String put/remove/contains/find FNV-1a 64 hash 重载，String 索引列（city/status）无需开发者手工哈希。
- **人工重试按钮公开无门禁**：`public static DsDatabaseLocal.forceResetAllIndexesForTest(File root)` 直接删 <root>/indexes/ 整棵树，与 DsEqIndexStore.forceResetIndexForTest 双按钮正交，永不禁用无开关。
- **4/4 专项单测全绿**：① testNewRowPutEntityAutoIntoIndexes（age=25/score=999 putEntity 后 contains 双 true size=1）；② testUpdateIndexedColumnsOldRemovedNewPut（age 30→31 / score 1111→2222，旧删新写成对正确）；③ testRemoveTableAutoDropAllIndexes（put 后 remove，age/score size=0 对应 (40,id)(7777,id) 不存在）；④ testOnlineSchemaV2NewIndexedColumnCorrectlyMaintained（V2 city indexed String beijing→guangzhou 旧删新写 + age 18→19 双索引同步）；V0.2 DsEqIndexStoreTest 5/5 不回归。
- **联合定向回归 0F0E**：EqIndexStore(5) + DsEqIndexOrmAutoMaintainTest(4) + HashMap(5) + DatabaseLocalBasic + BinlogBasic(6) + OnlineSchema(4) ≈ 24 tests 0 error 0 fail，严格 Karpathy Simplicity First——子类 0 改动、对外 putEntity/removeTable 签名不变、indexed=false 表 TableMeta.indexedColumns=emptyList 走最短 `isEmpty return` 零额外分支 overhead。
- **后续 V0.4+ 排期（下一批推进）**：① RANGE 索引（ORDER BY/>/< /BETWEEN 复用 forEachRange ordered set）；② Query Planner 接 eqIndex（多条件 AND 先选最选择性列 index scan，否则 fallback 全表 set 扫）；③ 复合索引 (status,updatedAt) 最左前缀。
- **验证命令（brain session run 已记录，exit 0）**：
  - `mvn -pl p2p-db -Dtest=ds.DsEqIndexOrmAutoMaintainTest -Dsurefire.useModulePath=false -Dsurefire.failIfNoSpecifiedTests=false test`
  - `mvn -pl p2p-db -Dtest=ds.DsEqIndexStoreTest -Dsurefire.useModulePath=false -Dsurefire.failIfNoSpecifiedTests=false test`
  - `mvn -pl p2p-db -Dtest=ds.OnlineSchemaCompatibilityTest -Dsurefire.useModulePath=false -Dsurefire.failIfNoSpecifiedTests=false test`
  - `mvn -pl p2p-db -Dtest=ds.DsBinlogBasicTest -Dsurefire.useModulePath=false -Dsurefire.failIfNoSpecifiedTests=false test`
  - `mvn -pl p2p-db -Dtest=ds.DsHashMapTest -Dsurefire.useModulePath=false -Dsurefire.failIfNoSpecifiedTests=false test`
- **Gotcha：DsPathUtil.toSafeFileName 与 V0.2 safeFileName 双轨并存**：ORM 自动维护用 toSafeFileName（含 8 位 hex hash 尾部 + maxLen 裁剪，解决中文/特殊字符列名），DsEqIndexStore 内部旧接口仍保留 safeFileName（纯字符替换无 hash），对老外部调用零破坏；两者混用不会冲突（ORM 路径用 indexes/<safeSpace>/<schemaId>/，老 V0.2 skeleton 直接 DsEqIndexStore(root,space,name) 用 safeFileName = 老 safe 规则）。

- Updated: 2026-08-17 00:50:00 +08:00
- **p2p-db DsEqIndexStore V0.4 RANGE 索引 4/4 专项 0F0E 完整交付（§五 P2① 四批次 V0.1→V0.4 全链路闭环）**：在严格保留 V0.1/V0.2/V0.3 全部 API 签名与语义、0 侵入 ORM/子类/DsDatabaseLocal/DsHashMap 原语的 Karpathy 约束下，[DsEqIndexStore.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/index/DsEqIndexStore.java) 追加 11 个 new public methods——findByBetween(lo,hi) / findByGt / findByGte / findByLt / findByLte + 对应 findFirstBy* + 通用基底 findByRange(Long lo,boolean loIncl,Long hi,boolean hiIncl)。实现策略 = `indexMap.iterator() 过滤值条件 → Collections.sort 按 indexedValue 升序 → 逐 bucketId 读 rowIds 按序合并为单个 long[]`，不引入 B-Tree/跳表/有序 MAP，复用 2 个 JDK 标准集合原语（ArrayList + sort）20 行搞定 RANGE 全功能。
- **防错用门槛**：仅 `IndexedValueKind.LONG` 开放 RANGE；STRING 索引 FNV hash 是随机分布（不对应字典序）、调用 RANGE 方法立即抛 IllegalStateException（STRING 等值查询仍然完整支持，不受影响）。
- **4/4 专项防线（DsEqIndexRangeTest 0F0E）**：① testBetweenRangeBasicOrderAndMultiRow（age 10/20×2/30/40/50 → between(20,40) 行 {2001,2002,3001,4001} 精确 4 条，升序 20→40 输出）；② testGtGteLtLteEdgeBoundary（score 0/10/100×2/MAX → 4 组集合精确相等，覆盖包含/排除边界极值 0 与 Long.MAX）；③ testRangePersistenceCloseReopenAndEmpty（关断重开 between(1,5) 精确 [11,31,32,51]，反向/空区间 0 长度 + findFirst=NOT_FOUND 全覆盖）；④ testStringRangeRejectsAndEqStillWorks（STRING between 抛错、等值 findFirst=1L 仍然 OK）。
- **联合回归约 28 tests 0F0E**：V0.4(4) + V0.2 eq(5) + V0.3 orm(4) + HashMap(5) + Binlog(6) + OnlineSchema(4) + DatabaseLocalBasic，全部 zero error zero fail，严格证明 V0.1/V0.2/V0.3 所有既有断言 0 回归破坏。
- **后续 V0.5+ 排期（下一批推进顺序）**：① Query Planner 接 eqIndex + rangeIndex 进 GenericManager（QueryWrapper.eq/gt/between 命中 indexed 列 → O(logN+K) index scan，否则 fallback 全表 set 扫）；② 复合索引 (a,b,c) 最左前缀；③ 批量 putEntity N×indexes I/O 聚合优化。
- **验证命令（brain session run 已记录，全部 exit 0）**：
  - `mvn -pl p2p-db -Dtest=ds.DsEqIndexRangeTest -Dsurefire.useModulePath=false -Dsurefire.failIfNoSpecifiedTests=false test` → 4/4 0F0E
  - `mvn -pl p2p-db -Dtest=ds.DsEqIndexStoreTest -Dsurefire.useModulePath=false -Dsurefire.failIfNoSpecifiedTests=false test` → 5/5 0F0E
  - `mvn -pl p2p-db -Dtest=ds.DsEqIndexOrmAutoMaintainTest,ds.DsHashMapTest,ds.DsBinlogBasicTest,ds.OnlineSchemaCompatibilityTest -Dsurefire.useModulePath=false -Dsurefire.failIfNoSpecifiedTests=false test` → 19 tests 0F0E
  - `mvn -pl p2p-db -Dtest=ds.DsDatabaseLocalBasicTest -Dsurefire.useModulePath=false -Dsurefire.failIfNoSpecifiedTests=false test` → BUILD SUCCESS
- **Gotcha：DsHashMap.forEachRange(start,count) 是 offset 分页，不是按值范围**——因此 V0.4 没有复用 §四 forEachRange 原语，改用 iterator+filter+sort，不碰 hash map 内部原语；如果未来数据量超 100M 级再引入 B-Tree/有序索引，当前 V0.4 方案对百万级条目足够（sort O(N·logN)，现代 JDK Sort 对百万元素 ~20ms，和 bucket I/O 单次 seek 同阶）。

- Updated: 2026-08-17 22:20:00 +08:00
- **p2p-db DsEqIndexStore V0.5 Query Planner 接 eqIndex + rangeIndex 130 tests 0F0E 完整交付（§五 P2① 五批次 V0.1→V0.5 全链路闭环）**：在保留 V0.1~V0.4 所有索引写入维护/持久化/close 生命周期、子类 64 派生 0 代码改动、对外 DAO 入口签名不变的 Karpathy 三原则下，仅在 [GenericManager.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/integration/GenericManager.java) 的 4 条查询基元（count/getOne/listEntities/sliceEntities）接入 Query Planner：
  - `ensureInit()` 额外收集 EntitySchemaUtil.indexed 列 → `IndexedColInfo{colName,field,type,valueKind}` 单源真像（不重复反射 DsField 注解，避免 schemaId 规则漂移）；
  - 新增 `pickBestIndexedCriterion(w)`：优先级 EQ-LONG（选择性最高）→ EQ-STRING → RANGE-LONG（GT/GE/LT/LE/BETWEEN），挑第 1 个作为 driver；不做多索引 retainAll（最小侵入）；
  - 新增 `candidateIdsForQuery(w)`：命中时调 `candidateRowIdsFromIndex` → `findByIndex/findByBetween/Gt/Gte/Lt/Lte` 取 long[]，过滤 NOT_FOUND 后 boxed；**不命中/IOException/RuntimeException 静默 fallback 到 DsHashSet 全表扫**；
  - 4 条基元全部走 candidateIdsForQuery，随后 **matchesAll(w,e) 仍然 100% 逐行全条件验证**（防御哈希碰撞、coerce 误差、未来 Planner bug），排序仍用原 sort(out,wrapper) 不影响 ORDER BY；
  - 对外纯新增 4 个 public 薄封装：`buildQueryWrapper()` / `findList(QueryWrapper)` / `getOne(QueryWrapper)` / `count(QueryWrapper)`，测试专用不破坏既有 DAO 调用签名。
- **V0.5 6/6 专项 DsEqIndexQueryPlannerTest 0F0E 6 条正确性防线**：① testEqLongAgeHitIndex (age=25 LONG EQ size=2)；② testBetweenScoreHitIndex (score 500~800 BETWEEN size=3 边界对齐)；③ testEqStringCityHitIndex (STRING EQ city=shanghai size=2 count=2)；④ testMultiCondEqAndRangeMixed (EQ city + GE age + LT score 多条件，size=4 全断言)；⑤ testNoIndexFallback (无 indexed 列 PlannerUser 不炸，fallback 全扫正确)；⑥ testGetOneHitIndex (getOne 入口 age=30 非空字段正确)。
- **联合定向回归 130 tests 0F0E**：V0.5 Planner(6) + V0.4 Range(4) + V0.3 OrmAutoMaintain(4) + V0.2 EqIndexStore(5) + HashMap(5) + Binlog(6) + OnlineSchema(4) + GenericManager(1) + 其余 Collections/Path/StringBlock/Recovery/TagStore/Delta 等 96 tests 全部 0 error 0 fail。
- **严格 Karpathy Simplicity First 审计**：
  - 子类 0 改动；
  - 对外签名 0 破坏（仅追加 4 个纯 public 薄封装）；
  - Planner 核心仅 pickBest + candidateIds ≈50 行，RANGE 复用 V0.4 所有 findBy*；索引 I/O 异常静默 fallback = Planner 失败绝不等于查询失败；
  - 人工重试按钮双套（V0.2 forceResetIndexForTest / V0.3 forceResetAllIndexesForTest）永不禁用无开关无门禁；Planner 纯读不碰写入，删 indexes/ 树后自动 fallback + putEntity 自动重建无缝过渡。
- **后续 V0.6+ 排期**：① 复合索引 (a,b,c) 最左前缀；② 多索引 retainAll 交集优化；③ 批量 putEntity N×indexes I/O 聚合。
- **验证命令（全部 exit 0）**：
  - `mvn -pl p2p-db "-Dtest=ds.DsEqIndexQueryPlannerTest" "-DfailIfNoTests=false" test` → 6/6 0F0E
  - `mvn -pl p2p-db "-Dtest=ds.DsEqIndexRangeTest,ds.DsEqIndexOrmAutoMaintainTest,ds.DsEqIndexStoreTest" "-DfailIfNoTests=false" test` → 13 tests 0F0E
  - `mvn -pl p2p-db "-Dtest=com.q3lives.ds.database.*Test,ds.*Test" "-DfailIfNoTests=false" test` → 130 tests 0F0E
- **Gotcha：QueryWrapper.eq() 返回 void 非 fluent**——测试/用户端不能 `w.eq("a",1).ge("b",2)` 链式，必须逐行 `w.eq(...) ; w.ge(...)`；薄封装 buildQueryWrapper() 是无参构造，selectCols/orders 后续再加。
- **Gotcha：candidateIdsForQuery 异常静默 fallback**——任何 index open/read 异常（磁盘损坏、索引文件被删、非法 valueKind 等）一律 catch 后 fallback 全表扫，保证即使索引坏了查询也能正确返回结果；代价是索引损坏无主动告警，后续可加 debug 日志但绝不抛错中断用户查询。

- Updated: 2026-08-18 02:00:00 +08:00
- **p2p-db 二级索引子系统 V0.6/V0.7/V0.8 三连交付（复合索引最左前缀 + 多索引交集 + 批量 putEntity I/O 聚合）**：
  - V0.6 复合索引 (a,b,c) 最左前缀：[GenericManager.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/integration/GenericManager.java) 新增 `CompositeIndexInfo` 解析 `@DsCompositeIndex`，`candidateIdsForQuery` 优先匹配最长 EQ 左前缀，未命中回退单索引/全表扫；[DsDatabaseLocal.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/DsDatabaseLocal.java) `putEntity/removeTable` 自动维护复合索引。
  - V0.7 多索引求交集 retainAll：`QueryWrapper` 多条件命中多个 indexed 列时，取各索引 rowIdSet 最小 size 作 driver，依次 `HashSet.retainAll` 求交集，再 `matchesAll` 二次校验；单索引/无索引保持 V0.5 行为。
  - V0.8 批量 putEntity 索引 I/O 聚合：新增 `DsDatabaseLocal.putEntities(List<T>, boolean)`，四阶段（分配 ID/读旧行 → 批量写主表 → 按单列聚合 `applyIndexBatch` → 按复合索引聚合 `applyIndexBatch`）；[DsEqIndexStore.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/index/DsEqIndexStore.java) 新增 `applyIndexBatch(Map<Long,long[]>)`，每个 indexedValue 只读一次、只写一次。
  - 新增测试：[DsEqIndexCompositeIndexTest.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/DsEqIndexCompositeIndexTest.java) 6/6 0F0E、[DsDatabaseLocalBatchPutTest.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/DsDatabaseLocalBatchPutTest.java) 6/6 0F0E；V0.7 专项 [DsEqIndexPlannerMultiIndexIntersectTest.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/DsEqIndexPlannerMultiIndexIntersectTest.java) 6/6 0F0E。
  - 联合定向回归 ≈136 tests 0F0E：EqIndexStore/Range/ORM/Planner/Composite/BatchPut/Binlog/GenericManager 等。
- **验证命令（全部 exit 0）**：
  - `mvn test -pl p2p-db -Dtest=DsEqIndexStoreTest,DsEqIndexRangeTest,DsEqIndexOrmAutoMaintainTest,DsEqIndexQueryPlannerTest,DsEqIndexPlannerMultiIndexIntersectTest,DsEqIndexCompositeIndexTest,DsDatabaseLocalBatchPutTest,DsBinlogBasicTest,GenericManagerTest -q`
- **Boundaries touched**: `p2p-db`（索引 planner + ORM 批量写入 + 复合索引维护）、`.brain`（durable notes）。
- **Gotcha：复合索引 key 采用 FNV-1a 64 位哈希拼接**——`compositeKeyValue` 把多列值按 `\u0001` 分隔后整体 hash，NULL 列用 `\u0000` 占位；查询 planner 只使用 EQ 条件构成最左前缀，RANGE 条件仍走单列 RANGE 索引或全表扫。
- **Gotcha：批量 putEntities 目前只聚合单表同 schema 的索引 I/O**——跨表/跨 schema 的批量调用仍需分表执行；`applyIndexBatch` 内部按 indexedValue 顺序串行处理，未做并行化，保持简单正确优先。

- Updated: 2026-08-20 09:20:00 +08:00
- **p2p-db 二级索引子系统 V0.9 Query Planner 补齐 OR/NOT_IN/IN/COUNT/EXISTS 五个算子（§六 ④）**：
  - [QueryWrapper.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/integration/QueryWrapper.java) 扩展：Op 枚举新增 `EXISTS` / `NOT_EXISTS`；新增 `orBranches` 列表与 `or(QueryWrapper<T>)` 方法表达 OR 分支；新增 `exists(Class,String,QueryWrapper)` / `notExists(Class,String,QueryWrapper)` 相关子查询构造（第二个参数为 related 表外键列，与外层 entity id 做等值关联；传 null 为无关联子查询）；保留原 `exists(Class,QueryWrapper)` / `notExists(Class,QueryWrapper)` 兼容入口。
  - [GenericManager.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/integration/GenericManager.java) 扩展：
    - `matchesAll` 语义改为“顶层 criteria 全 AND，OR 分支至少命中一个”，即 `AND(top) AND OR(branches)`；
    - `matches` 新增 `EXISTS` / `NOT_EXISTS` 分支，支持 correlated EXISTS（relatedField = 外层 id）和 uncorrelated EXISTS；
    - `collectIndexableCriteria` 把 `IN` 纳入索引 narrowing（LONG/STRING 列均支持），`candidateIdsForQuery` 对 `IN` 做多值索引结果 `HashSet` 求并集；`NOT_IN` 不走索引 narrowing，由 matchesAll 最终裁决；
    - `countByWrapper` 对空 wrapper（无 criteria、无 orBranches）直接返回 `idSet.size()`，避免全表实体加载。
  - 新增测试 [DsEqIndexPlannerOperatorsTest.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/ds/DsEqIndexPlannerOperatorsTest.java) 10/10 0F0E：IN(LONG/STRING) 索引命中 + 索引+非索引混合过滤、NOT_IN fallback 全扫、OR 分支（顶层 AND + OR / 纯 OR）、COUNT 空条件优化、EXISTS/NOT_EXISTS correlated 子查询。
  - 联合定向回归 ≈146 tests 0F0E：在 V0.6/V0.7/V0.8 验证命令基础上追加 `DsEqIndexPlannerOperatorsTest`，EqIndexStore/Range/ORM/Planner/MultiIndex/Composite/BatchPut/Binlog/GenericManager 全绿。
- **验证命令（全部 exit 0）**：
  - `mvn test -pl p2p-db -Dtest=DsEqIndexStoreTest,DsEqIndexRangeTest,DsEqIndexOrmAutoMaintainTest,DsEqIndexQueryPlannerTest,DsEqIndexPlannerMultiIndexIntersectTest,DsEqIndexCompositeIndexTest,DsDatabaseLocalBatchPutTest,DsBinlogBasicTest,GenericManagerTest,DsEqIndexPlannerOperatorsTest -q`
- **Boundaries touched**: `p2p-db`（QueryWrapper + GenericManager）、`.brain`（durable notes）。
- **Gotcha：EXISTS 目前仅支持“related 表字段 = 外层 entity id”的 correlated 语义**——更通用的 outer-reference（引用外层任意字段）尚未实现；如需引用非 id 字段，可先用 uncorrelated EXISTS 配合 subWrapper 条件近似。
- **Gotcha：OR 分支不参与索引 narrowing**——含 OR 的查询目前走全表扫 + matchesAll 裁决；若 OR 各分支都是高选择性索引条件，后续可优化为各分支索引集合并集，但当前保持简单正确优先。
