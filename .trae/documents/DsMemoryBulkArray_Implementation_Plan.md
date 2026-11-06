# DsMemoryBulkArray（字节数组跨 BLOCK 截断） Implementation Plan

## Repository Research
**目标问题**：`dsdb-design-status-and-roadmap.md` 第八章「最优先 1 个立即动作」描述的「loadLongOffset/storeIntOffset 数组读写截断 bug」——代码审计发现当前版本 long[]/int[] 数组版方法语义正确（用 putLong/getLong/putInt/getInt，while+for 跨块循环条件正确），但 **`DsMemory.loadBytesOffset(position, byte[], destOffset, length)` 存在完全等价的跨块截断 bug**：

**当前实现（BUGGY，L549-553）**：
```java
protected void loadBytesOffset(long position, byte[] dest, int destOffset, int length) {
    runWithBufferLocks(() -> {
        loadBufferWithOffset(position).get(dest, destOffset, length);  // 单 buffer.get()，无跨块
    });
}
```

**对比正确实现（DsObject L1234-1253）**：
```java
while (remaining > 0) {
    long bufferIndex = bufferIndexFromPosition(currentPos);
    int offset = bufferOffsetFromPosition(currentPos);
    int take = Math.min(remaining, BLOCK_SIZE - offset);  // 按块切片 take
    buffers[(int)bufferIndex].get(offset, dest, dp, take);
    remaining -= take; dp += take; currentPos += take;
}
```

**影响范围**：
- 调用方：`DsHashMap` / `DsHashSet` / `DsMemorySet`（`grep loadBytesOffset files_with_matches=5`），批量 byte[] rowId 返回（P1 OQ4 二级索引批量 long[] 场景映射到字节层）。
- 触发条件：`position + length` 跨越 64KB BLOCK_SIZE 边界 → `buffer.get(dest, destOffset, length)` 因 `remaining < length` 抛 `BufferUnderflowException`，或最严重时只读到第一个 block 末尾就截断返回（但 ByteBuffer.get(len) 会抛，所以是确定性 Exception）。
- 风险等级（roadmap 风险 #2/3/7）：P1 二级索引批量 long[] rowId 返回一旦启用 100% 触发。

**DONE：long[]/int[] 数组版方法复核**：
- `DsMemory.storeLongOffset(position, long[]) L380-420`：putLong 正确，while(j<N)+i<chunkElems 正确
- `DsMemory.loadLongOffset(position, long[]) L429-466`：getLong 正确，while+for 正确
- `DsMemory.storeIntOffset(position, int[]) L717-757`：putInt 正确
- `DsMemory.loadIntOffset(position, int[]) L766-803`：getInt 正确

## Files and Modules
- `p2p-db/src/main/java/com/q3lives/ds/core/DsMemory.java`：Edit 2 处（① loadBytesOffset(position,dest,destOffset,length) L549 补 while+take 跨块循环；② 加 storeBytesOffset 对称写入方法，避免将来 p2p-sync 批量字节写入缺方法）
- `p2p-db/src/test/java/com/q3lives/ds/core/DsMemoryBulkArrayTest.java`：NEW 单测（3 场景 × 字节 + 对称 long/int 数组 roundtrip）

## Implementation Steps
1. **单测先行（3 场景字节跨块 + 3 对称 long/int）**：
   - 场景 A（单块内）：`position = BLOCK_SIZE/2 - 1024`，`length = 2048`（完全在 Block 0）
   - 场景 B（跨 2 块）：`position = BLOCK_SIZE - 100`，`length = 200`（100B 在 Block 0，100B 在 Block 1）
   - 场景 C（跨 3+ 块）：`position = BLOCK_SIZE - 50`，`length = 2*BLOCK_SIZE + 200`（50B Block0 + 64KB Block1 + 64KB Block2 + 200B Block3）
   - 场景 D/F：同 A/C 触发 `storeBytesOffset → loadBytesOffset` 往返（如果加 storeBytesOffset），`Arrays.equals` 精确相等
   - 场景 E：long[] 跨 2 块 roundtrip（100 elems × 8B = 800B，position = BLOCK_SIZE - 500 → 跨 Block 0/1）
   - 场景 G：int[] 跨 3 块 roundtrip（30000 elems × 4B = 120KB，position = BLOCK_SIZE - 100 → 跨 3 blocks）
2. **修 DsMemory.loadBytesOffset(position, dest, destOffset, length)**：
   - 先校验：`destOffset < 0 || destOffset + length > dest.length → IllegalArgumentException`
   - 再 while(remaining>0)：bufferIndexFromPosition / bufferOffsetFromPosition / take = min(remaining, BLOCK_SIZE-offset)
   - `loadBuffer(bufferIndex).get(offset, dest, dp, take)`，每次剩余递减
3. **（可选但推荐）加 DsMemory.storeBytesOffset(position, src, srcOffset, length) + 2 参数重载**：
   - 对称实现，跨块 put；每次 loadBuffer 后 markDirty
   - 避免将来 p2p-sync 调用方引入 NoSuchMethodError
4. **mvn test DsMemoryBulkArrayTest**：全部 PASS → 再跑 JV 回归（ColumnarStoreTest / Orm_P7_Tgt2_Handler_6_Test / DsRegistry_P7_Tgt3），无回归

## Dependencies and Considerations
- **runWithBufferLocks 语义保持**：loadBytesOffset 原方法在 `runWithBufferLocks` lambda 内，修正后仍保持同一 lambda 内锁语义（不拆锁），避免跨块中途写线程 dirtyBuffers 驱逐
- **loadBuffer vs loadBufferWithOffset**：循环内用 `loadBuffer(bufferIndex)` + `buf.get(offset, dest, dp, take)`（absolute get，不改变 buf.position），与原 single-get 语义不变
- **storeBytesOffset NEW 修饰符 protected**：与 DsMemory 其它 store* 方法保持 protected/package 边界一致，不 new public API
- **零新增 Maven 依赖**：沿用 JUnit（已有），不新引入依赖

## Validation
1. `mvn -pl p2p-db -Dtest=DsMemoryBulkArrayTest test` — 3+4 场景全部 `Arrays.equals()` 精确相等，无 BufferUnderflow
2. `mvn -pl p2p-db -Dtest=DsMemory*Test,ColumnarStore*Test test` — 原有 DsMemory* 单测 0 回归
3. JV 回归：`mvn -pl p2p-db -Dtest=Orm_P7_Tgt2_Handler_6_Test,DsRegistry_P7_Tgt3_HandlerTest test` — 0F0E

## Risks
- **锁时间变长（跨多块都在 runWithBufferLocks）**：批量 2 块以上会一直持有 buffer 锁；风险极低（P1 批量场景最大 10MB 级 157 块 ≈ 毫秒级，且目前无高并发写压力）→ 处理：保持单 lambda 语义，不做分片锁拆分（此修复 scope 不含性能优化）
- **ByteBuffer absolute vs relative 语义差异**：改用 `buf.get(offset, dest, dp, take)`（absolute）而不是 `buf.position(offset).get(dest, dp, take)`（relative）→ 降低线程可见性风险（MappedByteBuffer 跨线程 position 非线程安全），处理：absolute 版本确保不对 buf.position 产生副作用
- **future storeBytesOffset 不调用方未使用**：没 caller，但 DsObject 有 load 对称模式 → 保留方法加 `// TODO: 批量字节写入入口` 注释即可
