# DsFreeRing expand() 零拷贝恢复 + Performance First 核心规则

## 变更背景

用户明确反对 DsFreeRing expand() 之前的 "全量 long[] 两段遍历读 + 线性写回 + head=0/tail=count 拉平" 实现（O(n) 读 + O(n) 写 + 堆分配 long[count]），要求"恢复原始设计：物理顺序无关，只 setLength 翻倍 + 调 header 即可"；并要求把"性能为主，严禁不必要的过度保护"写进项目核心规则。

## 技术决策（Performance First）

### 1. 错误的过度保护（已删除）

原 Session 写的 invariant："expand() 必须线性化拉平，保证 FIFO 顺序与旧实现物理一致"——**完全错误**，带来的 O(n) 成本没有任何收益。Queue FIFO 顺序只由 head/tail/count 三件套决定，**物理位置可以任意**，count 两段遍历不会因为物理不线性就读错。

### 2. 纯 header 调参第一次尝试（失败 — 证据：DsFreeRingAsInodeSetTest 第 8 个 test fail）

```java
// ❌ 第一次尝试（wrap-around + expand 组合下失败）
private void expand(int newCap) throws IOException {
    if (raf.length() < OFF_DATA + newCap * 8L) raf.setLength(...);
    cap = newCap;
    tail = (head + count) % cap;
    // 没 writeHeader，而且 wrap 时 slot 4..7 新扩 0-fill 会被 count 两段遍历先读到
}
```

**失败场景证据**（`testExpandWhileInWrapPhasePreservesAllValues` L200-L223）：
- 扩容前 cap=4, head=2, tail=2, count=4（环形满）
- 物理位置：[0]=5, [1]=6, [2]=3, [3]=4
- 逻辑顺序（旧 count 两段遍历）：[2]=3, [3]=4, [0]=5, [1]=6 ✓
- 纯 setLength(8) + cap=8 后，新的 count 两段遍历读 `[head=2, cap=8)`：
  - slot 2 = 3 ✓
  - slot 3 = 4 ✓
  - slot 4 = 0 ❌（新扩区域 OS 零填充，原逻辑这里应该是 5）
  - slot 5 = 0 ❌（原逻辑应该是 6）
  - remain 减完直接结束，第二段 `[0, head=2)` 根本不会被读到！
- **snapshot 结果 {0, 3, 4, 7}，期望 {3,4,5,6,7}**，断言失败。

### 3. 最终正确实现（线性阶段真·零拷贝；wrap 阶段只搬 wrapCount ≤ count/2）

```java
// ✅ [DsFreeRing.java expand](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/core/DsFreeRing.java#L316-L330)
private void expand(int newCap) throws IOException {
    final int oldCap = cap;
    if (raf.length() < OFF_DATA + newCap * 8L) {
        raf.setLength(OFF_DATA + newCap * 8L);  // 可能的 1 次 syscall
    }
    // 仅当逻辑序列跨越 oldCap 边界（= wrap-around 存在）才局部搬 wrapCount 个到 oldCap 起点
    // 线性阶段 head+count <= oldCap: 完全不搬任何字节（真·零拷贝，90%+ expand 触发场景）
    if (head + count > oldCap) {
        final int wrapCount = (head + count) - oldCap;
        for (int k = 0; k < wrapCount; k++) {
            writeAt((long)oldCap + k, readAt(k));  // slot [0..wrapCount) → [oldCap..oldCap+wrapCount)
        }
    }
    cap = newCap;
    tail = (head + count) % cap;  // 新的 tail = 最末逻辑元素的下一个物理位置
    writeHeader();  // 把 cap/head/tail/count 写回文件（之前第一次尝试漏了！）
}
```

**性质**：
- **复杂度**：线性阶段（head+count ≤ oldCap）**O(1)**（没有任何数据读/写/堆分配）；wrap 阶段 **O(wrapCount)** 最坏 count/2 = 旧实现 O(count) 成本的一半以下，平均更小。
- **正确性等价于拉平方案**：扩容后再跑 count 两段遍历 `[head→newCap) + [0→head)`，前 count 个元素的物理位置的值与原逻辑序列完全一致；poll FIFO 顺序与原拉平实现完全一致（poll 第 k 次 = 逻辑序列第 k 个）；offer 从 tail 往后写不会覆盖任何已有元素。
- **持久化安全**：expand 末尾 `writeHeader()` 把 cap 新值、tail 新值 写回文件，后续 reload 打开后 `readHeader()` 读到的就是正确的三件套。

## 项目核心规则（已写入 AGENTS.md Local Notes 第 1 条）

- Core Rule — Performance First：本项目以性能为第一优先；严禁不必要的过度保护、全量拷贝、防御性兜底式"以防万一"式写法。4 条细则：
  1. 禁止无理由的全量数据拷贝（可以原地调 header 实现的地方绝对不许 O(n) 读 + O(n) 写）；DsFreeRing expand() 绝对禁止临时 long[] 全量读+写。
  2. 禁止为了"简化心智负担 / 线性化美观 / 为了跟旧实现完全物理一致"而引入本来能避免的磁盘 I/O、堆分配、遍历复制。
  3. 防御性检查必须有证据支持才加：只有实际出现 bug report / test fail / 明确用户要求才能加；凡是"假设万一"式想法但没实际 bug 报告的一律不准加。
  4. 所有 I/O、内存分配、计算的默认立场是 **最小集**；要加额外开销必须先证明不加会真的错。

## 验证命令（两条命令全绿 = 回归通过）

```powershell
# ① Set 语义 + Queue 回归 + wrap-phase expand（8 tests 全 0F0E）
brain session run --project . -- mvn -pl p2p-db -Dtest=DsFreeRingAsInodeSetTest test
# → Tests run: 8, Failures: 0, Errors: 0, Skipped: 0

# ② 调用方（DsFreeRing 作为 HashMap entry 回收池 + FixedValueStore 回收池的真实用法）全量回归
brain session run --project . -- mvn -pl p2p-db '-Dtest=DsHashMapEntryFreeRingTest,DsFixedValueStoreTest,DsHashMapTest,DsStringRefCountTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
# → Tests run: 17 (6+1+8+2), Failures: 0, Errors: 0, Skipped: 0
```

总共 25 tests run，0 failure，0 error。

## Durable 变更（promotion targets）

1. **[AGENTS.md](file:///i:/2026/code/p2p-net-storage/AGENTS.md#L125-L160)**：
   - 新增 Core Rule（Performance First）4 条于 Local Notes 顶部。
   - 修改 DsFreeRing 2 条 invariant：(a) 遍历括号内删除 expand（expand 本身不再需要遍历）；(b) expand invariant 从"必须拉平拷贝"改为"零拷贝 + 局部仅搬 wrapCount 个到 oldCap 起点，末尾 writeHeader"。
2. **[DsFreeRing.java expand](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/core/DsFreeRing.java#L316-L330)**：14 行实现，删除 16 行的 long[] 全量遍历读写。
3. **[.brain/context/current-state.md](file:///i:/2026/code/p2p-net-storage/.brain/context/current-state.md#L568-L)**：Session outcome 条目（追加到底部）。

## 两个 Durable Gotcha（重复陷阱）

1. **Gotcha: expand 纯 header 调参在 wrap-around 阶段不成立**。newCap > oldCap 时，count 两段遍历 `[head→newCap)` 会先跨到 oldCap..newCap 的 0-fill 区域，把原来 wrap 尾部 [0, tail_old) 的值直接跳过；必须把 wrapCount 个从 [0, wrapCount) 的物理位置搬到 [oldCap, oldCap+wrapCount)，才能让新的遍历序列读到逻辑上等价的值。
2. **Gotcha: Maven `-Dtest` 多类 filter PowerShell 语法**：逗号 OR 匹配必须写 `-Dtest='A,B,C,D'`（单引号防 PowerShell 把逗号当数组）；**`+` 号在 Surefire 是 AND**，写 `A+B+C` 会被解释为"同时满足四个全字匹配的单类"→ No tests match，结果 exit 1（不是代码错，是语法错）。Windows mvn 多类跑务必用逗号 + 单引号。
