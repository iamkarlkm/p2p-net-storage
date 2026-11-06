package com.q3lives.ds.database.columnar;

import com.q3lives.ds.cluster.DsNodeContext;
import com.q3lives.ds.cluster.DsPartitionRouter;
import com.q3lives.ds.cluster.DsServerDirectory;
import com.q3lives.ds.collections.DsHashMap;
import com.q3lives.ds.collections.DsHashMapIdAllocator;
import java.io.File;
import java.net.ServerSocket;
import java.nio.file.Files;
import javax.net.p2p.server.P2PServerTcp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 三节点副本 fan-out 集成测试。
 *
 * <p>serverId 1 为主节点，serverId 2/3 为其副本（R=2）。写入主节点后，
 * 验证两个副本节点均可读到一致数据；删除主节点后，副本也同步删除。</p>
 */
public class DsDistributedReplicaTest {

    private File baseDir;
    private DsPartitionManager manager1;
    private DsPartitionManager manager2;
    private DsPartitionManager manager3;
    private DsHashMapIdAllocator allocator;
    private P2PServerTcp server1;
    private P2PServerTcp server2;
    private P2PServerTcp server3;
    private Thread serverThread1;
    private Thread serverThread2;
    private Thread serverThread3;
    private int port1;
    private int port2;
    private int port3;
    private DsDatabaseClusterClient client1;
    private DsDatabaseClusterClient client2;
    private DsDatabaseClusterClient client3;

    @BeforeEach
    public void setUp() throws Exception {
        baseDir = Files.createTempDirectory("dsdb-replica").toFile();

        manager1 = new DsPartitionManager(new File(baseDir, "node1"), DsPartitionRouter.maxPartitionCount(), new String[0]);
        manager2 = new DsPartitionManager(new File(baseDir, "node2"), DsPartitionRouter.maxPartitionCount(), new String[0]);
        manager3 = new DsPartitionManager(new File(baseDir, "node3"), DsPartitionRouter.maxPartitionCount(), new String[0]);
        DsNodeContext.register(1, manager1);
        DsNodeContext.register(2, manager2);
        DsNodeContext.register(3, manager3);

        allocator = new DsHashMapIdAllocator(new DsHashMap(new File(baseDir, "agg.map")));

        port1 = randomTcpPort();
        port2 = randomTcpPort();
        port3 = randomTcpPort();

        server1 = new P2PServerTcp(port1);
        server2 = new P2PServerTcp(port2);
        server3 = new P2PServerTcp(port3);
        serverThread1 = new Thread(server1::start, "p2p-replica-1-" + port1);
        serverThread2 = new Thread(server2::start, "p2p-replica-2-" + port2);
        serverThread3 = new Thread(server3::start, "p2p-replica-3-" + port3);
        serverThread1.setDaemon(true);
        serverThread2.setDaemon(true);
        serverThread3.setDaemon(true);
        serverThread1.start();
        serverThread2.start();
        serverThread3.start();
        Thread.sleep(1000);

        DsServerDirectory directory = new DsServerDirectory() {
            @Override
            public int[] listServerIds() {
                return new int[]{1, 2, 3};
            }

            @Override
            public Endpoint endpoint(int serverId) {
                return switch (serverId) {
                    case 1 -> new Endpoint("127.0.0.1", port1, 4096);
                    case 2 -> new Endpoint("127.0.0.1", port2, 4096);
                    case 3 -> new Endpoint("127.0.0.1", port3, 4096);
                    default -> null;
                };
            }

            @Override
            public int[] replicas(int serverId) {
                if (serverId == 1) {
                    return new int[]{2, 3};
                }
                return new int[0];
            }
        };

        client1 = new DsDatabaseClusterClient(1, manager1, allocator, directory);
        client2 = new DsDatabaseClusterClient(2, manager2, allocator, directory);
        client3 = new DsDatabaseClusterClient(3, manager3, allocator, directory);
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (client1 != null) {
            client1.close();
        }
        if (client2 != null) {
            client2.close();
        }
        if (client3 != null) {
            client3.close();
        }
        if (server1 != null) {
            server1.stop();
        }
        if (server2 != null) {
            server2.stop();
        }
        if (server3 != null) {
            server3.stop();
        }
        if (serverThread1 != null) {
            serverThread1.interrupt();
            serverThread1.join(2000);
        }
        if (serverThread2 != null) {
            serverThread2.interrupt();
            serverThread2.join(2000);
        }
        if (serverThread3 != null) {
            serverThread3.interrupt();
            serverThread3.join(2000);
        }
        DsNodeContext.clear();
        deleteDir(baseDir);
    }

    @Test
    public void testReplicaFanOutPutAndRemove() throws Exception {
        String entity = DsDistributedTestRow.class.getName();
        long rowId = allocator.allocateRowId(1, 5);

        // 写入主节点 1，应 fan-out 到副本 2 和 3
        long valueId = client1.putCol(entity, rowId, "username", "sixteen chars!!!".getBytes(), true);
        assertTrue(valueId > 0L);

        // 副本 2 应能读到
        byte[] got2 = client2.getCol(entity, rowId, "username");
        assertArrayEquals("sixteen chars!!!".getBytes(), got2);

        // 副本 3 应能读到
        byte[] got3 = client3.getCol(entity, rowId, "username");
        assertArrayEquals("sixteen chars!!!".getBytes(), got3);

        // 删除主节点，应 fan-out 删除到副本
        assertTrue(client1.removeCol(entity, rowId, "username"));
        assertNull(client2.getCol(entity, rowId, "username"));
        assertNull(client3.getCol(entity, rowId, "username"));
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
