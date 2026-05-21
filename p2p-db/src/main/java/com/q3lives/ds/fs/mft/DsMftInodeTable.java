package com.q3lives.ds.fs.mft;

import com.q3lives.ds.collections.DsHashSet;
import com.q3lives.ds.constant.DsConstant;
import com.q3lives.ds.fs.Ds128Inode;
import com.q3lives.ds.fs.Ds128SuperInode;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 固定 128 字节槽位的 MFT（Master File Table）主文件分配表。
 *
 * <p>存储布局（纯 slot 数组，无独立头部）：</p>
 * <pre>
 * offset 0..127:    slot 0 = Ds128SuperInode（超级块，inode == 0）
 * offset 128..255:  slot 1 = Ds128Inode #1
 * offset 256..383:  slot 2 = Ds128Inode #2
 * ...
 * offset (n * 128): slot n = Ds128Inode #n
 * </pre>
 *
 * <p>核心约定：</p>
 * <ul>
 *   <li>fileId 即数组下标，O(1) 寻址：{@code offset = fileId * SLOT_SIZE}</li>
 *   <li>slot 0 固定为 SuperInode，{@link #allocateInode()} 永远不会返回 0</li>
 *   <li>空闲 fileId 由独立的 {@link DsHashSet} 管理，不复用 inode 内部字段</li>
 *   <li>nextSeqId 持久化在 SuperInode 的 {@code sn} 字段中</li>
 *   <li>maxSlots 通过文件大小实时计算：{@code fileSize / SLOT_SIZE}</li>
 *   <li>支持动态扩容：文件大小翻倍，unmap 旧 buffer 后重新 map</li>
 * </ul>
 */
public final class DsMftInodeTable implements AutoCloseable {

    public static final int SLOT_SIZE = 128;

    // ========== Inode 字段偏移（slot 内） ==========
    private static final int INO_OFF_REF_COUNT = 0;
    private static final int INO_OFF_I_MODE = 4;
    private static final int INO_OFF_I_FLAGS = 6;
    private static final int INO_OFF_DATA_SIZE = 8;
    private static final int INO_OFF_DATA_CTIME = 16;
    private static final int INO_OFF_DATA_MTIME = 24;
    private static final int INO_OFF_NAME = 32;
    private static final int INO_OFF_INODE_PARENT = 64;
    private static final int INO_OFF_BUCKET_ID = 72;
    private static final int INO_OFF_INODE_CTIME = 80;
    private static final int INO_OFF_INODE_MTIME = 88;
    private static final int INO_OFF_I_ACL_ID = 96;
    private static final int INO_OFF_I_INHERITED_MGR_ID = 104;
    private static final int INO_OFF_DATA_CHECK_SUM = 112;
    private static final int INO_OFF_RESERVED = 120;

    // ========== SuperInode 字段偏移（slot 0 内） ==========
    private static final int SUP_OFF_MAGIC = 0;
    private static final int SUP_OFF_I_ROOT_NODE = 8;
    private static final int SUP_OFF_MFT_SIZE = 16;
    private static final int SUP_OFF_BLOCK_TOTAL = 24;
    private static final int SUP_OFF_BLOCK_SIZE = 32;
    private static final int SUP_OFF_I_MODE = 36;
    private static final int SUP_OFF_I_FLAGS = 38;
    private static final int SUP_OFF_NAME = 40;
    private static final int SUP_OFF_SN = 72;
    private static final int SUP_OFF_INODE_CTIME = 80;
    private static final int SUP_OFF_INODE_MTIME = 88;
    private static final int SUP_OFF_I_EXT_SUPER_MOUNT_NODES = 96;
    private static final int SUP_OFF_I_EXT_SUPER_BLOCK_NODES = 104;
    private static final int SUP_OFF_I_NEXT_MVCC_SUPER_NODE = 112;
    private static final int SUP_OFF_I_EXEC_ENTRY_NODE = 120;

    private final Path mftDir;
    private final Path mftFilePath;
    private final FileChannel channel;
    private MappedByteBuffer buffer;
    private final DsHashSet freeIds;
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * 打开或创建 MFT 存储目录。
     *
     * <p>在 {@code mftDir} 目录下自动管理以下文件：</p>
     * <ul>
     *   <li>{@code mft.dat} — MFT 主文件表（固定 128 字节 slot 数组）</li>
     *   <li>{@code free_file_ids.set} — 空闲 fileId 集合（DsHashSet）</li>
     * </ul>
     *
     * @param mftDir       MFT 存储目录路径
     * @param initialSlots 初始槽位数（至少为 1，包含 slot 0 的 SuperInode）
     */
    public DsMftInodeTable(Path mftDir, int initialSlots) throws IOException {
        this.mftDir = mftDir.toAbsolutePath().normalize();
        java.nio.file.Files.createDirectories(this.mftDir);
        this.mftFilePath = this.mftDir.resolve("mft.dat");
        Path freeIdsFilePath = this.mftDir.resolve("free_file_ids.set");
        this.freeIds = new DsHashSet(freeIdsFilePath.toFile());
        boolean newFile = !mftFilePath.toFile().exists() || mftFilePath.toFile().length() == 0;
        this.channel = FileChannel.open(mftFilePath,
                StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE);
        if (newFile) {
            int slots = Math.max(initialSlots, 1);
            long fileSize = (long) slots * SLOT_SIZE;
            initFile(fileSize);
        } else {
            long fileSize = channel.size();
            this.buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, fileSize);
            if (!checkMagic()) {
                close();
                throw new IOException("Invalid MFT SuperInode magic in " + mftFilePath);
            }
        }
    }

    // ================== 分配与释放 ==================

    /**
     * 分配一个空闲的 inode slot，返回 fileId（永远不会返回 0）。
     *
     * <p>优先从 {@link #freeIds} 集合复用；没有空闲时顺序分配新 slot，必要时触发扩容。</p>
     */
    public long allocateInode() throws IOException {
        lock.lock();
        try {
            Long freeId = freeIds.first();
            if (freeId != null) {
                freeIds.remove(freeId.longValue());
                clearSlot(freeId);
                buffer.putInt(slotOffset(freeId) + INO_OFF_REF_COUNT, 1);
                return freeId;
            }

            long nextSeqId = readNextSeqId();
            long maxSlots = getMaxSlots();
            if (nextSeqId >= maxSlots) {
                expand();
                maxSlots = getMaxSlots();
            }
            long fileId = nextSeqId;
            writeNextSeqId(nextSeqId + 1);
            clearSlot(fileId);
            buffer.putInt(slotOffset(fileId) + INO_OFF_REF_COUNT, 1);
            return fileId;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 释放指定的 inode slot，将其加入空闲集合。
     *
     * @param fileId 要释放的 fileId（必须 &gt; 0，slot 0 SuperInode 不允许释放）
     */
    public void freeInode(long fileId) throws IOException {
        if (fileId <= 0) {
            throw new IllegalArgumentException("cannot free SuperInode (fileId=0) or invalid fileId: " + fileId);
        }
        lock.lock();
        try {
            clearSlot(fileId);
            freeIds.add(fileId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 判断指定 fileId 是否已分配（ref_count &gt; 0）。
     */
    public boolean isAllocated(long fileId) {
        if (fileId < 0 || fileId >= getMaxSlots()) {
            return false;
        }
        return buffer.getInt(slotOffset(fileId) + INO_OFF_REF_COUNT) > 0;
    }

    // ================== Inode 读写 ==================

    /**
     * 读取指定 fileId 的 Ds128Inode。
     */
    public Ds128Inode readInode(long fileId) {
        if (fileId <= 0) {
            throw new IllegalArgumentException("use readSuperInode() for slot 0");
        }
        int off = slotOffset(fileId);
        Ds128Inode inode = new Ds128Inode();
        inode.ref_count = buffer.getInt(off + INO_OFF_REF_COUNT);
        inode.i_mode = buffer.getShort(off + INO_OFF_I_MODE);
        inode.i_flags = buffer.getShort(off + INO_OFF_I_FLAGS);
        inode.data_size = buffer.getLong(off + INO_OFF_DATA_SIZE);
        inode.data_ctime = buffer.getLong(off + INO_OFF_DATA_CTIME);
        inode.data_mtime = buffer.getLong(off + INO_OFF_DATA_MTIME);
        inode.name = new byte[32];
        for (int i = 0; i < 32; i++) {
            inode.name[i] = buffer.get(off + INO_OFF_NAME + i);
        }
        inode.inode_parent = buffer.getLong(off + INO_OFF_INODE_PARENT);
        inode.bucket_id = buffer.getLong(off + INO_OFF_BUCKET_ID);
        inode.inode_ctime = buffer.getLong(off + INO_OFF_INODE_CTIME);
        inode.inode_mtime = buffer.getLong(off + INO_OFF_INODE_MTIME);
        inode.i_acl_id = buffer.getLong(off + INO_OFF_I_ACL_ID);
        inode.i_inherited_mgr_id = buffer.getLong(off + INO_OFF_I_INHERITED_MGR_ID);
        inode.data_check_sum = buffer.getLong(off + INO_OFF_DATA_CHECK_SUM);
        return inode;
    }

    /**
     * 将 Ds128Inode 写入指定 fileId 的 slot。
     */
    public void writeInode(long fileId, Ds128Inode inode) {
        if (fileId <= 0) {
            throw new IllegalArgumentException("use writeSuperInode() for slot 0");
        }
        if (inode == null) {
            throw new IllegalArgumentException("inode is null");
        }
        int off = slotOffset(fileId);
        buffer.putInt(off + INO_OFF_REF_COUNT, inode.ref_count);
        buffer.putShort(off + INO_OFF_I_MODE, inode.i_mode);
        buffer.putShort(off + INO_OFF_I_FLAGS, inode.i_flags);
        buffer.putLong(off + INO_OFF_DATA_SIZE, inode.data_size);
        buffer.putLong(off + INO_OFF_DATA_CTIME, inode.data_ctime);
        buffer.putLong(off + INO_OFF_DATA_MTIME, inode.data_mtime);
        byte[] name = inode.name != null ? inode.name : new byte[32];
        for (int i = 0; i < 32; i++) {
            buffer.put(off + INO_OFF_NAME + i, i < name.length ? name[i] : 0);
        }
        buffer.putLong(off + INO_OFF_INODE_PARENT, inode.inode_parent);
        buffer.putLong(off + INO_OFF_BUCKET_ID, inode.bucket_id);
        buffer.putLong(off + INO_OFF_INODE_CTIME, inode.inode_ctime);
        buffer.putLong(off + INO_OFF_INODE_MTIME, inode.inode_mtime);
        buffer.putLong(off + INO_OFF_I_ACL_ID, inode.i_acl_id);
        buffer.putLong(off + INO_OFF_I_INHERITED_MGR_ID, inode.i_inherited_mgr_id);
        buffer.putLong(off + INO_OFF_DATA_CHECK_SUM, inode.data_check_sum);
    }

    // ================== SuperInode 读写 ==================

    /**
     * 读取 slot 0 的 Ds128SuperInode。
     */
    public Ds128SuperInode readSuperInode() {
        int off = slotOffset(0L);
        Ds128SuperInode sup = new Ds128SuperInode();
        buffer.get(off + SUP_OFF_MAGIC, sup.magic, 0, 8);
        sup.i_root_node = buffer.getLong(off + SUP_OFF_I_ROOT_NODE);
        sup.mft_size = buffer.getLong(off + SUP_OFF_MFT_SIZE);
        sup.block_total = buffer.getLong(off + SUP_OFF_BLOCK_TOTAL);
        sup.block_size = buffer.getInt(off + SUP_OFF_BLOCK_SIZE);
        sup.i_mode = buffer.getShort(off + SUP_OFF_I_MODE);
        sup.i_flags = buffer.getShort(off + SUP_OFF_I_FLAGS);
        sup.name = new byte[32];
        for (int i = 0; i < 32; i++) {
            sup.name[i] = buffer.get(off + SUP_OFF_NAME + i);
        }
        sup.sn = buffer.getLong(off + SUP_OFF_SN);
        sup.inode_ctime = buffer.getLong(off + SUP_OFF_INODE_CTIME);
        sup.i_ext_super_mount_nodes = buffer.getLong(off + SUP_OFF_I_EXT_SUPER_MOUNT_NODES);
        sup.i_ext_super_block_nodes = buffer.getLong(off + SUP_OFF_I_EXT_SUPER_BLOCK_NODES);
        sup.i_next_mvcc_super_node = buffer.getLong(off + SUP_OFF_I_NEXT_MVCC_SUPER_NODE);
        sup.i_exec_entry_node = buffer.getLong(off + SUP_OFF_I_EXEC_ENTRY_NODE);
        return sup;
    }

    /**
     * 将 Ds128SuperInode 写入 slot 0。
     */
    public void writeSuperInode(Ds128SuperInode sup) {
        if (sup == null) {
            throw new IllegalArgumentException("superInode is null");
        }
        int off = slotOffset(0L);
        buffer.put(off + SUP_OFF_MAGIC, sup.magic, 0, 8);
        buffer.putLong(off + SUP_OFF_I_ROOT_NODE, sup.i_root_node);
        buffer.putLong(off + SUP_OFF_MFT_SIZE, sup.mft_size);
        buffer.putLong(off + SUP_OFF_BLOCK_TOTAL, sup.block_total);
        buffer.putInt(off + SUP_OFF_BLOCK_SIZE, sup.block_size);
        buffer.putShort(off + SUP_OFF_I_MODE, sup.i_mode);
        buffer.putShort(off + SUP_OFF_I_FLAGS, sup.i_flags);
        byte[] name = sup.name != null ? sup.name : new byte[32];
        for (int i = 0; i < 32; i++) {
            buffer.put(off + SUP_OFF_NAME + i, i < name.length ? name[i] : 0);
        }
        buffer.putLong(off + SUP_OFF_SN, sup.sn);
        buffer.putLong(off + SUP_OFF_INODE_CTIME, sup.inode_ctime);
        // Ds128SuperInode 缺少 inode_mtime 字段，写入 0 占位以保持 128 字节对齐
        buffer.putLong(off + SUP_OFF_INODE_MTIME, 0L);
        buffer.putLong(off + SUP_OFF_I_EXT_SUPER_MOUNT_NODES, sup.i_ext_super_mount_nodes);
        buffer.putLong(off + SUP_OFF_I_EXT_SUPER_BLOCK_NODES, sup.i_ext_super_block_nodes);
        buffer.putLong(off + SUP_OFF_I_NEXT_MVCC_SUPER_NODE, sup.i_next_mvcc_super_node);
        buffer.putLong(off + SUP_OFF_I_EXEC_ENTRY_NODE, sup.i_exec_entry_node);
    }

    // ================== 容量查询 ==================

    public long getMaxSlots() {
        try {
            return channel.size() / SLOT_SIZE;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public long getNextSeqId() {
        return readNextSeqId();
    }

    // ================== 内部方法 ==================

    private void initFile(long fileSize) throws IOException {
        // FileChannel.truncate 不能扩展文件，通过 write 零字节扩展
        long currentSize = channel.size();
        if (fileSize > currentSize) {
            channel.position(currentSize);
            long remain = fileSize - currentSize;
            byte[] zeros = new byte[(int) Math.min(remain, 8192)];
            while (remain > 0) {
                int chunk = (int) Math.min(remain, zeros.length);
                channel.write(java.nio.ByteBuffer.wrap(zeros, 0, chunk));
                remain -= chunk;
            }
        }
        this.buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, fileSize);

        // 初始化 SuperInode：magic + sn = nextSeqId(从 1 开始)
        Ds128SuperInode sup = new Ds128SuperInode();
        sup.magic = DsConstant.DS_VERSION;
        sup.sn = 1L;
        writeSuperInode(sup);
    }

    private boolean checkMagic() {
        byte[] magic = new byte[8];
        buffer.get(slotOffset(0L) + SUP_OFF_MAGIC, magic, 0, 8);
        return java.util.Arrays.equals(magic, DsConstant.DS_VERSION);
    }

    private long readNextSeqId() {
        return buffer.getLong(slotOffset(0L) + SUP_OFF_SN);
    }

    private void writeNextSeqId(long nextSeqId) {
        buffer.putLong(slotOffset(0L) + SUP_OFF_SN, nextSeqId);
    }

    private static int slotOffset(long fileId) {
        return (int) (fileId * SLOT_SIZE);
    }

    private void clearSlot(long fileId) {
        int off = slotOffset(fileId);
        for (int i = 0; i < SLOT_SIZE; i++) {
            buffer.put(off + i, (byte) 0);
        }
    }

    private void expand() throws IOException {
        long oldMaxSlots = getMaxSlots();
        long newMaxSlots = oldMaxSlots * 2;
        long newSize = newMaxSlots * SLOT_SIZE;

        unmap(buffer);
        buffer = null;

        long currentSize = channel.size();
        if (newSize > currentSize) {
            channel.position(currentSize);
            long remain = newSize - currentSize;
            byte[] zeros = new byte[(int) Math.min(remain, 8192)];
            while (remain > 0) {
                int chunk = (int) Math.min(remain, zeros.length);
                channel.write(java.nio.ByteBuffer.wrap(zeros, 0, chunk));
                remain -= chunk;
            }
        }
        this.buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, newSize);
    }

    private static void unmap(MappedByteBuffer buffer) {
        if (buffer == null) {
            return;
        }
        try {
            Method unmapper = MappedByteBuffer.class.getDeclaredMethod("unmapper");
            unmapper.setAccessible(true);
            Object unmapObj = unmapper.invoke(buffer);
            if (unmapObj != null) {
                Method unmap = unmapObj.getClass().getMethod("unmap");
                unmap.invoke(unmapObj);
            }
            return;
        } catch (Throwable ignored) {
        }
        try {
            Class<?> uc = Class.forName("sun.misc.Unsafe");
            Method gf = uc.getDeclaredMethod("getUnsafe");
            gf.setAccessible(true);
            Object unsafe = gf.invoke(null);
            Method invokeCleaner = uc.getMethod("invokeCleaner", java.nio.ByteBuffer.class);
            invokeCleaner.invoke(unsafe, buffer);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void close() throws IOException {
        lock.lock();
        try {
            if (buffer != null) {
                buffer.force();
                unmap(buffer);
                buffer = null;
            }
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
            if (freeIds != null) {
                freeIds.close();
            }
        } finally {
            lock.unlock();
        }
    }
}
