# DsMftFileSystem 验收清单（草案）

## 功能

- MFT 固定记录文件可读写（128B/record），inodeId=0 为超级块。
- fileId 分配/回收可复用（free list 生效），重启后行为一致。
- 目录：
  - mkdirs 支持多级创建；
  - 目录成员表复用 4K/64K/64M 分级块；
  - listDir 支持分页读取并跳过空洞；
  - 目录 inode 的 fileId 指向 4K dirId；
  - 不使用全局 path_map，路径解析采用“层层解析 + 目录扫描”。
- 文件：
  - `fileId -> bucketId` 映射生效；
  - save/read 内容一致；
  - 覆盖写语义明确（是否回收旧 bucketId 有确定策略）。
- atime：
  - atime 独立存储，不进入 MFT；
  - `ds.fs.atime.enabled=false` 时读不产生写；
  - `ds.fs.atime.enabled=true` 时读会更新 atime.map（或按策略更新）。
- tags：
  - 保存文件可写入 tag；
  - tags 采用 DsManyToManyStore（tagStringId<->fileId 多对多语义）。
- inherited：
  - 可配置是否启用继承；
  - 启用时子节点可继承父目录的 inheritedMgrId。

## 一致性与恢复

- 写入顺序遵守约定（data -> map -> MFT -> dir/tag）。
- 崩溃模拟：
  - 仅写 data 未写 map/MFT 时，不应导致读路径崩溃；
  - map 与 MFT 不一致时，行为有明确错误返回（不 silent corruption）。
- 提供最小离线检查思路（即使二期才实现工具，也要在文档里写清楚）。

## 性能与可配置

- atime 默认关闭，开启后有明确性能影响说明。
- 目录与文件读写不引入不必要的对象拷贝，避免全量扫描。
- Sync/flush 策略可控（至少支持手动 sync；可选按秒/按写次数）。

## 测试

- 单元测试覆盖：
  - MFT 分配/回收/重启恢复
  - mkdirs/listDir
  - save/read/覆盖写
  - atime 开关
  - tags 查询
- `mvn -pl p2p-db test` 通过。
