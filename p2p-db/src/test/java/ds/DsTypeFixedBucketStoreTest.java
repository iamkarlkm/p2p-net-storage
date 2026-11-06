package ds;

import java.io.File;
import java.util.Arrays;
import java.util.Random;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.bucket.DsFixedBucketStore;
import com.q3lives.ds.bucket.DsTypeFixedBucketStore;
import com.q3lives.ds.util.MultiTypeDataId;

/**
 * 三段式 ID 工具类 + DsTypeFixedBucketStore 功能测试：
 *  1) 位编解码（全字段边界、最大最小值 / overflow clamp / isValid）
 *  2) DsTypeFixedBucketStore put/get/remove；不同 TypeID 物理隔离（子目录独立）
 *  3) 同一 rootDir 下 plain DsFixedBucketStore 与 DsTypeFixedBucketStore 可安全共存
 *  4) 批量 getNewIdsByPower typedId 全一致
 *  5) 跨 TypeID 读：store(typeId=B).get(type_A_id) 读到全 0（物理分离）
 */
public class DsTypeFixedBucketStoreTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    // ============ 1. MultiTypeDataId 纯位运算测试 ============

    @Test
    public void testIdCodec_boundaries() {
        Assert.assertEquals(48, MultiTypeDataId.TYPE_SHIFT);
        Assert.assertEquals(40, MultiTypeDataId.POWER_SHIFT);
        Assert.assertEquals(0xFFFFL, MultiTypeDataId.TYPE_MASK);
        Assert.assertEquals(0xFFL, MultiTypeDataId.POWER_MASK);
        Assert.assertEquals(0xFFFFFFFFFFL, MultiTypeDataId.INDEX_MASK);
    }

    @Test
    public void testIdCodec_typeMinMax() {
        for (int typeId : new int[]{0, 1, 255, 0xFF00, 0xFFFF}) {
            for (int power : new int[]{0, 7, 15, 23, 27}) {
                for (long idx : new long[]{0L, 1L, 255L, 0xFFFFFFL, (1L << 40) - 1}) {
                    long id = MultiTypeDataId.encode(typeId, power, idx);
                    Assert.assertEquals("typeId mismatch", typeId, MultiTypeDataId.decodeTypeId(id));
                    Assert.assertEquals("power mismatch", power, MultiTypeDataId.decodePower(id));
                    Assert.assertEquals("index mismatch", idx, MultiTypeDataId.decodeIndexId(id));
                }
            }
        }
    }

    @Test
    public void testIdCodec_overflowClamp_whenExceedingMaxValues() {
        long id = MultiTypeDataId.encode(0x1FFFF, 0x2FF, (1L << 45) + 77L);
        Assert.assertEquals(0xFFFF, MultiTypeDataId.decodeTypeId(id));
        Assert.assertEquals(0xFF, MultiTypeDataId.decodePower(id));
        Assert.assertEquals(77L, MultiTypeDataId.decodeIndexId(id));
    }

    @Test
    public void testIdCodec_notZero_vsValid() {
        Assert.assertFalse(MultiTypeDataId.isValid(0L));
        Assert.assertTrue(MultiTypeDataId.isValid(MultiTypeDataId.encode(0, 0, 1)));
    }

    // ============ 2. DsTypeFixedBucketStore 功能 + 物理隔离测试 ============

    private static byte[] randBytes(Random r, int len) {
        byte[] b = new byte[len];
        r.nextBytes(b);
        return b;
    }

    private static boolean allZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    @Test
    public void testPutRemove_defaultTypeId() throws Exception {
        String root = tmp.newFolder().getAbsolutePath();
        try (DsTypeFixedBucketStore store = new DsTypeFixedBucketStore(root, 17)) {
            Assert.assertTrue(new File(store.getTypeDir()).isDirectory());
            Assert.assertTrue(store.getTypeDir().endsWith("type_11"));  // 17 = 0x11
            Random r = new Random(42);
            byte[] data = randBytes(r, 345);
            long id = store.put("ds", "test", data);
            Assert.assertEquals(17, MultiTypeDataId.decodeTypeId(id));
            Assert.assertArrayEquals(data, store.get("ds", "test", id, data.length));
            store.remove("ds", "test", id);
            byte[] after = store.get("ds", "test", id, data.length);
            Assert.assertTrue("remove 后应全 0（tombstone）", allZero(after));
        }
    }

    @Test
    public void testMultipleTypeIds_physicallyIsolated() throws Exception {
        String root = tmp.newFolder().getAbsolutePath();
        final int TA = 0xA;
        final int TB = 0xBEEF;
        Random r = new Random(7);
        byte[] bA = randBytes(r, 100);
        byte[] bB = randBytes(r, 2000);
        long idA; long idB;
        try (DsTypeFixedBucketStore sA = new DsTypeFixedBucketStore(root, TA);
             DsTypeFixedBucketStore sB = new DsTypeFixedBucketStore(root, TB)) {
            idA = sA.put("ds", "t", bA);
            idB = sB.put("ds", "t", bB);
            Assert.assertEquals(TA, MultiTypeDataId.decodeTypeId(idA));
            Assert.assertEquals(TB, MultiTypeDataId.decodeTypeId(idB));
            Assert.assertArrayEquals(bA, sA.get("ds", "t", idA, bA.length));
            Assert.assertArrayEquals(bB, sB.get("ds", "t", idB, bB.length));
            File dirA = new File(sA.getTypeDir());
            File dirB = new File(sB.getTypeDir());
            Assert.assertTrue("type_10 子目录必须存在", dirA.isDirectory());
            Assert.assertTrue("type_BEEF 子目录必须存在", dirB.isDirectory());
            Assert.assertNotEquals("两个 type 的物理目录必须不同", dirA.getAbsolutePath(), dirB.getAbsolutePath());
            File dsA = new File(dirA, "ds/t");
            File dsB = new File(dirB, "ds/t");
            Assert.assertTrue("A 的 data_xxx.dat 必须在 type_A 子目录下", dsA.isDirectory());
            Assert.assertTrue("B 的 data_xxx.dat 必须在 type_BEEF 子目录下", dsB.isDirectory());
            java.util.Set<String> aFiles = new java.util.HashSet<>(Arrays.asList(dsA.list()));
            java.util.Set<String> bFiles = new java.util.HashSet<>(Arrays.asList(dsB.list()));
            Assert.assertTrue("A 目录有 16B~ 与 4KB~ 的 data_*.dat（A 写 100 字节 = power 7 = 128B）",
                    aFiles.stream().anyMatch(n -> n.startsWith("data_") && n.endsWith(".dat")));
            Assert.assertTrue("B 目录有 2KB~ 的 data_*.dat（B 写 2000 字节 = power 11 = 2048B）",
                    bFiles.stream().anyMatch(n -> n.startsWith("data_") && n.endsWith(".dat")));
            byte[] crossFromA = sB.get("ds", "t", idA, bA.length);
            Assert.assertTrue("用 B store 读 A 的 id 必须返回全 0（物理隔离，槽位未写入）", allZero(crossFromA));
            byte[] crossFromB = sA.get("ds", "t", idB, bB.length);
            Assert.assertTrue("用 A store 读 B 的 id 必须返回全 0（物理隔离，槽位未写入）", allZero(crossFromB));
        }
    }

    @Test
    public void testBatchIdsByPower_allCorrectlyTyped() throws Exception {
        String root = tmp.newFolder().getAbsolutePath();
        try (DsTypeFixedBucketStore store = new DsTypeFixedBucketStore(root, 0xC0FE)) {
            Assert.assertEquals("C0FE", store.getTypeDir().substring(store.getTypeDir().lastIndexOf('_') + 1));
            long[] ids = store.getNewIdsByPower("ds", "foo", 10, 32);
            for (long id : ids) {
                Assert.assertEquals(0xC0FE, MultiTypeDataId.decodeTypeId(id));
                Assert.assertEquals(10, MultiTypeDataId.decodePower(id));
                byte[] data = new byte[1024];
                Arrays.fill(data, (byte) 0xAB);
                store.overwrite("ds", "foo", id, data);
                byte[] back = store.get("ds", "foo", id, data.length);
                Assert.assertArrayEquals(data, back);
            }
        }
    }

    @Test
    public void testMixedOldAndNewStore_sameRoot_noConflicts() throws Exception {
        File root = tmp.newFolder();
        byte[] oldData = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
        long oldId;
        DsFixedBucketStore old = new DsFixedBucketStore(root.getAbsolutePath());
        try {
            oldId = old.put("ds", "oldstyle", oldData);
        } finally {
            old.close();
        }
        byte[] newData = new byte[80];
        Arrays.fill(newData, (byte) 0x55);
        long newId;
        try (DsTypeFixedBucketStore s = new DsTypeFixedBucketStore(root.getAbsolutePath(), 42)) {
            newId = s.put("ds", "newstyle", newData);
        }
        DsFixedBucketStore old2 = new DsFixedBucketStore(root.getAbsolutePath());
        try {
            Assert.assertArrayEquals(oldData, old2.get("ds", "oldstyle", oldId, oldData.length));
        } finally {
            old2.close();
        }
        try (DsTypeFixedBucketStore s = new DsTypeFixedBucketStore(root.getAbsolutePath(), 42)) {
            Assert.assertArrayEquals(newData, s.get("ds", "newstyle", newId, newData.length));
        }
        File plainDir = new File(root, "ds/oldstyle");
        File typedDir = new File(DsTypeFixedBucketStore.typeDirFor(root.getAbsolutePath(), 42), "ds/newstyle");
        Assert.assertTrue("plain store 文件直接落在 root/ds/oldstyle/ 下", plainDir.isDirectory());
        Assert.assertTrue("typed store 文件必须落在 type_2A/ds/newstyle/（42=0x2A）", typedDir.isDirectory());
        Assert.assertFalse("plain 与 typed 文件路径不得存在互相包含", plainDir.getAbsolutePath().equals(typedDir.getAbsolutePath()));
    }
}
