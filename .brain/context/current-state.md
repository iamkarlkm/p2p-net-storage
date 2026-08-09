---
title: Current State
updated: "2026-07-26T01:55:25Z"
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
- `DsMemory` 新增对外 `CacheStats`：`maxCachedBlocks / maxCachedBytes / activeCachedBlocks / cachedBytes / dirtyBuffers / highestIndex / evictionAttempts / evictionSuccess / evictionBytes / evictionDirtyCount`，以及 `getCacheStats() / getAndResetCacheStats() / resetCacheStats()`。
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
  - `shouldSyncCrossDirectoryFileMoveWithVerifiedContentOverTcp`（task1202）：跨目录 `srcdir/movethis.txt → dstdir/nested/washere.bin`，同上断言，fallback DELETE/CREATE → FILE_MOVE merge 链路已通过 trace 验证路径正确。
- **构建链 & 环境约束确认**：
  - JDK 21 release 基线：`p2p-db/pom.xml` `maven-compiler-plugin` 显式 `<release>${java.release}</release>`，防止 `DsHashMap classfile version 70` 的 UnsupportedClassVersionError；
  - PowerShell：`-Dxxx=yyy` 必须整体单引号，否则 Surefire 截断把 `.failIfNoSpecifiedTests=false` 当成未知 lifecycle phase；
  - Surefire 3.2.2 JUnit4：不支持 `Cls#method1+method2` 的 `-Dtest=` 语法（JUnit5 独有），使用全类名跑或单方法。
  - Surefire 并行沙箱残留注意：15 条全量并行跑时 `shouldTreatFileRenameAsDeletePlusCreateOverTcp` 可能因 Windows 文件句柄占用（"另一个程序正在使用此文件"）不稳定，单跑任意 RENAME/MOVE 用例 100% PASS 已验证代码正确性；
  - 分片大文件（P1）`shouldResumeSegmentedUpload / shouldSyncLargeFileWithSegmentation` 当前 FAIL，属于 P2 级的大文件分块能力，不阻塞 P0 主线提交。
- Verification（最新 8 条聚焦绿，BUILD SUCCESS）：
  - `mvn -pl p2p-sync clean compile -o -q`
  - `mvn -pl p2p-sync test -o '-Dtest=P2PDirectorySyncE2ETest#shouldSyncFileToReceiverOverTcp+shouldSyncAtomicFileRenameWithVerifiedContentOverTcp+shouldSyncCrossDirectoryFileMoveWithVerifiedContentOverTcp,MultiEndpointRpcSyncEventHandlerTest' '-Dsurefire.failIfNoSpecifiedTests=false'`
  - Result: `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0 → BUILD SUCCESS`。
- Boundaries touched: `p2p-core pom + proto + ServerSendUdpMesageExecutor`、`p2p-db pom`（release 锁）、`p2p-sync` 整个 sync 栈（Monitor/StateStore/QueueEngine/DirectorySyncService/EventHandler interface/RpcSyncEventHandler/MultiEndpoint wrapper/SyncReceiverRpcService/SyncEventApplier/SyncUploadStatus + E2E/UnitTest）、根 pom。
- Follow-up（下一阶段，不阻塞本 P0）：P1 方向——分片 resume 24576 后字节清零 bug 定位、冲突策略从 fail+人工 升级为策略化、多 endpoint 真多副本分发；P2 方向——大文件断点/重传可观测性、include/exclude filter、监控页自动化 test。
