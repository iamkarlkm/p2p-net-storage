# DsDatabase 分布式存储设计与实现计划（ORM 定义布局 · DsHashMap 索引 + 定长 valueStore + DsFixedBucketStore 变长数据版）

> 状态：已实现，2026-08-31 全量回归通过  
> 关联模块：`p2p-db`、`p2p-core`、`p2p-sync`  
> 生成时间：2026-08-30  
> 更新时间：2026-08-31  
> 核心原则：**54 位整数 id 即存储位置（兼容前端 JS 整数显示与回传）；是否分布、是否分区、是否多列组合，一律由 ORM 模型定义，默认单列存储；聚合层 DsHashMap 分配 rowId，rowId 的高 22 位 = serverId、低 8 位 = partitionId、中间 24 位 = 本地逻辑地址；存储层每个列/列组有自己的 DsHashMap，key = rowId，value 直接内联 ≤64 位数据，或索引当前 map 的定长 valueStore 存 >64 位已知固定长度数据，或索引 DsFixedBucketStore 存真正变长数据。**

---

## 1. 核心思想

### 1.1 ORM 定义存储布局

是否使用分布式、是否使用本地 256 分区、是否把多个列合并为固定长度组，**全部由 ORM 模型（`@DsTable` / `@DsField` / `@DsFieldGroup` 等注解）定义**，系统默认采用**单列存储**。

- **分布**：`@DsTable(distributed = true)` 表示该表数据跨节点；
- **分区**：`@DsTable(partitioned = true, partitions = 256)` 表示该表在本地使用 256 个分区目录，可进一步绑定多个物理目录实现多硬盘负载均衡；
- **列组**：`@DsFieldGroup({"colA", "colB", "colC"})` 把多个定长列合并为固定长度字节组；
- **默认**：无注解时单列独立存储。

### 1.2 两层 DsHashMap

| 层级 | 职责 | DsHashMap 的 entryId 含义 |
|------|------|--------------------------|
| **聚合层（Aggregation Layer）** | 统一分配全局 `rowId` | `entryId` **就是** `rowId`；高 22 位 = serverId，低 8 位 = partitionId |
| **存储层（Storage Layer）** | 每个列/列组各有一个 DsHashMap，负责 `rowId → value` | `entryId` 是 map 内部索引，**不等于** rowId；key 才是 rowId |

聚合层 DsHashMap 只负责通过 `nextEntryId` 生成全局唯一的 `rowId`；它本身不存业务数据，也可以兼作“存在性”索引（`rowId → 1` 表示该行存在）。

存储层每个列/列组一个 DsHashMap：

- key = `rowId`；
- value = 内联数据 / 定长 valueStore `recordId` / `DsFixedBucketStore.dataId`。

### 1.3 数据路由三层模型

```text
rowId（来自聚合层 DsHashMap entryId）
    │ 高 22 bit → serverId（跨节点路由）
    │ 低  8 bit → partitionId（本地 256 分区 / 多目录）
    │ 中间 24 bit → 本地逻辑地址（由聚合层内部解释）
    │
    ▼
目标节点 / 目标分区
    │
    ├── 单列 ≤64 位 ────────→ DsHashMap(colMap).put(rowId, inlineValue)
    │
    ├── 单列 >64 位定长 ────→ DsHashMap(colMap).put(rowId, recordId)
    │                         DsFixedValueStore(colMap.values).write(recordId, bytes)
    │
    ├── 单列变长 ───────────→ DsHashMap(colMap).put(rowId, dataId)
    │                         DsFixedBucketStore.write(dataId, bytes)
    │
    └── 列组（固定长度）────→ DsHashMap(groupMap).put(rowId, groupRecordId)
                              DsFixedValueStore(groupMap.values).write(groupRecordId, groupBytes)
```

### 1.4 54 位 id 位布局（仅对 rowId）

只有**聚合层 rowId** 需要嵌入位置信息；存储层 DsHashMap 的 entryId 和 valueStore 的 recordId 都是纯本地地址。整体控制在 54 位以内，可直接作为 JSON 数字往返前端。

```text
 53                32 31                 8 7        0
 +-------------------+----------------------+----------+
 |     serverId      |    local address     | partition|
 |     (22 bit)      |       (24 bit)       | (8 bit)  |
 +-------------------+----------------------+----------+
```

- **高 22 位（bits 32-53）**：`serverId`，最多约 419 万节点；
- **低 8 位（bits 0-7）**：`partitionId`，本地 256 个分区；可映射到单个目录，也可在 ORM 指定多个目录时分散到多硬盘；
- **中间 24 位（bits 8-31）**：`local address`，由聚合层内部自增分配，到达目标节点/分区后不再携带 serverId/partitionId 语义。

### 1.5 内联与变长的区分

- **≤ 64 位数据**：直接内联到存储层 DsHashMap 的 value slot；
- **> 64 位且已知固定长度**：存储层 DsHashMap value 存当前 map 的 `valueStore.recordId`；
- **变长数据**（如压缩列、`String`、`byte[]`）：存储层 DsHashMap value 存 `DsFixedBucketStore.dataId`。

> 启用压缩的列一定是变长数据，因此不走内联，也不走定长 valueStore。

### 1.6 列组规则

- 列组由 ORM 显式定义，总长度固定；
- 列组内新增/删除/重排列，**只要不超过组的总尺寸**，不需要调整 groupIndex 或 schemaId；
- 超过列组尺寸的列不允许加入，应报错或作为独立列/新列组存储；
- 列组只包含固定长度列；任何会变长的列（压缩、`String`、数组等）必须单列走 `DsFixedBucketStore`。

---

## 2. 现状基线

### 2.1 已有组件与职责

| 组件 | 当前职责 | 本计划中的角色 |
|------|----------|----------------|
| `DsHashMap` | 通用 `long → long` 映射；slot payload 8B；header 自增 entryId；支持回收；entryStore 固定 16B（key+value） | **聚合层 rowId 分配器** + **存储层列索引** + **≤64 位数据内联容器** |
| `DsFixedBucketStore` | 定长数据块存储；id = `power(8bit) + baseId(56bit)` | **仅用于真正变长数据** |
| `DsObject` | 基于内存映射的持久化基类，支持固定 recordSize | **定长 valueStore 的实现基座** |
| `ColumnarStore` | 列存：`DsHashMap(rowId → valueId)` + `DsFixedBucketStore(valueId → bytes)` | **列存参考实现；将扩展为优先使用定长 valueStore，且每个列 map 独立** |
| `DsDatabaseLocal` | ORM 层：`bucketStore.getNewId()` 分配行 id；`encodeRowId(rowKey, rowLength)` 编码 | **行存入口；rowId 改由聚合层 DsHashMap 分配；列布局由 ORM 定义** |
| `DsFieldGroupStruct` / `DsFieldGroupFreeBits` | 列组结构与组内空间回收 | **列组合并的实现基础** |
| `DsHashMapDfs` | 旧 12 位 `serverId` 路由（本计划已改为 22 位） | **分布式节点位映射参考** |
| `DsBinlogStore` | 行级事件日志；回放短路 `isInReplay()` | **跨节点事件传输格式** |
| `DsMemory` | `isDistributed / isPartitioned` 预留但从未启用 | **分布式/分区开关位** |

### 2.2 关键缺口

1. 缺少**聚合层 rowId 分配器**：需要一个新的 DsHashMap 专门负责按 ORM 定义生成带 serverId/partitionId 的 rowId；
2. `DsHashMap` 需要暴露公共的 entryId 分配/回收接口，用于聚合层 rowId 分配；
3. 缺少**定长 valueStore**（每个 map 一个 `.values` 文件），当前所有列值都走 `DsFixedBucketStore`；
4. `ColumnarStore` 未按 ORM 注解区分单列 / 列组 / 内联 / 定长 / 变长 / 压缩路径；
5. ORM 注解缺少分布、分区、列组、多目录绑定等元数据；
6. 分布式位映射尚未与“聚合层 rowId vs 存储层本地地址”解耦。

---

## 3. 目标架构

### 3.1 聚合层：rowId 分配与存在性索引

```text
DsHashMap(aggregationMap per table): entryId → rowId marker
```

- 每个表一个聚合层 DsHashMap；
- `allocateRowId(serverId, partitionId)`：
  1. 聚合层 map 分配下一个 entryId；
  2. 用 `DsIdLayout.compose(serverId, partitionId, localAddress)` 组装成全局 rowId；
  3. 返回 rowId。
- 删除行时回收 rowId，聚合层 map 可记录 tombstone 或复用 entryId；
- 该 map 本身也是**存在性索引**（`rowId → 1`）。

### 3.2 存储层：单列与列组

#### 3.2.1 单列 ≤64 位

```text
DsHashMap(colMap): key = rowId, value = inline data (≤8 bytes)
```

- 内联数据通过 `DsHashMap` header 的元数据（如 value length 表或 schema 声明）区分“这是内联数据，不是 recordId/dataId”；
- **不使用最高位标记**，避免与有效数据冲突。

#### 3.2.2 单列 >64 位定长

```text
DsHashMap(colMap): key = rowId, value = recordId
DsFixedValueStore(colMap.values): recordId → fixed-length bytes
```

- `colMap.values` 是 `colMap` 的 companion file；
- `recordSize` 由列 schema 确定（如 `long[2]` = 16B，`UUID` = 16B）；
- recordId 是 `DsFixedValueStore` 内部自增 + 回收，**不嵌入 serverId/partitionId**。

#### 3.2.3 单列变长 / 压缩

```text
DsHashMap(colMap): key = rowId, value = dataId
DsFixedBucketStore: dataId → variable-length bytes
```

- 压缩列属于变长数据，走此路径；
- `DsFixedBucketStore` 的 `power + baseId` 只在本地解释。

#### 3.2.4 列组

```text
DsHashMap(groupMap): key = rowId, value = groupRecordId
DsFixedValueStore(groupMap.values): recordId → [colA][colB][colC] (fixed groupSize bytes)
```

- 列组总长度 `groupSize` 由 ORM 定义，创建时确定；
- 列组内各列偏移固定，按偏移解析；
- 列组只包含固定长度列，禁止变长列加入。

### 3.3 存储位置映射流程

```text
客户端 putEntity(user, clazz)
  ↓
读取 clazz 的 ORM 元数据：distributed? partitioned? column groups?
  ↓
DsHashMapRowIdAllocator（聚合层）分配逻辑 rowId
  ↓
DsIdLayout 把 serverId/partitionId 嵌入 rowId
  ↓
按 serverId 路由到目标节点
  ↓
目标节点按 partitionId 选本地分区目录
  ↓
对每个列/列组：
    如果列属于固定长度列组：
        - groupMap.values 分配 groupRecordId，写入列组字节块
        - groupMap.put(rowId, groupRecordId)
    如果单列且 ≤64 位：
        - colMap.put(rowId, inlineValue)
    如果单列且 >64 位定长：
        - colMap.values 分配 recordId，写入数据
        - colMap.put(rowId, recordId)
    如果单列且变长/压缩：
        - DsFixedBucketStore 分配 dataId，写入数据
        - colMap.put(rowId, dataId)
  ↓
追加本地 binlog，再按 serverId fan-out 到副本节点
```

---

## 4. 核心抽象

| 抽象 | 职责 | 候选位置 |
|------|------|----------|
| `DsIdLayout` | 54 位 id 位运算：serverId、partitionId、localAddress | `p2p-db/.../cluster/DsIdLayout.java` |
| `DsHashMapIdAllocator` | 聚合层 rowId 分配器：基于 DsHashMap `nextEntryId` + free-ring | `p2p-db/.../collections/DsHashMapIdAllocator.java` |
| `DsHashMapInline` | 内联数据编码/解码；**通过 header 元数据或 schema 区分内联** | `p2p-db/.../collections/DsHashMapInline.java` |
| `DsFixedValueStore` | 每个 map 一个 `.values` 文件：`recordId → recordSize 字节` | `p2p-db/.../collections/DsFixedValueStore.java` |
| `DsValueResolver` | 根据 ORM schema 决定 value 走 inline / valueStore / bucket | `p2p-db/.../database/DsValueResolver.java` |
| `DsColumnGroupStore` | ORM 定义的列组：`rowId → recordId` + 组内偏移解析 | `p2p-db/.../database/columnar/DsColumnGroupStore.java` |
| `DsTableLayout` | 解析 `@DsTable` 分布/分区/多目录注解 | `p2p-db/.../database/DsTableLayout.java` |
| `DsNodeRouter` | 按 rowId 高 22 位路由到目标节点 | `p2p-db/.../cluster/DsNodeRouter.java` |
| `DsPartitionRouter` | 按 rowId 低 8 位映射到本地分区目录 | `p2p-db/.../cluster/DsPartitionRouter.java` |
| `DsPartitionManager` | 每节点管理 256 个本地 DsMemory / 目录实例 | `p2p-db/.../cluster/DsPartitionManager.java` |
| `DsBinlogFanOut` | 主节点 binlog 帧按 serverId fan-out 到副本 | `p2p-db/.../cluster/DsBinlogFanOut.java` |
| `DsDatabaseClusterClient` | 生成分布式 rowId，按位路由 | `p2p-db/.../database/DsDatabaseClusterClient.java` |

### 4.1 ORM 注解扩展草案

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface DsTable {
    boolean distributed() default false;
    boolean partitioned() default false;
    int partitions() default 256;          // 本地分区数，默认 256
    String[] directories() default {};     // 多目录绑定，空则全部落 nodeHome
    int replicas() default 1;              // 副本数，仅 distributed 时生效
}

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface DsFieldGroup {
    String groupName();                    // 列组名
    int groupIndex() default 0;          // 组内顺序
}
```

---

## 5. 实现阶段

### Phase 0：聚合层 rowId 分配器

- [x] 在 `DsHashMap` 中新增公共接口：
  - `long allocateEntryId()`：分配新的 entryId；
  - `void recycleEntryId(long entryId)`：回收 entryId；
- [x] 新增 `DsHashMapIdAllocator`：
  - 每个表一个聚合层 DsHashMap；
  - `allocateRowId(serverId, partitionId)` → 调用 map.allocateEntryId() 得到 entryId，再用 `DsIdLayout.compose(...)` 生成全局 rowId；
- [x] 单测验证：rowId 高 22 位和低 8 位正确、回收复用不冲突。

**验证**：

```bash
mvn test -pl p2p-db -Dtest=DsHashMapIdAllocatorTest,DsIdLayoutTest
```

### Phase 1：内联与 header 元数据

- [x] 确定内联区分机制：优先通过 schema 已知长度判断，可选在 DsHashMap header 增加 bitset 记录哪些 value 是内联；
- [x] 新增 `DsHashMapInline`：
  - `encode(byte[] data)`：将 ≤8 字节数据写入 long；
  - `decode(long value)`：还原为 byte[]；
  - **不占用最高位作为标记**；
- [x] 单测验证：所有 0~8 字节边界编码可逆。

**验证**：

```bash
mvn test -pl p2p-db -Dtest=DsHashMapInlineTest
```

### Phase 2：定长 valueStore（每个 map 一个 .values 文件）

- [x] 新增 `DsFixedValueStore`：
  - 构造时绑定到 `<mapFile>.values`；
  - 创建时指定 `recordSize`；
  - `allocateRecord()` / `write(recordId, byte[])` / `read(recordId)` / `recycle(recordId)`；
- [x] `DsHashMap` 可选持有 companion `DsFixedValueStore`；
- [x] 单测验证：record 分配/回收、跨 recordSize 创建、读写边界。

**验证**：

```bash
mvn test -pl p2p-db -Dtest=DsFixedValueStoreTest
```

### Phase 3：ORM 驱动的单列/列组路由

- [x] 扩展 ORM 注解：`@DsTable(distributed/partitioned/partitions/directories/replicas)`、`@DsFieldGroup`；
- [x] 新增 `DsTableLayout`：解析注解并生成每表的布局描述；
- [x] 新增 `DsValueResolver`：根据字段 schema 选择 inline / valueStore recordId / bucket dataId；
- [x] 新增 `DsColumnGroupStore`：
  - 按 ORM 列组定义计算 groupSize；
  - `put(rowId, groupBytes)` → valueStore 分配/更新 groupRecordId → `groupMap.put(rowId, recordId)`；
  - `getColumn(rowId, offset, length)` 按偏移解析；
- [x] 扩展 `ColumnarStore` / `DsDatabaseLocal`：按 ORM 布局创建列 map 和列组 map；
- [x] 单节点验证：同一张表内同时存在内联列、定长 valueStore 列、变长 bucket 列、列组，读写结果一致。

**验证**：

```bash
mvn test -pl p2p-db -Dtest=DsColumnGroupStoreTest,ColumnarStoreLayoutTest,DsValueResolverTest
```

### Phase 4：本地 256 分区与多目录

- [x] `DsPartitionRouter`：按 rowId 低 8 位选择 partitionId；
- [x] `DsPartitionManager`：每节点创建 256 个分区实例；
  - 若 ORM 未指定多目录：`partitions/<nnn>/` 落在 nodeHome；
  - 若 ORM 指定多目录：256 个分区目录轮询分散到指定目录；
- [x] 所有列 map、列组 map、valueStore、bucketStore 按 partitionId 落在对应实例；
- [x] 单节点验证：256 分区写入/读取、多目录绑定均衡。

**验证**：

```bash
mvn test -pl p2p-db -Dtest=DsPartitionRouterTest,DsDistributedSingleNodePartitionTest
```

### Phase 5：跨节点路由（distributed 落地）

- [x] 新增 `DsNodeRouter`：按 rowId 高 22 位路由；
- [x] `DsDatabaseClusterClient` 生成分布式 rowId；
- [x] 复用/扩展 `DsServerDirectory` 维护 `serverId → endpoint`；
- [x] 在 `P2PCommand` 新增 `DB_NODE_PUT/GET/REMOVE/QUERY_IDS` 命令区间（24100+，含对应 `R_OK` 响应）。

**验证**：

```bash
mvn test -pl p2p-db -Dtest=DsDistributedTwoNodeRouterTest
```

### Phase 6：副本 fan-out（按 server，不按 partition）

- [x] `DsServerDirectory` 为每个 `serverId` 维护副本列表（R 来自 `@DsTable.replicas()` / `Endpoint`）；
- [x] `DsDatabaseClusterClient` 在主节点写成功后，按 serverId 的副本列表发送 `DB_NODE_REPLICATE_FRAME`；
- [x] 副本节点 `DbNodeReplicateFrameServerHandler` 应用事件并写入本地 `ColumnarStore`；
- [ ] `lastAppliedSequenceId` 与副本 lag 监控留待后续补齐（当前监控固定返回 0）。

**验证**：

```bash
mvn test -pl p2p-db -Dtest=DsDistributedTwoNodeRouterTest
```

### Phase 7：分布式查询与可观测性

- [x] `DsDatabaseClusterClient.queryIds(String, DbQuery, offset, limit)` 遍历所有 `serverId × partitionId`，聚合后排序分页；
- [x] 单列点查 `putCol/getCol/removeCol` 按 rowId 位路由到本地或远程节点；
- [x] p2p-sync monitor 增加 `/sync/api/db-cluster`：展示 serverId→host、partition 占用、副本列表（副本 lag 暂为 0）；
- [x] 新增 `P2PCommand.DB_NODE_FORCE_MOVE_PARTITION` 管理命令及 `DbNodeForceMovePartitionServerHandler`。

**验证**：

```bash
mvn test -pl p2p-db -Dtest=DsDistributedTwoNodeRouterTest
mvn test -pl p2p-db -Dtest=DbNodeForceMovePartitionServerHandlerTest
mvn test -pl p2p-sync -Dtest=P2PSyncMonitorServerTest
```

---

## 6. 关键接口草案

### 6.1 `DsHashMapIdAllocator`（聚合层 rowId 分配器）

```java
public class DsHashMapIdAllocator {
    private final DsHashMap aggregationMap;

    /**
     * 分配全局 rowId。
     * 复用 aggregationMap 的 entryId 作为 localAddress，再嵌入 serverId/partitionId。
     */
    public long allocateRowId(int serverId, int partitionId) throws IOException {
        long localAddress = aggregationMap.allocateEntryId();
        return DsIdLayout.compose(serverId, partitionId, localAddress);
    }

    public void recycleRowId(long rowId) throws IOException {
        long localAddress = DsIdLayout.localAddress(rowId);
        aggregationMap.recycleEntryId(localAddress);
    }
}
```

### 6.2 `DsIdLayout`

```java
public final class DsIdLayout {
    public static final int SERVER_ID_BITS = 22;
    public static final int PARTITION_BITS = 8;
    public static final int TOTAL_ROW_ID_BITS = 54;
    public static final int LOCAL_ADDRESS_BITS = TOTAL_ROW_ID_BITS - SERVER_ID_BITS - PARTITION_BITS;

    public static final long SERVER_ID_MASK = (1L << SERVER_ID_BITS) - 1;
    public static final long PARTITION_MASK = (1L << PARTITION_BITS) - 1;
    public static final long LOCAL_ADDRESS_MASK = (1L << LOCAL_ADDRESS_BITS) - 1;

    public static int serverId(long id) {
        return (int) ((id >>> (TOTAL_ROW_ID_BITS - SERVER_ID_BITS)) & SERVER_ID_MASK);
    }

    public static int partitionId(long id) {
        return (int) (id & PARTITION_MASK);
    }

    public static long localAddress(long id) {
        return (id >>> PARTITION_BITS) & LOCAL_ADDRESS_MASK;
    }

    public static long compose(int serverId, int partitionId, long localAddress) {
        return ((long) serverId << (TOTAL_ROW_ID_BITS - SERVER_ID_BITS))
             | ((localAddress & LOCAL_ADDRESS_MASK) << PARTITION_BITS)
             | (partitionId & PARTITION_MASK);
    }
}
```

### 6.3 `DsHashMapInline`

```java
public final class DsHashMapInline {
    /**
     * 编码 ≤8 字节数据到 long。
     * 注意：不使用最高位作为内联标记；由内联通过 schema 或 header 元数据区分。
     */
    public static long encode(byte[] data) {
        if (data == null || data.length == 0) return 0L;
        long v = 0L;
        for (int i = 0; i < data.length; i++) {
            v |= ((long) (data[i] & 0xFF)) << (i * 8);
        }
        return v;
    }

    public static byte[] decode(long value, int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) (value >>> (i * 8));
        }
        return data;
    }
}
```

### 6.4 `DsFixedValueStore`

```java
public class DsFixedValueStore {
    public DsFixedValueStore(File valuesFile, int recordSize) throws IOException;

    public long allocateRecord() throws IOException;
    public byte[] read(long recordId) throws IOException;
    public void write(long recordId, byte[] data) throws IOException;
    public void recycle(long recordId) throws IOException;
}
```

### 6.5 `DsColumnGroupStore`

```java
public class DsColumnGroupStore {
    private final DsHashMap index;            // key = rowId, value = groupRecordId
    private final DsFixedValueStore valueStore; // recordId -> fixed group bytes
    private final GroupSchema schema;

    public long put(long rowId, byte[] groupBytes) throws IOException;
    public byte[] get(long rowId) throws IOException;
    public byte[] getColumn(long rowId, int offset, int length) throws IOException;
}
```

### 6.6 `DsTableLayout`

```java
public class DsTableLayout {
    public boolean isDistributed();
    public boolean isPartitioned();
    public int getPartitionCount();            // 默认 256
    public List<File> getPartitionDirectories(); // 多目录绑定
    public int getReplicaCount();
    public List<ColumnGroupDef> getColumnGroups();
    public StorageType getStorageType(Field field); // INLINE / FIXED_VALUE_STORE / BUCKET
}
```

### 6.7 `DsDatabaseClusterClient`

```java
public class DsDatabaseClusterClient implements AutoCloseable {
    public long nextRowId(Class<? extends DsTableAdapter> clazz, int serverId, int partitionId);

    public <T extends DsTableAdapter> long putEntity(T entity, boolean withRelations);
    public <T extends DsTableAdapter> T getTable(Class<T> clazz, long id);
    public <T extends DsTableAdapter> boolean removeTable(Class<T> clazz, long id);
    public <T extends DsTableAdapter> List<Long> queryIds(Class<T> clazz, QueryWrapper<T> wrapper, int start, int end);
}
```

### 6.8 新增 `P2PCommand` 区间

| 命令 | 序值 | 说明 |
|------|------|------|
| `DB_NODE_GET_TOPOLOGY` | 24100 | 获取节点拓扑 |
| `DB_NODE_PUT` | 24101 | 按全局 id 写入 |
| `R_OK_DB_NODE_PUT` | 24102 | 写入成功响应 |
| `DB_NODE_GET` | 24103 | 按全局 id 读取 |
| `R_OK_DB_NODE_GET` | 24104 | 读取成功响应 |
| `DB_NODE_REMOVE` | 24105 | 按全局 id 删除 |
| `R_OK_DB_NODE_REMOVE` | 24106 | 删除成功响应 |
| `DB_NODE_QUERY_IDS` | 24107 | 分区级 queryIds |
| `R_OK_DB_NODE_QUERY_IDS` | 24108 | queryIds 成功响应 |
| `DB_NODE_REPLICATE_FRAME` | 24109 | 副本复制帧 |
| `DB_NODE_REPLICATE_ACK` | 24110 | 副本复制 ACK |
| `DB_NODE_FORCE_MOVE_PARTITION` | 24111 | 管理命令：强制迁移分区 |
| `R_OK_DB_NODE_FORCE_MOVE_PARTITION` | 24112 | 分区迁移命令响应 |

---

## 7. 验证策略

### 7.1 测试金字塔

| 层级 | 测试 | 目标 |
|------|------|------|
| 单元 | `DsHashMapIdAllocatorTest` | 聚合层 rowId 分配、回收、位布局正确 |
| 单元 | `DsIdLayoutTest` | serverId/partitionId/localAddress 位运算 |
| 单元 | `DsHashMapInlineTest` | 0~8 字节编码/解码可逆，不使用最高位标记 |
| 单元 | `DsFixedValueStoreTest` | 定长 record 分配/回收/读写 |
| 单元 | `DsValueResolverTest` | 按 ORM schema 选择 inline/valueStore/bucket |
| 单元 | `DsColumnGroupStoreTest` | 列组合并读写、组内偏移解析、越界拒绝 |
| 单元 | `DsPartitionRouterTest` | 256 分区映射、多目录轮询 |
| 集成 | `ColumnarStoreLayoutTest` | 同表混合内联/定长/变长/列组路径 |
| 集成 | `DsDistributedSingleNodePartitionTest` | 单节点 256 分区写入/读取、多目录 |
| 集成 | `DsDistributedTwoNodeRouterTest` | 双节点按高 22 位路由 |
| 集成 | `DsDistributedReplicaTest` | R=2 副本最终一致 |
| E2E | `DsDistributedOrmE2ETest` | ORM put/get/query 端到端 |
| 回归 | 现有 `p2p-db` 全量测试 | 单节点语义不破坏 |

### 7.2 关键验证命令

```bash
# 聚合层 rowId 与 id 位布局
mvn test -pl p2p-db -Dtest=DsHashMapIdAllocatorTest,DsIdLayoutTest

# 内联编码
mvn test -pl p2p-db -Dtest=DsHashMapInlineTest

# 定长 valueStore
mvn test -pl p2p-db -Dtest=DsFixedValueStoreTest

# 列组合并、ORM 路由
mvn test -pl p2p-db -Dtest=DsColumnGroupStoreTest,ColumnarStoreLayoutTest,DsValueResolverTest

# 本地分区
mvn test -pl p2p-db -Dtest=DsPartitionRouterTest,DsDistributedSingleNodePartitionTest

# 跨节点路由与副本
mvn test -pl p2p-db -Dtest=DsDistributedTwoNodeRouterTest,DsDistributedReplicaTest

# 分布式查询
mvn test -pl p2p-db -Dtest=DsDistributedQueryTest

# 全量回归
mvn test -pl p2p-db
```

---

## 8. 已决策问题

1. **内联数据标记位**：**通过 schema 或 DsHashMap header 的 value 长度元数据区分**，不占用 64 位 value slot 的最高位。
2. **rowId 与 entryId 的关系**：
   - 聚合层 DsHashMap 的 `entryId` **就是** `rowId`；
   - 存储层 DsHashMap 的 `entryId` 是内部索引，**不等于** rowId，key 才是 rowId。
3. **valueStore 文件组织**：**每个 map 一个 `.values` 文件**。
4. **valueStore recordId 与全局 id 的映射**：recordId 是**纯本地地址**，不嵌入 serverId/partitionId；只有聚合层 rowId 携带位置信息。
5. **列组 schema 变更**：列组内新增/删除/重排列，只要不超过组的总尺寸，不需要调整 groupIndex 或 schemaId；超过尺寸的列不允许加入，应报错或作为独立列/新列组存储。
6. **压缩与内联的互斥**：启用压缩的列一定是变长数据，**不走内联，也不走定长 valueStore**，直接走 `DsFixedBucketStore`。
7. **低 8 位语义**：表示本地 256 个分区；如果 ORM 注解指定多个目录，则 256 个分区文件分散到这些目录实现多硬盘负载均衡。
8. **副本与分区关系**：副本按 **serverId** 对应，不按 partition；每个 server 有 R 个副本节点。

---

## 9. 风险与应对

| # | 风险 | 应对 |
|---|------|------|
| 1 | `power` 与 `serverId` 位冲突 | 只有 rowId 嵌入位置位；`DsFixedBucketStore.dataId` 到节点后转换成本地 id，不改现有编码 |
| 2 | 聚合层 DsHashMap entryId 溢出 | 复用现有 `nextHashMap` 层级扩展机制；24 位 localAddress（每分区 1670 万）理论容量足够 |
| 3 | 每个 map 一个 `.values` 文件，文件数量爆炸 | 同 schema 的列 map 可共享目录；系统按表+列名命名，避免冲突 |
| 4 | 列组合并后单条更新需重写整个组 | 仅适用于同时频繁访问的固定长度列；大字段/会变长字段仍单列存储 |
| 5 | 256 分区 × 多目录 × 多列 map 带来大量文件句柄 | 每个分区实例可配更小缓存；DsMemory 支持 close 释放句柄 |
| 6 | 跨节点 binlog fan-out 放大写入延迟 | 异步 fan-out；提供一致性级别参数 |
| 7 | 全局 rowId 生成冲突 | 各节点 partition 内独立自增；schemaId 参与区分不同表 |
| 8 | 与 `DsHashMapDfs` 的高 12 位语义不一致 | 本分布式存储独立使用 `SERVER_ID_BITS=22`，rowId 整体控制在 54 位以内，以兼容前端 JS 整数显示与回传；`DsHashMapDfs` 的 12 位 serverId 仅作旧逻辑参考 |

---

## 10. 交付里程碑

| 里程碑 | 交付物 | 可演示效果 |
|--------|--------|------------|
| M1 | `DsHashMapIdAllocator` + `DsIdLayout` | 聚合层分配带 serverId/partitionId 的 rowId |
| M2 | `DsHashMapInline`（无最高位标记） | ≤8 字节数据直接内联，schema/元数据区分内联 |
| M3 | `DsFixedValueStore`（每 map 一个 .values） | 已知固定长度数据走索引式定长 store |
| M4 | `DsTableLayout` + `DsValueResolver` + `DsColumnGroupStore` | ORM 定义列组、单列按长度自动选 inline/valueStore/bucket |
| M5 | 本地 256 分区 + 多目录绑定 | 同一节点内按 id 低 8 位落到不同目录 |
| M6 | 双节点按高 22 位路由 | 客户端 put 自动发到正确节点 |
| M7 | R=2 异步副本（按 server） | 主节点写入后副本数据一致 |
| M8 | 分布式查询聚合 + 监控 | 跨分区/跨节点 queryIds 有序；monitor 展示拓扑与 lag |

---

## 11. 相关文档与入口

- [DsDb 设计现状与未来方向](file:///i:/2026/code/p2p-net-storage/docs/dsdb-design-status-and-roadmap.md) — 现状基线与 P1 分布式规划
- [AGENTS.md](file:///i:/2026/code/p2p-net-storage/AGENTS.md) — 项目约定与 invariant
- 关键代码：
  - [DsHashMap.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/collections/DsHashMap.java) — 通用 long→long 索引、entryId 分配、64 位 slot payload
  - [DsObject.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/core/DsObject.java) — 定长 valueStore 的底层基座
  - [DsFixedBucketStore.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/bucket/DsFixedBucketStore.java) — 变长数据块存储
  - [ColumnarStore.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/columnar/ColumnarStore.java) — 列存参考实现
  - [DsDatabaseLocal.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/DsDatabaseLocal.java) — ORM 行存入口
  - [DsFieldGroupStruct.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/database/DsFieldGroupStruct.java) — 列组结构
  - [DsHashMapDfs.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/main/java/com/q3lives/ds/collections/DsHashMapDfs.java) — 旧 12 位 serverId 参考（本分布式存储已改用 22 位，整体 54 位）
  - [P2PCommand.java](file:///i:/2026/code/p2p-net-storage/p2p-core/src/main/java/javax/net/p2p/api/P2PCommand.java) — 命令区间
