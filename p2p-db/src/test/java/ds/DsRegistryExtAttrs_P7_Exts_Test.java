package ds;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.q3lives.ds.registry.DsRegistry;
import com.q3lives.ds.registry.DsRegistryExtAttrsHelper;

public class DsRegistryExtAttrs_P7_Exts_Test {

    private static File tmpDir(String prefix) throws Exception {
        File f = Files.createTempDirectory(prefix).toFile();
        f.deleteOnExit();
        return f;
    }

    @Test
    public void T01_Tag_addRemoveList_idempotent() throws Exception {
        File tmp = tmpDir("dsreg_exts_t01_");
        try (DsRegistry r = new DsRegistry(new File(tmp, "r1").getAbsolutePath())) {
            String ek = "/apps/item1";
            r.putString(ek, "payload1", com.q3lives.ds.enums.DataType.REG_SZ);

            Assertions.assertTrue(DsRegistryExtAttrsHelper.addTag(r, ek, "prod"));
            Assertions.assertFalse(DsRegistryExtAttrsHelper.addTag(r, ek, "prod"), "幂等 addTag 第二次 false");
            Assertions.assertTrue(DsRegistryExtAttrsHelper.addTag(r, ek, "v2"));
            Assertions.assertTrue(DsRegistryExtAttrsHelper.addTag(r, ek, "android"));
            Assertions.assertTrue(DsRegistryExtAttrsHelper.hasTag(r, ek, "v2"));
            Assertions.assertFalse(DsRegistryExtAttrsHelper.hasTag(r, ek, "not-exist"));

            LinkedHashSet<String> tags = DsRegistryExtAttrsHelper.listTags(r, ek);
            Assertions.assertEquals(3, tags.size());
            Assertions.assertTrue(tags.containsAll(Arrays.asList("prod", "v2", "android")));

            Assertions.assertTrue(DsRegistryExtAttrsHelper.removeTag(r, ek, "v2"));
            Assertions.assertFalse(DsRegistryExtAttrsHelper.removeTag(r, ek, "v2"), "幂等 removeTag 第二次 false");
            tags = DsRegistryExtAttrsHelper.listTags(r, ek);
            Assertions.assertEquals(2, tags.size());
            Assertions.assertFalse(tags.contains("v2"));
        }
    }

    @Test
    public void T02_Tag_addTags_removeTags_removeAllTags() throws Exception {
        File tmp = tmpDir("dsreg_exts_t02_");
        try (DsRegistry r = new DsRegistry(new File(tmp, "r1").getAbsolutePath())) {
            String ek = "/data/file000";
            r.putString(ek, "hello", com.q3lives.ds.enums.DataType.REG_SZ);
            List<String> tags = Arrays.asList("a", "b", "c", "d", "e");
            Assertions.assertTrue(DsRegistryExtAttrsHelper.addTags(r, ek, tags));
            Assertions.assertFalse(DsRegistryExtAttrsHelper.addTags(r, ek, tags), "第二次所有 tags 已存在，返回 false");
            Assertions.assertEquals(5, DsRegistryExtAttrsHelper.listTags(r, ek).size());

            int removed = DsRegistryExtAttrsHelper.removeTags(r, ek, Arrays.asList("a", "c", "e", "not-exist"));
            Assertions.assertEquals(3, removed);
            LinkedHashSet<String> remain = DsRegistryExtAttrsHelper.listTags(r, ek);
            Assertions.assertEquals(2, remain.size());
            Assertions.assertTrue(remain.containsAll(Arrays.asList("b", "d")));

            int all = DsRegistryExtAttrsHelper.removeAllTags(r, ek);
            Assertions.assertEquals(2, all);
            Assertions.assertEquals(0, DsRegistryExtAttrsHelper.listTags(r, ek).size());

            int empty = DsRegistryExtAttrsHelper.removeAllTags(r, ek);
            Assertions.assertEquals(0, empty, "空 removeAll 返回 0");
        }
    }

    @Test
    public void T03_Tag_list_emptyOrMissingKey_returnEmpty() throws Exception {
        File tmp = tmpDir("dsreg_exts_t03_");
        try (DsRegistry r = new DsRegistry(new File(tmp, "r1").getAbsolutePath())) {
            String ek = "/not/exist/key";
            Assertions.assertEquals(0, DsRegistryExtAttrsHelper.listTags(r, ek).size(), "不存在的 entry 返回空 tags");
            Assertions.assertFalse(DsRegistryExtAttrsHelper.removeTag(r, ek, "x"), "不存在 entry removeTag 返回 false");
            Assertions.assertFalse(DsRegistryExtAttrsHelper.hasTag(r, ek, "any"), "不存在 entry hasTag false");
        }
    }

    @Test
    public void T04_Tag_addTag_validate_blankEntry() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            File tmp = tmpDir("dsreg_exts_t04_");
            try (DsRegistry r = new DsRegistry(new File(tmp, "r1").getAbsolutePath())) {
                DsRegistryExtAttrsHelper.addTag(r, "", "t");
            }
        }, "entryKey 空抛 IAE");
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            File tmp = tmpDir("dsreg_exts_t04b_");
            try (DsRegistry r = new DsRegistry(new File(tmp, "r1").getAbsolutePath())) {
                DsRegistryExtAttrsHelper.addTag(r, "/apps/ok", null);
            }
        }, "tag null 抛 IAE");
    }

    @Test
    public void T05_Desc_addListLastRemoveLast_removeAll() throws Exception {
        File tmp = tmpDir("dsreg_exts_t05_");
        try (DsRegistry r = new DsRegistry(new File(tmp, "r1").getAbsolutePath())) {
            String ek = "/docs/spec001";
            r.putString(ek, "doc body", com.q3lives.ds.enums.DataType.REG_SZ);

            long s1 = DsRegistryExtAttrsHelper.addDesc(r, ek, "v1 initial");
            long s2 = DsRegistryExtAttrsHelper.addDesc(r, ek, "v2 fix typos");
            long s3 = DsRegistryExtAttrsHelper.addDesc(r, ek, "v3 add section 4");
            Assertions.assertTrue(s1 < s2 && s2 < s3, "seq 严格递增");

            List<String> ds = DsRegistryExtAttrsHelper.listDescs(r, ek);
            Assertions.assertEquals(3, ds.size());
            Assertions.assertEquals("v1 initial", ds.get(0));
            Assertions.assertEquals("v3 add section 4", ds.get(2));
            Assertions.assertEquals("v3 add section 4", DsRegistryExtAttrsHelper.lastDesc(r, ek));

            Assertions.assertTrue(DsRegistryExtAttrsHelper.removeLastDesc(r, ek));
            Assertions.assertEquals(2, DsRegistryExtAttrsHelper.listDescs(r, ek).size());
            Assertions.assertEquals("v2 fix typos", DsRegistryExtAttrsHelper.lastDesc(r, ek));

            int n = DsRegistryExtAttrsHelper.removeAllDescs(r, ek);
            Assertions.assertEquals(2, n);
            Assertions.assertTrue(DsRegistryExtAttrsHelper.listDescs(r, ek).isEmpty());
            Assertions.assertNull(DsRegistryExtAttrsHelper.lastDesc(r, ek));

            Assertions.assertFalse(DsRegistryExtAttrsHelper.removeLastDesc(r, ek), "空的 removeLastDesc 返回 false");
            Assertions.assertEquals(0, DsRegistryExtAttrsHelper.removeAllDescs(r, ek), "空的 removeAll 返回 0");
        }
    }

    @Test
    public void T06_Spec_addListLastRemoveLast_removeAll() throws Exception {
        File tmp = tmpDir("dsreg_exts_t06_");
        try (DsRegistry r = new DsRegistry(new File(tmp, "r1").getAbsolutePath())) {
            String ek = "/products/modelA";
            r.putString(ek, "skumodela", com.q3lives.ds.enums.DataType.REG_SZ);
            for (int i = 1; i <= 5; i++) DsRegistryExtAttrsHelper.addSpec(r, ek, "specV" + i);
            List<String> sp = DsRegistryExtAttrsHelper.listSpecs(r, ek);
            Assertions.assertEquals(5, sp.size());
            for (int i = 1; i <= 5; i++) Assertions.assertEquals("specV" + i, sp.get(i - 1));
            Assertions.assertEquals("specV5", DsRegistryExtAttrsHelper.lastSpec(r, ek));

            for (int i = 0; i < 3; i++) DsRegistryExtAttrsHelper.removeLastSpec(r, ek);
            Assertions.assertEquals(2, DsRegistryExtAttrsHelper.listSpecs(r, ek).size());
            Assertions.assertEquals("specV2", DsRegistryExtAttrsHelper.lastSpec(r, ek));

            int all = DsRegistryExtAttrsHelper.removeAllSpecs(r, ek);
            Assertions.assertEquals(2, all);
            Assertions.assertTrue(DsRegistryExtAttrsHelper.listSpecs(r, ek).isEmpty());
        }
    }

    @Test
    public void T07_Comment_addListLastRemoveLast_removeAll() throws Exception {
        File tmp = tmpDir("dsreg_exts_t07_");
        try (DsRegistry r = new DsRegistry(new File(tmp, "r1").getAbsolutePath())) {
            String ek = "/orders/orderX";
            r.putString(ek, "orderX", com.q3lives.ds.enums.DataType.REG_SZ);
            DsRegistryExtAttrsHelper.addComment(r, ek, "c1 下单");
            DsRegistryExtAttrsHelper.addComment(r, ek, "c2 付款");
            DsRegistryExtAttrsHelper.addComment(r, ek, "c3 发货");
            List<String> cs = DsRegistryExtAttrsHelper.listComments(r, ek);
            Assertions.assertEquals(3, cs.size());
            Assertions.assertEquals(Arrays.asList("c1 下单", "c2 付款", "c3 发货"), cs);
            Assertions.assertEquals("c3 发货", DsRegistryExtAttrsHelper.lastComment(r, ek));

            Assertions.assertTrue(DsRegistryExtAttrsHelper.removeLastComment(r, ek));
            Assertions.assertEquals(2, DsRegistryExtAttrsHelper.listComments(r, ek).size());
            Assertions.assertEquals("c2 付款", DsRegistryExtAttrsHelper.lastComment(r, ek));

            int n = DsRegistryExtAttrsHelper.removeAllComments(r, ek);
            Assertions.assertEquals(2, n);
            Assertions.assertTrue(DsRegistryExtAttrsHelper.listComments(r, ek).isEmpty());
        }
    }

    @Test
    public void T08_Desc_Spec_Comment_nonExisting_entry_returnEmpty() throws Exception {
        File tmp = tmpDir("dsreg_exts_t08_");
        try (DsRegistry r = new DsRegistry(new File(tmp, "r1").getAbsolutePath())) {
            String ek = "/not/exist/path";
            Assertions.assertTrue(DsRegistryExtAttrsHelper.listDescs(r, ek).isEmpty());
            Assertions.assertTrue(DsRegistryExtAttrsHelper.listSpecs(r, ek).isEmpty());
            Assertions.assertTrue(DsRegistryExtAttrsHelper.listComments(r, ek).isEmpty());
            Assertions.assertNull(DsRegistryExtAttrsHelper.lastDesc(r, ek));
            Assertions.assertNull(DsRegistryExtAttrsHelper.lastSpec(r, ek));
            Assertions.assertNull(DsRegistryExtAttrsHelper.lastComment(r, ek));
        }
    }

    @Test
    public void T09_AddNullText_treatedAsEmptyString() throws Exception {
        File tmp = tmpDir("dsreg_exts_t09_");
        try (DsRegistry r = new DsRegistry(new File(tmp, "r1").getAbsolutePath())) {
            String ek = "/items/nullable";
            r.putString(ek, "x", com.q3lives.ds.enums.DataType.REG_SZ);
            long s1 = DsRegistryExtAttrsHelper.addDesc(r, ek, null);
            long s2 = DsRegistryExtAttrsHelper.addSpec(r, ek, null);
            long s3 = DsRegistryExtAttrsHelper.addComment(r, ek, null);
            Assertions.assertTrue(s1 >= 1L && s2 >= 1L && s3 >= 1L);
            Assertions.assertEquals("", DsRegistryExtAttrsHelper.listDescs(r, ek).get(0));
            Assertions.assertEquals("", DsRegistryExtAttrsHelper.listSpecs(r, ek).get(0));
            Assertions.assertEquals("", DsRegistryExtAttrsHelper.listComments(r, ek).get(0));
        }
    }

    @Test
    public void T10_Multi_entries_separate_eachOther_noCross() throws Exception {
        File tmp = tmpDir("dsreg_exts_t10_");
        try (DsRegistry r = new DsRegistry(new File(tmp, "r1").getAbsolutePath())) {
            String ek1 = "/a/p1";
            String ek2 = "/b/p2";
            r.putString(ek1, "1", com.q3lives.ds.enums.DataType.REG_SZ);
            r.putString(ek2, "2", com.q3lives.ds.enums.DataType.REG_SZ);

            DsRegistryExtAttrsHelper.addTag(r, ek1, "tagA");
            DsRegistryExtAttrsHelper.addTag(r, ek2, "tagB");
            DsRegistryExtAttrsHelper.addDesc(r, ek1, "dA");
            DsRegistryExtAttrsHelper.addDesc(r, ek2, "dB");
            DsRegistryExtAttrsHelper.addSpec(r, ek1, "sA");
            DsRegistryExtAttrsHelper.addSpec(r, ek2, "sB");
            DsRegistryExtAttrsHelper.addComment(r, ek1, "cA");
            DsRegistryExtAttrsHelper.addComment(r, ek2, "cB");

            Assertions.assertEquals(new LinkedHashSet<>(Arrays.asList("tagA")), DsRegistryExtAttrsHelper.listTags(r, ek1));
            Assertions.assertEquals(new LinkedHashSet<>(Arrays.asList("tagB")), DsRegistryExtAttrsHelper.listTags(r, ek2));
            Assertions.assertEquals(Arrays.asList("dA"), DsRegistryExtAttrsHelper.listDescs(r, ek1));
            Assertions.assertEquals(Arrays.asList("dB"), DsRegistryExtAttrsHelper.listDescs(r, ek2));
            Assertions.assertEquals(Arrays.asList("sA"), DsRegistryExtAttrsHelper.listSpecs(r, ek1));
            Assertions.assertEquals(Arrays.asList("sB"), DsRegistryExtAttrsHelper.listSpecs(r, ek2));
            Assertions.assertEquals(Arrays.asList("cA"), DsRegistryExtAttrsHelper.listComments(r, ek1));
            Assertions.assertEquals(Arrays.asList("cB"), DsRegistryExtAttrsHelper.listComments(r, ek2));
        }
    }

    @Test
    public void T11_RemoveAll_afterReadd_seqStartFresh_from_1() throws Exception {
        File tmp = tmpDir("dsreg_exts_t11_");
        try (DsRegistry r = new DsRegistry(new File(tmp, "r1").getAbsolutePath())) {
            String ek = "/item/x";
            r.putString(ek, "x", com.q3lives.ds.enums.DataType.REG_SZ);
            DsRegistryExtAttrsHelper.addDesc(r, ek, "a");
            DsRegistryExtAttrsHelper.addDesc(r, ek, "b");
            DsRegistryExtAttrsHelper.removeAllDescs(r, ek);
            long fresh1 = DsRegistryExtAttrsHelper.addDesc(r, ek, "after reset 1");
            long fresh2 = DsRegistryExtAttrsHelper.addDesc(r, ek, "after reset 2");
            Assertions.assertEquals(1L, fresh1, "removeAll 后 nextSeq 重置为 1（nextSeq 1）");
            Assertions.assertEquals(2L, fresh2);
            Assertions.assertEquals(Arrays.asList("after reset 1", "after reset 2"), DsRegistryExtAttrsHelper.listDescs(r, ek));
        }
    }

    @Test
    public void T12_Tag_listTags_withLimit_withFilter() throws Exception {
        File tmp = tmpDir("dsreg_exts_t12_");
        try (DsRegistry r = new DsRegistry(new File(tmp, "r1").getAbsolutePath())) {
            String ek = "/items/x";
            r.putString(ek, "payload", com.q3lives.ds.enums.DataType.REG_SZ);
            List<String> all = Arrays.asList("apple", "banana", "cherry", "apricot", "blueberry");
            DsRegistryExtAttrsHelper.addTags(r, ek, all);

            LinkedHashSet<String> startA = DsRegistryExtAttrsHelper.listTags(r, ek, 100, t -> t.startsWith("a"));
            Assertions.assertEquals(2, startA.size());
            Assertions.assertTrue(startA.containsAll(Arrays.asList("apple", "apricot")));

            LinkedHashSet<String> limited = DsRegistryExtAttrsHelper.listTags(r, ek, 2, null);
            Assertions.assertEquals(2, limited.size(), "limit=2 只返回前 2 个");

            LinkedHashSet<String> emptyLim = DsRegistryExtAttrsHelper.listTags(r, ek, 0, null);
            Assertions.assertEquals(5, emptyLim.size(), "limit<=0 默认返回全部（内部 1000）");
        }
    }
}
