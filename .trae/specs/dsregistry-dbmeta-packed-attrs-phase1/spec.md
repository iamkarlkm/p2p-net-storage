# Spec：DsRegistry DB 专用一对一扩展属性打包 Phase 1（方案 A）

**Created:** 2026-09-16
**Component:** p2p-db `DsRegistry` / DsRegistry ExtAttrs Helper 旁支新 Helper
**Constraint Anchor:** 7 大底层零改 / DsRegistry.java 零改 / 跨节点 P2PCommand 零新增（完全复用 Tgt-3 24304-24312）

---

## 1. 问题与用户目标

### 1.1 用户原文诉求（Verbatim）
> 「增加几个数据库专用的一对一扩展属性吧：DESC，NAME等，为性能考虑，可以像列组一样组合起来一次读写 ->帮我研究一下 → 确认」

- **核心性能要求**：N 个一对一属性合并成 **1 次 put + 1 次 get**，避免 N 次 putString/putLong 多次 I/O、N 次 StampedLock.writeLock、N 次 DsFreeRing.offerUnique 子项注册、N 次 valueStrings/DsString 写入；
- **功能语义**：属性是 **一对一**（last-write-win 覆盖），与 Tgt-5 TAG/DESC/SPEC/COMMENT 多值/多版本语义隔离；
- **Scope**：Phase 1 最小集 = 9 字段（4 STR/ENUM + 4 NUMERIC + 1 BOOL）打包 + 本地 CRUD + 跨节点零新增命令号复用 Tgt-3。

### 1.2 非目标（Non-Goals / Explicit Out of Scope）
- ❌ 不替代/迁移/修改 Tgt-5 TAG/DESC/SPEC/COMMENT 多值多版本 23 API（AGENTS Invariant：路径隔离）
- ❌ 不把大文本 SPEC 正文、二进制备份日志、REG_MULTI_SZ 列表等 ≥1KB 打进 payload（单独 REG_BLOB 叶子）
- ❌ 不新增 P2PCommand 枚举（24336+ 明确预留给 Tgt-2 ORM，不可占用）
- ❌ 不引入 CRC32/压缩等默认可选项（默认 flags.bit0=0 无 CRC；加要 Phase 2 再开）
- ❌ 不修改 DsRegistry 底层（DsFixedBucketStore、DsFreeRing、DsString、DsTypeFixedBucketStore、DsTypeString、DsData、DsHashMap 7 大组件 = 0 行变更）
- ❌ 不开放 DsRegistry 底层 API（valueBinaries/nodeStore 等 package-private 字段保持不可见）

---

## 2. 功能性需求（Functional Requirements）

### FR-1 打包字段集（Phase 1 9 字段，顺序 tagId 严格递增不可改）
- **STRING × 3（可变长 UTF-8，max 256B/每个）**：NAME、DESC、OWNER
- **ENUM（STRING，值域自由不预定义，Phase 1 不校验）× 1**：ENV（"prod"/"stg"/"dev"/"test" 等）
- **NUMERIC × 4（U32/U64 LE，无符号位）**：TTL_SEC、RETENTION_DAYS、VERSION、PARTITION_COUNT
- **BOOL × 1（1B = 0x00 false / 0x01 true）**：IS_PARTITIONED

### FR-2 原子组写（1 次 I/O）
**rule：** 通过单一 Helper API 一次性写 N 字段 → 底层 DsRegistry 仅 **1 次 `putBytes(key, payload, DataType.REG_BLOB)`** 公开调用；StampedLock.writeLock 仅 1 次进入（或 0 次若 inline 命中，Helper 外层不自己再套锁，完全复用 putBytes 内部单锁）。

### FR-3 组读（1 次 get + ALU 解包）
**rule：** 通过单一 Helper API 一次性读取所有存在的字段 → 底层仅 **1 次 `DsRegistry.get()`**；解包在 JVM 堆内纯 ByteBuffer.wrap（零拷贝），不再次调任何 Registry 方法。

### FR-4 部分字段覆盖（合并写，Last-Write-Win）
**rule：** `putStr / putLong / putBool` 单写 API 语义 = **读旧 Map 合并新 KV → 再 putAll**；已存在字段的 value 被覆盖；未出现的旧字段 **保留不丢失**。

### FR-5 删除（组删 + 单删）
**rule：**
- `removeAll(entryKey)` 删除 `<entryKey>/@dbmeta/attrs.bin` 整个叶子（recursive=false，不碰未来 `@dbmeta/*` 兄弟叶子），返回 true=实际删除 / false=不存在；
- 单字段 remove：通过 putAll 传 `Map.of(X, REMOVED_SENTINEL)`（实现上 encode 时直接忽略该 tagId = 等价删除）。

### FR-6 跨节点复用 Tgt-3 9 Handler（零新增命令号）
**rule：** 四个动作全部复用 Tgt-3 命令号，不需要新 Request/Ack/Handler：
1. 写：`RegistryPutBytesServerHandler(24307)`，entryKey=`"<entryKey>/@dbmeta/attrs.bin"`，DataType=REG_BLOB(code=0x06)；
2. 读：`RegistryGetServerHandler(24308)`，Ack SimpEntry.bytesValue 解包；
3. 存在性：`RegistryExistsRequest(24309)`，boolValue；
4. 删：`RegistryRemoveRequest(24310)`，recursive=false。

### FR-7 命名空间隔离（强约束，不可越界）
**rule：**
- 打包 payload 持久化键唯一 = `entryKey + "/@dbmeta/attrs.bin"`；
- Helper 不读写 `entryKey + "/@tags/*"`、`/@meta/*`、`/_extNextSeq/*`（Tgt-5 ExtAttrs 三大前缀）；JUnit 显式断言 listChildren 分隔无交叉。

### FR-8 向后读兼容（未知 tagId 跳过）
**rule：** decode 时遇到未知 tagId（非 0x01-0x04 / 0x40-0x43 / 0x60）→ 按 len 跳过 `1 + 2 + len` 字节继续读后续字段，不抛异常，返回 Map 不含未知键。

---

## 3. 非功能性需求（Non-Functional Requirements）

### NFR-1 7 大底层零改 / DsRegistry.java 零改
**rule（硬约束，Karpathy "Surgical Changes"）：**
- DsFixedBucketStore / DsFreeRing / DsString / DsTypeFixedBucketStore / DsTypeString / DsData / DsHashMap 7 大底层 = 0 行代码变更；
- DsRegistry.java 主体文件 = 0 行变更（不新增 Public API，不修改 StampedLock/inline/smallBytes/bucket 写入逻辑）；
- 所有代码落在一个新文件 `DsRegistryDbMetaHelper.java`（新 package-private 工具类，同 `com.q3lives.ds.registry` 包）。

### NFR-2 小属性单字段写入 → INLINE 8B 零 I/O 命中
**rule：** 当写入只有 1 个 BOOL/U8（TLV=1+2+1=4B）+ Header 4B = 总 payload 8B → `DsRegistry INLINE_BYTES_MAX_LEN=8` 触发 `packSmallBytes(long v)`，不落 valueBinaries、不分配 bucket slot（性能第一原则）。

### NFR-3 锁竞争 × 9 量级减少（vs 分开 9 次 putString/putLong）
**rubric：维度=锁竞争减少率；Scale=0/1/2；Pass ≥ 2；
- 0：分开写和组写拿 writeLock 次数差 < × 2；
- 1：差 × 4~× 8；
- 2：≥ 9 次 StampedLock.writeLock 减到 1 次（× 9 减少）。**

### NFR-4 FreeRing 子项注册 1 次（vs 9 次）
**rubric：维度=FreeRing offerUnique 调用次数减少；Scale=0/1/2；Pass ≥ 2；
- 2：9 次 offerUnique 减到 1 次 offerUnique（仅 attrs.bin 1 叶子）；
- 1：减到 2~4 次；
- 0：没减少或新增更多。**

### NFR-5 冷启动 getAll 9 字段 → 读放大减少（1 次 get vs 9 次 get）
**rubric：维度=读放大减少率；Scale=0/1/2；Pass ≥ 2；
- 2：1 次 Registry.get + 1 次解包（nodeStore 读 1 次叶子 meta）对比 9 次 get = × 9 减少；
- 1：× 4~× 8；
- 0：< × 4。**

### NFR-6 跨节点 0 新增命令号 + 类型路由正确
**rule：** 24300~24335 区段无任何新枚举加入；Tgt-3/Tgt-5 7+4+12+14+5+1+3 = 46 烟测 tests 不受影响（仍 0F0E）；跨节点通过 payload 的 DataType.REG_BLOB get 端走 Category.BINARY 分支不抛错。

---

## 4. 约束（Constraints）
- C1：**性能第一** —— 严禁不必要的全量拷贝、防御性兜底；ByteBuffer.wrap 零拷贝必须用 `wrap(byte[])` 而不是 `ByteBuffer.allocate` 再 copy；
- C2：严格 ByteOrder.LITTLE_ENDIAN（对齐 DsRegistry VALUE_BIN_HEADER_LEN 4LE 规则 + DsRegistryMultiSzCodec 2LE len）；
- C3：tagId 永不复用（Phase 2 新增字段只能追加新 tagId 0x07/0x45… 不能改 Phase 1 已有 tagId 值）；
- C4：payload 只支持 9 字段类型，不能混入 REG_MULTI_SZ/列表（列表走独立 putList）；
- C5：`DbMetaAttr.ordinal()` 不能作为 tagId 依赖（enum 重排会改变 ordinal）→ 必须显式 `byte tagId` 字段 = 固定值。

## 5. 依赖 & 假设（Dependencies & Assumptions）
- D1：依赖 DsRegistry Public API 9 条稳定不变：putLong/putString/putBytes/get/exists/remove/close/listChildren/mkdirs；
- D2：依赖 DsRegistry INLINE_BYTES_MAX_LEN=8 常量（[DsRegistry.java#L67](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/DsRegistry.java#L67)），`packSmallBytes(byte[])` 逻辑稳定（[DsRegistry.java#L578-L603](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/registry/DsRegistry.java#L578-L603)）；
- D3：依赖 Tgt-3 RegistryPutBytes/Get/Exists/Remove 4 ServerHandler 稳定（跨节点复用零改）；
- **假设 A1：** 9 字段打包后总 payload ≤ 4KB（DsTypeFixedBucketStore bucket power 范围 ≤ 27，最大 2^27）；Phase 1 默认限制单字段 max 256B UTF-8，合计 3×256 + 1×256 + 4×8 + 1×1 + Header 4B + TLV 开销 ≈ 1052B ≤ 4KB，安全。
- **假设 A2：** 用户不需要在 Phase 1 做「基于 NAME/DESC 前缀的 DsRegistry findByPrefix」扫描（这 2 字段只做整体打包读写，不打算拿 NAME 当 key 遍历）；若未来需要可拆出独立叶子索引。

## 6. 验收标准总览（Acceptance Criteria Summary）
共 11 条 AC（8 rule + 3 rubric）：
- AC 8 rule：FR-1 / FR-2 / FR-3 / FR-4 / FR-5 / FR-6 / FR-7 / FR-8
- AC 3 rubric：NFR-2 / NFR-3 / NFR-4 / NFR-5（注：合并 NFR-2 8B inline 为 rule 单独加）

实际：**9 rule + 3 rubric = 12 AC。**
