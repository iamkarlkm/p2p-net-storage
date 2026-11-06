package com.q3lives.ds.registry;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.q3lives.ds.bucket.DsTypeFixedBucketStore;
import com.q3lives.ds.enums.DataType;

/**
 * DsRegistry 的 DIR/LEAF 节点元数据（64 bytes 定长 meta block，fit 64B bucket power=6）。
 *
 * <p>序列化格式（64B 总长度 = 恰好 1 个 64B bucket slot，零分配）：
 * <pre>
 * byte  0    : flag7=isDirectory(1=DIR,0=LEAF); [6:0]=reserved(0)
 * byte  1-2  : keyDataTypeCode (short BE, DataType.code)
 * byte  3-4  : valueDataTypeCode (short BE, DataType.code)
 * byte  5-7  : reserved (0)
 * byte  8-15 : keyPathId (long LE, DsTypeString typedId of "/a/b/c")
 * byte 16-23 : parentDirNodeId (long BE, 0 = 无父/根节点保留 marker，实际 root.dirNodeId=1)
 * byte 24-31 : siblingOrder (long BE，目录列表排序键，默认=0)
 * byte 32-39 : valueOrValueId (long BE)
 *   - NUMERIC/<=64bit value: 直接内联，解释=valueRaw(整数直接写/double=Double.doubleToLongBits)
 *   - BINARY/STRING/COMPLEX >64bit: 存放 typedId 指针（valueStrings 或 valueBinaries 的 16/8/40 typedId）
 * byte 40-47 : childRingFileId (long BE，DIR 节点才有效；LEAF 节点=0)
 * byte 48-55 : timestamp (long BE = System.currentTimeMillis()，写盘时才更新，读不用)
 * byte 56-63 : checksum (long BE，保留=0，暂不计算校验和，遵循"无 bug 证据不加校验"原则)
 * </pre>
 *
 * <p>字节序：keyPathId 用 LE（因为 DsTypeString 返回的 typedId 是 Java 原生 long，write 端一致即可）
 * 其余所有 short/long 用 BIG-ENDIAN（符合网络/文件常规，跨平台一致）。总长度 64B。
 */
public final class DsRegistryNodeSerializer {

    public static final int NODE_BLOCK_BYTES = 64;
    public static final int POWER_NODE_BLOCK = 6; // 2^6 = 64B，恒定分配
    static final String META_SPACE = "reg_meta";
    static final String META_TYPE  = "nodes";

    public static final class NodeMeta {
        public final boolean isDirectory;
        public final int     keyDataTypeCode;
        public final int     valueDataTypeCode;
        public final long    keyPathId;
        public final long    parentDirNodeId;
        public final long    siblingOrder;
        public final long    valueOrValueId;
        public final long    childRingFileId;
        public final long    timestamp;

            public NodeMeta(boolean isDirectory, int keyDataTypeCode, int valueDataTypeCode,
                 long keyPathId, long parentDirNodeId, long siblingOrder,
                 long valueOrValueId, long childRingFileId, long timestamp) {
            this.isDirectory = isDirectory;
            this.keyDataTypeCode = keyDataTypeCode;
            this.valueDataTypeCode = valueDataTypeCode;
            this.keyPathId = keyPathId;
            this.parentDirNodeId = parentDirNodeId;
            this.siblingOrder = siblingOrder;
            this.valueOrValueId = valueOrValueId;
            this.childRingFileId = childRingFileId;
            this.timestamp = timestamp;
        }

        public DataType keyDataType() {
            return DataType.getByCode(keyDataTypeCode).orElse(DataType.REG_REGISTRY_DIR);
        }

        public DataType valueDataType() {
            return DataType.getByCode(valueDataTypeCode).orElse(DataType.REG_BINARY);
        }
    }

    public static long allocateNodeId(DsTypeFixedBucketStore store, boolean isDirectory) throws IOException {
        return store.getNewIdByPower(META_SPACE, META_TYPE, POWER_NODE_BLOCK);
    }

    public static byte[] serialize(NodeMeta m) {
        ByteBuffer buf = ByteBuffer.allocate(NODE_BLOCK_BYTES).order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) (m.isDirectory ? (byte)0x80 : (byte)0x00));
        buf.putShort((short) (m.keyDataTypeCode & 0xFFFF));
        buf.putShort((short) (m.valueDataTypeCode & 0xFFFF));
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.putLong(m.keyPathId);
        buf.putLong(m.parentDirNodeId);
        buf.putLong(m.siblingOrder);
        buf.putLong(m.valueOrValueId);
        buf.putLong(m.childRingFileId);
        buf.putLong(m.timestamp);
        buf.putLong(0L); // checksum 暂=0
        return buf.array();
    }

    public static NodeMeta deserialize(byte[] b) {
        if (b == null || b.length < NODE_BLOCK_BYTES) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(b, 0, NODE_BLOCK_BYTES).order(ByteOrder.BIG_ENDIAN);
        byte flags = buf.get();
        boolean isDir = (flags & 0x80) != 0;
        int keyCode = buf.getShort() & 0xFFFF;
        int valCode = buf.getShort() & 0xFFFF;
        buf.get(); buf.get(); buf.get(); // reserved
        long keyPathId = buf.getLong();
        long parent   = buf.getLong();
        long sibling  = buf.getLong();
        long valOrPtr = buf.getLong();
        long ring     = buf.getLong();
        long ts       = buf.getLong();
        return new NodeMeta(isDir, keyCode, valCode, keyPathId, parent, sibling, valOrPtr, ring, ts);
    }

    public static void writeNode(DsTypeFixedBucketStore store, long nodeId, NodeMeta meta) throws IOException {
        store.overwrite(META_SPACE, META_TYPE, nodeId, serialize(meta));
    }

    public static NodeMeta readNode(DsTypeFixedBucketStore store, long nodeId) throws IOException {
        byte[] b = store.get(META_SPACE, META_TYPE, nodeId, NODE_BLOCK_BYTES);
        if (b == null || b.length < NODE_BLOCK_BYTES) return null;
        boolean allZero = true;
        for (int i = 0; i < b.length; i++) {
            if (b[i] != 0) { allZero = false; break; }
        }
        return allZero ? null : deserialize(b);
    }
}
