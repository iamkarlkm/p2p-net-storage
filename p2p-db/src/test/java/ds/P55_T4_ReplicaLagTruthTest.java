package ds;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.binlog.DsBinlogReplicator;
import com.q3lives.ds.cluster.DsClusterMonitor;
import com.q3lives.ds.cluster.DsNodeContext;
import com.q3lives.ds.cluster.DsServerDirectory;
import com.q3lives.ds.cluster.stats.DbStatsRegistry;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.integration.GenericManager;

/**
 * P55 T4（p2p-db 部分）：replication lag 真值（2 TR）。
 *
 * <p>tr44 {@code DsBinlogReplicator.replicationLag()} = source.sequence − applied（预置游标）；tr45
 * {@code DsClusterMonitor.replicaLag} 反映 {@code DbStatsRegistry} 聚合真值。
 *
 * <p>端点侧 tr41-tr43 位于 p2p-sync 的 {@code P55_T4_DbStatsEndpointTest}（依赖 HttpServer）。
 */
public class P55_T4_ReplicaLagTruthTest {

    /** 测试实体：name/age 两列。 */
    public static class UserT4 extends DsTableAdapter {
        @DsField(name = "name", length = 48)
        public String name;
        @DsField(name = "age", length = 4)
        public int age;

        public UserT4() {
        }
    }

    @Before
    public void setUp() {
        DbStatsRegistry.clear();
    }

    @After
    public void tearDown() {
        DbStatsRegistry.clear();
        DsClusterMonitor.clear();
        DsNodeContext.clear();
    }

    @Test(timeout = 60000)
    public void tr44_replicationLagEqualsSourceMinusApplied() throws Exception {
        File rootA = tmp("p55_t4_a_");
        File rootB = tmp("p55_t4_b_");
        File cursorDir = tmp("p55_t4_c_");
        DsDatabaseLocal dbA = new DsDatabaseLocal(rootA, 1, 0);
        DsDatabaseLocal dbB = new DsDatabaseLocal(rootB, 2, 0);
        try {
            GenericManager<UserT4> gm = new GenericManager<>(UserT4.class, dbA, 1, 0);
            for (int i = 0; i < 10; i++) {
                UserT4 u = new UserT4();
                u.name = "p55_t4_user_" + i;
                u.age = i;
                Assert.assertEquals(1, gm.saveOrUpdate(u));
            }
            long srcSeq = dbA.getBinlogStore().getCurrentPosition().sequence();
            Assert.assertTrue("source 序列应 ≥ 10", srcSeq >= 10L);

            // 全新游标：未定位 → 0
            DsBinlogReplicator fresh = new DsBinlogReplicator(dbA, dbB, cursorDir, 100L);
            try {
                Assert.assertEquals("未定位游标时 lag 应为 0", 0L, fresh.replicationLag());
            } finally {
                fresh.close();
            }

            // 预置游标 = srcSeq − 3 → lag 应恰为 3
            long applied = srcSeq - 3L;
            File cursorFile = new File(cursorDir, "binlog_cursor_1_0_to_2_0.yaml");
            Files.write(cursorFile.toPath(),
                    ("x|0|" + applied + "\n").getBytes(StandardCharsets.UTF_8));
            DsBinlogReplicator seeded = new DsBinlogReplicator(dbA, dbB, cursorDir, 100L);
            try {
                Assert.assertEquals("lag = src − applied", 3L, seeded.replicationLag());
            } finally {
                seeded.close();
            }
        } finally {
            dbA.close();
            dbB.close();
        }
    }

    @Test(timeout = 30000)
    public void tr45_clusterMonitorReplicaLagUsesRegistryTruth() {
        DbStatsRegistry.registerReplicator("r1", () -> 42L);
        DsClusterMonitor.registerDirectory(new DsServerDirectory() {
            @Override
            public int[] listServerIds() {
                return new int[]{1};
            }

            @Override
            public Endpoint endpoint(int serverId) {
                return new Endpoint("127.0.0.1", 20011, 16);
            }
        });

        Map<String, Object> snap = DsClusterMonitor.snapshotMap();
        Assert.assertEquals(Boolean.TRUE, snap.get("ok"));
        List<?> servers = (List<?>) snap.get("servers");
        Assert.assertEquals(1, servers.size());
        Map<?, ?> server = (Map<?, ?>) servers.get(0);
        Assert.assertEquals("replicaLag 必须为注册真值（非 0）",
                Long.valueOf(42L), server.get("replicaLag"));

        // 未注册任何 replicator 源 → 回落 0（结构不变）
        DbStatsRegistry.clear();
        Map<String, Object> snap2 = DsClusterMonitor.snapshotMap();
        Map<?, ?> server2 = (Map<?, ?>) ((List<?>) snap2.get("servers")).get(0);
        Assert.assertEquals(Long.valueOf(0L), server2.get("replicaLag"));
    }

    private static File tmp(String prefix) throws Exception {
        File f = Files.createTempDirectory(prefix).toFile();
        f.deleteOnExit();
        return f;
    }
}
