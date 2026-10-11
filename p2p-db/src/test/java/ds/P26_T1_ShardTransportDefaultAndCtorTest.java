package ds;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.FulltextStore.IngestStat;
import com.q3lives.ds.database.fulltext.FulltextStore.ShardTransport;
import com.q3lives.ds.database.fulltext.FulltextStore.LocalShardTransport;
import com.q3lives.ds.database.fulltext.FulltextStore.ShardRouter;

public class P26_T1_ShardTransportDefaultAndCtorTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private File root;

    @Before
    public void setup() throws IOException {
        root = tmp.newFolder("p26t1");
    }

    // TR1.1 rule: 原有 5 方法签名存在且 5/5 exact match
    @Test
    public void tr11_shardTransportFiveBaseMethodsExact() throws Exception {
        Method[] all = ShardTransport.class.getDeclaredMethods();
        Set<String> names = new TreeSet<>();
        for (Method m : all) names.add(m.getName());
        Set<String> expected = new TreeSet<>(Arrays.asList(
                "putDoc", "clearDoc", "searchWithVector", "indexTermVector", "close",
                "search", "searchWithExtras", "putDocBatch"));
        Assert.assertTrue("ShardTransport methods must be superset of original 5 + new 3; actual=" + names,
                names.containsAll(expected));

        Method putDoc = ShardTransport.class.getDeclaredMethod("putDoc", int.class, long.class, String.class);
        Assert.assertNotNull("putDoc(int,long,String)", putDoc);
        Assert.assertEquals(IngestStat.class, putDoc.getReturnType());

        Method clearDoc = ShardTransport.class.getDeclaredMethod("clearDoc", int.class, long.class);
        Assert.assertNotNull("clearDoc(int,long)", clearDoc);
        Assert.assertEquals(void.class, clearDoc.getReturnType());

        Method searchVec = ShardTransport.class.getDeclaredMethod("searchWithVector",
                int.class, String.class, float[].class, int.class, FulltextStore.SearchOptions.class);
        Assert.assertNotNull("searchWithVector", searchVec);
        Assert.assertEquals(java.util.List.class, searchVec.getReturnType());

        Method idxVec = ShardTransport.class.getDeclaredMethod("indexTermVector", int.class, long.class, float[].class);
        Assert.assertNotNull("indexTermVector(int,long,float[])", idxVec);

        Method close = ShardTransport.class.getDeclaredMethod("close");
        Assert.assertNotNull("close()", close);
    }

    // TR1.2 rule: ShardRouter 2 constructors (int,String) + (int,String,BiFunction)
    @Test
    public void tr12_shardRouterTwoConstructors() throws Exception {
        Constructor<?>[] ctors = ShardRouter.class.getDeclaredConstructors();
        Assert.assertEquals("ShardRouter must have exactly 2 public constructors after P26 Facade monotonic add",
                2, ctors.length);

        Constructor<?> ctorOld = ShardRouter.class.getDeclaredConstructor(int.class, String.class);
        Assert.assertNotNull("old ctor (int, String)", ctorOld);

        Constructor<?> ctorNew = ShardRouter.class.getDeclaredConstructor(
                int.class, String.class, java.util.function.BiFunction.class);
        Assert.assertNotNull("new ctor (int,String,BiFunction)", ctorNew);
    }

    // TR1.3 rule: Default search/searchWithExtras (no override) → UOE; putDocBatch default → loop putDoc no UOE
    // Note: LocalShardTransport has been intentionally overridden for search/searchWithExtras (T4 fix #3),
    // so we use an anonymous minimal implementation to test default-method UOE behavior.
    @Test
    public void tr13_defaultMethodsThrowUOEOnNoOverride() throws Exception {
        try (ShardRouter r = new ShardRouter(1, new File(root, "p26t1_local").getAbsolutePath())) {
            // Part A: anonymous ShardTransport (implements 5 base only, no search/searchWithExtras override)
            // -> default impls MUST throw UOE
            ShardTransport bareNoOverride = new ShardTransport() {
                @Override public IngestStat putDoc(int shardId, long docId, String text) { return IngestStat.zeroForTest(); }
                @Override public void clearDoc(int shardId, long docId) {}
                @Override public java.util.List<FulltextStore.ScoredDocHit> searchWithVector(
                        int shardId, String q, float[] qVec, int K, FulltextStore.SearchOptions o) { return new java.util.ArrayList<>(); }
                @Override public void indexTermVector(int shardId, long uid, float[] vec) {}
                @Override public void close() {}
            };
            try {
                bareNoOverride.search(0, "hello", 10, FulltextStore.SearchOptions.defaults());
                Assert.fail("search default (no override) must throw UOE");
            } catch (UnsupportedOperationException ok) { /* expected */ }
            try {
                bareNoOverride.searchWithExtras(0, "hello", new java.util.HashMap<>(), 10, FulltextStore.SearchOptions.defaults());
                Assert.fail("searchWithExtras default (no override) must throw UOE");
            } catch (UnsupportedOperationException ok) { /* expected */ }

            // Part B: LocalShardTransport has search/searchWithExtras OVERRIDDEN (T4 fix #3),
            // so calling them must NOT throw UOE -> return a valid list (empty or non-empty)
            ShardTransport local = new LocalShardTransport(new FulltextStore[]{r.getShardForTest(0)});
            java.util.List<FulltextStore.ScoredDocHit> hits = local.search(0, "hello", 10, FulltextStore.SearchOptions.defaults());
            Assert.assertNotNull("LocalShardTransport search overridden → non-null list (may be empty)", hits);
            java.util.List<FulltextStore.ScoredDocHitWithExtras> ex =
                    local.searchWithExtras(0, "hello", new java.util.HashMap<>(), 10, FulltextStore.SearchOptions.defaults());
            Assert.assertNotNull("LocalShardTransport searchWithExtras overridden → non-null list (may be empty)", ex);

            // Part C: putDocBatch default impl on LocalShardTransport or bare -> must NOT throw UOE
            ShardTransport t = new LocalShardTransport(new FulltextStore[]{r.getShardForTest(0)});
            java.util.Map<Long, String> m = new java.util.LinkedHashMap<>();
            m.put(1L, "hello world test doc");
            m.put(2L, "second document ingested via batch");
            IngestStat s = t.putDocBatch(0, m);
            Assert.assertNotNull("putDocBatch default returns non-null IngestStat", s);
            Assert.assertNotNull("doc 1 must exist locally", r.getShardForTest(0).getDocStatForTest(1L));
            Assert.assertNotNull("doc 2 must exist locally", r.getShardForTest(0).getDocStatForTest(2L));
        }
    }

    // TR1.4 rule: Custom transportFactory (mock counting wrapper) injected → 方法调用计数 1
    @Test
    public void tr14_transportFactoryInjectCallsMock() throws Exception {
        final AtomicInteger putCount = new AtomicInteger(0);
        final IngestStat zero = IngestStat.zeroForTest();

        java.util.function.BiFunction<File[], FulltextStore[], ShardTransport> mockFactory =
                (dirs, shards) -> new ShardTransport() {
                    @Override public IngestStat putDoc(int shardId, long docId, String text) {
                        putCount.incrementAndGet();
                        return zero;
                    }
                    @Override public void clearDoc(int shardId, long docId) {}
                    @Override public java.util.List<FulltextStore.ScoredDocHit> searchWithVector(
                            int shardId, String q, float[] qVec, int K, FulltextStore.SearchOptions o) { return new java.util.ArrayList<>(); }
                    @Override public void indexTermVector(int shardId, long uid, float[] vec) {}
                    @Override public void close() {}
                };

        try (ShardRouter r = new ShardRouter(4, new File(root, "p26t1_mock").getAbsolutePath(), mockFactory)) {
            r.putDocCluster(7L, "hello mock doc");
            Assert.assertEquals("Mock transport putDoc must be called exactly once via transportFactory injection",
                    1, putCount.get());
        }
    }
}
