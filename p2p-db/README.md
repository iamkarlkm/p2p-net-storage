# p2p-db 模块设计文档

## 1. 概述
`p2p-db` 是一个基于 Java NIO `MappedByteBuffer` (内存映射文件) 实现的高性能、持久化嵌入式存储引擎。它旨在为 P2P 系统提供底层的数据存储支持，包括文件索引、元数据管理和本地持久化集合。

## 2. 核心设计思想
*   **内存映射 IO (MMIO)**: 利用操作系统层面的虚拟内存管理，将文件直接映射到内存地址空间，避免了传统 IO 的用户态/内核态拷贝，提供接近内存操作的读写性能。
*   **堆外内存管理**: 数据直接存储在堆外内存（MappedByteBuffer），减少了 Java 堆内存压力和 GC 停顿。
*   **零拷贝 (Zero Copy)**: 在理想情况下，数据可以直接在 buffer 间传递或写入网络通道，减少 CPU 拷贝。
*   **分层存储结构**:
    *   `DsList`: 采用分层数据块设计 (DataLayer)，支持动态扩容。
    *   `DsHashSet`: 采用多级哈希 (Multi-level Hashing) 结构解决冲突和分布数据，类似 Trie 树或多级页表。
*   **细粒度锁**: 实现了基于 Buffer 的锁机制，尝试在保证线程安全的同时提供较高的并发能力。

## 3. 核心类说明

### 3.1. 基础组件
*   **`ds.DsObject`**: 所有存储对象的基类。
    *   **职责**: 管理 `MappedByteBuffer` 的生命周期（加载、卸载、扩容）、文件 IO 操作、基本数据类型 (int, long, byte[]) 的读写。
    *   **Buffer 管理**: 使用 `datatBuffers` (Map) 缓存映射的 Block (默认 64KB)，支持按需加载。
    *   **同步机制**: `sync()` 方法将脏页 (dirty pages) 刷入磁盘。
    *   **并发控制**: 提供 `bufferLock` 和 `idLockPool` 等基础锁设施。

### 3.2. 数据结构
*   **`ds.Ds128SuperInode`**:
    *   **用途**: 文件系统的元数据节点 (Super Block / Inode)。
    *   **结构**: 固定 128 字节 (或近似)，存储 Magic Number、Root Node 指针、Block 总数、Block 大小、文件属性等。
    *   **特性**: 类似于 Linux 文件系统的 Superblock。

*   **`ds.DsList`**:
    *   **用途**: 持久化的 `ArrayList` / `LinkedList` 混合体。
    *   **实现**: 数据被分片存储在多个 `DataLayer` 中。当一层存满后，会分配新的层级，并动态调整容量。
    *   **并发策略**: 采用细粒度锁 (Fine-grained Locking)，每个 `DataLayer` 拥有独立的 `ReentrantReadWriteLock`，支持并发的读写操作 (get/set/remove)，仅在扩容时需要全局锁。
    *   **特性**: 支持 `add`, `get`, `remove`, `set` 等操作，适合存储序列化数据或日志。

*   **`ds.DsHashSet`**:
    *   **用途**: 持久化的哈希集合。
    *   **实现**: 使用多级哈希表。
        *   **位图 (Bitmap)**: `isHasValueBitmap` 用于快速判断某位置是否有值。
        *   **冲突解决**: 采用多级桶策略。如果 Hash 冲突，该位置会存储一个指向下一级 Bucket 的指针 (Pointer)，直到达到最大层级。
    *   **并发策略**: 采用分段锁 (Segmented Locking)，将哈希空间分为 16 个段，每个段有独立的锁。常用操作 (add/remove/contains) 仅锁定对应段，极大提高了并发性能。全局元数据更新仍受 `headerOpLock` 保护。
    *   **特性**: 适合存储去重的 ID 集合、文件指纹等。

*   **`ds.DsDataIndex`**:
    *   **用途**: 定长记录存储 (Fixed-length Record Store)。
    *   **结构**: 类似于数据库的堆表 (Heap Table)。每个记录包含 `refCount`, `size`, `offset`, `hash`。
    *   **特性**: 用于构建数据索引。

## 4. 存储格式
*   **Magic Number**: 文件头通常包含 Magic Number (如 `.LIS`, `.SET`, `.IDX`, `DS64v1.0`) 用于标识文件类型。
*   **Block 对齐**: 默认使用 64KB (`BLOCK_SIZE`) 作为映射块大小。
*   **Header**: 每个文件头部包含元数据（Total count, Next Offset 等）。

## 5. 待优化项 (TODO)
1.  **资源回收**: 目前 `UnmapperProxy` 使用了非标准的 JDK API，可能在 JDK 9+ 中失效，需要适配 `MethodHandles` 或 `Unsafe`。
2.  **异常处理**: 代码中大量捕获 `Exception` 并抛出 `RuntimeException`，掩盖了具体的 IO 错误，需要细化异常类型。
3.  **并发性能**: `ConcurrentHashMap` 用于缓存 Buffer，但缺乏 LRU 淘汰机制，长期运行可能导致 Direct Memory 溢出。需要引入缓存淘汰策略。
4.  **事务支持**: 目前缺乏原子性事务支持，断电可能导致数据不一致（虽然有 `sync`，但非 WAL 机制）。
5.  **序列化**: 对象序列化目前较为手动，可以引入更高效的序列化框架 (如 Protobuf, Kryo)。

## 6. 依赖
*   JDK 1.8+ (推荐 JDK 11/17，需注意 Unsafe 访问权限)
*   Netty (用于部分 ByteBuf 操作，虽然核心逻辑主要依赖 JDK NIO)
