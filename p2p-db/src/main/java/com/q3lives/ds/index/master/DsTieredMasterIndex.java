package com.q3lives.ds.index.master;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;

import com.q3lives.ds.core.DsObject;
import com.q3lives.ds.util.DsDataUtil;

/**
 * 分级主控索引（Tiered Master Index）。
 *
 * <p>用途：</p>
 * <ul>
 *   <li>把任意 byte[] key 映射到一个 long indexId（通常是上层某个定长 index record 的 bucket id）。</li>
 *   <li>默认优先使用更便宜的哈希层（hash32），仅在“碰撞确认”时升级到更强的哈希层（hash64 / md5 / sha256）。</li>
 * </ul>
 *
 * <p>升级策略（碰撞驱动）：</p>
 * <ul>
 *   <li>当检测到两个不同 key 在当前层哈希值完全一致时，通过 {@link #promoteOnCollision(byte[], byte[], long)}
 *       在“当前层”的 slot 上写入 childLevel，标记该 hash 桶需要转到下一层。</li>
 *   <li>childLevel 的存放复用各层 MasterIndex 的 slot 状态（通常为 STATE_VALUE_CHILD），因此你可能会在
 *       hash32 还没走到 trie 的叶子之前看到该状态——那是 hash32 trie 的“前缀扩展”与 tiered childLevel 复用的结果。</li>
 * </ul>
 *
 * <p>并发：</p>
 * <ul>
 *   <li>get 不加锁（依赖子索引自身线程安全 / 读写可见性）。</li>
 *   <li>put/remove/promote 使用同一个 {@link #opLock} 串行化，保证 childLevel/size 元数据一致。</li>
 * </ul>
 */
public class DsTieredMasterIndex extends DsObject {
    private static final byte[] MAGIC = new byte[] {'.', 'M', 'T', 'I'};

    public static final int LEVEL_NONE = 0;
    public static final int LEVEL_HASH64 = 1;
    public static final int LEVEL_MD5 = 2;
    public static final int LEVEL_SHA256 = 3;

    private final ReentrantLock opLock = new ReentrantLock();

    private final DsHash32MasterIndex hash32Index;
    private final DsHash64MasterIndex hash64Index;
    private final DsShaMd5MasterIndex md5Index;
    private final DsSha256MasterIndex sha256Index;

    private long size;

    /**
     * 创建一个分级主控索引实例。
     *
     * <p>dir 用于存放四层索引文件与 master.meta 元数据文件。</p>
     */
    public DsTieredMasterIndex(File dir) {
        super(new File(dir, "master.meta"), 1);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        this.hash32Index = new DsHash32MasterIndex(new File(dir, "master_hash32.idx"));
        this.hash64Index = new DsHash64MasterIndex(new File(dir, "master_hash64.idx"));
        this.md5Index = new DsShaMd5MasterIndex(new File(dir, "master_md5.idx"));
        this.sha256Index = new DsSha256MasterIndex(new File(dir, "master_sha256.idx"));
        initHeader();
    }

    private void initHeader() {
        try {
            headerBuffer = loadBuffer(0L);
            byte[] m = new byte[4];
            headerBuffer.get(0, m, 0, 4);
            if (Arrays.equals(m, MAGIC)) {
                size = headerBuffer.getLong(8);
                return;
            }
            headerBuffer.put(0, MAGIC, 0, 4);
            size = 0;
            headerBuffer.putLong(8, size);
            dirty(0L);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 查询 keyBytes 对应的 indexId。
     *
     * <p>会按 childLevel 路由：hash32 -> hash64 -> md5 -> sha256。</p>
     *
     * @return indexId，不存在返回 null
     */
    public Long get(byte[] keyBytes) throws IOException {
        if (keyBytes == null) {
            return null;
        }
        // 先查 hash32；如果该 hash 桶已被标记 childLevel，则继续走下一层。
        int h32 = DsDataUtil.hash32(keyBytes);
        int l32 = hash32Index.getChildLevel(h32);
        if (l32 == LEVEL_NONE) {
            return hash32Index.get(h32);
        }

        long h64 = DsDataUtil.hash64(keyBytes);
        int l64 = hash64Index.getChildLevel(h64);
        if (l64 == LEVEL_NONE) {
            return hash64Index.get(h64);
        }

        byte[] md5 = DsDataUtil.md5(keyBytes);
        int lmd5 = md5Index.getChildLevel(md5);
        if (lmd5 == LEVEL_NONE) {
            return md5Index.get(md5);
        }

        byte[] sha = DsDataUtil.sha256(keyBytes);
        return sha256Index.get(sha);
    }

    /**
     * 写入 keyBytes -> indexId 映射。
     *
     * @param existed true 表示覆盖更新（size 不变），false 表示新增（size++）
     */
    public void put(byte[] keyBytes, long indexId, boolean existed) throws IOException {
        if (keyBytes == null) {
            throw new IllegalArgumentException("keyBytes cannot be null");
        }
        opLock.lock();
        try {
            // putResolved 只负责“定位到正确层并写入”，不维护 size 计数。
            // existed 由调用者判断：false 表示新增映射（size++），true 表示覆盖更新（size 不变）。
            putResolved(keyBytes, indexId);
            if (!existed) {
                size++;
                headerBuffer.putLong(8, size);
                dirty(0L);
            }
        } finally {
            opLock.unlock();
        }
    }

    private void putResolved(byte[] keyBytes, long indexId) throws IOException {
        int h32 = DsDataUtil.hash32(keyBytes);
        int l32 = hash32Index.getChildLevel(h32);
        if (l32 == LEVEL_NONE) {
            hash32Index.put(h32, indexId);
            return;
        }

        long h64 = DsDataUtil.hash64(keyBytes);
        int l64 = hash64Index.getChildLevel(h64);
        if (l64 == LEVEL_NONE) {
            hash64Index.put(h64, indexId);
            return;
        }

        byte[] md5 = DsDataUtil.md5(keyBytes);
        int lmd5 = md5Index.getChildLevel(md5);
        if (lmd5 == LEVEL_NONE) {
            md5Index.put(md5, indexId);
            return;
        }

        byte[] sha = DsDataUtil.sha256(keyBytes);
        sha256Index.put(sha, indexId);
    }

    /**
     * 删除 keyBytes 对应的映射。
     *
     * <p>会按 childLevel 路由到正确层执行 remove。</p>
     */
    public boolean remove(byte[] keyBytes) throws IOException {
        if (keyBytes == null) {
            return false;
        }
        opLock.lock();
        try {
            // removeResolved 只负责“定位到正确层并删除”，返回是否真的删掉了映射。
            boolean removed = removeResolved(keyBytes);
            if (removed) {
                size--;
                headerBuffer.putLong(8, size);
                dirty(0L);
            }
            return removed;
        } finally {
            opLock.unlock();
        }
    }

    private boolean removeResolved(byte[] keyBytes) throws IOException {
        int h32 = DsDataUtil.hash32(keyBytes);
        int l32 = hash32Index.getChildLevel(h32);
        if (l32 == LEVEL_NONE) {
            return hash32Index.remove(h32);
        }

        long h64 = DsDataUtil.hash64(keyBytes);
        int l64 = hash64Index.getChildLevel(h64);
        if (l64 == LEVEL_NONE) {
            return hash64Index.remove(h64);
        }

        byte[] md5 = DsDataUtil.md5(keyBytes);
        int lmd5 = md5Index.getChildLevel(md5);
        if (lmd5 == LEVEL_NONE) {
            return md5Index.remove(md5);
        }

        byte[] sha = DsDataUtil.sha256(keyBytes);
        return sha256Index.remove(sha);
    }

    /**
     * 确认碰撞后触发升级：把当前层的 hash 桶标记 childLevel，并把 otherIndexId 写入下一层。
     *
     * <p>该方法只在调用方已经确认“不同 key 但当前层哈希值相同”时调用。</p>
     */
    public void promoteOnCollision(byte[] keyBytes, byte[] otherKeyBytes, long otherIndexId) throws IOException {
        if (keyBytes == null || otherKeyBytes == null) {
            return;
        }
        opLock.lock();
        try {
            // 只有“确认碰撞”（两个 key 在当前层哈希值完全相同）才会升级，
            // 升级后把 otherIndexId 先写入下一层索引，避免升级窗口期丢失映射。
            //
            // 升级流程：
            // hash32 碰撞 -> setChildLevel(hash32, LEVEL_HASH64) 并写入 hash64Index(other)
            // hash64 碰撞 -> setChildLevel(hash64, LEVEL_MD5) 并写入 md5Index(other)
            // md5  碰撞 -> setChildLevel(md5,  LEVEL_SHA256) 并写入 sha256Index(other)
            int h32 = DsDataUtil.hash32(keyBytes);
            int h32b = DsDataUtil.hash32(otherKeyBytes);
            if (h32 != h32b) {
                return;
            }
            int l32 = hash32Index.getChildLevel(h32);
            if (l32 == LEVEL_NONE) {
                hash32Index.setChildLevel(h32, LEVEL_HASH64);
                long h64Other = DsDataUtil.hash64(otherKeyBytes);
                hash64Index.put(h64Other, otherIndexId);
                return;
            }

            long h64 = DsDataUtil.hash64(keyBytes);
            long h64b = DsDataUtil.hash64(otherKeyBytes);
            if (h64 != h64b) {
                return;
            }
            int l64 = hash64Index.getChildLevel(h64);
            if (l64 == LEVEL_NONE) {
                hash64Index.setChildLevel(h64, LEVEL_MD5);
                byte[] md5Other = DsDataUtil.md5(otherKeyBytes);
                md5Index.put(md5Other, otherIndexId);
                return;
            }

            byte[] md5 = DsDataUtil.md5(keyBytes);
            byte[] md5b = DsDataUtil.md5(otherKeyBytes);
            if (!Arrays.equals(md5, md5b)) {
                return;
            }
            int lmd5 = md5Index.getChildLevel(md5);
            if (lmd5 == LEVEL_NONE) {
                md5Index.setChildLevel(md5, LEVEL_SHA256);
                byte[] shaOther = DsDataUtil.sha256(otherKeyBytes);
                sha256Index.put(shaOther, otherIndexId);
            }
        } finally {
            opLock.unlock();
        }
    }

    /**
     * 返回当前映射条目数（上限截断到 int）。
     */
    public int size() {
        if (size > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) size;
    }

    /**
     * 关闭索引并同步落盘。
     */
    public void close() {
        sync();
        hash32Index.close();
        hash64Index.close();
        md5Index.close();
        sha256Index.close();
    }
}
