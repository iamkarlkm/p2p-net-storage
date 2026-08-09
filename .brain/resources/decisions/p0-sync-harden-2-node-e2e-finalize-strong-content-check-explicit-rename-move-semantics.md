# Why we chose P0 sync harden: 2-node E2E, finalize strong content check, explicit rename/move semantics

## Context

P0 主线要求"真实双节点/多节点 E2E、finalize 后内容强校验、明确 rename/move 语义"作为生产级同步平台的最硬门槛。当前主分支此前仅实现了 CREATE/MODIFY/DELETE 三条基线，rename/move 被退化为 DELETE+CREATE 两个独立事件，导致：
1. 发送端 monitor 观测不到 RENAME/MOVE 意图；
2. 接收端无法原子态地处理"source 删除 + target 重命名"，跨目录场景下 source 永不删除（DELETE 先被独立 enqueue，或 contentLength=-1 使 fallback merge 无法将 DELETE+CREATE 识别为 MOVE/RENAME）；
3. finalize 阶段无 verifiedContentLength/verifiedContentMd5 回写，无法在接收端磁盘上对上传内容做二次强校验。

## Options Considered

- Option A：仅在接收端 finalize 后单独对 source 做补删除（不改造协议与发送端 handleRename 链路）。缺点：srp 永远不进入协议/monitor/事件UID计算，幂等无法保证 rename/move 语义，重试后可能误删同名文件。
- Option B：完整协议扩展 + 发送端/接收端/队列/监控一整条 RENAME/MOVE 独立路径。缺点：改动面大（18 files +1460 -166），但能保持 CREATE/MODIFY/DELETE 原路径零破坏，向后兼容。
- Option C：引入独立 `SyncRenameMoveRequest` 新 RPC。缺点：幂等事件UID需要两条协议分别维护，破坏 APPLY_EVENT/FINALIZE_EVENT 两阶段一致性。

## Decision

选择 Option B：在 `p2p_rpc_sync.proto` 已有 `SyncEventRequest/SyncFinalizeRequest/SyncEventAck` 三消息体中分别追加新 field 号（8/9/6,7），向后兼容而不引入新 RPC；`FileSyncEventHandler` 提供 `handleRename(7 arg)` default method，老 5-arg 路径零破坏；7 个 wrapper 全部 override handleRename 保证 srp 正确透传；Fallback merge 修复 pushPendingDelete 真实 contentLength + 放宽未知 sizeDelta；Receiver finalizeEvent 采用"先独立删 source → 再 apply(req) 仅 setLastModified"的双保险原子态方案。

## Tradeoffs

- 改动面比 A 大，但 P0 语义与生产对齐；后续冲突策略、多副本分发时 rename/move 不再需要再回归协议。
- verifiedContentLength/verifiedContentMd5 与 sourcePath 在 Monitor JSON 中按"值非空才写入"渲染，避免老字段 schema 被打破。
- ORDER 重排为 CREATE 先、RENAME/MOVE 居中、DELETE 最后，与 pendingDeletes flush 500ms 窗口不冲突，但 MODIFY 被延后；实际生产中 MODIFY 是高频事件，若出现饥饿可独立调优。
