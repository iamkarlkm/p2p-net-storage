package com.q3lives.ds.fs.mft;

import com.q3lives.ds.fs.Ds128Inode;
import com.q3lives.ds.fs.Ds128SuperInode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DsMftInodeTable 固定长度 MFT 主文件分配表测试。
 */
class DsMftInodeTableTest {

    private Path mftDir;
    private DsMftInodeTable mft;

    @BeforeEach
    void setUp() throws IOException {
        mftDir = Files.createTempDirectory("mft_test_");
        mft = new DsMftInodeTable(mftDir, 4);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (mft != null) {
            mft.close();
        }
        if (mftDir != null) {
            Files.walk(mftDir).sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }

    @Test
    void testAllocateAndReadInode() throws IOException {
        long fileId = mft.allocateInode();
        assertEquals(1L, fileId, "first allocated fileId should be 1");

        Ds128Inode inode = new Ds128Inode();
        inode.ref_count = 1;
        inode.i_mode = (short) 0x81A4;
        inode.i_flags = (short) 0x0002;
        inode.data_size = 1024L;
        inode.data_ctime = System.currentTimeMillis();
        inode.data_mtime = System.currentTimeMillis();
        inode.name = new byte[32];
        inode.name[0] = 3;
        inode.name[1] = 'a';
        inode.name[2] = 'b';
        inode.name[3] = 'c';
        inode.inode_parent = 0L;
        inode.bucket_id = 42L;
        inode.inode_ctime = System.currentTimeMillis();
        inode.inode_mtime = System.currentTimeMillis();
        inode.i_acl_id = 0L;
        inode.i_inherited_mgr_id = 100L;
        inode.data_check_sum = 0xDEADBEEFL;

        mft.writeInode(fileId, inode);

        assertTrue(mft.isAllocated(fileId));

        Ds128Inode read = mft.readInode(fileId);
        assertEquals(1, read.ref_count);
        assertEquals((short) 0x81A4, read.i_mode);
        assertEquals((short) 0x0002, read.i_flags);
        assertEquals(1024L, read.data_size);
        assertEquals(42L, read.bucket_id);
        assertEquals(100L, read.i_inherited_mgr_id);
        assertEquals(0xDEADBEEFL, read.data_check_sum);
        assertEquals(3, read.name[0]);
        assertEquals('a', read.name[1]);
    }

    @Test
    void testSuperInodeReadWrite() {
        Ds128SuperInode sup = new Ds128SuperInode();
        sup.i_root_node = 1L;
        sup.mft_size = 4096L;
        sup.block_total = 100L;
        sup.block_size = 4096;
        sup.i_mode = (short) 0x41ED;
        sup.i_flags = (short) 0x0000;
        sup.name = new byte[32];
        sup.name[0] = 4;
        sup.name[1] = 'r';
        sup.name[2] = 'o';
        sup.name[3] = 'o';
        sup.name[4] = 't';
        sup.sn = 12345L;
        sup.inode_ctime = System.currentTimeMillis();
        sup.i_ext_super_mount_nodes = 10L;
        sup.i_ext_super_block_nodes = 20L;
        sup.i_next_mvcc_super_node = 30L;
        sup.i_exec_entry_node = 40L;

        mft.writeSuperInode(sup);

        Ds128SuperInode read = mft.readSuperInode();
        assertEquals(1L, read.i_root_node);
        assertEquals(4096L, read.mft_size);
        assertEquals(100L, read.block_total);
        assertEquals(4096, read.block_size);
        assertEquals((short) 0x41ED, read.i_mode);
        assertEquals(12345L, read.sn);
        assertEquals(10L, read.i_ext_super_mount_nodes);
        assertEquals(20L, read.i_ext_super_block_nodes);
        assertEquals(30L, read.i_next_mvcc_super_node);
        assertEquals(40L, read.i_exec_entry_node);
        assertEquals(4, read.name[0]);
        assertEquals('r', read.name[1]);
    }

    @Test
    void testFreeAndReuse() throws IOException {
        long id1 = mft.allocateInode();
        long id2 = mft.allocateInode();
        long id3 = mft.allocateInode();
        assertEquals(1L, id1);
        assertEquals(2L, id2);
        assertEquals(3L, id3);

        mft.freeInode(id2);
        assertFalse(mft.isAllocated(id2));

        // 复用空闲的 id2
        long reused = mft.allocateInode();
        assertEquals(2L, reused, "freed slot should be reused first");

        // 继续分配应得到 id4
        long id4 = mft.allocateInode();
        assertEquals(4L, id4);
    }

    @Test
    void testCannotAllocateZero() {
        assertThrows(IllegalArgumentException.class, () -> mft.readInode(0L));
        assertThrows(IllegalArgumentException.class, () -> mft.writeInode(0L, new Ds128Inode()));
    }

    @Test
    void testCannotFreeSuperInode() {
        assertThrows(IllegalArgumentException.class, () -> mft.freeInode(0L));
    }

    @Test
    void testExpand() throws IOException {
        // 初始 4 个槽位（slot 0 + 3 个可用）
        assertEquals(4L, mft.getMaxSlots());

        mft.allocateInode(); // 1
        mft.allocateInode(); // 2
        mft.allocateInode(); // 3

        // 第 4 个需要扩容（nextSeqId=4 >= maxSlots=4）
        long id4 = mft.allocateInode();
        assertEquals(4L, id4);
        assertTrue(mft.getMaxSlots() >= 4L, "maxSlots should be at least 4 after expand");

        // 验证扩容后数据未丢失
        assertTrue(mft.isAllocated(1L));
        assertTrue(mft.isAllocated(2L));
        assertTrue(mft.isAllocated(3L));
    }

    @Test
    void testClearSlotOnAllocate() throws IOException {
        long id = mft.allocateInode();
        Ds128Inode inode = new Ds128Inode();
        inode.ref_count = 5;
        inode.data_size = 999L;
        mft.writeInode(id, inode);

        mft.freeInode(id);
        assertFalse(mft.isAllocated(id));

        long reused = mft.allocateInode();
        assertEquals(id, reused);

        Ds128Inode fresh = mft.readInode(reused);
        assertEquals(1, fresh.ref_count); // allocateInode 默认设置 ref_count=1
        assertEquals(0L, fresh.data_size); // 其他字段应被清零
    }

    @Test
    void testOffsetCalculation() throws IOException {
        // slot 0 = SuperInode 在 offset 0
        Ds128SuperInode sup = mft.readSuperInode();
        assertNotNull(sup);
        assertTrue(java.util.Arrays.equals(sup.magic, com.q3lives.ds.constant.DsConstant.DS_VERSION));

        // slot 1 在 offset 128，slot 2 在 offset 256
        long id1 = mft.allocateInode();
        long id2 = mft.allocateInode();
        assertEquals(1L, id1);
        assertEquals(2L, id2);

        // 写入不同 slot 的数据不应互相覆盖
        Ds128Inode inode1 = new Ds128Inode();
        inode1.data_size = 111L;
        mft.writeInode(id1, inode1);

        Ds128Inode inode2 = new Ds128Inode();
        inode2.data_size = 222L;
        mft.writeInode(id2, inode2);

        assertEquals(111L, mft.readInode(id1).data_size);
        assertEquals(222L, mft.readInode(id2).data_size);
    }
}
