# 为什么在 DsFreeRing 内部同时支持 Queue + Set 语义，而不是新建 DsLongSetFile

**日期**：2026-09-03  
**关联任务**：DsFreeRing 增加 64bit-inode Set 语义（contains/remove/snapshot/size）并修 offerUnique wrap 查重 / tail=cap 死线

## Context

用户当前要在 p2p-fs / p2p-sync 的文件目录下存"该目录内所有 inode id（64-bit long）"的集合，
需要 4 个基础能力：判断 inode 是否在目录、加 inode（去重）、删 inode、列出所有 inode，
并要求跨 JVM 持久化（不用每次重启遍历外存）。

p2p-db 已有 DsFreeRing 组件，原本承担：
1. DsFixedValueStore 已分配 slot 回收池（Queue：offer/poll FIFO）
2. DsHashMap 链表桶空闲 hash 回收池（Queue：offer/poll FIFO）

其物理格式是"20B 头（cap/head/tail/count 4×int） + cap × 8B slot（long 位宽正好 64-bit）"
的 RandomAccessFile 持久化环形队列，initialCap 可配置（两个现有调用方分别是 1024 和外部传入）。

## Options Considered

### A. 在 DsFreeRing 内新增 Set 4 公开 API

**做法**：
- 保持 Queue 语义 100% 不变（offer/offerUnique/poll/count/clear/close）
- 新增 4 公开 API：`contains(long)` / `remove(long)` / `snapshot()` / `size()`
- 所有遍历统一通过私有 3 helper：`findSlotLocked(long)` / `forEachSlotLocked(LongConsumer)` / `removeSlotLocked(int)`，
  所有 helper 用 `count`（剩余步数）+ 两段 `[head→cap) ∪ [0→head)` 遍历，不依赖 head/tail 大小比较。
- 扩容 expand 每次必线性化 long[] → slot[0..count) → head=0 tail=count。
- 删除任意 slot idx 用"搬 lastIdx=(tail+cap-1)%cap 到 idx，tail=lastIdx，count--"的 O(1) 压缩策略。

**优点**：
1. 不新增文件持久化格式代码——单 DsFreeRing.java 改，300+ 行内完成（含 helper）。
2. 两个已有 Queue 调用方（DsFixedValueStore、DsHashMap）无任何 API/行为变更。
3. 文件格式 100% 与旧 `.free` 兼容（旧目录直接 open 就能读 contains/remove）。
4. inode 集合天然无序，O(1) 删除搬尾不会破坏"集合无序"语义。

**缺点**：
1. 单类承担 Queue + Set 两种语义，阅读成本略高（但 helper 统一后，contains/remove/expand 代码都是 forEach 组合）。
2. contains/remove 是 O(count) 线性扫描，万级以下目录完全 OK，百万级需拆分子桶或换 DsHashMap<Long,BOOLEAN>。

### B. 新建独立 DsLongSetFile 类

**做法**：完全新写一个持久化 long set（物理格式可以与 DsFreeRing 一样，但不 offer/poll 方法）。

**优点**：语义单一。

**缺点**：
1. 要重复写一遍 RandomAccessFile 的 header（magic/version/cap/head/tail/count）读写、close、
   expand、openOrInit、lock 等 ~80 行代码，增加未来维护面积。
2. 两套相同格式文件要写两套测试，容易有不一致。
3. p2p-db 现在就已经有 40+ 个持久化组件，新增会让新人学习曲线更高。

## Decision

选择 **A. 在 DsFreeRing 内新增 Set 4 公开 API**。核心原因：
1. 物理文件格式完全一样（cap 个 64-bit slot），新建类没有任何物理格式上的收益。
2. Queue 和 Set 的遍历核心都是"跨 head/tail 做 count 次迭代"，用统一的 helper 反而减少 bug 面（
   之前 offerUnique 的 wrap 漏查重 bug 就是因为没有统一 helper，写了 for(head→tail)）。
3. inode 集合是 p2p-fs 的小规模场景（单目录 ≤ 几万 inode 是绝大多数情况），线性扫描可接受。
   如果将来有大桶需要，再做 16 分桶或换 DsHashMap 都是 1-2 天内可完的增量改造，不用现在提前引入复杂度。

## Tradeoffs

- **放弃了"单类单语义"的纯度**，换来了持久化格式与回归测试的 100% 复用。权衡后接受：
  Queue/Set 语义在 DsFreeRing 中通过命名区分（offer/poll vs contains/remove/snapshot/size），
  不会混淆调用方。
- **O(n) contains/remove**，对万级目录 OK，对百万级是性能风险。Tradeoff 接受：当前 p2p-sync / p2p-fs
  的文件目录规模没到十万级以上，且用户明确要求"通用 64-bit id 集合"，先做通用。将来可加 bloom filter
  前置拦截或改用 DsHashMap<Long, Boolean> 做 O(1)，这一层在目录集合类上层加，不动 DsFreeRing。
- **expand 线性化写回 cost = O(count)**。权衡后 OK：扩容本来就是 ×2 指数级，触发次数是 O(log N)，
  且每次扩容后 head=0 线性布局，后续 poll/迭代都更快（少一次模运算分支），整体摊还开销可忽略。
