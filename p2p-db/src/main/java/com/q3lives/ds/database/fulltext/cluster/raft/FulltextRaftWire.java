package com.q3lives.ds.database.fulltext.cluster.raft;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * P58 T2：fulltext 分片写命令在 Raft 日志中的线格式。
 *
 * <p>
 * P57 只暴露 {@code appendPutRegistry(path, payload)}，因此把 fulltext 变更编码进
 * {@code registryPath + payload} 二元组：path 携带操作种类与实体 id，payload 携带正文/向量。
 * 每个副本用同一套解码逻辑重放同一有序日志，从而得到确定性一致的副本状态。</p>
 */
public final class FulltextRaftWire {

    /**
     * 所有被复制的 fulltext 命令共享该前缀，用于与非 fulltext 日志条目区分。
     */
    public static final String PREFIX = "/ft/";

    public static final String OP_PUT_DOC = "/ft/doc/";
    public static final String OP_CLEAR_DOC = "/ft/clr/";
    public static final String OP_INDEX_VEC = "/ft/vec/";

    private FulltextRaftWire() {
    }

    public static String docPath(long docId) {
        requirePositiveId(docId, "docId");
        return OP_PUT_DOC + docId;
    }

    public static String clearPath(long docId) {
        requirePositiveId(docId, "docId");
        return OP_CLEAR_DOC + docId;
    }

    public static String vecPath(long uid) {
        requirePositiveId(uid, "uid");
        return OP_INDEX_VEC + uid;
    }

    public static boolean isFulltextPath(String registryPath) {
        return registryPath != null && registryPath.startsWith(PREFIX);
    }

    public static byte[] encodeText(String text) {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        return text.getBytes(StandardCharsets.UTF_8);
    }

    public static String decodeText(byte[] payload) {
        return new String(payload == null ? new byte[0] : payload, StandardCharsets.UTF_8);
    }

    public static byte[] encodeFloats(float[] vec) {
        if (vec == null || vec.length == 0) {
            throw new IllegalArgumentException("vec must not be null or empty");
        }
        ByteBuffer bb = ByteBuffer.allocate(vec.length * 4);
        for (float v : vec) {
            bb.putFloat(v);
        }
        return bb.array();
    }

    public static float[] decodeFloats(byte[] payload) {
        if (payload == null || payload.length == 0 || (payload.length & 3) != 0) {
            throw new IllegalArgumentException("payload must be non-empty float32-aligned, len="
                    + (payload == null ? -1 : payload.length));
        }
        ByteBuffer bb = ByteBuffer.wrap(payload);
        float[] out = new float[payload.length / 4];
        for (int i = 0; i < out.length; i++) {
            out[i] = bb.getFloat();
        }
        return out;
    }

    /**
     * 解析 {@code prefix + <id>} 形式的路径尾部 id。
     */
    public static long parseId(String registryPath, String prefix) {
        if (registryPath == null || prefix == null || !registryPath.startsWith(prefix)) {
            throw new IllegalArgumentException("path not under " + prefix + ": " + registryPath);
        }
        String tail = registryPath.substring(prefix.length());
        try {
            return Long.parseLong(tail);
        } catch (NumberFormatException nfe) {
            throw new IllegalArgumentException("invalid id '" + tail + "' in " + registryPath, nfe);
        }
    }

    private static void requirePositiveId(long id, String name) {
        if (id <= 0L) {
            throw new IllegalArgumentException(name + " must be positive: " + id);
        }
    }
}
