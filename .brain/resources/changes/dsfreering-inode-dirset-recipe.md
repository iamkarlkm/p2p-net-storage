# DsFreeRing 作为目录 inode 集合（64-bit id 集合）的使用 Recipe

## 为什么用 DsFreeRing 存目录

- **位宽精确匹配**：slot = `readLong/writeLong` 8B/64-bit，正好匹配 inode id / DsIdLayout rowId / p2p-db 的 54 位安全整数范围。
- **持久化到文件**：20B 头 + `cap*8B` 数据区，跨 JVM 重启自动读取，不用恢复内存状态。
- **线程安全**：单 `ReentrantLock` 全包，目录层级的并发 add/remove/contains 都安全。
- **无需额外依赖**：纯 `java.io.RandomAccessFile`，不引第三方。

## 目录操作 Recipe 映射

```java
File dirFile = new File("/data/my-p2p-fs/dir_" + dirId + ".inodes");
int initialCap = 64;  // 初始容量，满了自动 ×2 扩容
try (DsFreeRing dirSet = new DsFreeRing(dirFile, initialCap)) {

  // === 新建空目录 → 默认构造后就是空的 ===

  // === 向目录加一个 inode（mkdir -p / touch / hard link 场景） ===
  boolean added = dirSet.offerUnique(inodeId);  // 去重加：已经有则 false
  // 如果你想允许重复（硬链接多次记录），用 offer()
  boolean linked = dirSet.offer(inodeId);

  // === 判断 inode 是否在目录内（stat / ls 命中判断） ===
  boolean exists = dirSet.contains(inodeId);

  // === 从目录删 inode（unlink / rm 场景） ===
  boolean removed = dirSet.remove(inodeId);  // 不在集合内返回 false

  // === 列目录所有 inode（readdir / find 场景） ===
  long[] inodes = dirSet.snapshot();
  for (long inode : inodes) {
    // resolve inode → filename/metadata ...
  }

  // === 当前文件数 ===
  int filesInDir = dirSet.size();       // Set 语义名
  int same      = dirSet.count();      // Queue 语义原名

  // === 清空目录（rmdir 后回收所有条目，但目录 inode 自己保留） ===
  dirSet.clear();

  // === 回收 id（原 Queue 语义，如果你想做"删除后的 inode id 池复用"） ===
  dirSet.offer(freedInodeId);           // 放回回收池
  long reuse = dirSet.poll();           // 取出一个复用；-1 表示池空
}
```

## 典型容量与磁盘占用参考

每个 inode 8B，实际文件 = 20B 头 + 8B × `capacity()`：

| capacity() | 文件大小 | 典型场景 |
|---|---|---|
| 64 | 20 + 512 = **532 B** | 小目录（配置文件目录，<50 文件） |
| 512 | 20 + 4096 = **4.02 KB** | 中目录（用户 home，几百文件） |
| 8192 | 20 + 65536 = **64 KB** | 大目录（相册 / 视频索引，几千条目） |
| 131072 | 20 + 1MB = **1 MB** | 超大目录（百万级对象桶前缀目录） |

**`initialCap` 怎么选？** 建议比目录"典型稳态大小"的 2× 还小一档——expand 自动 ×2 会在 count==cap 时发生，对性能影响可忽略（一次性 long[] 复制 + 单次 setLength + 顺序写回）。

## 跨进程 / 多 Writer 注意

- DsFreeRing **当前没有 FileChannel 级文件锁**。同一 JVM 内可以多线程共享（`ReentrantLock` 保护），但两个 JVM / 两个不同的 DsFreeRing 实例同时写同一个 .free 文件会互相覆盖写导致丢条目。
- 如果你真要做跨进程目录共享：**在外面加 `FileLock` 包一层**，或让一个进程做 "directory server"，其他进程走 RPC。

## 何时不要用 DsFreeRing 作目录

- 目录需要**按 inode 排序 / 按文件名查找 / 存 (inode → filename, mode, mtime) 复合元组**：DsFreeRing 只存单 long id，应改用 `DsFixedValueStore + EqIndexStore` 做变长记录 + 二级索引。
- 单目录条目 > 千万级：contains()/remove() 现在是 O(capacity) 线性扫描，应切分子桶（如按 inode 末 4bit 分 16 个 DsFreeRing 文件）降低线性开销。
- 需要持久化原子崩溃恢复：DsFreeRing 自己没有 WAL；配合 p2p-db 的 `DsBinlogStore` 包一层事务（add/remove 记 binlog，启动重放）即可。
