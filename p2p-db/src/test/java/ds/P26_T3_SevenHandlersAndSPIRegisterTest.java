package ds;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire;
import java.io.File;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.ServiceLoader;
import java.util.Set;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.interfaces.P2PCommandHandler;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class P26_T3_SevenHandlersAndSPIRegisterTest {

    private static String root;

    @BeforeClass
    public static void before() {
        File tmp = new File("./target/p26_t3_handlers/");
        tmp.mkdirs();
        root = tmp.getAbsolutePath();
    }

    @AfterClass
    public static void after() {
        try { Class<?> c = Class.forName("javax.net.p2p.server.handler.fulltext.DsServerFulltextRegistry");
            Method m = c.getDeclaredMethod("clearAll"); m.setAccessible(true); m.invoke(null); } catch (Exception ignore) {}
    }

    // TR3.1: ServiceLoader loads exactly 7 new handlers from fulltext package
    @Test
    public void tr31_spiLoadsSevenNewFulltextHandlers() {
        ServiceLoader<P2PCommandHandler> loader = ServiceLoader.load(P2PCommandHandler.class);
        int fulltextCount = 0;
        Set<String> names = new HashSet<>();
        for (P2PCommandHandler h : loader) {
            String cn = h.getClass().getName();
            if (cn.startsWith("javax.net.p2p.server.handler.fulltext.")
                    && cn.contains("FulltextShard")) {
                fulltextCount++;
                names.add(cn);
            }
        }
        Assert.assertEquals("7 distinct fulltext shard handlers loaded via SPI", 7, fulltextCount);
        Assert.assertTrue("contains PutDoc handler", names.contains("javax.net.p2p.server.handler.fulltext.FulltextShardPutDocHandler"));
        Assert.assertTrue("contains ClearDoc handler", names.contains("javax.net.p2p.server.handler.fulltext.FulltextShardClearDocHandler"));
        Assert.assertTrue("contains IndexVector handler", names.contains("javax.net.p2p.server.handler.fulltext.FulltextShardIndexVectorHandler"));
        Assert.assertTrue("contains SearchVector handler", names.contains("javax.net.p2p.server.handler.fulltext.FulltextShardSearchVectorHandler"));
        Assert.assertTrue("contains Search handler", names.contains("javax.net.p2p.server.handler.fulltext.FulltextShardSearchHandler"));
        Assert.assertTrue("contains SearchExtras handler", names.contains("javax.net.p2p.server.handler.fulltext.FulltextShardSearchExtrasHandler"));
        Assert.assertTrue("contains Close handler", names.contains("javax.net.p2p.server.handler.fulltext.FulltextShardCloseHandler"));
    }

    // TR3.2: 7 handler command values are 24354..24360 strictly distinct ascending
    @Test
    public void tr32_sevenCommandIdsDistinctInRange() {
        ServiceLoader<P2PCommandHandler> loader = ServiceLoader.load(P2PCommandHandler.class);
        Set<Integer> ids = new HashSet<>();
        for (P2PCommandHandler h : loader) {
            String cn = h.getClass().getName();
            if (cn.startsWith("javax.net.p2p.server.handler.fulltext.")
                    && cn.contains("FulltextShard")) {
                P2PCommand cmd = h.getCommand();
                ids.add(cmd.getValue());
            }
        }
        Assert.assertEquals("7 distinct command ids", 7, ids.size());
        int[] expected = {24354, 24355, 24356, 24357, 24358, 24359, 24360};
        for (int e : expected) {
            Assert.assertTrue("contains id " + e, ids.contains(e));
        }
    }

    // TR3.3: putDoc handler end-to-end produces a non-zero IngestStat (pairsTotal >=1 for non-empty text)
    @Test
    public void tr33_putDocHandlerProducesIngestStat() throws Exception {
        Class<?> hCls = Class.forName("javax.net.p2p.server.handler.fulltext.FulltextShardPutDocHandler");
        P2PCommandHandler h = (P2PCommandHandler) hCls.getDeclaredConstructor().newInstance();
        Assert.assertEquals("handler cmd id matches", 24354, h.getCommand().getValue());
        FulltextShardWire.PutDocShardRequest req = FulltextShardWire.PutDocShardRequest.newBuilder()
                .setShardId(0).setDocId(1001L).setText("人民民主是中国的核心价值").setClusterRoot(root + "/t33").build();
        Class<?> pwCls = Class.forName("javax.net.p2p.model.P2PWrapper");
        Method bm = pwCls.getMethod("build", int.class, P2PCommand.class, Object.class);
        Object wrapper = bm.invoke(null, 1, P2PCommand.FULLTEXT_SHARD_PUT_DOC, req);
        Object respWrap = h.process((javax.net.p2p.model.P2PWrapper) wrapper);
        Object data = pwCls.getMethod("getData").invoke(respWrap);
        Assert.assertTrue("payload is PutDocShardResponse", data instanceof FulltextShardWire.PutDocShardResponse);
        FulltextShardWire.PutDocShardResponse r = (FulltextShardWire.PutDocShardResponse) data;
        Assert.assertEquals("status code 0 OK", 0, r.getStatusCode());
        Assert.assertEquals("doc id match", 1001L, r.getDocId());
        Assert.assertTrue("pairsTotal >= term count", r.getPairsTotal() >= 2);
        Assert.assertEquals("rowsPerLevel has 6 ints (shard L1..L6)", 6, r.getRowsPerLevelCount());
    }

    // TR3.4: No handler conflicts — 7 fulltext handler command ids don't collide with any existing non-fulltext handler
    // Also verify handlers are ordered after processing in fulltext.* only
    @Test
    public void tr34_noHandlerCommandIdOverlapWithExistingHandlers() {
        ServiceLoader<P2PCommandHandler> loader = ServiceLoader.load(P2PCommandHandler.class);
        Set<Integer> ftIds = new HashSet<>();
        Set<Integer> otherIds = new HashSet<>();
        for (P2PCommandHandler h : loader) {
            String cn = h.getClass().getName();
            if (cn.startsWith("javax.net.p2p.server.handler.fulltext.") && cn.contains("FulltextShard")) {
                ftIds.add(h.getCommand().getValue());
            } else {
                otherIds.add(h.getCommand().getValue());
            }
        }
        for (int fid : ftIds) {
            Assert.assertFalse("fulltext command id " + fid + " must NOT collide with other handlers",
                    otherIds.contains(fid));
        }
        // 24340..24344 range is ORM range; verify ft ids are NOT in ORM range
        for (int fid : ftIds) {
            Assert.assertFalse("fulltext id " + fid + " not in ORM 24340..24344 range",
                    fid >= 24340 && fid <= 24344);
        }
    }
}
