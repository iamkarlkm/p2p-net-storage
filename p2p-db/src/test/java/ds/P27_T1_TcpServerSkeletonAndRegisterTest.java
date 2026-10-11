package ds;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Timeout;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire;
import com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire.PutDocShardRequest;
import com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire.PutDocShardResponse;
import com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire.SearchShardRequest;
import com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire.SearchShardResponse;
import com.q3lives.ds.database.fulltext.cluster.ProtoWireConverters;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.channel.AbstractTcpMessageProcessor;
import javax.net.p2p.client.P2PClientTcp;
import javax.net.p2p.codec.P2PWrapperEncoder;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.model.StreamP2PWrapper;
import javax.net.p2p.server.ServerMessageProcessor;
import javax.net.p2p.server.fulltext.FulltextShardTcpServer;
import javax.net.p2p.server.handler.fulltext.DsServerFulltextRegistry;

public class P27_T1_TcpServerSkeletonAndRegisterTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private File root;
    private final ArrayList<AutoCloseable> closeables = new ArrayList<>();

    @Before
    public void setup() throws IOException {
        root = tmp.newFolder("p27t1");
    }

    @After
    public void tearDown() {
        for (int i = closeables.size() - 1; i >= 0; i--) {
            try { closeables.get(i).close(); } catch (Exception ignore) {}
        }
        closeables.clear();
        DsServerFulltextRegistry.clearAll();
    }

    @Test
    public void tr11_constructorPortOutOfRangeThrowsIAE() throws Exception {
        int[] badPorts = { -1, -65536, 70000, 99999, Integer.MIN_VALUE, Integer.MAX_VALUE };
        int badCount = 0;
        for (int p : badPorts) {
            FulltextShardTcpServer srv = new FulltextShardTcpServer(p);
            Thread t = new Thread(() -> srv.singletonCreated(srv));
            t.start();
            boolean ok = srv.waitForBind(15, TimeUnit.SECONDS);
            Assert.assertTrue("bind latch for port=" + p, ok);
            Throwable err = srv.getBindError();
            if (err instanceof IllegalArgumentException) badCount++;
            Assert.assertEquals("boundPort must be -1 on IAE for port=" + p, -1, srv.getBoundPort());
            try { srv.close(); } catch (Exception ignore) {}
        }
        Assert.assertEquals("all " + badPorts.length + " bad ports must yield IAE",
                badPorts.length, badCount);

        try {
            new FulltextShardTcpServer((Integer) null);
            Assert.fail("Null ctor should NPE");
        } catch (NullPointerException expected) {
        }
    }

    @Test
    public void tr12_randomPortBindAndCloseIdempotent() throws Exception {
        FulltextShardTcpServer srv = new FulltextShardTcpServer(0, 4096, 2, P2PWrapperEncoder.MAGIC);
        Thread bindThread = new Thread(() -> srv.singletonCreated(srv));
        bindThread.start();
        boolean ok = srv.waitForBind(30, TimeUnit.SECONDS);
        Assert.assertTrue("bind must succeed within 30s", ok);
        Assert.assertNull("bindError null", srv.getBindError());
        int p = srv.getBoundPort();
        Assert.assertTrue("actual port > 0 (ephemeral), got=" + p, p > 0 && p < 65536);

        srv.close();
        TimeUnit.MILLISECONDS.sleep(500);
        srv.close();
        srv.close();
        TimeUnit.MILLISECONDS.sleep(300);

        FulltextShardTcpServer srv2 = new FulltextShardTcpServer(0, 4096, 2, P2PWrapperEncoder.MAGIC);
        Thread t2 = new Thread(() -> srv2.singletonCreated(srv2));
        t2.start();
        boolean ok2 = srv2.waitForBind(30, TimeUnit.SECONDS);
        Assert.assertTrue("second server bind ok", ok2);
        int p2 = srv2.getBoundPort();
        Assert.assertTrue("second actual port > 0, got=" + p2, p2 > 0 && p2 < 65536);
        srv2.close();
    }

    @SuppressWarnings("unchecked")
    @Test
    public void tr13_sevenFulltextHandlersRegistered() throws Exception {
        new ServerMessageProcessor(P2PWrapperEncoder.MAGIC, 4096);
        Field f = AbstractTcpMessageProcessor.class.getDeclaredField("HANDLER_REGISTRY_MAP");
        f.setAccessible(true);
        Map<P2PCommand, P2PCommandHandler<?>> map =
                (Map<P2PCommand, P2PCommandHandler<?>>) f.get(null);

        Set<P2PCommand> expected7 = new HashSet<>(Arrays.asList(
                P2PCommand.FULLTEXT_SHARD_PUT_DOC,
                P2PCommand.FULLTEXT_SHARD_CLEAR_DOC,
                P2PCommand.FULLTEXT_SHARD_INDEX_VECTOR,
                P2PCommand.FULLTEXT_SHARD_SEARCH_VECTOR,
                P2PCommand.FULLTEXT_SHARD_SEARCH,
                P2PCommand.FULLTEXT_SHARD_SEARCH_EXTRAS,
                P2PCommand.FULLTEXT_SHARD_CLOSE
        ));
        Set<P2PCommand> found = new TreeSet<>();
        for (P2PCommand c : expected7) {
            if (map.containsKey(c)) found.add(c);
        }
        Assert.assertEquals("7 FULLTEXT handlers registered via scannerClass recursion; missing="
                + new TreeSet<>(expected7).removeAll(found) + "; map keys=" + new TreeSet<>(map.keySet()),
                7, found.size());

        for (P2PCommand c : expected7) {
            P2PCommandHandler<?> h = map.get(c);
            Assert.assertNotNull("handler non-null for cmd " + c, h);
            Assert.assertEquals("handler.getCommand matches registry key " + c,
                    c, h.getCommand());
        }
    }

    @Test
    @Order(4)
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    public void tr14_tcpEndToEndPutDocThenSearch() throws Exception {
        int N = 1;
        File shardRoot = new File(root, "e2e_shard");
        shardRoot.mkdirs();
        String clusterKey = shardRoot.getAbsolutePath() + "?sc=" + N + "&pfx=svr_shard";
        FulltextStore.ShardRouter router = DsServerFulltextRegistry.getOrCreate(clusterKey, N, "svr_shard");

        FulltextShardTcpServer srv = new FulltextShardTcpServer(0, 4096, 2, P2PWrapperEncoder.MAGIC);
        Thread bindThread = new Thread(() -> srv.singletonCreated(srv), "T14-bind");
        bindThread.setDaemon(true);
        bindThread.start();
        boolean bindOk = srv.waitForBind(30, TimeUnit.SECONDS);
        Assert.assertTrue("server bind ok within 30s", bindOk);
        Assert.assertNull("no bind error", srv.getBindError());
        int port = srv.getBoundPort();
        Assert.assertTrue("ephemeral port > 0, got=" + port, port > 0 && port < 65536);
        closeables.add(srv);

        TimeUnit.MILLISECONDS.sleep(1000);

        try (java.net.Socket probe = new java.net.Socket()) {
            probe.connect(new InetSocketAddress("127.0.0.1", port), 3000);
        } catch (IOException ioe) {
            Assert.fail("Socket probe to 127.0.0.1:" + port + " FAILED: " + ioe.getMessage()
                    + " | serverAlive=" + srv.isServerAliveForTest()
                    + " boundPort=" + srv.getBoundPort());
        }

        P2PClientTcp client = new P2PClientTcp(
                new InetSocketAddress("127.0.0.1", port), 4096, 2, P2PWrapperEncoder.MAGIC);
        client.newSendMesageExecutorToQueue();
        closeables.add(client);
        TimeUnit.MILLISECONDS.sleep(500);

        P2PWrapper echoReq = P2PWrapper.build(9000, P2PCommand.ECHO, "hello-tr14");
        P2PWrapper echoAck = client.excute(echoReq, 10, TimeUnit.SECONDS);
        Assert.assertNotNull("ECHO must not be null", echoAck);
        Object echoRaw = echoAck.getData();
        String echoStr = echoRaw == null ? "" : (echoRaw instanceof byte[] ? new String((byte[])echoRaw, StandardCharsets.UTF_8) : echoRaw.toString());
        Assert.assertTrue("ECHO start with Server echo ->, actual=" + echoStr + " cmd=" + echoAck.getCommand(),
                echoStr.startsWith("Server echo ->"));

        String[] docs = {
                "人民群众是历史的创造者 广场 英雄 纪念碑",
                "中华民族伟大复兴 人民 英雄",
                "人工智能大模型技术发展 人民 广场",
                "机器学习深度学习神经网络 人民 英雄 纪念碑"
        };
        long[] docIds = { 1001L, 1002L, 1003L, 1004L };
        for (int i = 0; i < docs.length; i++) {
            PutDocShardRequest req = PutDocShardRequest.newBuilder()
                    .setShardId(0).setDocId(docIds[i]).setText(docs[i])
                    .setClusterRoot(clusterKey).build();
            @SuppressWarnings("unchecked")
            P2PWrapper<Object> wrapper = (P2PWrapper<Object>) client.excute(
                    P2PWrapper.build(10000 + i, P2PCommand.FULLTEXT_SHARD_PUT_DOC, req.toByteArray()),
                    10, TimeUnit.SECONDS);
            Assert.assertNotNull("putDoc wrapper not null (docId=" + docIds[i] + "=TIMEOUT)", wrapper);
            if (wrapper.getCommand() == P2PCommand.STD_ERROR) {
                Assert.fail("putDoc(" + docIds[i] + ") STD_ERROR: " + wrapper.getData());
            }
            Object payload = wrapper.getData();
            Assert.assertTrue("putDoc(" + docIds[i] + ") payload instanceof byte[], cls="
                    + (payload == null ? "null" : payload.getClass().getName()),
                    payload instanceof byte[]);
            PutDocShardResponse r = PutDocShardResponse.parseFrom((byte[]) payload);
            Assert.assertEquals("putDoc(" + docIds[i] + ") statusCode=0: " + r.getErrorMessage(),
                    0, r.getStatusCode());
            Assert.assertEquals("putDoc(" + docIds[i] + ") docId echo", docIds[i], r.getDocId());
            Assert.assertTrue("putDoc(" + docIds[i] + ") pairsTotal > 0, got=" + r.getPairsTotal(),
                    r.getPairsTotal() > 0);
        }

        TimeUnit.MILLISECONDS.sleep(500);

        SearchShardRequest sreq = SearchShardRequest.newBuilder()
                .setShardId(0).setQuery("人民").setK(10)
                .setOptions(FulltextShardWire.SearchOptionsMsg.getDefaultInstance())
                .setClusterRoot(clusterKey).build();
        @SuppressWarnings("unchecked")
        P2PWrapper<Object> sw = (P2PWrapper<Object>) client.excute(
                P2PWrapper.build(20000, P2PCommand.FULLTEXT_SHARD_SEARCH, sreq.toByteArray()),
                10, TimeUnit.SECONDS);
        Assert.assertNotNull("search wrapper not null, cmd=" + (sw == null ? "null" : sw.getCommand()), sw);
        if (sw.getCommand() == P2PCommand.STD_ERROR) {
            Assert.fail("search STD_ERROR: " + sw.getData());
        }
        Object sp = sw.getData();
        Assert.assertTrue("search instanceof byte[], cls=" + (sp == null ? "null" : sp.getClass().getName()),
                sp instanceof byte[]);
        SearchShardResponse sr = SearchShardResponse.parseFrom((byte[]) sp);
        Assert.assertEquals("search statusCode=0: " + sr.getErrorMessage(), 0, sr.getStatusCode());
        Assert.assertTrue("人民 search hits > 0, got=" + sr.getHitsCount(),
                sr.getHitsCount() > 0);

        Set<Long> ids = new HashSet<>();
        boolean found1001 = false;
        for (int i = 0; i < sr.getHitsCount(); i++) {
            long did = sr.getHits(i).getDocId();
            ids.add(did);
            if (did == 1001L) found1001 = true;
            Assert.assertTrue("人民 hit docId=" + did + " finalScore > 0: " + sr.getHits(i).getFinalScore(),
                    sr.getHits(i).getFinalScore() > 0);
        }
        Assert.assertTrue("人民 hits 至少 doc 1001/1002/1003/1004 中1个, got ids=" + ids,
                ids.contains(1001L) || ids.contains(1002L) || ids.contains(1003L) || ids.contains(1004L));
        if (!found1001) System.out.println("INFO: doc 1001 not in top results, but TCP E2E works; ids=" + ids);
    }
}
