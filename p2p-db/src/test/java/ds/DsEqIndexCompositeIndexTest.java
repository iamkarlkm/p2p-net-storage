package ds;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

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

/**
 * V0.7 复合索引 (a,b,c) 最左前缀专项测试。
 */
public class DsEqIndexCompositeIndexTest {

    private File tmpRoot;
    private String oldHome;

    @DsCompositeIndex(name = "idx_abc", columns = {"a", "b", "c"})
    public static class CompositeUser extends DsTableAdapter {
        @DsField(name = "name", length = 16)
        public String name;
        @DsField(name = "a", length = 4)
        public int a;
        @DsField(name = "b", length = 4)
        public int b;
        @DsField(name = "c", length = 4)
        public int c;

        public CompositeUser() {}
    }

    @Before
    public void before() throws IOException {
        tmpRoot = Files.createTempDirectory("dsdb_composite_idx_").toFile();
        DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());
    }

    @After
    public void after() {
        try {
            DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        } catch (Exception ignore) {}
        try {
            DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        } catch (Exception ignore) {}
        deleteRecursive(tmpRoot);
        if (oldHome == null) {
            System.clearProperty("p2p.db.home");
        } else {
            System.setProperty("p2p.db.home", oldHome);
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null) return;
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) for (File c : ch) deleteRecursive(c);
        }
        f.delete();
    }

    private static CompositeUser u(String name, int a, int b, int c) {
        CompositeUser u = new CompositeUser();
        u.name = name;
        u.a = a;
        u.b = b;
        u.c = c;
        return u;
    }

    @Test
    public void testFullCompositeMatch() throws Exception {
        GenericManager<CompositeUser> m = new GenericManager<>(CompositeUser.class);
        m.saveOrUpdate(u("a1", 1, 2, 3));
        m.saveOrUpdate(u("a2", 1, 2, 4));
        m.saveOrUpdate(u("a3", 1, 3, 3));
        m.saveOrUpdate(u("a4", 2, 2, 3));

        QueryWrapper<CompositeUser> w = m.buildQueryWrapper();
        w.eq("a", 1);
        w.eq("b", 2);
        w.eq("c", 3);
        List<CompositeUser> list = m.findList(w);
        Assert.assertEquals(1, list.size());
        Assert.assertEquals("a1", list.get(0).name);
    }

    @Test
    public void testLeftPrefixA() throws Exception {
        GenericManager<CompositeUser> m = new GenericManager<>(CompositeUser.class);
        m.saveOrUpdate(u("a1", 1, 2, 3));
        m.saveOrUpdate(u("a2", 1, 2, 4));
        m.saveOrUpdate(u("a3", 1, 3, 3));
        m.saveOrUpdate(u("a4", 2, 2, 3));

        QueryWrapper<CompositeUser> w = m.buildQueryWrapper();
        w.eq("a", 1);
        List<CompositeUser> list = m.findList(w);
        Assert.assertEquals(3, list.size());
        for (CompositeUser u : list) Assert.assertEquals(1, u.a);
    }

    @Test
    public void testLeftPrefixAB() throws Exception {
        GenericManager<CompositeUser> m = new GenericManager<>(CompositeUser.class);
        m.saveOrUpdate(u("a1", 1, 2, 3));
        m.saveOrUpdate(u("a2", 1, 2, 4));
        m.saveOrUpdate(u("a3", 1, 3, 3));
        m.saveOrUpdate(u("a4", 2, 2, 3));

        QueryWrapper<CompositeUser> w = m.buildQueryWrapper();
        w.eq("a", 1);
        w.eq("b", 2);
        List<CompositeUser> list = m.findList(w);
        Assert.assertEquals(2, list.size());
        for (CompositeUser u : list) {
            Assert.assertEquals(1, u.a);
            Assert.assertEquals(2, u.b);
        }
    }

    @Test
    public void testCompositePlusNonIndexMatchesAll() throws Exception {
        GenericManager<CompositeUser> m = new GenericManager<>(CompositeUser.class);
        m.saveOrUpdate(u("alice", 1, 2, 3));
        m.saveOrUpdate(u("bob", 1, 2, 3));
        m.saveOrUpdate(u("carol", 1, 2, 4));

        QueryWrapper<CompositeUser> w = m.buildQueryWrapper();
        w.eq("a", 1);
        w.eq("b", 2);
        w.eq("c", 3);
        w.like("name", "b");
        List<CompositeUser> list = m.findList(w);
        Assert.assertEquals(1, list.size());
        Assert.assertEquals("bob", list.get(0).name);
    }

    @Test
    public void testUpdateMaintainsCompositeIndex() throws Exception {
        GenericManager<CompositeUser> m = new GenericManager<>(CompositeUser.class);
        CompositeUser u1 = u("old", 1, 2, 3);
        m.saveOrUpdate(u1);

        // 先按原复合值查得到
        QueryWrapper<CompositeUser> w = m.buildQueryWrapper();
        w.eq("a", 1);
        w.eq("b", 2);
        w.eq("c", 3);
        Assert.assertEquals(1, m.findList(w).size());

        // 修改 b、c 后保存
        u1.b = 5;
        u1.c = 6;
        m.saveOrUpdate(u1);

        // 旧复合值查不到
        Assert.assertEquals(0, m.findList(w).size());

        // 新复合值查到
        QueryWrapper<CompositeUser> w2 = m.buildQueryWrapper();
        w2.eq("a", 1);
        w2.eq("b", 5);
        w2.eq("c", 6);
        List<CompositeUser> list = m.findList(w2);
        Assert.assertEquals(1, list.size());
        Assert.assertEquals("old", list.get(0).name);
    }

    @Test
    public void testNoMatchingPrefixFallsBack() throws Exception {
        GenericManager<CompositeUser> m = new GenericManager<>(CompositeUser.class);
        m.saveOrUpdate(u("a1", 1, 2, 3));
        m.saveOrUpdate(u("a2", 1, 2, 4));
        m.saveOrUpdate(u("a3", 2, 3, 4));

        // 只有 b=? 不满足最左前缀，fallback 全表扫描 + matchesAll
        QueryWrapper<CompositeUser> w = m.buildQueryWrapper();
        w.eq("b", 2);
        List<CompositeUser> list = m.findList(w);
        Assert.assertEquals(2, list.size());
        for (CompositeUser u : list) Assert.assertEquals(2, u.b);
    }
}
