# DsMftFileSystem 实施计划（确认版草案）

> 原则：小步迭代、每一步可验证、尽量复用现有 DS 组件（DsFixedBucketStore / DsHashMap / DsSha256KV / Ds256DirectoryStore）。

## 0. 设计确认点（需要你确认的关键决策）

- A. 不使用全局路径映射（path_map/dir_map），路径解析采用“层层解析 + 目录扫描”，是否确认？
- B. 目录项 entryKey 编码：
  - 最高位 0：entryKey=fileId（短文件名，名字从 MFT inode.name 读取）
  - 最高位 1：entryKey=nameId（DsString id；DsString.value 前 8B 为 fileId，后续为长文件名 bytes）
  是否确认？
- C. 文件数据是否只支持“单块 bucket”（fileId->bucketId）：
  - 若接受：最大文件大小受单块上限约束；
  - 若不接受：需要二期引入 extent 列表（例如 fileId->firstExtentId，再用 DirStore/链表管理分片）。
- D. tags：改为多对多存储（DsManyToManyStore：tagStringId<->fileId），是否确认？

## 1. Phase 1：底座落盘与 MFT 读写（MVP）

- 1.1 新增模块目录与包结构：`com.q3lives.ds.fs.mft`（或沿用 `com.q3lives.ds.fs`）
- 1.2 实现 MFT 固定记录文件
  - 文件：`mft/mft.dat`（128B record）
  - 基础 API：
    - readInode(fileId) -> Ds128Inode
    - writeInode(fileId, inode)
    - readSuper() / writeSuper()
  - free list：`mft/mft.free`
  - `meta/meta.map` 管理 `nextInodeId` 与 feature bits
- 1.3 实现 inode 分配/回收
  - allocateFileId()
  - freeFileId(fileId)
  - 初始化：确保 inodeId=0 存在并写入 Ds128SuperInode
- 1.4 单测
  - 连续分配 fileId 单调递增
  - 回收后优先复用
  - 重启后仍能继续分配与复用

## 2. Phase 2：目录（复用 Ds256DirectoryStore）与路径映射

- 2.1 复用 Ds256DirectoryStore
  - 目录 inode 的 data 指向 dirId（4K bucketId）
  - mkdirs：逐段创建目录 inode 并挂接到父目录（短名/长名规则）
  - listDir：分页列出 entryKey（上层解释 fileId/nameId）
- 2.2 引入层层解析 lookup
  - lookup(parentDirFileId, nameBytes) -> childFileId
  - 统一路径规范与校验（复用 Ds256File.normalizeDsPath 风格约束）
- 2.3 单测
  - mkdirs("/a/b/c") 后目录成员表正确
  - listDir("/a/b") 能看到子目录 c

## 3. Phase 3：文件数据（fileId -> bucketId）与读写 API

- 3.1 引入 file_data.map（DsHashMap）
  - saveFile(path, bytes)
  - readFile(path) / readFileById(fileId)
- 3.2 文件覆盖更新语义
  - 写入顺序：data bucket -> file_data.map -> MFT -> 目录挂接
  - 删除旧 bucketId 的策略：
    - MVP：覆盖时不立即回收旧 bucket（避免复杂一致性）
    - 二期：在确认新写入完成后回收旧 bucket（需要防止并发读写）
- 3.3 atime 可选存储
  - atime.map（DsHashMap）
  - 全局开关 `ds.fs.atime.enabled`
  - MVP：enabled 时每次读都写 nowMillis；二期再做降频/批量
- 3.4 单测
  - save/read 内容一致
  - atime.enabled=false 时读不写 atime.map
  - atime.enabled=true 时读更新 atime.map

## 4. Phase 4：tags（索引）与继承属性（inherited）

- 4.1 tags
  - `tags/strings/`：DsString 管理 tagStringId
  - `tags/rel/`：DsManyToManyStore 存储 tagStringId<->fileId 多对多关系（两侧各自维护 DsMemoryRing）
- 4.2 inherited
  - inherited.map：fileId -> inheritedMgrId
  - mkdirs 时子节点缺省继承父目录的 inheritedMgrId（若开启）
- 4.3 单测
  - 按 tag 查询能返回保存过的文件
  - inherited 能从父目录下发到子文件/子目录（若启用）

## 5. Phase 5：恢复工具与兼容性（可选）

- 5.1 离线校验与修复工具（命令行）
  - 扫描 MFT 与 map 之间的一致性
  - 找出孤儿 bucketId / 孤儿 map 记录
- 5.2 性能策略
  - atime 写回降频
  - 批量写入与 sync 策略（按秒/按写次数/强制 100ms）

## 6. 验证命令（每阶段都执行）

- 单测：
  - `mvn -pl p2p-db test`
- 若阶段引入新测试集（建议新增到 `p2p-db/src/test/java/ds/`）：
  - 最少覆盖：分配/回收、mkdirs/listDir、save/read、atime 开关
