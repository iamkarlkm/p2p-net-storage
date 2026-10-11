package com.q3lives.ds.database;

import com.q3lives.ds.collections.DsHashMapInline;

/**
 * 根据字段 schema 决定 value 的存储路径：内联 / 定长 valueStore / 变长 bucket。
 */
public final class DsValueResolver {

    public enum StorageType {
        /**
         * ≤64 位已知固定长度，直接内联到 DsHashMap value slot。
         */
        INLINE,
        /**
         * >64 位已知固定长度，DsHashMap value 存当前 map 的定长 valueStore recordId。
         */
        FIXED_VALUE_STORE,
        /**
         * 变长数据（压缩、String、数组等），DsHashMap value 存 DsFixedBucketStore dataId。
         */
        VARIABLE_BUCKET
    }

    private DsValueResolver() {
    }

    /**
     * 根据字段声明长度决定存储类型。
     *
     * @param fieldLength {@code DsField.length()}，0 表示未声明长度（变长）
     * @return 存储类型
     */
    public static StorageType resolve(int fieldLength) {
        if (fieldLength < 0) {
            throw new IllegalArgumentException("fieldLength must be >= 0");
        }
        if (fieldLength == 0) {
            return StorageType.VARIABLE_BUCKET;
        }
        if (fieldLength <= DsHashMapInline.MAX_INLINE_BYTES) {
            return StorageType.INLINE;
        }
        return StorageType.FIXED_VALUE_STORE;
    }

    /**
     * 将数据填充或截断到指定固定长度（用于定长列写入）。
     */
    public static byte[] padToLength(byte[] data, int length) {
        if (data == null) {
            data = new byte[0];
        }
        if (data.length == length) {
            return data;
        }
        byte[] padded = new byte[length];
        System.arraycopy(data, 0, padded, 0, Math.min(data.length, length));
        return padded;
    }
}
