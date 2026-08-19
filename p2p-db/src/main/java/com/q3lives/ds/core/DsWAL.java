package com.q3lives.ds.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

/**
 * 单实例 Block+Row 级 WAL（Write-Ahead Log）。
 *
 * <p>严格架构边界（与已交付 DsBinlog 正交、职责不重叠）：
 * <ul>
 *   <li>本 DsWAL = 单实例本机 crash recovery：写 {@code dataFile.getName()+"_wal.log"}，
 *       职责 = 本机断电/进程 kill 导致的 64KB 块半写恢复，<b>只管本机</b>。</li>
 *   <li>已交付 DsBinlog（§四 row#10）= 跨节点行级事件交换：写
 *       {@code dsbinlog_<dayKey>_fixed.dat/_dyn.dat + DsBytes} 去重，<b>跨 serverId 多节点同步，不管本机 crash</b>。</li>
 *   <li><b>严格两阶段提交顺序（强制）</b>：WAL fsync → dataFile 写盘 → binlog append，绝不反过来。</li>
 * </ul>
 *
 * <p>帧格式（对齐 FileStoreIdRegistry entry 帧同构、宁停不越截断策略）：
 * <pre>
 *   ENTRY_HEAD = 32B（SSD 页友好，8B 对齐）：
 *     0..3   MAGIC        0x57414C42 ("WALB" = WAL Block)
 *     4..7   VERSION      int32 = 1
 *     8..11  blockIndex   int32  DsMemory bufferIndex（对应 dataFile 内 i*BLOCK_SIZE 偏移）
 *    12..15  payloadLen   int32  恒 = BLOCK_SIZE=65536 for Block 级；预留 &lt;65536 for Row/Txn 批量
 *    16..19  sequence     int32  单调递增序号，乱序检测；默认 = 0 不启用
 *    20..23  flags        int32  bit0=TXN_START bit1=TXN_END（预留 Txn 批量包装后续用）
 *    24..27  reserved1    int32
 *    28..31  reserved2    int32
 *
 *   PAYLOAD：payloadLen 字节（65536B for Block 级 / 可变 for Row/Txn 级）
 *
 *   TAIL_CRC32：4B，IEEE 0xEDB88320 查表，calc 范围 = ENTRY_HEAD[0..31] + PAYLOAD[0..payloadLen)
 *     写：HEAD+PAYLOAD 填好 → calc → 写 TAIL → fsync
 *     回放：读 HEAD(MAGIC/VERSION 校验) → 读 PAYLOAD → calc(HEAD+PAYLOAD) vs TAIL → 任一失败 break 宁停不越
 * </pre>
 */
public class DsWAL implements AutoCloseable {

    public static final int MAGIC = 0x57414C42;
    public static final int VERSION = 1;

    public static final int ENTRY_HEAD_SIZE = 32;
    public static final int OFF_MAGIC = 0;
    public static final int OFF_VERSION = 4;
    public static final int OFF_BLOCK_INDEX = 8;
    public static final int OFF_PAYLOAD_LEN = 12;
    public static final int OFF_SEQUENCE = 16;
    public static final int OFF_FLAGS = 20;
    public static final int OFF_RESERVED1 = 24;
    public static final int OFF_RESERVED2 = 28;

    public static final int TAIL_CRC32_LEN = 4;

    public static final int FLAG_TXN_START = 1 << 0;
    public static final int FLAG_TXN_END   = 1 << 1;

    private static final int[] CRC32_TABLE = buildCrc32Table();

    private static int[] buildCrc32Table() {
        int[] table = new int[256];
        for (int i = 0; i < 256; i++) {
            int c = i;
            for (int j = 0; j < 8; j++) {
                c = (c & 1) != 0 ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
            }
            table[i] = c;
        }
        return table;
    }

    static int crc32(byte[] b, int off, int len) {
        int c = 0xFFFFFFFF;
        for (int i = off, end = off + len; i < end; i++) {
            c = CRC32_TABLE[(c ^ (b[i] & 0xFF)) & 0xFF] ^ (c >>> 8);
        }
        return c ^ 0xFFFFFFFF;
    }

    private final File walFile;
    private RandomAccessFile raf;
    private int nextSequence;

    public DsWAL(File dataFile) {
        if (dataFile == null) {
            this.walFile = null;
            this.raf = null;
            this.nextSequence = 0;
            return;
        }
        File parent = dataFile.getParentFile();
        String walName = dataFile.getName() + "_wal.log";
        this.walFile = parent == null ? new File(walName) : new File(parent, walName);
        this.nextSequence = 0;
        open();
    }

    private void open() {
        if (walFile == null) return;
        try {
            if (walFile.getParentFile() != null && !walFile.getParentFile().exists()) {
                walFile.getParentFile().mkdirs();
            }
            this.raf = new RandomAccessFile(walFile, "rw");
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to open WAL file: " + walFile, ex);
        }
    }

    public synchronized void appendBlockEntry(int blockIndex, byte[] block) throws IOException {
        appendBlockEntry(blockIndex, block, 0);
    }

    public synchronized void appendBlockEntry(int blockIndex, byte[] block, int flags) throws IOException {
        if (raf == null) return;
        if (block == null || block.length != DsMemory.BLOCK_SIZE) {
            throw new IllegalArgumentException("block must be BLOCK_SIZE=" + DsMemory.BLOCK_SIZE + " bytes");
        }
        int payloadLen = DsMemory.BLOCK_SIZE;
        int seq = nextSequence++;
        byte[] head = new byte[ENTRY_HEAD_SIZE];
        ByteBuffer hb = ByteBuffer.wrap(head);
        hb.putInt(OFF_MAGIC, MAGIC);
        hb.putInt(OFF_VERSION, VERSION);
        hb.putInt(OFF_BLOCK_INDEX, blockIndex);
        hb.putInt(OFF_PAYLOAD_LEN, payloadLen);
        hb.putInt(OFF_SEQUENCE, seq);
        hb.putInt(OFF_FLAGS, flags);
        hb.putInt(OFF_RESERVED1, 0);
        hb.putInt(OFF_RESERVED2, 0);

        int totalEntry = ENTRY_HEAD_SIZE + payloadLen + TAIL_CRC32_LEN;
        byte[] fullEntry = new byte[totalEntry];
        System.arraycopy(head, 0, fullEntry, 0, ENTRY_HEAD_SIZE);
        System.arraycopy(block, 0, fullEntry, ENTRY_HEAD_SIZE, payloadLen);

        int crc = crc32(fullEntry, 0, ENTRY_HEAD_SIZE + payloadLen);
        ByteBuffer.wrap(fullEntry).putInt(ENTRY_HEAD_SIZE + payloadLen, crc);

        raf.seek(raf.length());
        raf.write(fullEntry);
    }

    public synchronized void fsync() throws IOException {
        if (raf == null) return;
        raf.getChannel().force(true);
    }

    public synchronized void truncate() throws IOException {
        if (raf == null) return;
        raf.setLength(0);
        raf.getChannel().force(true);
        nextSequence = 0;
    }

    public synchronized int replayAll(List<byte[]> dataBytes, DsMemory owner) throws IOException {
        if (raf == null || walFile == null || !walFile.exists()) return 0;
        long flen = raf.length();
        if (flen < ENTRY_HEAD_SIZE + TAIL_CRC32_LEN) return 0;
        raf.seek(0);
        int applied = 0;
        long pos = 0L;
        while (pos + ENTRY_HEAD_SIZE <= flen) {
            byte[] head = new byte[ENTRY_HEAD_SIZE];
            raf.readFully(head, 0, ENTRY_HEAD_SIZE);
            ByteBuffer hb = ByteBuffer.wrap(head);
            int magic = hb.getInt(OFF_MAGIC);
            if (magic != MAGIC) {
                break;
            }
            int version = hb.getInt(OFF_VERSION);
            if (version != VERSION) {
                break;
            }
            int blockIndex = hb.getInt(OFF_BLOCK_INDEX);
            int payloadLen = hb.getInt(OFF_PAYLOAD_LEN);
            if (payloadLen < 0 || payloadLen > DsMemory.BLOCK_SIZE * 2) {
                break;
            }
            long need = pos + ENTRY_HEAD_SIZE + payloadLen + TAIL_CRC32_LEN;
            if (need > flen) {
                break;
            }
            byte[] payload = new byte[payloadLen];
            raf.readFully(payload, 0, payloadLen);
            byte[] tail = new byte[TAIL_CRC32_LEN];
            raf.readFully(tail, 0, TAIL_CRC32_LEN);
            int storedCrc = ByteBuffer.wrap(tail).getInt(0);

            int fullLen = ENTRY_HEAD_SIZE + payloadLen;
            byte[] calcBuf = new byte[fullLen];
            System.arraycopy(head, 0, calcBuf, 0, ENTRY_HEAD_SIZE);
            System.arraycopy(payload, 0, calcBuf, ENTRY_HEAD_SIZE, payloadLen);
            int calcCrc = crc32(calcBuf, 0, fullLen);
            if (calcCrc != storedCrc) {
                break;
            }

            while (dataBytes.size() <= blockIndex) {
                dataBytes.add(new byte[DsMemory.BLOCK_SIZE]);
            }
            byte[] dest = dataBytes.get(blockIndex);
            if (dest == null || dest.length != payloadLen) {
                dest = new byte[DsMemory.BLOCK_SIZE];
                dataBytes.set(blockIndex, dest);
            }
            System.arraycopy(payload, 0, dest, 0, Math.min(payloadLen, dest.length));
            if (owner != null) {
                owner.dirtyBufferIndices.add(blockIndex);
                if (blockIndex > owner.highestBufferIndexEverSeen) {
                    owner.highestBufferIndexEverSeen = blockIndex;
                }
            }
            applied++;
            pos = need;
        }
        return applied;
    }

    public boolean isOpen() {
        return raf != null;
    }

    public File getWalFile() {
        return walFile;
    }

    @Override
    public synchronized void close() throws IOException {
        if (raf != null) {
            try {
                raf.getChannel().force(true);
            } finally {
                raf.close();
                raf = null;
            }
        }
    }

    public static void forceResetForTest(File dataFile) {
        if (dataFile == null) return;
        File parent = dataFile.getParentFile();
        String walName = dataFile.getName() + "_wal.log";
        File wal = parent == null ? new File(walName) : new File(parent, walName);
        if (wal.exists()) {
            wal.delete();
        }
    }
}
