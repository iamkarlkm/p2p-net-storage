package com.q3lives.ds.registry;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import com.q3lives.ds.enums.Category;
import com.q3lives.ds.enums.DataType;

/**
 * 注册表单条结果的 immutable 包装。提供类型安全的 getter，按 {@link DataType#getCategory()} 路由：
 * <ul>
 *   <li>NUMERIC → inline value（{@link #asLong()} / {@link #asInt()} / {@link #asDouble()} / {@link #asDate()}）
 *   <li>STRING/CONFIG/SCRIPT/DOCS/CODE → {@link #stringValue()}
 *   <li>BINARY/ZIP/SETUP/EXEC → {@link #bytesValue()}
 *   <li>COMPLEX → 由调用方自行按 DataType 做外部反序列化，本类只提供 bytes 或 string raw
 * </ul>
 *
 * 设计原则：零拷贝引用（string/bytes 只读引用）。
 */
public final class RegistryEntry {

    public static final RegistryEntry EMPTY = new Builder().build();

    private final String key;
    private final boolean directory;
    private final DataType dataType;
    private final OptionalLong inlineValue;
    private final Optional<String> stringValue;
    private final Optional<byte[]> bytesValue;
    private final Optional<List<String>> listValue;
    private final long nodeId;
    private final long parentDirNodeId;
    private final long keyPathId;
    private final long childRingFileId;
    private final long globalNodeId;

    private RegistryEntry(Builder b) {
        this.key = b.key;
        this.directory = b.directory;
        this.dataType = b.dataType;
        this.inlineValue = b.hasInline ? OptionalLong.of(b.inline) : OptionalLong.empty();
        this.stringValue = Optional.ofNullable(b.stringValue);
        this.bytesValue = Optional.ofNullable(b.bytesValue);
        this.listValue = b.listValue == null ? Optional.empty() : Optional.of(Collections.unmodifiableList(new java.util.ArrayList<>(b.listValue)));
        this.nodeId = b.nodeId;
        this.parentDirNodeId = b.parentDirNodeId;
        this.keyPathId = b.keyPathId;
        this.childRingFileId = b.childRingFileId;
        this.globalNodeId = b.globalNodeId;
    }

    public String key() { return key; }
    public boolean isPresent() { return this != EMPTY; }
    public boolean isDirectory() { return directory; }
    public DataType dataType() { return dataType; }
    public Category category() { return dataType == null ? Category.STRING : dataType.getCategory(); }
    public long nodeId() { return nodeId; }
    public long parentDirNodeId() { return parentDirNodeId; }
    public long keyPathId() { return keyPathId; }
    public long childRingFileId() { return childRingFileId; }
    public long globalNodeId() { return globalNodeId; }
    public OptionalLong inlineValueRaw() { return inlineValue; }
    public Optional<List<String>> listValueRaw() { return listValue; }
    public Optional<String> stringValue() { return stringValue; }
    public Optional<byte[]> bytesValue() { return bytesValue; }

    public long asLong() {
        if (inlineValue.isPresent()) return inlineValue.getAsLong();
        throw new IllegalStateException("Entry key=" + key + " has no inline NUMERIC; category=" + category());
    }

    public int asInt() {
        long v = asLong();
        if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
            throw new ArithmeticException("inline NUMERIC out of int range for key=" + key);
        }
        return (int) v;
    }

    public double asDouble() {
        if (inlineValue.isPresent()) return Double.longBitsToDouble(inlineValue.getAsLong());
        throw new IllegalStateException("Entry key=" + key + " has no inline DOUBLE/NUMERIC; category=" + category());
    }

    public Date asDate() {
        if (dataType == null) throw new IllegalStateException("Entry key=" + key + " unknown dataType");
        Category c = dataType.getCategory();
        if (c == Category.NUMERIC || dataType == DataType.REG_CHAR_DATETIME) {
            return new Date(asLong());
        }
        throw new IllegalStateException("Entry key=" + key + " not a DATETIME category; type=" + dataType);
    }

    public String asString() {
        return stringValue.orElseGet(() -> {
            if (bytesValue.isPresent()) return new String(bytesValue.get(), java.nio.charset.StandardCharsets.UTF_8);
            if (inlineValue.isPresent()) return Long.toString(inlineValue.getAsLong());
            return null;
        });
    }

    public byte[] asBytes() {
        if (bytesValue.isPresent()) return bytesValue.get();
        if (stringValue.isPresent()) return stringValue.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (inlineValue.isPresent()) {
            return DsRegistry.unpackSmallBytes(inlineValue.getAsLong());
        }
        return new byte[0];
    }

    public List<String> asList() {
        if (listValue.isPresent()) return listValue.get();
        DataType t = this.dataType;
        if (t != null && (t == DataType.REG_MULTI_SZ || t == DataType.REG_LIST)) {
            byte[] raw = asBytes();
            if (raw != null && raw.length >= 2) {
                try {
                    return DsRegistryMultiSzCodec.decode(raw);
                } catch (IllegalArgumentException ignore) {
                    return Collections.emptyList();
                }
            }
            return Collections.emptyList();
        }
        throw new UnsupportedOperationException("Entry key=" + key + " 不是 REG_MULTI_SZ 或 REG_LIST，实际 dataType=" + dataType);
    }

    public static final class Builder {
        String key;
        boolean directory;
        DataType dataType = DataType.REG_BINARY;
        boolean hasInline;
        long inline;
        String stringValue;
        byte[] bytesValue;
        List<String> listValue;
        long nodeId;
        long parentDirNodeId;
        long keyPathId;
        long childRingFileId;
        long globalNodeId;

        public Builder key(String v) { this.key = v; return this; }
        public Builder directory(boolean v) { this.directory = v; return this; }
        public Builder dataType(DataType v) { this.dataType = v == null ? DataType.REG_BINARY : v; return this; }
        public Builder inline(long v) { this.hasInline = true; this.inline = v; return this; }
        public Builder stringValue(String v) { this.stringValue = v; return this; }
        public Builder bytesValue(byte[] v) { this.bytesValue = v; return this; }
        public Builder listValue(List<String> v) { this.listValue = v; return this; }
        public Builder nodeId(long v) { this.nodeId = v; return this; }
        public Builder parentDirNodeId(long v) { this.parentDirNodeId = v; return this; }
        public Builder keyPathId(long v) { this.keyPathId = v; return this; }
        public Builder childRingFileId(long v) { this.childRingFileId = v; return this; }
        public Builder globalNodeId(long v) { this.globalNodeId = v; return this; }

        public RegistryEntry build() {
            return new RegistryEntry(this);
        }
    }
}
