# DsMftFileSystem 详细设计（草案）

## 1. 背景与目标

当前仓库中已存在基于 KV+目录成员表的文件系统实现（Ds256FileSystem）。本设计拟新增一个偏“类 NTFS/MFT”的本地文件系统实现：DsMftFileSystem，以固定长度 inode 表（MFT）作为文件/目录的主索引，并将可选/高频属性（如 atime）独立拆分为可开关的外部存储，以兼顾性能与可配置性。

### 1.1 目标

- 提供一个以 **MFT（固定 128B inode 记录表）** 为核心的数据组织方式：
  - inodeId 即 fileId（索引下标就是文件 id）
  - inodeId=0 固定为超级块（Ds128SuperInode）
- 最近访问时间（atime）**不写入 MFT**，单独存储，可全局禁用。
- 目录成员表复用现有分级固定块方案：**4K inode / 64K indirect / 64M indirect**。
- 文件数据存储采用：`DsHashMap(fileId -> bucketId)`，bucketId 指向固定桶存储（DsFixedBucketStore）中的内容块。
- 预留继承属性、标签索引等能力，形成可持续演进的持久化布局。

### 1.2 非目标（本期不做）

- 分布式一致性、跨节点复制、事务（跨多个 store 的原子提交）。
- 完整 POSIX 语义（硬链接/符号链接/rename 原子性等）。
- 强一致的 tag 去重与反向索引删除联动（可后续补齐）。

## 2. 总体架构

DsMftFileSystem 由以下持久化组件组成（同一卷/命名空间一套）：

- **MFT 主文件表（固定记录文件）**
  - inodeId -> Ds128Inode（固定 128B）
  - inodeId=0 使用 Ds128SuperInode（同样按 128B 结构写入）
- **目录成员表（DirStore）**
  - 复用 Ds256DirectoryStore：dirId -> entryId 列表
  - 约定：目录 inode 的 fileId 指向一个 4K dirId（也即 Ds256DirectoryStore 的 inode 块 id）
  - 目录解析采用“层层解析”，不提供全局 path->id 映射
- **文件数据块存储（BucketStore + DataMap）**
  - DsFixedBucketStore：存放文件内容 bytes
  - DsHashMap：`fileId -> bucketId`
- **可选属性存储（AtimeStore）**
  - DsHashMap：`fileId -> accessTimeMillis`
  - 通过全局开关控制读路径是否更新
- **继承属性存储（InheritedStore）**
  - DsHashMap：`fileId -> inheritedMgrId`（或 `fileId -> inheritedBlobId`，二期）
- **标签索引（TagIndex）**
  - 双向索引（可选方案见 7.2）

## 3. 路径与命名空间

### 3.1 路径规范

- 统一 Linux 风格，绝对路径以 `/` 开头。
- 禁止目录穿透：任何路径段出现 `.` 或 `..` 必须抛异常。

### 3.2 命名空间

DsMftFileSystem 建议沿用 Ds256FileSystem 的四命名空间（global/group/private/system）组织根目录：

- `<root>/global/`
- `<root>/group/`
- `<root>/private/`
- `<root>/system/`

每个命名空间各自拥有独立的 MFT、map、bucket、dir_blocks、tags 等文件夹与文件。

## 4. 持久化布局（单命名空间）

建议目录布局如下（以 `<nsRoot>` 表示单命名空间根目录）：

- `<nsRoot>/mft/mft.dat`：固定 128B 记录文件（inodeId -> inode record）
- `<nsRoot>/mft/mft.free`：空闲 inodeId 链（用于回收与复用）
- `<nsRoot>/meta/meta.map`：元信息（nextInodeId、版本号、特性位等）
- `<nsRoot>/dir_blocks/`：目录成员表块（复用 Ds256DirectoryStore）
- `<nsRoot>/data/`：文件内容块（DsFixedBucketStore 根目录）
- `<nsRoot>/maps/file_data.map`：`fileId -> bucketId`
- `<nsRoot>/maps/atime.map`：`fileId -> accessTimeMillis`（可选）
- `<nsRoot>/maps/inherited.map`：`fileId -> inheritedMgrId`（可选）
- `<nsRoot>/tags/`：标签索引（可选）

> 说明：上述布局不要求与现存 Ds256FileSystem 完全兼容，但应尽量复用既有 DS 组件（DsFixedBucketStore / DsHashMap / DsSha256KV / Ds256DirectoryStore）。

## 5. MFT（固定 128B inode 记录表）

### 5.1 结构复用

复用现有结构定义：

- 超级块：Ds128SuperInode（inodeId==0）
- 普通 inode：Ds128Inode（inodeId>=1）

### 5.2 记录语义

- inodeId = fileId（稳定句柄）
- inode.type 由 i_mode / i_flags 组合判定（文件/目录）
- 目录 inode 的 fileId 指向 dirId（4K inode bucketId）
- 文件 inode 的数据通过 file_data.map 解析为 bucketId
- 文件名存储规则：
  - 短文件名（UTF-8 字节长度 <= 31）：直接存放在 inode.name[32]（以 0 结尾或按长度截断）
  - 长文件名（UTF-8 字节长度 > 31）：存放在 DsString 中，目录项保存 nameId；DsString 的 value 使用可解析格式：`<fileIdHex16>:<fullName>`（前 16 个十六进制字符为 fileId）


### 5.3 分配与回收

- 分配：
  - 优先从 `mft.free` 取回收的 inodeId
  - 否则从 `meta.map` 的 `nextInodeId` 递增分配
- 回收：
  - 删除文件/目录后，将 inodeId 写入 `mft.free`
  - 同时清理 file_data.map / atime.map / inherited.map / path_map / tag 反向索引（按阶段实现）

## 6. 路径解析与目录树（层层解析）

本设计不提供全局 `path->id` 映射。路径解析与传统文件系统一致：从根目录开始，逐段在当前目录中查找下一段名字对应的子节点。

### 6.1 目录项编码（entryKey）

目录成员表（DirStore）仍只存 `long entryKey`，但其语义由 DsMftFileSystem 定义：

- 若 `entryKey` 的最高位为 0：`entryKey` 直接是 `fileId`
- 若 `entryKey` 的最高位为 1：表示这是一个 `nameId`（DsString id），`nameId = entryKey & 0x7FFF_FFFF_FFFF_FFFFL`
  - DsString.value 格式：`<fileIdHex16>:<fullName>`（可解析出 fileId 与文件名）

这样设计的好处是：

- 短文件名不需要额外存储，直接从 MFT 的 inode.name 读取即可；
- 长文件名无需在 inode 内保存指针，目录项通过 nameId 即可解析出 fileId 与 name；
- 保持 DirStore 元素仍为 long，复用现有 4K/64K/64M 分级块实现。

### 6.2 lookup 算法（按目录扫描）

给定 `parentDirFileId` 与 `nameBytes`，查找子节点：

1. 读取 `parentDirFileId` 的 inode，并得到其 `dirId`（目录成员表 4K inode bucketId）。
2. 遍历 `dirId` 的目录成员表 entryKey：
   - 若 entryKey 是 fileId：读取子 inode，取 inode.name 与 nameBytes 比较；
   - 若 entryKey 是 nameId：读取 DsString.value，解析出 fileId 与 nameBytes 比较；
3. 命中则返回 fileId，否则返回不存在。

> 说明：该方案是 O(n) 目录扫描；后续若需要可在目录内增加可选索引（例如 nameHash -> entryKey 的 DsHashMap），但本期按你要求不引入“全局 path 映射”。

## 7. 可选属性与索引

### 7.1 atime（最近访问时间）独立存储

- 存储：`atime.map`（DsHashMap：fileId -> accessTimeMillis）
- 开关：
  - `ds.fs.atime.enabled=false|true`（默认 false）
  - 当关闭时，任何读操作不更新 atime，避免写放大与锁竞争
- 更新策略（后续可选）：
  - 读路径“采样写”：例如最小间隔 N 秒才写回一次
  - 批量异步写回（需要队列/后台线程，后续迭代）

### 7.2 tags 双向索引

按你确认的“多对多”方案实现（核心结构：DsMemoryRing <-> DsMemoryRing + DsHashMap）：

- tag 字符串池：`tags/strings/`（DsString，返回 tagStringId）
- 关系存储：DsManyToManyStore
  - `tagStringId -> [fileId...]`：用 DsHashMap 记录 tagStringId 对应的 ringId；ring 内存 DsMemoryRing（持久化在 bucket）
  - `fileId -> [tagStringId...]`：用 DsHashMap 记录 fileId 对应的 ringId；ring 内存 DsMemoryRing（持久化在 bucket）

接口语义：

- 一个文件可以绑定多个 tag；
- 一个 tag 也可以绑定多个文件；
- 查 tag 得文件列表、查文件得 tag 列表都为 O(n) 扫描 ring（n 为关联数量）。

### 7.3 继承属性

短期：
- `inherited.map`：DsHashMap(fileId -> inheritedMgrId)；若无继承则不存在该 key

中期：
- `inherited_blob`：将 InheritedMetadata 序列化为 bytes 存入 DsData/DsFixedBucketStore，并由 map 存 blobId

## 8. 读写流程（关键路径）

### 8.1 创建目录 mkdirs(path)

逐段解析 path（层层解析）：

- 从根目录 fileId 开始；
- 对每个 path segment：
  - 在当前目录中 lookup 该名字：
    - 若存在且为目录：进入该目录
    - 若不存在：创建新目录 inode：
      - 分配 fileId（inodeId）
      - 在 DirStore 分配 dirId（4K inode bucketId）
      - 写入 MFT（目录 inode）
      - 将新目录挂接到父目录的 DirStore：
        - 短名：直接追加 fileId
        - 长名：写入 DsString（8B fileId + name bytes），追加 nameId（最高位置 1 的 entryKey）

### 8.2 写文件 saveFile(path, bytes, metadata)

- 确保父目录存在（mkdirs parent），并通过层层解析得到父目录 fileId
- 定位/创建 fileId：
  - 在父目录中 lookup 文件名：
    - 若存在：得到旧 fileId（覆盖更新）
    - 若不存在：分配新 fileId 并挂接到父目录 DirStore（短名/长名规则同上）
- 写数据：
  - bytes 写入 DsFixedBucketStore，得到 bucketId
  - 更新 file_data.map(fileId -> bucketId)
- 写 inode：
  - 更新 inode 的 size/mtime/ctime 等（atime 不在此写）
  - 写回 MFT 记录
- tags：
  - 按选定方案更新 tag 索引

### 8.3 读文件 readFile(path)

- 从根目录开始层层解析 path，得到 fileId
- MFT -> inode（验证类型=文件）
- file_data.map -> bucketId -> 读内容 bytes
- 若 atime.enabled：
  - atime.map(fileId -> nowMillis)（可按策略降频）

## 9. 一致性与崩溃恢复（本地单进程）

本设计默认不提供跨 store 的事务；为降低崩溃后的“孤儿块/半写入”概率，建议约定写入顺序：

- 写文件内容：先写 data bucket
- 再写 file_data.map
- 再写 MFT（使 inode 指向已存在的 bucket）
- 最后写目录挂接与 tag 索引

恢复策略（最小可用）：

- 启动时不做全量扫描修复，仅保证读路径不崩溃：
  - 若 inode 存在但 file_data.map 缺失：视为内容缺失（返回错误）
  - 若 file_data.map 存在但 inode 不存在：视为孤儿映射（后续提供离线修复工具）

二期可补充：
- 按 inode 扫描校验 map 一致性工具
- 垃圾回收：回收孤儿 bucketId

## 10. 配置项

- `ds.fs.atime.enabled`：是否启用 atime（默认 false）
- `ds.fs.namespace.enabled`：命名空间开关（默认启用四命名空间）

## 11. 验证与测试策略

- 单元测试：
  - inode 分配/回收（free list）
  - mkdirs 目录链创建与 list
  - save/read 文件数据一致性
  - atime.enabled=false 不产生写入；enabled=true 更新 map
- 崩溃恢复（最小）：
  - 模拟写到一半（仅写 data 或仅写 map）后重启读取行为
