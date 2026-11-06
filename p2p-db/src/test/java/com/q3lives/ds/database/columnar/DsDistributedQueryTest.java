package com.q3lives.ds.database.columnar;

import com.q3lives.ds.cluster.DsNodeContext;
import com.q3lives.ds.cluster.DsPartitionRouter;
import com.q3lives.ds.cluster.DsServerDirectory;
import com.q3lives.ds.collections.DsHashMap;
import com.q3lives.ds.collections.DsHashMapIdAllocator;
import java.io.File;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.util.List;
import javax.net.p2p.server.P2PServerTcp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 分布式查询聚合集成测试。
 *
 * <p>在双节点集群中写入跨 serverId 与跨 partition 的数据，通过
 * {@link DsDatabaseClusterClient#queryIds(String, javax.net.p2p.model.DbQuery, int, int)}
 * 验证全集群查询能聚合所有分区结果并按 offset/limit 正确分页。</p>
 */
public class DsDistributedQueryTest {

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

    @BeforeEach
    public void setUp() throws Exception {
        baseDir = Files.createTempDirectory("dsdb-query").toFile();

        manager1 = new DsPartitionManager(new File(baseDir, "node1"), DsPartitionRouter.maxPartitionCount(), new String[0]);
        manager2 = new DsPartitionManager(new File(baseDir, "node2"), DsPartitionRouter.maxPartitionCount(), new String[0]);
        DsNodeContext.register(1, manager1);
        DsNodeContext.register(2, manager2);

        allocator = new DsHashMapIdAllocator(new DsHashMap(new File(baseDir, "agg.map")));

        port1 = randomTcpPort();
        port2 = randomTcpPort();

        server1 = new P2PServerTcp(port1);
        server2 = new P2PServerTcp(port2);
        serverThread1 = new Thread(server1::start, "p2p-query-1-" + port1);
        serverThread2 = new Thread(server2::start, "p2p-query-2-" + port2);
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
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (client1 != null) {
            client1.close();
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
    public void testClusterWideQueryIdsAggregatesAcrossNodesAndPartitions() throws Exception {
        String entity = DsDistributedTestRow.class.getName();

        long rowId1 = allocator.allocateRowId(1, 5);
        long rowId2 = allocator.allocateRowId(1, 5);
        long rowId3 = allocator.allocateRowId(1, 6);
        long rowId4 = allocator.allocateRowId(2, 5);
        long rowId5 = allocator.allocateRowId(2, 7);

        client1.putCol(entity, rowId1, "username", "user one!!!!!!!!".getBytes(), true);
        client1.putCol(entity, rowId2, "username", "user two!!!!!!!!".getBytes(), true);
        client1.putCol(entity, rowId3, "username", "user three!!!!!!".getBytes(), true);
        client1.putCol(entity, rowId4, "username", "user four!!!!!!!".getBytes(), true);
        client1.putCol(entity, rowId5, "username", "user five!!!!!!!".getBytes(), true);

        List<Long> all = client1.queryIds(entity, null, 0, 100);
        assertEquals(5, all.size());
        assertTrue(all.contains(rowId1));
        assertTrue(all.contains(rowId2));
        assertTrue(all.contains(rowId3));
        assertTrue(all.contains(rowId4));
        assertTrue(all.contains(rowId5));

        // 全局排序后 natural order 即按 rowId 升序
        for (int i = 1; i < all.size(); i++) {
            assertTrue(all.get(i - 1) < all.get(i));
        }

        List<Long> paged = client1.queryIds(entity, null, 1, 2);
        assertEquals(2, paged.size());
        assertEquals(all.get(1), paged.get(0));
        assertEquals(all.get(2), paged.get(1));
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
