# DsFreeRing Set 语义 + 环形遍历不变性修复

**落地日期**：2026-09-03  
**关联模块**：`p2p-db/src/main/java/com/q3lives/ds/core/DsFreeRing.java`  
**关联测试**：`p2p-db/src/test/java/ds/DsFreeRingAsInodeSetTest.java`（8 个）、`DsObjectFreeRingRafTest.java`（回归 1 个）

## 原问题与修复点

### bug 1：offerUnique 在环形队列满 (count==cap, head==tail) 时直接漏查重
旧代码 `for (int i=head; i<tail; i++)`，当 `head==tail` 时循环 0 次，所有已有元素查重都被跳过。

**修复**：统一使用 `findSlotLocked(long value)` 内部 helper，用 `count` 步长的两段遍历 `[head→cap) ∪ [0→head)`，不管空/线性/环绕哪种布局都能覆盖 `count` 个 slot。

### bug 2：offer() 扩容前写死 `tail = cap` 脏回写
旧代码在扩容前强制 `tail=cap`，在并发/异常路径下会把真实 tail 覆盖。

**修复**：删除死线，统一交给新 expand() 管理。

### bug 3：expand() 只 setLength 不迁移 → wrap 布局下元素物理不连续
旧 expand 仅扩文件长度，没有把 `[head→cap) + [0→tail)` 的环形两段在物理上重新线性化，导致后续 poll/contains 在新 cap 下遍历顺序被破坏。

**修复**：每次扩容必执行：`count 步 forEachSlotLocked → 读入 long[] → 写回 slot [0..count) → head=0 tail=count`。因此扩容后永远是线性布局，FIFO 顺序严格保留，Queue API 100% 回归。

## 新增 Set 语义公开 API

| API | 签名 | 行为 | 典型场景 |
|---|---|---|---|
| `contains` | `boolean contains(long value) throws IOException` | 两段计数遍历查找，命中即 true；不改状态；支持环形满状态正确命中 | "文件 inode 是否在此目录内" |
| `remove` | `boolean remove(long value) throws IOException` | findSlot → idx；若不是尾部则 swap(idx, lastIdx=(tail+cap-1)%cap)；`tail=lastIdx`；`count--`；返回 false 表示不存在 | "从目录删某个 inode" |
| `snapshot` | `long[] snapshot() throws IOException` | 锁内 new long[count]；两段遍历写入返回。顺序与 offer/poll 逻辑 FIFO 一致（对 inode set 无序列表无影响，但对 Queue 调试友好） | "列目录所有 inode" |
| `size` | `int size() throws IOException` | alias `count()`，贴近 java.util.Set 语义 | `for (int i=0; i<ring.size(); i++)` 风格写法 |

## 验证命令

```bash
mvn -pl p2p-db test \
  -Dtest=DsFreeRingAsInodeSetTest,DsObjectFreeRingRafTest \
  -DfailIfNoTests=false
```

**验收（最终通过）**：`Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`

## 不变式（已写入 AGENTS.md）

1. **遍历不用 head<=tail**：所有跨元素访问（contains/offerUnique/forEach/snapshot/expand）必须用 `count` 剩余步数 + 两段 `[head→cap) ∪ [0→head)`，禁止依赖 head/tail 数值大小关系分支。
2. **扩容必线性化**：expand() 从任意布局 → 线性 head=0/tail=count 布局，保证后续 poll 顺序与历史 offer 顺序完全一致。

## 不变式 Gotcha（历史教训）

`count == cap` 的环形满状态下必然 `head == tail`，这是 `head/tail/count` 三件套环形队列的固有性质。**任何 head<=tail → 线性 / head>tail → 环绕 的分支判断都会在满状态下错误地走到"线性→空循环"路径**，造成漏读/漏查重/丢数据。因此遍历永远用 count + 两段，不要做 head 与 tail 的比较分支。
