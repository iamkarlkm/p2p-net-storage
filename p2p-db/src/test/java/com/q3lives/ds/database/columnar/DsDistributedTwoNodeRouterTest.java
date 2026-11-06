package com.q3lives.ds.database.columnar;

import com.q3lives.ds.cluster.DsIdLayout;
import com.q3lives.ds.cluster.DsNodeContext;
import com.q3lives.ds.cluster.DsNodeRouter;
import com.q3lives.ds.cluster.DsPartitionRouter;
import com.q3lives.ds.cluster.DsServerDirectory;
import com.q3lives.ds.collections.DsHashMap;
import com.q3lives.ds.collections.DsHashMapIdAllocator;
import java.io.File;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.util.List;
import javax.net.p2p.client.P2PClientTcp;
import javax.net.p2p.model.DbColPutRequest;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.model.StreamP2PWrapper;
import javax.net.p2p.server.P2PServerTcp;
import javax.net.p2p.utils.SerializationUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 双节点跨路由集成测试。
 *
 * <p>在同一 JVM 中启动两个 TCP P2P 服务，分别代表 serverId 1 与 serverId 2。
 * 通过 {@link DsDatabaseClusterClient} 写入/读取对方节点上的 rowId，验证
 * DB_NODE_PUT/GET/REMOVE/QUERY_IDS 命令与跨节点路由正确工作。</p>
 */
public class DsDistributedTwoNodeRouterTest {

    private File baseDir;
    private DsPartitionManager manager1;
    private DsPartitionManager manager2;
    private DsHashMapIdAllocator allocator;
    private P2PServerTcp server1;
    private P2PServerTcp server2;
    private Thread serverThread1;
    private Thread serverThread2;
    private int port1;
    private int port2;
    private DsDatabaseClusterClient client1;
    private DsDatabaseClusterClient client2;

    @BeforeEach
    public void setUp() throws Exception {
        baseDir = Files.createTempDirectory("dsdb-two-node").toFile();

        manager1 = new DsPartitionManager(new File(baseDir, "node1"), DsPartitionRouter.maxPartitionCount(), new String[0]);
        manager2 = new DsPartitionManager(new File(baseDir, "node2"), DsPartitionRouter.maxPartitionCount(), new String[0]);
        DsNodeContext.register(1, manager1);
        DsNodeContext.register(2, manager2);

        allocator = new DsHashMapIdAllocator(new DsHashMap(new File(baseDir, "agg.map")));

        port1 = randomTcpPort();
        port2 = randomTcpPort();

        server1 = new P2PServerTcp(port1);
        server2 = new P2PServerTcp(port2);
        serverThread1 = new Thread(server1::start, "p2p-node-1-" + port1);
        serverThread2 = new Thread(server2::start, "p2p-node-2-" + port2);
        serverThread1.setDaemon(true);
        serverThread2.setDaemon(true);
        serverThread1.start();
        serverThread2.start();
        Thread.sleep(1000);

        DsServerDirectory directory = new DsServerDirectory() {
            @Override
            public int[] listServerIds() {
                return new int[]{1, 2};
            }

            @Override
            public Endpoint endpoint(int serverId) {
                return switch (serverId) {
                    case 1 -> new Endpoint("127.0.0.1", port1, 4096);
                    case 2 -> new Endpoint("127.0.0.1", port2, 4096);
                    default -> null;
                };
            }
        };

        client1 = new DsDatabaseClusterClient(1, manager1, allocator, directory);
        client2 = new DsDatabaseClusterClient(2, manager2, allocator, directory);
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (client1 != null) {
            client1.close();
        }
        if (client2 != null) {
            client2.close();
        }
        if (server1 != null) {
            server1.stop();
        }
        if (server2 != null) {
            server2.stop();
        }
        if (serverThread1 != null) {
            serverThread1.interrupt();
            serverThread1.join(2000);
        }
        if (serverThread2 != null) {
            serverThread2.interrupt();
            serverThread2.join(2000);
        }
        DsNodeContext.clear();
        deleteDir(baseDir);
    }

    @Test
    public void testRemotePutGetRemoveOnNode2() throws Exception {
        String entity = DsDistributedTestRow.class.getName();
        long rowId = allocator.allocateRowId(2, 7);

        // client1 写入 server2 上的 rowId（跨节点）
        long valueId = client1.putCol(entity, rowId, "username", "sixteen chars!!!".getBytes(), true);
        assertTrue(valueId > 0L);

        // client2（本地节点 2）读取
        byte[] got = client2.getCol(entity, rowId, "username");
        assertArrayEquals("sixteen chars!!!".getBytes(), got);

        // client1 跨节点删除
        assertTrue(client1.removeCol(entity, rowId, "username"));

        // 再次读取应为 null
        assertNull(client2.getCol(entity, rowId, "username"));
    }

    @Test
    public void testRemotePutGetFromNode1() throws Exception {
        String entity = DsDistributedTestRow.class.getName();
        long rowId = allocator.allocateRowId(1, 3);

        // client1 写入 server1 本地
        client1.putCol(entity, rowId, "bio", "hello from node1".getBytes(), true);

        // client2 跨节点读取 server1
        byte[] got = client2.getCol(entity, rowId, "bio");
        assertArrayEquals("hello from node1".getBytes(), got);
    }

    @Test
    public void testRemoteQueryIds() throws Exception {
        String entity = DsDistributedTestRow.class.getName();
        long rowId1 = allocator.allocateRowId(2, 5);
        long rowId2 = allocator.allocateRowId(2, 5);
        long rowId3 = allocator.allocateRowId(2, 6);

        client1.putCol(entity, rowId1, "username", "user one!!!!!!!!".getBytes(), true);
        client1.putCol(entity, rowId2, "username", "user two!!!!!!!!".getBytes(), true);
        client1.putCol(entity, rowId3, "username", "user three!!!!!!".getBytes(), true);

        // 查询 server2 partition 5 的 ids
        long anchor = DsIdLayout.compose(2, 5, 1L);
        List<Long> ids = client1.queryIds(entity, anchor, null, 0, 100);
        assertEquals(2, ids.size());
        assertTrue(ids.contains(rowId1));
        assertTrue(ids.contains(rowId2));

        // offset/limit
        List<Long> paged = client2.queryIds(entity, anchor, null, 1, 1);
        assertEquals(1, paged.size());
    }

    @Test
    public void testSerializeRoundTrip() throws Exception {
        DbColPutRequest req = new DbColPutRequest("com.q3lives.ds.database.columnar.DsDistributedTestRow", 12345L, "username", "sixteen chars!!!".getBytes(), true);
        StreamP2PWrapper<DbColPutRequest> wrapper = StreamP2PWrapper.buildStream(0, 0, javax.net.p2p.api.P2PCommand.DB_NODE_PUT, req);
        byte[] bytes = SerializationUtil.serialize(wrapper);
        @SuppressWarnings("unchecked")
        StreamP2PWrapper<DbColPutRequest> decoded = SerializationUtil.deserialize(StreamP2PWrapper.class, bytes);
        assertNotNull(decoded);
        assertEquals(javax.net.p2p.api.P2PCommand.DB_NODE_PUT, decoded.getCommand());
        DbColPutRequest payload = decoded.getData();
        assertNotNull(payload);
        assertEquals(req.entityClassName, payload.entityClassName);
        assertEquals(req.rowId, payload.rowId);
        assertEquals(req.logicalName, payload.logicalName);
        assertArrayEquals(req.valueBytes, payload.valueBytes);
        assertEquals(req.upsertRow, payload.upsertRow);
    }

    @Test
    public void testSerializeByteBufRoundTrip() throws Exception {
        DbColPutRequest req = new DbColPutRequest("com.q3lives.ds.database.columnar.DsDistributedTestRow", 12345L, "username", "sixteen chars!!!".getBytes(), true);
        StreamP2PWrapper<DbColPutRequest> wrapper = StreamP2PWrapper.buildStream(0, 0, javax.net.p2p.api.P2PCommand.DB_NODE_PUT, req);
        byte[] bytesDirect = SerializationUtil.serialize(wrapper);
        io.netty.buffer.ByteBuf buf = javax.net.p2p.utils.SerializationUtil.serializeToByteBuf(wrapper, 0x12345678);
        int length = buf.readInt();
        int magic = buf.readInt();
        assertEquals(0x12345678, magic);
        byte[] payload = new byte[length];
        buf.readBytes(payload);
        System.out.println("direct bytes length=" + bytesDirect.length + ", bytebuf payload length=" + payload.length);
        System.out.println("direct bytes hex=" + bytesToHex(bytesDirect));
        System.out.println("bytebuf payload hex=" + bytesToHex(payload));
        assertArrayEquals(bytesDirect, payload, "ByteBuf serialization payload must match direct serialization");
        @SuppressWarnings("unchecked")
        StreamP2PWrapper<DbColPutRequest> decoded = SerializationUtil.deserialize(StreamP2PWrapper.class, payload);
        assertNotNull(decoded);
        assertEquals(javax.net.p2p.api.P2PCommand.DB_NODE_PUT, decoded.getCommand());
        DbColPutRequest payload2 = decoded.getData();
        assertNotNull(payload2);
        assertEquals(req.entityClassName, payload2.entityClassName);
        io.netty.util.ReferenceCountUtil.safeRelease(buf);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private int randomTcpPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void deleteDir(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        File[] fs = dir.listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (f.isDirectory()) {
                    deleteDir(f);
                } else {
                    f.delete();
                }
            }
        }
        dir.delete();
    }
}
