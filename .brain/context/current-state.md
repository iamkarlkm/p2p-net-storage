---
updated: "2026-05-04T17:50:52Z"
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

- `p2p-db` 新增 columnar（零行头）存储与表/复合列元数据落盘（见 `.brain/resources/changes/dsdb-columnar-store.md`）。
- 复合列组（`@DsCompositeField.group`）也作为“列”注册：每个 group 绑定一个 colId（colKey=`<entity>#@composite:<group>`），并可按列读写该 group 的 packed bytes。
- 验证：`mvn -pl p2p-db test`。
- Gotcha：Windows 下 YAML 元数据文件需用独立 `*.lock` 文件做串行化更新，避免自锁读失败（已修复并回归）。
- Gotcha：复合列组读取固定 `group.length` 字节，因此首次分配 valueId 时必须按 `group.length` 申请 bucket（避免 “data overflow bucket unit”）。
- Resolved：复合列 group 写入现使用 `colKey=<entity>#@composite:<group>` 注册并分配 colId；首次写入按 `group.length` 分配 valueId，读写回归通过。
- Resolved：元数据校验增强：拒绝重复 `@DsField` 同名但类型/长度不一致；拒绝 `@DsCompositeField` 同 group 但 length 不一致；拒绝复合位段越界/重叠/同名 item。
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
- Local DB root can be loaded from YAML: `SystemConfig.yaml` key `DbHome` (or system properties `p2p.system.yaml` / `p2p.db.home`).
- `p2p-db` adds a remote client `DsDatabaseServer` using new `P2PCommand` pair `DB_ENTITY_PUT/GET` to store/load `DsTableAdapter` rows on a server that has `p2p-db` on its classpath.
- Remote `DB_ENTITY_PUT/GET` now transfers row bytes plus optional relation payload bytes (encoded/decoded via `DbEntityRelationsCodec`) when `withRelations=true`.
- Remote DS_DB commands now also cover `DB_ENTITY_EXISTS/REMOVE/QUERY_IDS`, and server handlers maintain the same per-entity `ids.set` index so exists/query works after remote PUT/REMOVE.
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
