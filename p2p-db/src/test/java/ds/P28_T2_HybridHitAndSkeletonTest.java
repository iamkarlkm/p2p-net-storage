package ds;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.annotation.DsCompositeIndex;
import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHit;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHitWithExtras;
import com.q3lives.ds.database.fulltext.cluster.DsFulltextClusterClient;
import com.q3lives.ds.database.fulltext.cluster.DsHybridQueryClient;
import com.q3lives.ds.database.fulltext.cluster.HybridHit;

public class P28_T2_HybridHitAndSkeletonTest {

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
        tmpRoot = Files.createTempDirectory("dsdb_p28_t2_").toFile();
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

    private static ScoredDocHit newHit(long docId, double score) {
        return new ScoredDocHit(docId, 0L, 0, 0, 0L, score, 0.0, 0.0, 0.0, 0, 0.0, 0);
    }

    private static ScoredDocHitWithExtras newFtx(ScoredDocHit core) {
        return new ScoredDocHitWithExtras(core, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
    }

    @Test
    public void tr21_hybridHitDocIdMismatchThrowIae() {
        AbcEntity e = new AbcEntity();
        e.setId(1L);
        e.name = "x"; e.a = 100; e.b = "y"; e.c = 1;
        ScoredDocHit wrong = newHit(2L, 0.95);
        ScoredDocHitWithExtras wrongFtx = newFtx(wrong);
        try {
            new HybridHit<>(e, wrongFtx);
            Assert.fail("HybridHit(docId mismatch) 必须抛 IAE，消息含 mismatch");
        } catch (IllegalArgumentException iae) {
            String m = iae.getMessage() == null ? "" : iae.getMessage();
            Assert.assertTrue("错误消息包含 mismatch: " + m, m.contains("mismatch"));
        }
    }

    @Test
    public void tr22_hybridHitNullParamThrow() {
        AbcEntity e = new AbcEntity();
        e.setId(1L);
        e.name = "x"; e.a = 100; e.b = "y"; e.c = 1;
        ScoredDocHit ok = newHit(1L, 0.95);
        ScoredDocHitWithExtras okFtx = newFtx(ok);
        boolean n1 = false;
        try { new HybridHit<>(null, okFtx); } catch (NullPointerException | IllegalArgumentException ex) { n1 = true; }
        Assert.assertTrue("entity=null 抛异常", n1);

        boolean n2 = false;
        try { new HybridHit<>(e, null); } catch (NullPointerException | IllegalArgumentException ex) { n2 = true; }
        Assert.assertTrue("ftx=null 抛异常", n2);

        boolean n3 = false;
        try { new HybridHit<>(null, null); } catch (NullPointerException | IllegalArgumentException ex) { n3 = true; }
        Assert.assertTrue("双 null 抛异常", n3);
    }

    @Test
    public void tr23_ofLocalNullArgsThrowNpe() throws Exception {
        AbcEntity e = new AbcEntity();
        e.setId(1L);
        DsFulltextClusterClient ftMock = null;
        com.q3lives.ds.database.integration.GenericManager<AbcEntity> gm =
                new com.q3lives.ds.database.integration.GenericManager<>(AbcEntity.class);

        boolean n1 = false;
        try { DsHybridQueryClient.ofLocal(AbcEntity.class, null, ftMock); }
        catch (NullPointerException | IllegalArgumentException ex) { n1 = true; }
        Assert.assertTrue("gm=null 抛异常", n1);

        boolean n2 = false;
        try { DsHybridQueryClient.ofLocal(AbcEntity.class, gm, null); }
        catch (NullPointerException | IllegalArgumentException ex) { n2 = true; }
        Assert.assertTrue("ftClient=null 抛异常", n2);

        boolean n3 = false;
        DsFulltextClusterClient validFt = new DsFulltextClusterClient(1, null, tmpRoot.getAbsolutePath() + "/ft", "shard");
        try { DsHybridQueryClient.ofLocal(null, gm, validFt); }
        catch (NullPointerException | IllegalArgumentException ex) { n3 = true; }
        finally { validFt.close(); }
        Assert.assertTrue("entityClass=null 抛异常", n3);
    }

    @Test
    public void tr24_constructorInjectionReflectCorrect() throws Exception {
        BiFunction<com.q3lives.ds.database.integration.QueryWrapper<AbcEntity>, Integer, List<Long>> idsProvider =
                (w, hint) -> Arrays.asList(1L, 2L, 3L);
        Function<Collection<Long>, Map<Long, AbcEntity>> batchGetter = ids -> {
            Map<Long, AbcEntity> out = new HashMap<>();
            for (Long id : ids) {
                AbcEntity e = new AbcEntity();
                e.setId(id);
                e.a = id * 100;
                out.put(id, e);
            }
            return out;
        };
        DsFulltextClusterClient ftMock = new DsFulltextClusterClient(1, null, tmpRoot.getAbsolutePath() + "/ft2", "shard");
        DsHybridQueryClient<AbcEntity> client = new DsHybridQueryClient<>(
                AbcEntity.class, idsProvider, batchGetter, ftMock, 3);

        Field fe = DsHybridQueryClient.class.getDeclaredField("entityClass");
        fe.setAccessible(true);
        Assert.assertEquals("entityClass 注入", AbcEntity.class, fe.get(client));

        Field ffm = DsHybridQueryClient.class.getDeclaredField("fallbackMultiplier");
        ffm.setAccessible(true);
        Assert.assertEquals("fallbackMultiplier 注入 = 3", Integer.valueOf(3), ffm.get(client));

        Field fft = DsHybridQueryClient.class.getDeclaredField("ftClient");
        fft.setAccessible(true);
        Assert.assertSame("ftClient 注入 = 同一实例", ftMock, fft.get(client));

        client.close();
        ftMock.close();
    }
}
