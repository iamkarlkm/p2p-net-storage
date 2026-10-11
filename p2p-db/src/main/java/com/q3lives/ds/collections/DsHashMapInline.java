package com.q3lives.ds.collections;

/**
 * DsHashMap value slot 内联数据编解码。
 *
 * <p>≤64 位的已知固定长度数据可以直接放进 DsHashMap 的 8 字节 value slot 中，
 * 从而避免二次索引。该工具类只负责字节与 long 之间的无损转换；
 * “value 是内联数据还是 recordId/dataId” 由外层 schema 或 header 元数据判断，
 * <b>不占用最高位作为内联标记</b>。</p>
 */
public final class DsHashMapInline {

    public static final int MAX_INLINE_BYTES = Long.BYTES; // 8

    private DsHashMapInline() {
    }

    /**
     * 将 ≤8 字节的数据编码为一个 long（小端序）。
     *
     * @param data 要内联的字节，长度必须在 [0, 8] 之间
     * @return 编码后的 long，空数据返回 0L
     * @throws IllegalArgumentException 若数据长度超过 8 字节或为 null
     */
    public static long encode(byte[] data) {
        if (data == null) {
            throw new NullPointerException("data");
        }
        if (data.length > MAX_INLINE_BYTES) {
            throw new IllegalArgumentException(
                    "inline data too long: " + data.length + " > " + MAX_INLINE_BYTES);
        }
        long v = 0L;
        for (int i = 0; i < data.length; i++) {
            v |= ((long) (data[i] & 0xFF)) << (i * 8);
        }
        return v;
    }

    /**
     * 从 long 中解码出指定长度的字节（小端序）。
     *
     * @param value  编码后的 long
     * @param length 要还原的字节长度，必须在 [0, 8] 之间
     * @return 解码后的字节数组
     * @throws IllegalArgumentException 若 length 超过 8 字节
     */
    public static byte[] decode(long value, int length) {
        if (length < 0 || length > MAX_INLINE_BYTES) {
            throw new IllegalArgumentException(
                    "inline length out of range: " + length);
        }
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) (value >>> (i * 8));
        }
        return data;
    }
}
