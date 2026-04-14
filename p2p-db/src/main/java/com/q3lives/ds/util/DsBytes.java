package com.q3lives.ds.util;

import java.io.IOException;

import com.q3lives.ds.bucket.DsFixedBucketStore;

/**
 * byte[] 的最薄封装：把 {@link DsFixedBucketStore} 绑定到固定的 space/type 上。
 *
 * <p>用途：</p>
 * <ul>
 *   <li>用于把“一个逻辑数据集”映射到同一组 bucket 文件（按 type 分组，按长度选择 power 档位）。</li>
 *   <li>对外只暴露 put/get/remove/overwrite，id 语义完全由 bucket 层决定。</li>
 * </ul>
 */
public class DsBytes {
    private final DsFixedBucketStore store;
    private final String space;
    private final String type;

    /**
     * 创建一个 bytes 存储（默认 space=independent）。
     *
     * @param rootDir bucket 根目录
     * @param type bucket 的 type（单段名称）
     */
    public DsBytes(String rootDir, String type) {
        DsPathUtil.validateSegment(type, "type");
        this.store = new DsFixedBucketStore(rootDir);
        this.space = DsFixedBucketStore.INDEPENDENT_SPACE;
        this.type = type;
    }

    /**
     * 创建一个 bytes 存储（显式指定 space/type）。
     *
     * @param rootDir bucket 根目录
     * @param space space（支持 dotted，例如 a.b.c）
     * @param type type（单段名称）
     */
    public DsBytes(String rootDir, String space, String type) {
        DsPathUtil.dottedToLinuxPath(space, "space");
        DsPathUtil.validateSegment(type, "type");
        this.store = new DsFixedBucketStore(rootDir);
        this.space = space;
        this.type = type;
    }

    /**
     * 创建一个 bytes 存储（复用外部 bucketStore）。
     *
     * <p>用于多个逻辑模块共享同一个 {@link DsFixedBucketStore} 实例（共享 mmap 缓存与文件句柄）。</p>
     */
    public DsBytes(DsFixedBucketStore store, String space, String type) {
        DsPathUtil.dottedToLinuxPath(space, "space");
        DsPathUtil.validateSegment(type, "type");
        this.store = store;
        this.space = space;
        this.type = type;
    }

    /**
     * 写入一条记录并返回 id。
     *
     * <p>id 的编码由 {@link DsFixedBucketStore} 决定（包含 power+baseId）。</p>
     */
    public long put(byte[] data) throws IOException {
        return store.put(space, type, data);
    }

    /**
     * 读取一条记录的前 length 个字节。
     */
    public byte[] get(long id, int length) throws IOException {
        return store.get(space, type, id, length);
    }

    /**
     * 回收一个 id（加入 free-ring，允许后续 put 复用）。
     */
    public void remove(long id) throws IOException {
        store.remove(space, type, id);
    }

    /**
     * 覆盖写入（id 不变，按当前 id 的 power 档位写入；不足会自动补 0）。
     */
    public void overwrite(long id, byte[] data) throws IOException {
        store.overwrite(space, type, id, data);
    }
}
