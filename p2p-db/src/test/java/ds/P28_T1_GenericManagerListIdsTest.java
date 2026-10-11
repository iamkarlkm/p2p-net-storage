package ds;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.annotation.DsCompositeIndex;
import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.integration.QueryWrapper;

public class P28_T1_GenericManagerListIdsTest {

    @DsCompositeIndex(name = "idx_abc", columns = {"a", "b", "c"})
    public static class AbcEntity extends DsTableAdapter {
        @DsField(name = "name", length = 48)
        public String name;
        @DsField(name = "a", length = 8)
        public long a;
        @DsField(name = "b", length = 16)
        public String b;
        @DsField(name = "c", length = 4)
        public int c;
        public AbcEntity() {}
    }

    private File tmpRoot;
    private String oldHome;

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p28_t1_").toFile();
        DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());
    }

    @After
    public void after() {
        try { DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot); } catch (Exception ignore) {}
        try { DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot); } catch (Exception ignore) {}
        deleteRecursive(tmpRoot);
        if (oldHome == null) System.clearProperty("p2p.db.home");
        else System.setProperty("p2p.db.home", oldHome);
    }

    private static void deleteRecursive(File f) {
        if (f == null) return;
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) for (File c : ch) deleteRecursive(c);
        }
        f.delete();
    }

    @Test
    public void tr11_nullWrapperFulltableNoNpe() {
        GenericManager<AbcEntity> gm = new GenericManager<>(AbcEntity.class);
        AbcEntity e = new AbcEntity();
        e.name = "row_1"; e.a = 100; e.b = "x"; e.c = 1;
        gm.saveOrUpdate(e);
        AbcEntity e2 = new AbcEntity();
        e2.name = "row_2"; e2.a = 200; e2.b = "y"; e2.c = 2;
        gm.saveOrUpdate(e2);
        List<Long> ids = gm.listIds(null);
        Assert.assertNotNull("wrapper=null ids 非 null", ids);
        Assert.assertEquals("全表 size=2（已写 2 行）", 2, ids.size());
    }

    @Test
    public void tr12_listIds_orderEqualCandidateIdsForQueryOrder() throws Exception {
        GenericManager<AbcEntity> gm = new GenericManager<>(AbcEntity.class);
        int N = 30;
        List<AbcEntity> inserted = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            AbcEntity e = new AbcEntity();
            e.name = "name_" + String.format("%02d", i);
            e.a = 100 + (i % 3) * 100;
            e.b = (i % 2 == 0) ? "x" : "y";
            e.c = i + 1;
            gm.saveOrUpdate(e);
            inserted.add(e);
        }
        QueryWrapper<AbcEntity> w = new QueryWrapper<>();
        w.orderByAsc("a");
        w.orderByAsc("id");
        List<Long> a = new ArrayList<>();
        Method cand = GenericManager.class.getDeclaredMethod("candidateIdsForQuery", QueryWrapper.class);
        cand.setAccessible(true);
        @SuppressWarnings("unchecked") Iterable<Long> it = (Iterable<Long>) cand.invoke(gm, w);
        if (it != null) for (Long id : it) a.add(id);
        else {
            Method pi = GenericManager.class.getDeclaredMethod("primaryIdIterator");
            pi.setAccessible(true);
            @SuppressWarnings("unchecked") java.util.Iterator<Long> pit = (java.util.Iterator<Long>) pi.invoke(gm);
            while (pit.hasNext()) {
                Long id = pit.next();
                if (id != null && id != 0L) a.add(id);
            }
        }
        List<Long> b = gm.listIds(w);
        Assert.assertEquals("listIds 顺序 = candidateIdsForQuery 顺序，size 相等", a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            Assert.assertEquals("index " + i + " 逐项 id 全等", a.get(i), b.get(i));
        }
    }

    @Test
    public void tr13_eqA100Filter100percentMatch() {
        GenericManager<AbcEntity> gm = new GenericManager<>(AbcEntity.class);
        int N = 30;
        for (int i = 0; i < N; i++) {
            AbcEntity e = new AbcEntity();
            e.name = "name_" + i;
            e.a = 100 + (i % 3) * 100;
            e.b = (i % 2 == 0) ? "x" : "y";
            e.c = i + 1;
            gm.saveOrUpdate(e);
        }
        QueryWrapper<AbcEntity> w = new QueryWrapper<>();
        w.eq("a", 100L);
        List<Long> ids = gm.listIds(w);
        Assert.assertEquals("a=100 命中 N/3 = 10 条", 10, ids.size());
        for (Long id : ids) {
            AbcEntity e = gm.getById(id);
            Assert.assertEquals("id=" + id + " 的实体 a 必须 = 100，0 误匹配", 100L, e.a);
        }
    }

    @Test
    public void tr14_compositeIndexABetweenC3to7AllMatch() {
        GenericManager<AbcEntity> gm = new GenericManager<>(AbcEntity.class);
        int k = 1;
        for (int group = 0; group < 10; group++) {
            AbcEntity e = new AbcEntity();
            e.name = "g1_n_" + k;
            e.a = 100; e.b = "x"; e.c = k;
            gm.saveOrUpdate(e);
            k++;
        }
        for (int group = 0; group < 10; group++) {
            AbcEntity e = new AbcEntity();
            e.name = "g2_n_" + k;
            e.a = 100; e.b = "y"; e.c = 10 + k;
            gm.saveOrUpdate(e);
            k++;
        }
        for (int group = 0; group < 10; group++) {
            AbcEntity e = new AbcEntity();
            e.name = "g3_n_" + k;
            e.a = 200; e.b = "z"; e.c = k;
            gm.saveOrUpdate(e);
            k++;
        }
        QueryWrapper<AbcEntity> w = new QueryWrapper<>();
        w.eq("a", 100L);
        w.eq("b", "x");
        w.between("c", 3, 7);
        List<Long> ids = gm.listIds(w);
        Assert.assertEquals("复合索引 a=100 b=x c∈[3,7] → 命中 5 条 (c=3,4,5,6,7)", 5, ids.size());
        Set<Integer> cValues = new HashSet<>();
        for (Long id : ids) {
            AbcEntity e = gm.getById(id);
            Assert.assertEquals("a=100", 100L, e.a);
            Assert.assertEquals("b=x", "x", e.b);
            Assert.assertTrue("c ∈ [3,7]", e.c >= 3 && e.c <= 7);
            cValues.add(e.c);
        }
        Assert.assertEquals("5 条 c 值互不相同，无重复 / 无越界（0 条越界 c 误匹配）", 5, cValues.size());
    }
}
