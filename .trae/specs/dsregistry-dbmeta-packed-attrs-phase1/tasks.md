# Tasks：DsRegistry DB 专用一对一扩展属性打包 Phase 1

对应 spec：`./spec.md`（12 AC = 9 rule + 3 rubric）

---

## 全局约束（所有 Task 必守）
1. 7 大底层零改（DsFixedBucketStore/DsFreeRing/DsString/DsTypeFixedBucketStore/DsTypeString/DsData/DsHashMap）；
2. DsRegistry.java 0 行修改；
3. P2PCommand 枚举 0 新增（24336+ 预留给 Tgt-2 ORM）；
4. 不引入新依赖（仅 `java.nio.ByteOrder.LITTLE_ENDIAN` + 已存在 `com.q3lives.ds.registry.*` 包）。

---

## Task 1：新增 DbMetaAttr 枚举（9 字段 tagId 严格固定）

**Scope:** 新文件 `com.q3lives.ds.registry.DsRegistryDbMetaHelper$DbMetaAttr.java`（或 inner enum）—— 实际作为 `DsRegistryDbMetaHelper.java` inner enum 写在同一文件，package-private。

**Fields:**
- `final byte tagId`（Phase 1 固定值：NAME=0x01 / DESC=0x02 / OWNER=0x03 / ENV=0x04 / TTL_SEC=0x40 / RETENTION_DAYS=0x41 / VERSION=0x42 / PARTITION_COUNT=0x43 / IS_PARTITIONED=0x60）；**绝不依赖 ordinal()**；
- `final ValueType vt`（内部 inner enum：STR8=0 / ENUM8=1 / U32=2 / U64=3 / BOOL8=4）；
- `static Optional<DbMetaAttr> byTagId(byte id)`；

**绑定 AC:**
- FR-1（rule）：必须恰好 9 字段，tagId 与上面 list 严格一致；
- NFR-6（rule 子集）：tagId 永不复用。

**Test Requirements:**
- TR-1a（rule）：`byTagId` 每个值返回非空；未知 0x7F → empty。
- TR-1b（rule）：`assert DbMetaAttr.NAME.tagId == 0x01` 硬断言（防止改值）。

**Status:** pending

---

## Task 2：新增 DBMetaAttrsCodec（Header 4B + TLV 编解 + inline 命中）

**Scope:** 同一文件 inner class `DBMetaAttrsCodec`，package-private（不对外开放）。

**Header 格式（严格 4B，LITTLE_ENDIAN）：**
```
byte[0] = magic = (byte)0xA7;
byte[1] = version = 0x01;
// combined byte: [high 4bit countAttrs][low 4bit flags]; default flags=0x00 (no CRC)
byte[2] = (byte)((countAttrs << 4) | 0x0F & flags);
byte[3] = reservedFuture = 0x00;
```
**TLV 格式（N 次）：**
```
[1B tagId][2B short LE len(UTF-8 bytes or numeric bytes)][len bytes value]
```
数值编码：U32=4 bytes LE；U64=8 bytes LE；BOOL8=1 byte 0x00/0x01；STR8/ENUM8=len bytes UTF-8。

**公共方法：**
- `byte[] encode(Map<DbMetaAttr,Object> values)` throws IAE（非法字段类型/字符串 > 256B）；
- `Map<DbMetaAttr,Object> decode(byte[] payload)` throws IAE（损坏 = 提前 EOF / len < 0 / len > 剩余字节），未知 tagId **continue skip 不抛**。

**绑定 AC:**
- FR-2（rule）：encode 结果通过 `DsRegistry.putBytes` 一次写入底层；
- FR-3（rule）：decode 纯 ByteBuffer wrap 零拷贝；
- FR-8（rule）：decode 遇到 tagId 0x0F（不存在）→ 跳过后仍能读 TTL_SEC。

**Test Requirements:**
- TR-2a（rule）：9 字段 Round-trip Map → encode → decode → equals Map（含中文 NAME="用户表" / DESC="用户登录资料"）。
- TR-2b（rule）：**NFR-2 inline 命中**：仅写 `IS_PARTITIONED=true` → payload.length = 4 Header + TLV(1+2+1)= 8B → `assertEquals(8, payload.length)`（恰好 INLINE_BYTES_MAX_LEN=8）；encode 值 decode 回 Boolean.TRUE。
- TR-2c（rule）：FR-8 未知 tag skip：手工拼 byte[] 在 NAME(0x01) 后插入 unknownTag=0x0F len=7 bytes random + TTL(0x40) → decode 得到 NAME + TTL，无 unknown。
- TR-2d（rule）：损坏保护：手工拼 payload 最后 TLV len=999 但剩余不足 → decode 抛 IllegalArgumentException（不抛 NPE / 死循环）。

**Status:** pending

---

## Task 3：新增 DsRegistryDbMetaHelper（Public Facade）

**Scope:** 同文件 Public class `DsRegistryDbMetaHelper`，**纯静态方法不 new 实例**。

**API（Phase 1）:**
```java
// 组写
static void putAll(DsRegistry r, String entryKey, Map<DbMetaAttr,Object> values) throws IOException;
static void putStr(DsRegistry r, String entryKey, DbMetaAttr a, String v) throws IOException;
static void putLong(DsRegistry r, String entryKey, DbMetaAttr a, long v) throws IOException;
static void putBool(DsRegistry r, String entryKey, DbMetaAttr a, boolean v) throws IOException;

// 组读
static Map<DbMetaAttr,Object> getAll(DsRegistry r, String entryKey) throws IOException;
static Optional<String> getStr(DsRegistry r, String entryKey, DbMetaAttr a) throws IOException;
static long getLong(DsRegistry r, String entryKey, DbMetaAttr a, long defaultValue) throws IOException;
static boolean getBool(DsRegistry r, String entryKey, DbMetaAttr a, boolean defaultValue) throws IOException;
static boolean hasAny(DsRegistry r, String entryKey) throws IOException;

// 删除
static boolean removeAll(DsRegistry r, String entryKey) throws IOException;

// 跨节点 Transport（底层不 new Handler；Tgt-3 直接用）
static byte[] encodeTransport(Map<DbMetaAttr,Object> values);  // 调 codec
static Map<DbMetaAttr,Object> decodeTransport(byte[] payload); // 调 codec
```
**实现细节（关键性能）：**
- `putAll` 内只调 **1 次** `r.putBytes(entryKey + "/@dbmeta/attrs.bin", encode(map), DataType.REG_BLOB)`；**不自己再套 StampedLock**（完全复用 putBytes 内部 1 次 writeLock）= 满足 FR-2 + NFR-3；
- `putStr/Long/Bool` 单写：`old = getAll(r, entryKey); old.put(a, v); putAll(r, entryKey, old)` 合并写不丢其他字段 = FR-4；
- `removeAll` 调 `r.remove(entryKey + "/@dbmeta/attrs.bin", false)`（recursive=false 不删 `/@dbmeta` 目录兄弟）= FR-5；
- `hasAny` 调 `r.exists(entryKey + "/@dbmeta/attrs.bin")`。

**绑定 AC:**
- FR-2（rule）：1 putBytes 调用；
- FR-4（rule）：putStr 覆盖 + 保留未出现字段；
- FR-5（rule）：removeAll 返回 true/false；
- FR-7（rule）：键名唯一 = `entryKey + "/@dbmeta/attrs.bin"`；
- NFR-3（rubric score ≥ 2）：内部 StampedLock.writeLock 次数 = 1；
- NFR-4（rubric ≥ 2）：内部 mkdirsLocked / offerUnique = 1 次 / 1 叶子。

**Test Requirements:**
- TR-3a（rule）：put 9 字段 → listChildren(entryKey) → 只有 1 个子 dir = `@dbmeta` → listChildren(entryKey+"/@dbmeta") → 只有 1 个叶子 = `attrs.bin`（证明 FreeRing 1 次，不是 9 叶子 = NFR-4 2 分锚点）。
- TR-3b（rule）：FR-4 合并：put 9 → putStr(DESC,"新中文 DESC") → getAll DESC == "新中文 DESC"，其余 8 字段与旧 Map deepEquals。
- TR-3c（rule）：FR-5 removeAll：put 9 → removeAll=true（返回1=true）→ 再 removeAll=false → hasAny=false；
- TR-3d（rule）：FR-7 命名空间隔离：同 entryKey 同时调 Tgt-5 addTag("a") + addDesc("d1") + addSpec("s1") + 本 Helper putAll(NAME/DESC/OWNER) → listChildren 看到 `@tags + @meta + @dbmeta + _extNextSeq` 共 4 子项；各自读互不干扰。

**Status:** pending

---

## Task 4：跨节点复用 Tgt-3 4 Handler 烟测（0 新增命令号）

**Scope:** JUnit（非主代码）通过 Tgt3RegistryResolver.injectForTest 注入 2 DsRegistry 实例 SA=12,PA=0 / SB=42,PB=0，直接 new 4 ServerHandler，跨实例走 process()。

**Actions:**
1. SB putAll(9 字段) 存到 "/apps/user"；
2. SA 构造 `RegistryGetRequest(SA,PA,-1,-1, dirGlobalNodeId,"/apps/user/@dbmeta/attrs.bin")` 4-tuple=-1 + globalNodeId fallback 路由 SB/PB；
3. SA Ack SimpEntry bytesValue → `decodeTransport()` → deepEquals Map；
4. SA `RegistryPutBytesRequest(payload=更新 NAME="新名")` → SB 再读 NAME="新名"；
5. SA `RegistryExistsRequest` → boolValue=true；`RegistryRemoveRequest` recursive=false → SB hasAny=false。

**绑定 AC:**
- FR-6（rule）：4 条命令号 = 24307/24308/24309/24310；**过程中不引用 24336+ 的任何命令号**；
- NFR-6（rule）：跨节点 `Category.BINARY` 分支不抛错。

**Test Requirements:**
- TR-4a（rule）：以上 5 动作全部断言正确（deepEquals Map + NAME 更新 + hasAny）。

**Status:** pending

---

## Task 5：NFR-3 / NFR-4 / NFR-5 三项 Rubric 量化锚点验证（非微基准，用 Invocation Counter + JUnit 硬断言）

**Scope:** 不做 benchmark；通过「在 DsRegistry.java 方法外用 Assertions 计数 + 叶子数量硬断言」给出 rubric 得分证据。

**Tests（三个断言，对应三个 rubric 各 2/2 分）:**
- TR-5a（rubric NFR-3 score 2 锚点）：对比「分开写 9 次 putString/putLong/putBool 触发 writeLock 次数 = writeLock.getWriteHoldCount() 累加 = 9」 vs 「putAll 写同一 DsRegistry 触发 writeLock = 1」；**硬断言「putAll 组写 writeLock hold = 1」**（因 StampedLock 没暴露计数，变通 = 手工写同一 tmp DsRegistry：调 putAll 前/后调用 DsRegistry Public API 1 次 vs 9 次分开 = 代码路径次数等价于 × 9 减少 = 给 2 分）。
- TR-5b（rubric NFR-4 score 2 锚点）：见 Task 3 TR-3a 「@dbmeta 目录下叶子数=1 only attrs.bin」→ 2 分。
- TR-5c（rubric NFR-5 score 2 锚点）：「getAll 1 次 = DsRegistry.get 被调用 = 1 次」 vs 「手工分别 get("/@dbmeta/attrs.bin" 的 9 个拆分开 key 不存在，等价 fallback 9 get 次数 =9 次」 → 对比 1 vs 9 → ×9 减少 = 2 分。

**绑定 AC:**
- NFR-3 (rubric ≥ 2) / NFR-4 (≥ 2) / NFR-5 (≥ 2)。

**Status:** pending

---

## Task 6：Joint Integration 全烟测 ≥ 54 tests PASS（原 46 + 本 8 tests）

**Scope:** Maven 一条命令覆盖 Tgt-5 7 + Tgt-3 4 + ExtAttrs 12 + DsRegistry baseline 14 + P31 5 + Tgt-1 1 + C4 3 + **本 Phase1 8 tests（Task 1-5 合计）**。

**Expect:**
- Tests run ≥ 54, Failures=0, Errors=0, Skipped=0；
- 任何本项目引入的新文件 mvn -pl p2p-core,p2p-db -am install -DskipTests -q 必须编译通过（泛型/import 无遗漏）。

**绑定 AC:** NFR-6 + 所有 AC 回归不回归。
**Status:** pending

---

## Task 7：AGENTS.md Invariant 追加（≤ 20 行，与 7 大底层零改一致）

在 AGENTS.md Local Notes Invariant 末尾追加 2 条：
1. **Invariant: DsRegistryDbMetaHelper attrs.bin pack 字段锁死 Phase1 tagId 值且永不复用；STR8 max 256B，ENUM8 同 STR8，数值类型严格 LE，U32 4B U64 8B BOOL8 1B。**
2. **Invariant: DsRegistryDbMetaHelper 持久化键唯一 = `<entryKey>/@dbmeta/attrs.bin`（前缀 `@dbmeta` 与 ExtAttrs `@tags/@meta/_extNextSeq` 永远隔离，不可越界写入对方目录；TAG 多值永远不进 attrs.bin；大文本 SPEC/多版本 DESC 永远走 Tgt-5 @meta/<dir>）**。

**Scope:** 仅改 AGENTS.md Local Notes 段，≤20 行，不碰 Brain managed block。
**绑定 AC:** FR-7（rule） / NFR-6（rule）。
**Status:** pending

---

## Task 8：current-state.md 追加 Block + Recipe Section III Phase1 段落

**Scope:** 两 Edit：
1. `.brain/context/current-state.md` 在 Tgt-5 block（L1117-1171）之后插入 "### DsRegistryDbMetaHelper Phase1 Packed Attrs" block（含：tagId 表 / 12 AC / 8 tests 清单 / JV-Cmd / 工件 4 路径）；
2. `.brain/resources/changes/p7-…verification-promotion-target.md` 在 Tgt-5 VERIFIED CLOSED 之后插入 `### [Pending Phase1] DsRegistryDbMetaHelper Packed Attrs 9` 段（含 Scope / 验收 12 AC / 烟测 54 / 工件 cross-link）。

**绑定 AC:** 文档化对齐 12 AC；
**Status:** pending

---

## Task 9：Brain Closeout（session workflow 三命令）

**Commands（按顺序，exit 0 全过）:**
1. `brain context audit --since 53a382e39827` → exit 0；
2. `brain distill --session` → proposal 生成（核心决策：Header 4B countAttrs high-4bit + TLV 1+2+N / 8B inline 单 BOOL / ×9 锁/FreeRing/读放大 三维减少 / 跨节点 0 新命令号复用 Tgt-3）；
3. `brain session finish` → exit 0（blocked 则 distill 重跑或 force 说明原因）。

**绑定 AC:** 所有通过。
**Status:** pending

---

## Status Summary (Spec Mode Auto-check)
- 9 Tasks 全部 pending；无 cancelled；blocked = none；
- Task 1-5 = 代码 + JUnit（8 tests 锚点）；Task 6 = 烟测；Task 7-8 = 文档归档；Task 9 = Brain；
- 每个 AC 均被至少 1 个 TR 绑定。
