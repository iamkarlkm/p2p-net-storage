package com.q3lives.ds.database.tx;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * 2PC Op 记录（跨包 TxOpRegistry 需要 TYPE_* 常量，所以 class 必须 public）。
 * 封装约束：
 *   ① class public final，② 构造器 package-private，③ 所有字段 default（package-private）
 * 这样外部包读不到 field 内容，只能用 TxOpRegistry 静态 helper 构造 TxOp 实例。
 * <pre>
 * LE 字节布局 spec FR4（pack/unpack 必须一致）：
 *   type[1B] | pathLen[u16] | registryPath[pathLen bytes]
 *   | prevLen[i32, -1 表示 absent] | prevBytes[prevLen bytes]
 *   | newLen[i32, -1 表示 absent 只有 ROW_DEL]
 *   | newPayload[var]
 * </pre>
 * 所有 op 必须记录 expectedPrevBytes（不允许 null），保证 ABORTING 时 100% 还原到原值。
 */
public final class TxOp {

    /** Op type 枚举（7 种，禁止新增第 8 种直到 P14 VERIFIED CLOSED） */
    public static final byte TYPE_ROW_PUT = 0x01;
    public static final byte TYPE_ROW_DEL = 0x02;
    public static final byte TYPE_COL_PUT = 0x03;
    public static final byte TYPE_GROUP_PUT = 0x04;
    public static final byte TYPE_INDEX_ADD = 0x05;
    public static final byte TYPE_INDEX_REMOVE = 0x06;
    public static final byte TYPE_BLOOM_SET = 0x07;

    /** 分配的 opSeq（由 TxContext.addOp 写入） */
    int opSeq;

    /** type[1B] */
    final byte type;

    /** registry path（不能 null/空） */
    final String registryPath;

    /** expectedPrevBytes（CAS 时校验的旧值，可能 0B（absent），不能 null）。
     * <p>
     * 非 final：同一事务内同 (type, registryPath) 多次 putEntity 合并 TxOp 时，
     * 最后一次 registerRowPut 在 mvccLock sync 内读的 expectedPrevBytes 是最新的 Registry cur bytes，
     * 要替换第一次的旧值，防止 commit applyOpCas 用旧 prev 造成 CAS mismatch（TPCC t04 90% abort 根因）。
     */
    byte[] expectedPrevBytes;

    /** newPayload（新 payload bytes，删除操作为 0B；不能 null）。
     * <p>
     * 非 final：同一事务内同 (type, registryPath) 多次 putEntity 合并 TxOp 时，
     * 保留最后写的 newPayload（最新 bytes）。
     */
    byte[] newPayload;

    TxOp(byte type, String registryPath, byte[] expectedPrevBytes, byte[] newPayload) {
        if (type < TYPE_ROW_PUT || type > TYPE_BLOOM_SET) {
            throw new IllegalArgumentException("unknown TxOp type: " + type);
        }
        this.type = type;
        this.registryPath = Objects.requireNonNull(registryPath, "registryPath null");
        if (this.registryPath.isEmpty()) {
            throw new IllegalArgumentException("registryPath empty");
        }
        this.expectedPrevBytes = Objects.requireNonNull(expectedPrevBytes, "expectedPrevBytes null");
        this.newPayload = Objects.requireNonNull(newPayload, "newPayload null");
    }

    /** 序列化（pack）到 byte[]，对齐 spec FR4 字节布局。 */
    byte[] pack() {
        byte[] pathBytes = registryPath.getBytes(StandardCharsets.UTF_8);
        if (pathBytes.length > 0x0000FFFF) {
            throw new IllegalArgumentException("registryPath too long: " + pathBytes.length);
        }
        boolean prevAbsent = expectedPrevBytes.length == 0;
        int prevLen = prevAbsent ? -1 : expectedPrevBytes.length;
        boolean newAbsent = newPayload.length == 0 && type == TYPE_ROW_DEL;
        int newLen = newAbsent ? -1 : newPayload.length;

        int total = 1 + 2 + pathBytes.length + 4 + expectedPrevBytes.length + 4 + newPayload.length;
        ByteBuffer buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(type);
        buf.putShort((short) pathBytes.length);
        buf.put(pathBytes);
        buf.putInt(prevLen);
        if (!prevAbsent) buf.put(expectedPrevBytes);
        buf.putInt(newLen);
        if (!newAbsent) buf.put(newPayload);
        return buf.array();
    }

    /** 反序列化（unpack）用于崩溃恢复。 */
    static TxOp unpack(byte[] packed) {
        Objects.requireNonNull(packed, "packed null");
        ByteBuffer buf = ByteBuffer.wrap(packed).order(ByteOrder.LITTLE_ENDIAN);
        byte type = buf.get();
        int pathLen = Short.toUnsignedInt(buf.getShort());
        byte[] pathBytes = new byte[pathLen];
        buf.get(pathBytes);
        String registryPath = new String(pathBytes, StandardCharsets.UTF_8);
        int prevLen = buf.getInt();
        byte[] expectedPrevBytes;
        if (prevLen == -1) {
            expectedPrevBytes = new byte[0];
        } else {
            if (prevLen < 0) throw new IllegalArgumentException("prevLen negative: " + prevLen);
            expectedPrevBytes = new byte[prevLen];
            buf.get(expectedPrevBytes);
        }
        int newLen = buf.getInt();
        byte[] newPayload;
        if (newLen == -1) {
            newPayload = new byte[0];
        } else {
            if (newLen < 0) throw new IllegalArgumentException("newLen negative: " + newLen);
            newPayload = new byte[newLen];
            buf.get(newPayload);
        }
        if (buf.remaining() != 0) {
            throw new IllegalArgumentException("packed trailing bytes: remaining=" + buf.remaining());
        }
        return new TxOp(type, registryPath, expectedPrevBytes, newPayload);
    }

    /** expectedPrevBytes / newPayload 都是 defensive copy 构造，引用比较不会误判，内容比对 equals 用 Arrays。 */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TxOp)) return false;
        TxOp txOp = (TxOp) o;
        return type == txOp.type
            && registryPath.equals(txOp.registryPath)
            && Arrays.equals(expectedPrevBytes, txOp.expectedPrevBytes)
            && Arrays.equals(newPayload, txOp.newPayload);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(type, registryPath);
        result = 31 * result + Arrays.hashCode(expectedPrevBytes);
        result = 31 * result + Arrays.hashCode(newPayload);
        return result;
    }
}
