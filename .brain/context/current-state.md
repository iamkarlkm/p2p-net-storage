---
updated: "2026-05-06T23:06:00Z"
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

## Work Notes

- `p2p-db` DS_DB 已支持“无类”模式的 DDL/CRUD/QUERY：`DB_META_GET/PUT`、`DB_ROW_*`、`DB_COL_*`、`DB_ROW_PUT/GET`、`DB_ROW_QUERY_IDS`，底层为稀疏列存（每列一个 `DsHashMap(rowId->valueId)`，value 落 `DsFixedBucketStore` 的 `type=col_<colId>`）。
- 复合列组按逻辑列 `@composite:<group>` 注册稳定 `colId/colKey`，以 group.length 作为 packed bytes 长度写入/读取。
- 新增等值二级索引：`DB_INDEX_CREATE` 创建/重建 `<logicalName>.eq.idx.yaml` 元数据 + `eq_<colId>.map`，并全表回填；查询端 `DB_ROW_QUERY_IDS` 会在存在 EQ 条件且索引存在时先走索引候选集再做完整过滤/排序/分页。
- `DB_INDEX_DROP` 支持删除等值索引：会释放索引节点并清空 `eq_<colId>.map`，同时删除 `<logicalName>.eq.idx.yaml` 元数据；删除后查询会自动回退到全表扫描。
- 新增索引元信息查询：`DB_INDEX_LIST` 列出表上的索引（当前仅 EQ），`DB_INDEX_INFO` 查询单个索引（返回 exists + {logicalName,colId,type}）。
- 写入一致性：`DB_COL_PUT/REMOVE`、`DB_ROW_PUT`、`DB_ROW_REMOVE` 会对已存在的 EQ 索引做增量维护（先移除旧值再写入新值/或删除）。
- String 定长列一致性：动态列写入会对 `valueBytes` 做零填充到列定义长度；查询解码时会 trim 尾部 0 字节，避免 `"bob\\0\\0..."` 影响 EQ 判断与索引匹配。
- Gotcha：开发时若本机 `p2p-core` 依赖 jar 落后，会导致 `p2p-db` 编译缺少新 model 类；需先 `mvn -pl p2p-core -DskipTests=true install`。
- Boundaries touched: `.brain/`, `p2p-core/`, `p2p-db/`。
- High-signal changed files: `p2p-db` 的 ColumnarStore 与 DS_DB handlers（Row/Col/Query/Index），以及 `DbEntityP2PHandlersTest` 的索引回归用例；`.brain/context/current-state.md`、`.brain/resources/changes/dsdb.md`。


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
- Local DB root can be loaded from YAML: `SystemConfig.yaml` key `DbHome` (or system properties `p2p.system.yaml` / `p2p.db.home`).
- `p2p-db` adds a remote client `DsDatabaseServer` using new `P2PCommand` pair `DB_ENTITY_PUT/GET` to store/load `DsTableAdapter` rows on a server that has `p2p-db` on its classpath.
- Remote `DB_ENTITY_PUT/GET` now transfers row bytes plus optional relation payload bytes (encoded/decoded via `DbEntityRelationsCodec`) when `withRelations=true`.
- Remote DS_DB commands now also cover `DB_ENTITY_EXISTS/REMOVE/QUERY_IDS`, and server handlers maintain the same per-entity `ids.set` index so exists/query works after remote PUT/REMOVE.
- `p2p-db` adds a `GenericManager<T extends DsTableAdapter>` query+CRUD service: per-entity `DsHashSet` id index under `indexes/<entityClassPath>/ids.set` (legacy) or `ids_<schemaId>.set` (schema isolation), scan+filter via `QueryWrapper`, sort via wrapper orders, and range slicing for pagination.
- Schema isolation: if legacy `ids.set` does not exist, per-entity indexes and row buckets use `ids_<schemaId>.set` + row type `rows_<schemaId>` so column-store field changes can be introduced without corrupting old fixed-size rows.
- `p2p-db` ??????????????????????????????colId ????`DsHashMap(rowId->valueId)`??alue ??? `DsFixedBucketStore`???????????`type=col_<colId>`???/??????????????`table.meta.yaml/columns.meta.yaml`??- `p2p-db` ???????????????????????????`com.q3lives.ds.exception.meta.*`?????? `IllegalStateException/RuntimeException` ???????????????????`MetaDeletedColumnException`??- Boundaries touched: `p2p-db`??olumnar ??? + ???????????.brain`??urable notes????p2p-core`????????????????? DS_DB/RPC/STD_ERROR ??????????- Gotcha: `DsHashSet.remove(long)` fast-path previously returned true without actually deleting; removal now always goes through the full remove path.
- Gotcha: Windows ?????? YAML ????????????????????????????????????????????????????`*.lock` ??????????????self-lock ???????- Resolved: `ColumnarStoreTest` ????????YAML meta ??????????????????????lock-file ?????????????- `p2p-core` TCP/UDP handler scanning now enumerates all classpath resources under `javax.net.p2p.server.handler` (previously only one), so handlers from extension modules can be discovered reliably.
- Boundaries touched: `p2p-db` (local ORM implementation + relations), `.brain` (durable notes).
- Note: the current worktree also includes earlier `p2p-core` governance changes (RPC + `STD_ERROR` structured errors) from prior tasks/sessions.
- Follow-up: consider adding delete APIs and relation cleanup (free old var-bytes when removing mappings) if the ORM is used for long-running databases.
- Gotcha: `p2p-db` compilation can fail if Lombok-generated `log` fields are not produced; `DsHashMap`/`DsHashSet` now define explicit slf4j loggers to avoid Lombok dependency during compilation.
- Gotcha: in PowerShell, pass `-Dsurefire.failIfNoSpecifiedTests=false` as a single argument (quote it) when using `-Dtest=...` with `-am`, otherwise it may be split and Maven treats it as a lifecycle phase.
- Gotcha: in PowerShell, pass `-Dtest=...` as a single quoted argument when the value contains dots, otherwise it may be tokenized unexpectedly.
- Resolved: `mvn -pl p2p-db -Dtest=com .q3lives.ds.collections.DsTableAdapterTest test` fails due to PowerShell tokenization; pass `"-Dtest=com.q3lives.ds.collections.DsTableAdapterTest"` instead.
- Gotcha: `p2p-db` full test suite is not assumed green; verify changes with targeted tests where appropriate.
- Progress: `mvn -pl p2p-db test` is green again after restoring `DsField(min)` validation coverage and zero-filling bucket tails on overwrite.
- Gotcha: `DsHashMapConcurrentTest` ????????writers latch ??await ????????????????????????????? missing key???????????assert await ???????????????
- Note: `DB_META_GET` ?????????????? entityClassName ?????????? meta yaml?????????ensureFresh=true??????????????????best-effort ???? ensureMeta?
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
