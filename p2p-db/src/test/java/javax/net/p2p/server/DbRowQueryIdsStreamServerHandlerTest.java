package javax.net.p2p.server;

import com.q3lives.ds.database.columnar.ColumnarStore;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.DatagramPacket;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.common.AbstractSendMesageExecutor;
import javax.net.p2p.common.ExecutorServicePool;
import javax.net.p2p.model.DbCellValue;
import javax.net.p2p.model.DbColumnSchema;
import javax.net.p2p.model.DbMetaPutRequest;
import javax.net.p2p.model.DbQuery;
import javax.net.p2p.model.DbQueryCriterion;
import javax.net.p2p.model.DbQueryOp;
import javax.net.p2p.model.DbQueryOrder;
import javax.net.p2p.model.DbRowPutRequest;
import javax.net.p2p.model.DbRowPutResponse;
import javax.net.p2p.model.DbRowQueryIdsResponse;
import javax.net.p2p.model.DbRowQueryIdsStreamRequest;
import javax.net.p2p.model.DbTableSchema;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.model.StreamP2PWrapper;
import javax.net.p2p.server.handler.DbMetaPutServerHandler;
import javax.net.p2p.server.handler.DbRowPutServerHandler;
import javax.net.p2p.server.handler.DbRowQueryIdsStreamServerHandler;
import javax.net.p2p.utils.SerializationUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class DbRowQueryIdsStreamServerHandlerTest {
    @BeforeAll
    public static void initPools() {
        ExecutorServicePool.createServerPools();
    }

    @AfterAll
    public static void shutdownPools() {
        ExecutorServicePool.releaseP2PServerPools();
    }

    @Test
    public void streamQueryIdsReturnsChunksUntilCompleted() throws Exception {
        registerUdpHandler(P2PCommand.DB_ROW_QUERY_IDS_STREAM, new DbRowQueryIdsStreamServerHandler());

        java.io.File home = Files.createTempDirectory("dsdb-stream-query-ids").toFile();
        String old = System.getProperty("p2p.db.home");
        try {
            System.setProperty("p2p.db.home", home.getAbsolutePath());

            String table = "com.example.DynamicUserStreamQueryIdsV1";
            DbTableSchema schema = new DbTableSchema();
            schema.columns.add(new DbColumnSchema("username", "java.lang.String", 32, 0, 0));
            schema.columns.add(new DbColumnSchema("age", "int", 4, 0, 0));
            DbMetaPutRequest putMeta = new DbMetaPutRequest(table, schema, true);
            P2PWrapper metaResp = new DbMetaPutServerHandler().process(P2PWrapper.build(200, P2PCommand.DB_META_PUT, putMeta));
            Assertions.assertEquals(P2PCommand.R_OK_DB_META_PUT, metaResp.getCommand());

            long r1 = putRow(table, "bob", 12);
            long r2 = putRow(table, "alice", 12);
            long r3 = putRow(table, "amy", 12);
            Assertions.assertTrue(r1 > 0 && r2 > 0 && r3 > 0);

            DbQuery q = new DbQuery();
            q.where.add(new DbQueryCriterion(DbQueryOp.EQ, "age", "12", null, null));
            DbRowQueryIdsStreamRequest payload = new DbRowQueryIdsStreamRequest(table, q, 0, 100, 2);

            TestProcessor p = new TestProcessor(0x1234, 16);
            EmbeddedChannel ch = new EmbeddedChannel();
            ch.pipeline().addLast(p);
            ChannelHandlerContext ctx = ch.pipeline().context(p);
            InetSocketAddress sender = new InetSocketAddress("127.0.0.1", 20011);
            DatagramPacket pkt = new DatagramPacket(Unpooled.EMPTY_BUFFER, new InetSocketAddress("127.0.0.1", 0), sender);

            int seq = 77;
            StreamP2PWrapper req = StreamP2PWrapper.buildStream(seq, 0, P2PCommand.DB_ROW_QUERY_IDS_STREAM, payload, true);
            p.processMessage(ctx, pkt, req);

            P2PWrapper<?> ack = p.outgoing.poll();
            Assertions.assertNotNull(ack);
            Assertions.assertEquals(seq, ack.getSeq());
            Assertions.assertEquals(P2PCommand.STREAM_ACK, ack.getCommand());

            ArrayList<Long> got = new ArrayList<>();
            boolean completed = false;
            for (int i = 0; i < 400; i++) {
                P2PWrapper<?> m = p.outgoing.poll();
                if (m == null) {
                    Thread.sleep(5);
                    continue;
                }
                if (!(m instanceof StreamP2PWrapper<?> sm)) {
                    continue;
                }
                if (sm.getCommand() != P2PCommand.R_OK_DB_ROW_QUERY_IDS_STREAM) {
                    continue;
                }
                Assertions.assertEquals(seq, sm.getSeq());
                Assertions.assertTrue(sm.getIndex() >= 0);

                DbRowQueryIdsResponse chunkPayload = (DbRowQueryIdsResponse) sm.getData();
                long[] ids = SerializationUtil.deserialize(long[].class, chunkPayload.idsBytes);
                if (ids != null) {
                    Assertions.assertTrue(ids.length <= 2);
                    for (long v : ids) {
                        if (v > 0L) {
                            got.add(v);
                        }
                    }
                }
                if (sm.isCompleted()) {
                    completed = true;
                    break;
                }
            }
            Assertions.assertTrue(completed);

            HashSet<Long> set = new HashSet<>(got);
            Assertions.assertEquals(3, set.size());
            Assertions.assertTrue(set.contains(r1));
            Assertions.assertTrue(set.contains(r2));
            Assertions.assertTrue(set.contains(r3));
            ch.finishAndReleaseAll();
        } finally {
            if (old == null) {
                System.clearProperty("p2p.db.home");
            } else {
                System.setProperty("p2p.db.home", old);
            }
        }
    }

    @Test
    public void streamQueryIdsOrderByTopKReturnsOrderedIds() throws Exception {
        registerUdpHandler(P2PCommand.DB_ROW_QUERY_IDS_STREAM, new DbRowQueryIdsStreamServerHandler());

        java.io.File home = Files.createTempDirectory("dsdb-stream-query-ids-orderby").toFile();
        String old = System.getProperty("p2p.db.home");
        try {
            System.setProperty("p2p.db.home", home.getAbsolutePath());

            String table = "com.example.DynamicUserStreamQueryIdsOrderByV1";
            DbTableSchema schema = new DbTableSchema();
            schema.columns.add(new DbColumnSchema("username", "java.lang.String", 32, 0, 0));
            schema.columns.add(new DbColumnSchema("age", "int", 4, 0, 0));
            DbMetaPutRequest putMeta = new DbMetaPutRequest(table, schema, true);
            P2PWrapper metaResp = new DbMetaPutServerHandler().process(P2PWrapper.build(210, P2PCommand.DB_META_PUT, putMeta));
            Assertions.assertEquals(P2PCommand.R_OK_DB_META_PUT, metaResp.getCommand());

            putRow(table, "u1", 12);
            putRow(table, "u2", 10);
            putRow(table, "u3", 11);
            putRow(table, "u4", 9);

            DbQuery q = new DbQuery();
            q.orderBy.add(new DbQueryOrder("age", true));
            DbRowQueryIdsStreamRequest payload = new DbRowQueryIdsStreamRequest(table, q, 0, 3, 2);

            TestProcessor p = new TestProcessor(0x1234, 16);
            EmbeddedChannel ch = new EmbeddedChannel();
            ch.pipeline().addLast(p);
            ChannelHandlerContext ctx = ch.pipeline().context(p);
            InetSocketAddress sender = new InetSocketAddress("127.0.0.1", 20012);
            DatagramPacket pkt = new DatagramPacket(Unpooled.EMPTY_BUFFER, new InetSocketAddress("127.0.0.1", 0), sender);

            int seq = 78;
            StreamP2PWrapper req = StreamP2PWrapper.buildStream(seq, 0, P2PCommand.DB_ROW_QUERY_IDS_STREAM, payload, true);
            p.processMessage(ctx, pkt, req);

            P2PWrapper<?> ack = p.outgoing.poll();
            Assertions.assertNotNull(ack);
            Assertions.assertEquals(seq, ack.getSeq());
            Assertions.assertEquals(P2PCommand.STREAM_ACK, ack.getCommand());

            ArrayList<Long> got = new ArrayList<>();
            boolean completed = false;
            for (int i = 0; i < 400; i++) {
                P2PWrapper<?> m = p.outgoing.poll();
                if (m == null) {
                    Thread.sleep(5);
                    continue;
                }
                if (!(m instanceof StreamP2PWrapper<?> sm)) {
                    continue;
                }
                if (sm.getCommand() != P2PCommand.R_OK_DB_ROW_QUERY_IDS_STREAM) {
                    continue;
                }
                DbRowQueryIdsResponse chunkPayload = (DbRowQueryIdsResponse) sm.getData();
                long[] ids = SerializationUtil.deserialize(long[].class, chunkPayload.idsBytes);
                if (ids != null) {
                    for (long v : ids) {
                        if (v > 0L) {
                            got.add(v);
                        }
                    }
                }
                if (sm.isCompleted()) {
                    completed = true;
                    break;
                }
            }
            Assertions.assertTrue(completed);
            Assertions.assertEquals(3, got.size());

            ColumnarStore store = new ColumnarStore(home);
            ArrayList<Integer> ages = new ArrayList<>();
            for (long rowId : got) {
                byte[] b = store.getValue(table, "age", rowId);
                Assertions.assertNotNull(b);
                ages.add(ByteBuffer.wrap(b).getInt());
            }
            Assertions.assertEquals(java.util.List.of(9, 10, 11), ages);
            ch.finishAndReleaseAll();
        } finally {
            if (old == null) {
                System.clearProperty("p2p.db.home");
            } else {
                System.setProperty("p2p.db.home", old);
            }
        }
    }

    @Test
    public void streamQueryIdsCancelStopsFurtherOutput() throws Exception {
        registerUdpHandler(P2PCommand.DB_ROW_QUERY_IDS_STREAM, new DbRowQueryIdsStreamServerHandler());

        java.io.File home = Files.createTempDirectory("dsdb-stream-query-ids-cancel").toFile();
        String old = System.getProperty("p2p.db.home");
        try {
            System.setProperty("p2p.db.home", home.getAbsolutePath());

            String table = "com.example.DynamicUserStreamQueryIdsCancelV1";
            DbTableSchema schema = new DbTableSchema();
            schema.columns.add(new DbColumnSchema("username", "java.lang.String", 32, 0, 0));
            schema.columns.add(new DbColumnSchema("age", "int", 4, 0, 0));
            DbMetaPutRequest putMeta = new DbMetaPutRequest(table, schema, true);
            P2PWrapper metaResp = new DbMetaPutServerHandler().process(P2PWrapper.build(220, P2PCommand.DB_META_PUT, putMeta));
            Assertions.assertEquals(P2PCommand.R_OK_DB_META_PUT, metaResp.getCommand());

            for (int i = 0; i < 200; i++) {
                putRow(table, "u" + i, 12);
            }

            DbQuery q = new DbQuery();
            q.where.add(new DbQueryCriterion(DbQueryOp.EQ, "age", "12", null, null));
            DbRowQueryIdsStreamRequest payload = new DbRowQueryIdsStreamRequest(table, q, 0, 10_000, 1);

            TestProcessor p = new TestProcessor(0x1234, 16);
            EmbeddedChannel ch = new EmbeddedChannel();
            ch.pipeline().addLast(p);
            ChannelHandlerContext ctx = ch.pipeline().context(p);
            InetSocketAddress sender = new InetSocketAddress("127.0.0.1", 20013);
            DatagramPacket pkt = new DatagramPacket(Unpooled.EMPTY_BUFFER, new InetSocketAddress("127.0.0.1", 0), sender);

            int seq = 79;
            StreamP2PWrapper req = StreamP2PWrapper.buildStream(seq, 0, P2PCommand.DB_ROW_QUERY_IDS_STREAM, payload, true);
            p.processMessage(ctx, pkt, req);

            P2PWrapper<?> ack = waitFor(p.outgoing, seq, P2PCommand.STREAM_ACK, 400);
            Assertions.assertNotNull(ack);

            P2PWrapper<?> first = waitFor(p.outgoing, seq, P2PCommand.R_OK_DB_ROW_QUERY_IDS_STREAM, 800);
            Assertions.assertNotNull(first);

            // 发送 STD_CANCEL 后，服务端应尽快终止该 seq 的流任务，不再继续输出大量 chunk
            p.processMessage(ctx, pkt, P2PWrapper.build(seq, P2PCommand.STD_CANCEL, null));
            P2PWrapper<?> cancel = waitFor(p.outgoing, seq, P2PCommand.STD_CANCEL, 800);
            Assertions.assertNotNull(cancel);

            // 取消后允许有少量“在途/已入队”的 chunk，但应很快进入稳定的空闲期（不再持续产出）
            long stableNs = 0L;
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline) {
                P2PWrapper<?> m = p.outgoing.poll();
                if (m != null) {
                    stableNs = 0L;
                    continue;
                }
                if (stableNs == 0L) {
                    stableNs = System.nanoTime();
                }
                long quietNs = System.nanoTime() - stableNs;
                if (quietNs >= java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(200)) {
                    break;
                }
                Thread.sleep(10);
            }
            Assertions.assertTrue(stableNs != 0L);
            Assertions.assertTrue(System.nanoTime() - stableNs >= java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(200));
            ch.finishAndReleaseAll();
        } finally {
            if (old == null) {
                System.clearProperty("p2p.db.home");
            } else {
                System.setProperty("p2p.db.home", old);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void registerUdpHandler(P2PCommand cmd, Object handler) throws Exception {
        Field f = javax.net.p2p.channel.AbstractUdpMessageProcessor.class.getDeclaredField("HANDLER_REGISTRY_MAP");
        f.setAccessible(true);
        ConcurrentHashMap<P2PCommand, Object> map = (ConcurrentHashMap<P2PCommand, Object>) f.get(null);
        map.put(cmd, handler);
    }

    private static P2PWrapper<?> waitFor(
        ConcurrentLinkedQueue<P2PWrapper<?>> outgoing,
        int seq,
        P2PCommand cmd,
        long timeoutMs
    ) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            P2PWrapper<?> m = outgoing.poll();
            if (m == null) {
                Thread.sleep(5);
                continue;
            }
            if (m.getSeq() == seq && m.getCommand() == cmd) {
                return m;
            }
        }
        return null;
    }

    private static long putRow(String table, String username, int age) throws Exception {
        java.util.List<DbCellValue> values = new java.util.ArrayList<>();
        values.add(new DbCellValue("username", username.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        ByteBuffer buf = ByteBuffer.allocate(4);
        buf.putInt(age);
        values.add(new DbCellValue("age", buf.array()));
        DbRowPutRequest putRow = new DbRowPutRequest(table, 0L, true, values);
        P2PWrapper putRowResp = new DbRowPutServerHandler().process(P2PWrapper.build(0, P2PCommand.DB_ROW_PUT, putRow));
        Assertions.assertEquals(P2PCommand.R_OK_DB_ROW_PUT, putRowResp.getCommand());
        return ((DbRowPutResponse) putRowResp.getData()).rowId;
    }

    static class TestProcessor extends ServerUdpMessageProcessor {
        final ConcurrentLinkedQueue<P2PWrapper<?>> outgoing = new ConcurrentLinkedQueue<>();

        TestProcessor(int magic, int queueSize) {
            super(null, magic, queueSize);
        }

        @Override
        public void sendResponse(Channel channel, InetSocketAddress remoteAddess, P2PWrapper response, int magic) {
            outgoing.add(response);
        }

        @Override
        protected AbstractSendMesageExecutor createExecutor(ChannelHandlerContext ctx, InetSocketAddress remote, int magic) {
            return new FakeExecutor(outgoing);
        }
    }

    static class FakeExecutor extends AbstractSendMesageExecutor {
        private final ConcurrentLinkedQueue<P2PWrapper<?>> outgoing;

        FakeExecutor(ConcurrentLinkedQueue<P2PWrapper<?>> outgoing) {
            super(16);
            this.outgoing = outgoing;
            this.connected = true;
        }

        @Override
        public void connect(io.netty.channel.EventLoopGroup io_work_group, io.netty.bootstrap.Bootstrap bootstrap) {
        }

        @Override
        public void recycle() {
        }

        @Override
        public void sendResponse(P2PWrapper response) {
            outgoing.add(response);
        }
    }
}
