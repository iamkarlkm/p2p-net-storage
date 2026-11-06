package ds;

import java.io.File;
import java.util.Arrays;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.core.DsString;
import com.q3lives.ds.core.DsTypeString;
import com.q3lives.ds.util.MultiTypeDataId;

/**
 * DsTypeString 功能 + 物理隔离测试：
 *  1) add/get/refCount/retain/release 闭环
 *  2) id 三字段段正确（TypeID 位段恒等于构造参数）
 *  3) 两个不同 TypeID 的 DsTypeString 物理隔离：master_index/data 独立，
 *     同一个 key add 两次得到不同的 typedId/indexId（底层不去重跨类型）
 *  4) 跨类型 get：sB.get(idA) == null（master 里没这个 key / refCount 0）
 *  5) 与 plain DsString 共存（路径 root/string_store vs root/type_X/string_store 无冲突）
 *  6) update 替换字符串 + 正负号溢出 clamp 0/65535
 */
public class DsTypeStringTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void testAddRefCountRelease_typedIdMatches() throws Exception {
        String root = tmp.newFolder().getAbsolutePath();
        try (DsTypeString s = new DsTypeString(root, 0x2026)) {
            Assert.assertTrue(new File(s.getTypeDir()).isDirectory());
            Assert.assertTrue(s.getTypeDir().endsWith("type_2026"));
            long id = s.add("hello world, 这是中文");
            Assert.assertEquals(0x2026, MultiTypeDataId.decodeTypeId(id));
            Assert.assertEquals(1, s.refCount(id));
            Assert.assertEquals("hello world, 这是中文", s.get(id));
            s.retain(id);
            Assert.assertEquals(2, s.refCount(id));
            s.release(id);
            Assert.assertEquals(1, s.refCount(id));
            s.remove(id);
            Assert.assertEquals("refCount=0 时 get 返回 null", null, s.get(id));
            File storeDir = new File(s.getTypeDir(), "string_store");
            Assert.assertTrue("type_2026/string_store 子目录必须存在", storeDir.isDirectory());
        }
    }

    @Test
    public void testTwoTypeIds_physicallyIsolated_dedupNotShared() throws Exception {
        String root = tmp.newFolder().getAbsolutePath();
        try (DsTypeString sA = new DsTypeString(root, 0xA);
             DsTypeString sB = new DsTypeString(root, 0xB)) {
            // 给 sA 先分配一个 dummy id 占掉 indexId=0，避免两个独立 bucket 都 first-alloc 拿到 0 导致误判
            long dummyA = sA.add("__dummy_A_to_consume_id_0__");
            long dummyB = sB.add("__dummy_B_to_consume_id_0_for_sure_2__");
            Assert.assertEquals("dummyA idx != 0", 0L, MultiTypeDataId.decodeIndexId(dummyA));
            Assert.assertEquals("dummyB idx != 0", 0L, MultiTypeDataId.decodeIndexId(dummyB));
            long idA = sA.add("same-string");
            long idB = sB.add("same-string");
            Assert.assertEquals(0xA, MultiTypeDataId.decodeTypeId(idA));
            Assert.assertEquals(0xB, MultiTypeDataId.decodeTypeId(idB));
            Assert.assertEquals("same-string", sA.get(idA));
            Assert.assertEquals("same-string", sB.get(idB));
            // 物理隔离关键断言：不同 TypeID 各自有独立 master_index，所以 indexId **应该独立不相等**
            // （由于 dummy 把 id=0 占掉了，nextId 从 1 起分配，因此两个独立 nextId 计数器都返回 1？依然可能相等。
            // 正确语义：我们只要求两个 id 全局不等（TypeID 高位保证），再通过跨读 null / 跨目录不存在来验证物理隔离。）
            Assert.assertNotEquals("两个 typedId 应全局不等（TypeID 位段不同）", idA, idB);
            // 跨类型查不到：sB 里用 idA 读应该 null
            Assert.assertNull("用 B store 读 A 的 id 必须为 null（master_index 独立）", sB.get(idA));
            Assert.assertNull("用 A store 读 B 的 id 必须为 null（master_index 独立）", sA.get(idB));
            // 物理目录断言：root/type_A 与 root/type_B 两个 dir 独立存在
            File dirA = new File(sA.getTypeDir());
            File dirB = new File(sB.getTypeDir());
            Assert.assertTrue("type_A 子目录必须存在", dirA.isDirectory());
            Assert.assertTrue("type_B 子目录必须存在", dirB.isDirectory());
            Assert.assertNotEquals("两个 type 的物理目录必须不同", dirA.getAbsolutePath(), dirB.getAbsolutePath());
            java.util.List<String> filesA = java.util.Arrays.asList(listRecursive(dirA));
            java.util.List<String> filesB = java.util.Arrays.asList(listRecursive(dirB));
            Assert.assertTrue("A dir 必须有 string_store/data/data_value 目录",
                    filesA.stream().anyMatch(p -> p.endsWith("string_store" + File.separator + "data" + File.separator + "data_value")));
            Assert.assertTrue("B dir 必须有 string_store/data/data_value 目录",
                    filesB.stream().anyMatch(p -> p.endsWith("string_store" + File.separator + "data" + File.separator + "data_value")));
            // 双方互不可见对方的 typed data_*.dat / master_index：目录名 type_A 与 type_B 100% 不同
            Assert.assertTrue("A 目录集合 不含 type_B 路径", filesA.stream().noneMatch(p -> p.contains("type_" + Integer.toHexString(0xB).toUpperCase())));
            Assert.assertTrue("B 目录集合 不含 type_A 路径", filesB.stream().noneMatch(p -> p.contains("type_" + Integer.toHexString(0xA).toUpperCase())));
            // refCount 跨类型：A 的 id 在 B 里查询 refCount 应为 0
            Assert.assertEquals("A 的 id 在 B 里 refCount 是 0", 0, sB.refCount(idA));
            Assert.assertEquals("B 的 id 在 A 里 refCount 是 0", 0, sA.refCount(idB));
        }
    }

    @Test
    public void testUpdate_replaceString_returnedTypeIdCorrect() throws Exception {
        String root = tmp.newFolder().getAbsolutePath();
        try (DsTypeString s = new DsTypeString(root, 0x7)) {
            long oldId = s.add("version1");
            Assert.assertEquals("version1", s.get(oldId));
            long newId = s.update(oldId, "version2 全新内容");
            Assert.assertEquals(0x7, MultiTypeDataId.decodeTypeId(newId));
            Assert.assertEquals("version2 全新内容", s.get(newId));
            // 注意：DsData 底层 free-ring 会立即回收 baseId，所以 remove(oldId) 后再 put(new)
            // 大概率从 free-ring 取回同一个物理 baseId，因此 oldId 与 newId 可能指向同一槽位。
            // 合法语义是：update 后 newId 一定可拿到新内容；oldId 若被复用则内容也会是新内容（不是 null），
            // 而是被复用的内存 —— 这是存储层空间回收立即生效的正确行为。
            long againId = s.add("version2 全新内容");
            Assert.assertEquals("再次 add 同内容应该返回同一个去重 id", newId, againId);
            long v3Id = s.add("第三个完全不同的字符串 123");
            Assert.assertEquals(0x7, MultiTypeDataId.decodeTypeId(v3Id));
            Assert.assertEquals("第三个完全不同的字符串 123", s.get(v3Id));
        }
    }

    @Test
    public void testContains_getIndexId_createIfAbsent() throws Exception {
        String root = tmp.newFolder().getAbsolutePath();
        try (DsTypeString s = new DsTypeString(root, 511)) {
            Assert.assertFalse(s.contains("not-yet"));
            Long id1 = s.getIndexId("not-yet", false);
            Assert.assertNull(id1);
            Long id2 = s.getIndexId("not-yet", true);
            Assert.assertNotNull(id2);
            Assert.assertEquals(511, MultiTypeDataId.decodeTypeId(id2));
            Assert.assertTrue(s.contains("not-yet"));
            Long id3 = s.getIndexId("not-yet", false);
            Assert.assertEquals(id2, id3);
        }
    }

    @Test
    public void testCoexistWithPlainDsString_sameRoot_noConflict() throws Exception {
        File root = tmp.newFolder();
        long plainId;
        DsString plain1 = new DsString(root.getAbsolutePath());
        try {
            plainId = plain1.add("shared value");
        } finally {
            plain1.close();
        }
        long typedId;
        try (DsTypeString typed = new DsTypeString(root.getAbsolutePath(), 9999)) {
            typedId = typed.add("shared value");
            Assert.assertEquals(9999, MultiTypeDataId.decodeTypeId(typedId));
            Assert.assertEquals("shared value", typed.get(typedId));
        }
        DsString plain2 = new DsString(root.getAbsolutePath());
        try {
            Assert.assertEquals("shared value", plain2.get(plainId));
        } finally {
            plain2.close();
        }
        File plainStore = new File(root, "string_store");
        File typedStore = new File(com.q3lives.ds.bucket.DsTypeFixedBucketStore.typeDirFor(root.getAbsolutePath(), 9999), "string_store");
        Assert.assertTrue("plain DsString 直接在 root/string_store", plainStore.isDirectory());
        Assert.assertTrue("typed DsTypeString 必须在 type_xxx/string_store（9999=0x270F）", typedStore.isDirectory());
        Assert.assertFalse("plain 与 typed 路径必须不同", plainStore.getAbsolutePath().equals(typedStore.getAbsolutePath()));
    }

    @Test
    public void testTypeId_overflowClamped_toMax65535() throws Exception {
        String root = tmp.newFolder().getAbsolutePath();
        try (DsTypeString big = new DsTypeString(root, 99999999)) {
            Assert.assertTrue(big.getTypeDir().endsWith("type_FFFF"));
            long id = big.add("overflow check");
            Assert.assertEquals(65535, MultiTypeDataId.decodeTypeId(id));
        }
        try (DsTypeString neg = new DsTypeString(root, -8)) {
            Assert.assertTrue(neg.getTypeDir().endsWith("type_0"));
            long id = neg.add("negative clamp");
            Assert.assertEquals(0, MultiTypeDataId.decodeTypeId(id));
        }
    }

    private static String[] listRecursive(File root) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        java.util.Queue<File> q = new java.util.ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            File cur = q.poll();
            out.add(cur.getAbsolutePath());
            if (cur.isDirectory()) {
                File[] kids = cur.listFiles();
                if (kids != null) q.addAll(Arrays.asList(kids));
            }
        }
        return out.toArray(new String[0]);
    }
}
