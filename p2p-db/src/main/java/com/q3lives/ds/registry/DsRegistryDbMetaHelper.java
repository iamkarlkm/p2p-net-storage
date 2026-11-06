package com.q3lives.ds.registry;

import com.q3lives.ds.enums.Category;
import com.q3lives.ds.enums.DataType;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class DsRegistryDbMetaHelper {

    public static final String DBMETA_LEAF_SUFFIX = "/@dbmeta/attrs.bin";
    public static final DataType PERSIST_TYPE = DataType.REG_BLOB;

    static final byte MAGIC = (byte) 0xA7;
    static final byte VERSION = 0x01;
    static final byte VERSION_V2 = 0x02;
    static final int HEADER_LEN = 4;
    static final int TLV_TAG_LEN = 1;
    static final int TLV_LEN_BYTES = 2;
    static final int TLV_TOTAL_OVERHEAD_PER_FIELD = TLV_TAG_LEN + TLV_LEN_BYTES;
    static final int MAX_STR8_BYTES = 256;
    static final int COUNT_SHIFT = 4;
    static final int FLAGS_MASK = 0x0F;
    static final int V1_MAX_COUNT = 0x0F;
    static final int V2_MAX_COUNT = 0xFF;

    public enum ValueType {
        STR8,
        ENUM8,
        U32,
        U64,
        BOOL8
    }

    public enum DbMetaAttr {
        NAME((byte) 0x01, ValueType.STR8),
        DESC((byte) 0x02, ValueType.STR8),
        OWNER((byte) 0x03, ValueType.STR8),
        ENV((byte) 0x04, ValueType.ENUM8),
        SOURCE((byte) 0x05, ValueType.STR8),
        REGION((byte) 0x06, ValueType.ENUM8),
        ZONE((byte) 0x07, ValueType.ENUM8),
        CLUSTER((byte) 0x08, ValueType.STR8),
        RACK((byte) 0x09, ValueType.STR8),
        LABELS((byte) 0x0A, ValueType.STR8),
        BACKUP_POLICY((byte) 0x0B, ValueType.ENUM8),

        TTL_SEC((byte) 0x40, ValueType.U32),
        RETENTION_DAYS((byte) 0x41, ValueType.U32),
        VERSION((byte) 0x42, ValueType.U64),
        PARTITION_COUNT((byte) 0x43, ValueType.U32),
        REPLICA_COUNT((byte) 0x44, ValueType.U32),
        SHARD_ID((byte) 0x45, ValueType.U32),
        QUOTA_BYTES((byte) 0x46, ValueType.U64),
        BATCH_SIZE((byte) 0x47, ValueType.U32),
        CHECKSUM_CRC32((byte) 0x48, ValueType.U64),

        IS_PARTITIONED((byte) 0x60, ValueType.BOOL8),
        IS_COMPRESSED((byte) 0x61, ValueType.BOOL8),
        IS_ENCRYPTED((byte) 0x62, ValueType.BOOL8),
        IS_READ_ONLY((byte) 0x63, ValueType.BOOL8),
        IS_ARCHIVED((byte) 0x64, ValueType.BOOL8);

        public final byte tagId;
        public final ValueType vt;

        DbMetaAttr(byte tagId, ValueType vt) {
            this.tagId = tagId;
            this.vt = vt;
        }

        private static final Map<Byte, DbMetaAttr> BY_TAG;
        static {
            Map<Byte, DbMetaAttr> m = new HashMap<>();
            for (DbMetaAttr a : values()) {
                DbMetaAttr prev = m.put(a.tagId, a);
                if (prev != null) {
                    throw new IllegalStateException("DbMetaAttr tagId 冲突: 0x"
                            + Integer.toHexString(prev.tagId & 0xFF) + " 同时属于 " + prev + " 与 " + a);
                }
            }
            BY_TAG = Collections.unmodifiableMap(m);
        }

        public static Optional<DbMetaAttr> byTagId(byte id) {
            return Optional.ofNullable(BY_TAG.get(id));
        }
    }

    static final class DBMetaAttrsCodec {

        private DBMetaAttrsCodec() {}

        static byte[] encode(Map<DbMetaAttr, Object> values) {
            Map<DbMetaAttr, Object> src = (values == null) ? Collections.emptyMap() : values;
            int countAttrs = 0;
            for (Map.Entry<DbMetaAttr, Object> e : src.entrySet()) {
                if (e == null || e.getKey() == null || e.getValue() == null) continue;
                countAttrs++;
            }
            if (countAttrs > V2_MAX_COUNT) {
                throw new IllegalArgumentException("countAttrs 超过 V2 8-bit 上限 255，实际 " + countAttrs);
            }

            Map<DbMetaAttr, byte[]> encoded = new EnumMap<>(DbMetaAttr.class);
            int total = HEADER_LEN;
            for (Map.Entry<DbMetaAttr, Object> e : src.entrySet()) {
                DbMetaAttr a = e.getKey();
                Object v = e.getValue();
                if (a == null || v == null) continue;
                byte[] b = encodeValue(a, v);
                encoded.put(a, b);
                total += TLV_TOTAL_OVERHEAD_PER_FIELD + b.length;
            }

            byte version = (countAttrs <= V1_MAX_COUNT) ? VERSION : VERSION_V2;
            byte flags = 0;
            ByteBuffer buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
            buf.put(MAGIC);
            buf.put(version);
            if (version == VERSION) {
                buf.put((byte) ((countAttrs << COUNT_SHIFT) | (flags & FLAGS_MASK)));
                buf.put((byte) 0x00);
            } else {
                buf.put((byte) countAttrs);
                buf.put(flags);
            }
            for (Map.Entry<DbMetaAttr, byte[]> e : encoded.entrySet()) {
                buf.put(e.getKey().tagId);
                byte[] b = e.getValue();
                if (b.length > 0x7FFF) {
                    throw new IllegalArgumentException("TLV len 超过 2B signed max 32767，字段=" + e.getKey() + " len=" + b.length);
                }
                buf.putShort((short) b.length);
                buf.put(b);
            }
            return buf.array();
        }

        private static byte[] encodeValue(DbMetaAttr a, Object v) {
            ValueType vt = a.vt;
            switch (vt) {
                case STR8:
                case ENUM8: {
                    String s = (String) v;
                    if (s == null) s = "";
                    byte[] b = s.getBytes(StandardCharsets.UTF_8);
                    if (b.length > MAX_STR8_BYTES) {
                        throw new IllegalArgumentException(a + " STR8/ENUM8 UTF-8 超过上限 " + MAX_STR8_BYTES + "B，实际 " + b.length);
                    }
                    return b;
                }
                case U32: {
                    long l = toLong(v);
                    if (l < 0L || l > 0xFFFFFFFFL) {
                        throw new IllegalArgumentException(a + " U32 越界，必须 0..0xFFFFFFFF，实际 " + l);
                    }
                    return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) l).array();
                }
                case U64: {
                    long l = toLong(v);
                    return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(l).array();
                }
                case BOOL8: {
                    boolean b = toBool(v);
                    return new byte[] { b ? (byte)0x01 : (byte)0x00 };
                }
            }
            throw new IllegalArgumentException("未知 ValueType: " + vt);
        }

        private static long toLong(Object v) {
            if (v instanceof Long) return ((Long) v).longValue();
            if (v instanceof Integer) return ((Integer) v).longValue() & 0xFFFFFFFFL;
            if (v instanceof Short) return ((Short) v).longValue() & 0xFFFFL;
            if (v instanceof Byte) return ((Byte) v).longValue() & 0xFFL;
            if (v instanceof Number) return ((Number) v).longValue();
            throw new IllegalArgumentException("U32/U64 值必须是 Number，实际 " + (v == null ? "null" : v.getClass()));
        }

        private static boolean toBool(Object v) {
            if (v instanceof Boolean) return ((Boolean) v).booleanValue();
            if (v instanceof Number) return ((Number) v).intValue() != 0;
            throw new IllegalArgumentException("BOOL8 必须是 Boolean/Number，实际 " + (v == null ? "null" : v.getClass()));
        }

        static Map<DbMetaAttr, Object> decode(byte[] payload) {
            if (payload == null || payload.length < HEADER_LEN) {
                return new EnumMap<>(DbMetaAttr.class);
            }
            ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
            byte magic = buf.get();
            if (magic != MAGIC) {
                throw new IllegalArgumentException("损坏 Header: magic 应为 0xA7，实际 0x" + Integer.toHexString(magic & 0xFF));
            }
            byte ver = buf.get();
            if (ver != VERSION && ver != VERSION_V2) {
                throw new IllegalArgumentException("版本不支持: 期望 0x01 或 0x02，实际 0x" + Integer.toHexString(ver & 0xFF));
            }
            int countAttrs;
            @SuppressWarnings("unused")
            int flags;
            if (ver == VERSION) {
                byte combined = buf.get();
                countAttrs = ((combined >>> COUNT_SHIFT) & 0x0F);
                flags = combined & FLAGS_MASK;
                buf.get();
            } else {
                countAttrs = (buf.get() & 0xFF);
                flags = (buf.get() & 0xFF);
            }
            Map<DbMetaAttr, Object> out = new EnumMap<>(DbMetaAttr.class);
            int remain = countAttrs;
            while (buf.remaining() > 0 && remain-- > 0) {
                if (buf.remaining() < TLV_TOTAL_OVERHEAD_PER_FIELD) {
                    throw new IllegalArgumentException("损坏 TLV header 不足，位置 " + buf.position() + " 剩余 " + buf.remaining());
                }
                byte tagId = buf.get();
                int len = Short.toUnsignedInt(buf.getShort());
                if (len < 0) {
                    throw new IllegalArgumentException("TLV len <0: tag=0x" + Integer.toHexString(tagId & 0xFF) + " len=" + len);
                }
                if (buf.remaining() < len) {
                    throw new IllegalArgumentException("损坏 TLV payload 不足: tag=0x"
                            + Integer.toHexString(tagId & 0xFF) + " 需要 " + len + " 实际剩余 " + buf.remaining());
                }
                Optional<DbMetaAttr> attrOpt = DbMetaAttr.byTagId(tagId);
                if (!attrOpt.isPresent()) {
                    if (len > 0) buf.position(buf.position() + len);
                    continue;
                }
                DbMetaAttr a = attrOpt.get();
                byte[] b = new byte[len];
                if (len > 0) buf.get(b);
                Object val = decodeValue(a, b);
                if (val != null) out.put(a, val);
            }
            return out;
        }

        private static Object decodeValue(DbMetaAttr a, byte[] b) {
            ValueType vt = a.vt;
            switch (vt) {
                case STR8:
                case ENUM8:
                    return new String(b, StandardCharsets.UTF_8);
                case U32: {
                    if (b.length != 4) throw new IllegalArgumentException(a + " U32 字节长度错误 期望 4 实际 " + b.length);
                    return Long.valueOf(Integer.toUnsignedLong(ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt()));
                }
                case U64: {
                    if (b.length != 8) throw new IllegalArgumentException(a + " U64 字节长度错误 期望 8 实际 " + b.length);
                    return Long.valueOf(ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getLong());
                }
                case BOOL8: {
                    if (b.length != 1) throw new IllegalArgumentException(a + " BOOL8 字节长度错误 期望 1 实际 " + b.length);
                    return Boolean.valueOf(b[0] != 0);
                }
            }
            throw new IllegalArgumentException("未知 ValueType: " + vt);
        }
    }

    private DsRegistryDbMetaHelper() {}

    public static byte[] encodeTransport(Map<DbMetaAttr, Object> values) {
        return DBMetaAttrsCodec.encode(values);
    }

    public static Map<DbMetaAttr, Object> decodeTransport(byte[] payload) {
        return DBMetaAttrsCodec.decode(payload);
    }

    private static String normalizeEntry(String entryKey) {
        if (entryKey == null || entryKey.isEmpty() || "/".equals(entryKey)) {
            throw new IllegalArgumentException("entryKey 必须非空且非 / 根，实际: [" + entryKey + "]");
        }
        if (entryKey.charAt(entryKey.length() - 1) == '/') {
            entryKey = entryKey.substring(0, entryKey.length() - 1);
        }
        return entryKey;
    }

    public static void putAll(DsRegistry r, String entryKey, Map<DbMetaAttr, Object> values) throws IOException {
        if (r == null) throw new IllegalArgumentException("DsRegistry r is null");
        String ek = normalizeEntry(entryKey);
        byte[] payload = DBMetaAttrsCodec.encode(values);
        r.putBytes(ek + DBMETA_LEAF_SUFFIX, payload, PERSIST_TYPE);
    }

    public static void putStr(DsRegistry r, String entryKey, DbMetaAttr a, String v) throws IOException {
        if (a == null) throw new IllegalArgumentException("attr is null");
        if (a.vt != ValueType.STR8 && a.vt != ValueType.ENUM8) {
            throw new IllegalArgumentException("putStr 仅支持 STR8/ENUM8，实际 " + a.vt);
        }
        Map<DbMetaAttr, Object> old = getAll(r, entryKey);
        if (old.isEmpty()) old = new EnumMap<>(DbMetaAttr.class);
        old.put(a, v == null ? "" : v);
        putAll(r, entryKey, old);
    }

    public static void putLong(DsRegistry r, String entryKey, DbMetaAttr a, long v) throws IOException {
        if (a == null) throw new IllegalArgumentException("attr is null");
        if (a.vt != ValueType.U32 && a.vt != ValueType.U64) {
            throw new IllegalArgumentException("putLong 仅支持 U32/U64，实际 " + a.vt);
        }
        Map<DbMetaAttr, Object> old = getAll(r, entryKey);
        if (old.isEmpty()) old = new EnumMap<>(DbMetaAttr.class);
        old.put(a, Long.valueOf(v));
        putAll(r, entryKey, old);
    }

    public static void putBool(DsRegistry r, String entryKey, DbMetaAttr a, boolean v) throws IOException {
        if (a == null) throw new IllegalArgumentException("attr is null");
        if (a.vt != ValueType.BOOL8) {
            throw new IllegalArgumentException("putBool 仅支持 BOOL8，实际 " + a.vt);
        }
        Map<DbMetaAttr, Object> old = getAll(r, entryKey);
        if (old.isEmpty()) old = new EnumMap<>(DbMetaAttr.class);
        old.put(a, Boolean.valueOf(v));
        putAll(r, entryKey, old);
    }

    public static Map<DbMetaAttr, Object> getAll(DsRegistry r, String entryKey) throws IOException {
        if (r == null) throw new IllegalArgumentException("DsRegistry r is null");
        String ek = normalizeEntry(entryKey);
        RegistryEntry e = r.get(ek + DBMETA_LEAF_SUFFIX);
        if (e == null || !e.isPresent()) return new EnumMap<>(DbMetaAttr.class);
        DataType t = e.dataType();
        if (t == null) t = DataType.REG_BINARY;
        Category c = e.category();
        byte[] raw;
        java.util.OptionalLong inlineOpt = e.inlineValueRaw();
        if (inlineOpt.isPresent()) {
            long inline = inlineOpt.getAsLong();
            if (inline == 0L) return new EnumMap<>(DbMetaAttr.class);
            raw = DsRegistry.unpackSmallBytes(inline);
        } else {
            raw = e.asBytes();
        }
        if (raw == null) return new EnumMap<>(DbMetaAttr.class);
        return DBMetaAttrsCodec.decode(raw);
    }

    public static Optional<String> getStr(DsRegistry r, String entryKey, DbMetaAttr a) throws IOException {
        if (a == null) return Optional.empty();
        if (a.vt != ValueType.STR8 && a.vt != ValueType.ENUM8) {
            throw new IllegalArgumentException("getStr 仅支持 STR8/ENUM8，实际 " + a.vt);
        }
        Map<DbMetaAttr, Object> all = getAll(r, entryKey);
        Object v = all.get(a);
        return (v instanceof String) ? Optional.of((String) v) : Optional.empty();
    }

    public static long getLong(DsRegistry r, String entryKey, DbMetaAttr a, long defaultValue) throws IOException {
        if (a == null) return defaultValue;
        if (a.vt != ValueType.U32 && a.vt != ValueType.U64) {
            throw new IllegalArgumentException("getLong 仅支持 U32/U64，实际 " + a.vt);
        }
        Map<DbMetaAttr, Object> all = getAll(r, entryKey);
        Object v = all.get(a);
        if (v instanceof Long) return ((Long) v).longValue();
        if (v instanceof Number) return ((Number) v).longValue();
        return defaultValue;
    }

    public static boolean getBool(DsRegistry r, String entryKey, DbMetaAttr a, boolean defaultValue) throws IOException {
        if (a == null) return defaultValue;
        if (a.vt != ValueType.BOOL8) {
            throw new IllegalArgumentException("getBool 仅支持 BOOL8，实际 " + a.vt);
        }
        Map<DbMetaAttr, Object> all = getAll(r, entryKey);
        Object v = all.get(a);
        if (v instanceof Boolean) return ((Boolean) v).booleanValue();
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        return defaultValue;
    }

    public static boolean hasAny(DsRegistry r, String entryKey) throws IOException {
        if (r == null) throw new IllegalArgumentException("DsRegistry r is null");
        String ek = normalizeEntry(entryKey);
        return r.exists(ek + DBMETA_LEAF_SUFFIX);
    }

    public static boolean removeAll(DsRegistry r, String entryKey) throws IOException {
        if (r == null) throw new IllegalArgumentException("DsRegistry r is null");
        String ek = normalizeEntry(entryKey);
        String leaf = ek + DBMETA_LEAF_SUFFIX;
        if (!r.exists(leaf)) return false;
        r.remove(leaf, false);
        return true;
    }
}
