# p2p-db 集合结构（I64）说明

本文档聚焦 `DsHashSetI64` 与 `DsHashMapI64` 的底层存储布局与“有序访问”语义，便于在优化/重构时保持一致性。

## 1. 位图驱动 256-ary trie

两者都使用同一套节点结构：

- 每个 node 固定 256 个 slot。
- 每个 slot 使用 2-bit 状态位（位图）描述该 slot 的含义：
  - `STATE_EMPTY`：该 slot 空。
  - `STATE_VALUE`：该 slot 存放一个 value（set 为 key，map 为 entryId 指向 key/value）。
  - `STATE_CHILD`：该 slot 指向下一级子 node。
  - `STATE_NEXT_LEVEL`：该 slot 发生升级，完整 key 必须下沉到 next-level 结构继续查询/写入（slot payload 本身不作为判空依据）。

有序遍历顺序由“slot 扫描顺序 + DFS 下钻”唯一决定；对外体现为按 key 的自然序（signed long）输出。

## 2. slot 区索引指针变长存储（2/4/8）

slot payload 使用可配置的 `ptrSize`（2/4/8 字节）存储“索引指针”，用于：

- `STATE_CHILD`：存放 `child nodeId`
- `STATE_VALUE`：
  - `DsHashSetI64`：存放 `entryId`，entryStore 内保存 `key`
  - `DsHashMapI64`：存放 `entryId`，entryStore 内保存 `key + value`

该设计的目标是让小 key 段使用更小的 node 体积（更少 IO/更少映射占用），同时保持对外 API 不变。

## 3. 三段(16/32/64)快速跳跃寻址

为了让“小范围 key”更省空间并减少随机 IO，两者都采用同一套值域分段：

- `key < 0`：走 64-bit 段（负数段）
- `0 <= key < 0xFFFF`：走 16-bit 段（主结构本地段）
- `0xFFFF <= key < 0xFFFFFFFF`：走 32-bit 段
- `key >= 0xFFFFFFFF`：走 64-bit 段（正数大 key 段）

对外语义保持透明；有序遍历时整体顺序固定为：

1) 负数 64-bit 段  
2) 本地 16-bit 段  
3) 32-bit 段  
4) 正数 64-bit 段

## 4. 有序 API 约束

为了避免超大数据集的 O(n) 快照成本：

- `iterator()` 必须是 DFS 惰性遍历（不允许先收集再排序）。
- `first/last/getByIndex/indexOf/range/forEachRange` 应与 `iterator()` 保持同一套顺序定义（避免“接口间顺序不一致”）。

## 5. DsHashMapI64：entryId 回收复用（free-ring）

`DsHashMapI64` 的 entryStore 会产生可复用的 `entryId`。删除时将 entryId 放入 free-ring（旁路 `.free` 文件）：

- 重新插入时优先从 free-ring 取出复用，避免 entryStore 单调膨胀。
- 写入路径的结构调整/升级阶段不应复用仍被 trie 引用的 entryId，避免覆盖导致的“串号/误删/遍历异常”。

