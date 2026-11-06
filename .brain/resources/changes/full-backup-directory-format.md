---
updated: "2026-08-24T17:45:41Z"
---
# P2+P3 备份目录化格式、增量 WAL / Snapshot / 时间线恢复实现记录

> 记录时间：2026-08-23 ~ 2026-08-24
> 目标读者：p2p-db 核心开发
> 关联模块：`p2p-db`
> 关联 roadmap：§五 P2 能力清单、§六 P3 ③ 备份与恢复
> 状态：已落地实现

---

## 1. 背景与目标

当前 `p2p-db` 的持久化层由若干**单文件**实体组成：

- `DsMemory` 的 `dataFile`（主数据文件，64KB block 定长随机读写）；
- `DsMemory` 的 `dataFile + "_wal.log"`（单实例 crash recovery WAL）；
- `DsFixedBucketStore` 继承 `DsMemory`，因此每个行存/列存空间各对应一对 `dataFile + _wal.log`；
- `DsHashMap` / `DsHashSet` / `DsString` 等集合原语同样派生自 `DsMemory`，各自拥有独立文件；
- `indexes/<space>/` 下的索引文件（`DsEqIndexStore` 的 rowStore 与 indexMap）；
- `_binlog/` 下的跨节点行级事件日志（`DsBinlogStore`）。

这些文件分散在 `dbHome` 下，全量备份若直接拷贝整个目录，存在三个问题：

1. **一致性窗口模糊**：运行中的进程持续写盘，简单 `cp -r` 会得到一个时间点不一致的镜像；
2. **恢复粒度粗**：无法识别哪些文件属于同一逻辑表、哪些属于元数据、哪些是临时/可重建文件（如索引）；
3. **与增量 WAL 衔接困难**：P3 计划引入增量快照，需要一份**格式自描述**的全量基线。

本记录定义并实现了一种**目录化全量备份格式**，为 P2 阶段提供可落地的全量备份基线，并为 P3 的增量 snapshot 预留接口。

---

## 2. 目录化全量备份格式（DsFullBackup Layout）

备份目录命名：

```text
<dbHome>/backups/full/<backupId>/
```

其中 `backupId` 采用 `<ISO8601>_<uuid-short>`，例如 `2026-08-23T120000Z_a7f3e2`。

### 2.1 目录结构

```text
2026-08-23T120000Z_a7f3e2/
├── manifest.yaml          # 自描述清单
├── meta/
│   └── dsdb/              # 与 <dbHome> 根目录结构一一对应的元数据
│       ├── table.meta.yaml
│       ├── columns.meta.yaml
│       └── ...
├── bucket_data/
│   └── dsdb/
│       ├── data_<space>.dat       # DsFixedBucketStore / DsMemory 数据文件
│       ├── data_<space>.dat.free  # free 位图
│       ├── data_<space>_wal.log   # 对应 WAL
│       └── ...
├── index_eq/
│   └── dsdb/
│       └── <space>/
│           ├── ids_<schemaId>.set
│           ├── <col>.idx
│           └── (<col1>,<col2>).idx
└── binlog_fixed/
│   └── _binlog/
│       └── dsbinlog_<dayKey>_fixed.dat
└── binlog_dyn/
│   └── _binlog/
│       └── dsbinlog_<dayKey>_dyn.dat
└── binlog_bytes/
    └── _binlog_bytes/
        └── ...
```

### 2.2 关键设计决策

- **顶层按 role 分子目录**：`bucket_data/`（行存/列存数据）、`index_eq/`（等值/范围/复合索引）、`meta/`（YAML 元数据）、`binlog_fixed/` / `binlog_dyn/` / `binlog_bytes/`（跨节点行级事件日志）。
- **data 与 meta 物理隔离**：把可变大数据与小型元数据文件物理隔离，便于未来对 `bucket_data/` 做压缩、冷存、分片传输。
- **保留相对路径**：`manifest.yaml` 中所有路径均为相对于 `backupId/` 的相对路径，恢复时可映射到任意 `dbHome`。
- **索引文件可重建但默认备份**：索引属于可重建数据，但全量备份时仍拷贝，避免恢复后首次启动的大规模重建耗时。
- **WAL 文件原样保留**：作为 crash recovery 的第一道防线，备份时 WAL 与 dataFile 必须成对出现。
- **binlog 文件独立保留**：`DsBinlogStore` 的 fixed/dyn/bytes 三类文件全部纳入备份，确保跨节点事件日志不丢失。

---

## 3. Manifest 格式（manifest.yaml）

```yaml
backup:
  version: 1
  backupId: 2026-08-23T120000Z_a7f3e2
  createdAt: "2026-08-23T12:00:00Z"
  dbHome: /path/to/dbHome
  type: FULL

entries:
  - role: BUCKET_DATA
    relativePath: bucket_data/dsdb/data_rows_abc123.dat
    originalPath: /path/to/dbHome/dsdb/data_rows_abc123.dat
    size: 1073741824
    checksum:
      algorithm: CRC32C
      value: a1b2c3d4
  - role: BUCKET_WAL
    relativePath: bucket_data/dsdb/data_rows_abc123_wal.log
    originalPath: /path/to/dbHome/dsdb/data_rows_abc123_wal.log
    size: 16777216
    checksum:
      algorithm: CRC32C
      value: e5f6a7b8
  - role: INDEX_EQ
    relativePath: index_eq/dsdb/com.example.User/age.idx
    originalPath: /path/to/dbHome/indexes/dsdb/com.example.User/age.idx
    size: 65536
    checksum:
      algorithm: CRC32C
      value: c3d4e5f6
  - role: BINLOG_FIXED
    relativePath: binlog_fixed/_binlog/dsbinlog_20260823_fixed.dat
    originalPath: /path/to/dbHome/_binlog/dsbinlog_20260823_fixed.dat
    size: 262144
    checksum:
      algorithm: CRC32C
      value: d4e5f6a7
  - role: META
    relativePath: meta/dsdb/table.meta.yaml
    originalPath: /path/to/dbHome/dsdb/table.meta.yaml
    size: 1024
    checksum:
      algorithm: CRC32C
      value: b2c3d4e5

metadata:
  tables: []
```

### 3.1 Role 枚举

实现类 `DsFullBackup.BackupRole` 定义：

| Role | 说明 |
|------|------|
| `MEMORY_DATA` | `DsMemory.dataFile` 主数据文件 |
| `MEMORY_WAL` | `DsMemory` 对应的 `_wal.log` |
| `BUCKET_DATA` | `DsFixedBucketStore` 派生的行存/列存 dataFile 及 `.dat.free` |
| `BUCKET_WAL` | 上述文件的 WAL |
| `INDEX_EQ` | `DsEqIndexStore` / `DsHashSet` / `DsHashMap` 索引文件 |
| `INDEX_MAP` | `DsHashMap` 派生的映射文件 |
| `INDEX_SET` | `DsHashSet` 派生的 set 文件 |
| `STRING_DICT` | `DsString` 字符串字典文件 |
| `BINLOG_FIXED` | `DsBinlogStore` 定长部分 `_fixed.dat` |
| `BINLOG_DYN` | `DsBinlogStore` 变长部分 `_dyn.dat` |
| `BINLOG_BYTES` | `DsBinlogStore` 去重 bytes 目录 `_binlog_bytes/` |
| `META` | YAML / dat 元数据文件 |

---

## 4. 实现类 `DsFullBackup`

入口：

```java
public static File create(File dbHome, File backupRoot) throws IOException
public static File create(File dbHome, File backupRoot, String backupId) throws IOException
public static void restore(File backupDir, File targetDbHome) throws IOException
public static void validate(File backupDir) throws IOException
public static void validate(File backupDir, boolean checksum) throws IOException
```

### 4.1 备份流程

1. 分配备份目录 `<backupRoot>/<backupId>/`；
2. 扫描 `dbHome` 下所有常规文件（跳过 `backups/`、隐藏文件、临时文件）；
3. `classify(rel, fileName)` 按路径/文件名判定 `BackupRole`；
4. 对每个 entry 执行 `linkOrCopy`：优先 `Files.createLink` 硬链接，失败回退 `Files.copy`；
5. 生成 `manifest.yaml`，记录每个文件的 role、relativePath、originalPath、size、CRC32C。

### 4.2 恢复流程

1. 读取并解析 `manifest.yaml`；
2. 校验目标目录为空或仅含 `backups/`；
3. 按 `entries` 顺序拷贝文件到 `targetDbHome`：
   - `stripRolePrefix(relativePath)` 去掉顶层 role 目录前缀；
   - 映射回原始相对路径；
   - 拷贝后校验 size 与 CRC32C；
4. 启动 `DsDatabaseLocal`，由 `DsWAL.replayAll` 自动重放 WAL。

### 4.3 校验流程

`validate(backupDir, checksum)`：

1. 读取 `manifest.yaml`；
2. 检查每个 entry 文件存在；
3. 比对 size；
4. 若 `checksum=true`，计算 CRC32C 并与 manifest 比对。

---

## 5. `DsBinlogStore` 接入 ORM 写路径

`DsDatabaseLocal` 已在以下路径追加 binlog 写入：

| 路径 | 事件类型 | 说明 |
|------|----------|------|
| 非 MVCC `putEntity` | INSERT / UPDATE | 旧行不存在为 INSERT，否则 UPDATE |
| MVCC `putEntity` | INSERT / UPDATE | 由 `prevVersionId` 判断 |
| 批量非 MVCC `putEntities` | INSERT / UPDATE | 逐条判断旧行是否存在 |
| 非 MVCC `removeTable` | DELETE | 删除前读取旧行并写 binlog |
| MVCC `removeTable` | DELETE | 删除前读取旧行并写 binlog |

tableId / colId 映射：

- `tableId`：实体类全限定名 FNV-1a 64 位哈希；
- `colId`：schema 列顺序的零基索引；
- binlog values 通过 `fieldValueToBinlog(...)` 把字段值转为 binlog 可序列化类型。

---

## 6. 一致性保证

- **备份前必须 syncStore**：调用方负责在调用 `DsFullBackup.create` 前对所有 `DsMemory` 实例执行 `syncStore()`，将脏页刷到 `dataFile`，并滚动/截断 WAL。
- **备份期间禁止写操作**：本格式为**离线/暂停写模式**设计；在线热备属于 P3 snapshot 范畴。
- **硬链接优先**：在同一文件系统内使用 `Files.createLink` 实现零拷贝、零额外磁盘空间；跨文件系统时回退到 `Files.copy`。
- **WAL 与 dataFile 成对**：恢复时必须同时还原 `_wal.log` 与对应 `dataFile`，由 `DsWAL.replayAll` 补齐一致性。
- **binlog 完整保留**：`BINLOG_FIXED` / `BINLOG_DYN` / `BINLOG_BYTES` 三类文件均纳入备份，恢复后 `_binlog/` 树保持完整。

---

## 7. 验证结果

新增回归测试：

- [DsFullBackupTest.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/com/q3lives/ds/backup/DsFullBackupTest.java) `testCreateRestoreAndValidate`
  - 写入 3 条 → 更新 1 条 → 删除 1 条 → close db → 创建备份 → validate → 检查 binlog 文件存在 → restore → 打开新 db 验证数据与索引查询 → 检查删除行不再出现在索引查询结果中。

定向回归测试命令（全部 BUILD SUCCESS）：

```powershell
mvn test -pl p2p-db '-Dtest=com.q3lives.ds.backup.DsFullBackupTest' '-Dsurefire.useFile=false'
mvn test -pl p2p-db '-Dtest=ds.DsDatabaseLocalBatchPutTest,ds.DsEqIndexOrmAutoMaintainTest' '-Dsurefire.useFile=false'
mvn test -pl p2p-db '-Dtest=ds.DsBinlogBasicTest' '-Dsurefire.useFile=false'
```

---

## 8. 边界与限制

- **非热备**：当前格式要求备份前 syncStore 并暂停写；热备由 P3 snapshot 覆盖。
- **单节点**：未涉及多副本/分布式一致性快照；分布式 snapshot 属于 P1/P3 分片复制范畴。
- **索引可重建**：备份包含索引文件是为了加速恢复，但索引损坏不会导致数据丢失。
- **WAL 必须成对**：`dataFile` 与 `_wal.log` 必须来自同一 syncStore 时刻，否则 WAL 重放可能产生不一致。
- **非 MVCC 删除语义**：`DsFixedBucketStore.remove` 仅标记 free 位，未清零行数据；验证删除一致性时优先使用索引查询而非直接 `getTable`。

---

## 9. 与 P3 增量快照的衔接

P3 阶段将在此目录化格式基础上引入：

- **增量 WAL 备份**：备份自上次 full/incr 以来的 WAL 片段，而非整个 `_wal.log`；
- **硬链接 snapshot**：利用文件系统硬链接在 `syncStore()` 后瞬间生成只读 snapshot 目录，避免全量拷贝；
- **时间线恢复**：`full backup + 后续 incr WAL` 恢复到任意 checkpoint。

本格式为 P3 提供的基线：

- `manifest.yaml` 的 `type` 字段未来可扩展为 `FULL` / `INCREMENTAL` / `SNAPSHOT`；
- `bucket_data/` / `index_eq/` / `meta/` / `binlog_*/` 的 role 划分使得未来可以仅对变化 block/文件做增量；
- checksum 与 role 元数据为增量校验提供基础。

---

## 10. P3 增量 WAL 备份、Snapshot 与时间线恢复（2026-08-24 落地）

### 10.1 设计目标

在 P2 目录化全量备份格式基础上，实现不依赖 SSTable、仍基于定长 block + 索引变长数据的三种能力：

1. **增量 WAL 备份**：只备份自上次 full/incr 之后产生的 WAL 片段，避免重复拷贝全量数据文件；
2. **硬链接 snapshot**：在 `syncStore()` 之后通过文件系统硬链接瞬间生成只读 snapshot 目录；
3. **时间线恢复**：基于 `full backup + 后续增量 WAL` 恢复到任意 checkpoint 时刻。

### 10.2 关键实现

#### 10.2.1 WAL 归档（DsMemory）

- 新增字段 `walArchiveDirectory`，默认 `null`（不启用）；
- 调用方可通过 `setWalArchiveDirectory(File)` 开启归档，例如：
  ```java
  memory.setWalArchiveDirectory(new File(dbHome, "_wal_archive"));
  ```
- 在 `syncStore()` 截断 WAL **之前**，把当前 `_wal.log` 拷贝到归档目录：
  ```text
  <dbHome>/_wal_archive/<hashedRelativePath>/<dataFileName>_<checkpointTimestamp>.wal
  ```
- 同时生成同名 `.yaml` sidecar，记录：
  - `dataFileRelativePath`：该 WAL 片段归属的 dataFile 相对 dbHome 路径；
  - `dataFileName`：dataFile 文件名；
  - `checkpointTimestamp`：归档时刻毫秒时间戳；
  - `walSize`：片段字节数。
- `resolveDataFileRelativePath()` 将 dataFile 的绝对路径解析为相对 `dbHome` 的路径，并用 SHA-256 摘要做目录名，保证跨平台合法目录。

#### 10.2.2 硬链接 Snapshot（DsFullBackup）

- 新增 `BackupRole.WAL_ARCHIVE`，用于标识归档的 WAL 片段；
- 新增 `TYPE_SNAPSHOT = "SNAPSHOT"`；
- 新增入口：
  ```java
  public static File createSnapshot(File dbHome, File snapshotRoot) throws IOException
  public static File createSnapshot(File dbHome, File snapshotRoot, String backupId) throws IOException
  ```
- `createSnapshot` 与 `create` 共享同一目录化布局，只是 `manifest.yaml` 中的 `type` 为 `SNAPSHOT`；
- 内部仍使用 `linkOrCopy`：优先硬链接，跨文件系统时回退拷贝。

#### 10.2.3 增量备份（DsIncrementalBackup）

- 入口：
  ```java
  public static File create(File dbHome, File backupRoot, File baseBackupDir) throws IOException
  public static File create(File dbHome, File backupRoot, File baseBackupDir, String backupId) throws IOException
  ```
- `manifest.yaml` 中 `type=INCREMENTAL`，并记录 `parentBackupId` 与 `parentBackupDir`；
- 当前策略：以基线 manifest 为参考，只备份自基线创建时间之后新增的 WAL 归档片段（`_wal_archive/` 下 `.wal`）；
- 复用 `DsFullBackup` 包级可见 helper：`scan`、`shouldSkip`、`classify`、`buildManifest`、`writeManifest`、`linkOrCopy` 等。

#### 10.2.4 时间线恢复（DsTimelineRestore）

- 入口：
  ```java
  public static void restoreTimeline(List<File> chain, File targetDbHome) throws IOException
  public static void restoreTimeline(List<File> chain, File targetDbHome, long checkpointTimestamp) throws IOException
  ```
- 恢复流程：
  1. 先恢复 chain 中第一个 full backup（调用 `DsFullBackup` 逻辑）；
  2. 依次恢复后续 incremental backup 的 WAL_ARCHIVE 片段；
  3. 读取 sidecar，按 `dataFileRelativePath` 分组；
  4. 对每组按 `checkpointTimestamp` 排序，把 `checkpointTimestamp <= 目标时刻` 的片段追加到对应 dataFile 的 `_wal.log`；
  5. 目标目录启动 `DsMemory.syncLoad()` 时由 `DsWAL.replayAll` 自动重放。
- 不指定 `checkpointTimestamp` 时默认使用 `Long.MAX_VALUE`，即恢复到最新。

### 10.3 关键修复

- **`DsMemory` 实现 `AutoCloseable`**：添加 `close()` 方法关闭内部 `DsWAL`，避免 Windows 下 `RandomAccessFile` 句柄未释放导致临时目录无法删除；
- **`DsIncrementalBackupTest` 全部 try-with-resources**：所有 `DsMemory` 实例在测试中使用 try-with-resources；
- **`deleteRecursive` 删除失败抛异常**：改为删除失败时抛 `IOException`，便于快速定位文件占用；
- **sidecar 路径计算**：修复增量恢复时 sidecar 路径前缀错误，确保 WAL_ARCHIVE 片段正确进入 `walRefs` 分组。

### 10.4 验证结果

新增/更新回归测试：

- [DsIncrementalBackupTest.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/com/q3lives/ds/backup/DsIncrementalBackupTest.java)
  - `testWalArchiveOnSyncStore`：验证 syncStore 后产生 WAL 归档片段与 sidecar；
  - `testSnapshotCreatesHardLinks`：验证 snapshot 硬链接生成与 manifest 正确性；
  - `testIncrementalBackupCapturesWalArchives`：验证增量备份只捕获新增的 WAL 归档；
  - `testTimelineRestoreToCheckpoint`：验证全量 + 增量 WAL 按 checkpoint 恢复到指定时刻。
- [DsFullBackupTest.java](file:///i:/2026/code/p2p-net-storage/p2p-db/src/test/java/com/q3lives/ds/backup/DsFullBackupTest.java) `testCreateRestoreAndValidate`：P2 回归未破坏。

定向回归测试命令（全部 BUILD SUCCESS）：

```powershell
mvn test -pl p2p-db '-Dtest=com.q3lives.ds.backup.DsIncrementalBackupTest' '-Dsurefire.useFile=false'
mvn test -pl p2p-db '-Dtest=com.q3lives.ds.backup.DsFullBackupTest' '-Dsurefire.useFile=false'
```

### 10.5 边界与限制

- **WAL 归档默认关闭**：需要调用方显式 `setWalArchiveDirectory`，否则 `syncStore()` 仍直接截断 WAL；
- **离线 snapshot**：当前 snapshot 仍要求调用方在 `syncStore()` 后、无并发写时创建；严格热备需结合上层写暂停或 MVCC 快照隔离；
- **增量粒度为 WAL 片段**：当前增量备份粒度是单个 WAL 归档片段，不是 block 级 delta；
- **时间线恢复依赖 sidecar**：缺失或损坏 sidecar 会导致 WAL 片段无法正确映射到 dataFile；
- **Windows 句柄释放**：生产环境使用 snapshot / restore 时，必须确保 `DsMemory` 实例被正确 `close()`，否则无法删除/覆盖目录。
