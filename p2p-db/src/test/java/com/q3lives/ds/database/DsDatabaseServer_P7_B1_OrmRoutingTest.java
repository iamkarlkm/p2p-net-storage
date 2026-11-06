package com.q3lives.ds.database;

import java.io.File;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.p2p.client.AbstractP2PClient;
import javax.net.p2p.common.AbstractSendMesageExecutor;
import javax.net.p2p.model.P2PWrapper;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.cluster.DsIdLayout;
import com.q3lives.ds.cluster.DsServerDirectory;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.integration.GenericManager;

public class DsDatabaseServer_P7_B1_OrmRoutingTest {

    public static class B1User extends DsTableAdapter {
        @DsField(name = "name", length = 32)
        public String name;
        @DsField(name = "age", length = 4)
        public int age;
        public B1User() {}
    }

    private static final InetSocketAddress DUMMY_ADDR = new InetSocketAddress("127.0.0.1", 18999);

    private static class CountingMockP2PClient extends AbstractP2PClient {
        final AtomicInteger count = new AtomicInteger(0);
        final P2PWrapper reply;
        CountingMockP2PClient(P2PWrapper reply) {
            super(DUMMY_ADDR, 2, 2, 0xABCD);
            this.reply = reply;
        }
        @Override public AbstractSendMesageExecutor newSendMesageExecutorToQueue() { return null; }
        @Override public P2PWrapper excute(P2PWrapper msg) {
            count.incrementAndGet();
            return reply;
        }
    }

    private static class NoopMockP2PClient extends AbstractP2PClient {
        NoopMockP2PClient() {
            super(DUMMY_ADDR, 2, 2, 0xABCD);
        }
        @Override public AbstractSendMesageExecutor newSendMesageExecutorToQueue() { return null; }
        @Override public P2PWrapper excute(P2PWrapper msg) { return null; }
    }

    private static File tmpDir(String prefix) throws Exception {
        File f = Files.createTempDirectory(prefix).toFile();
        f.deleteOnExit();
        return f;
    }

    private static DsServerDirectory mapDir(Map<Integer,DsServerDirectory.Endpoint> m) {
        final Map<Integer,DsServerDirectory.Endpoint> copy = new HashMap<>(m);
        return new DsServerDirectory() {
            @Override public int[] listServerIds() {
                return copy.keySet().stream().mapToInt(Integer::intValue).toArray();
            }
            @Override public DsServerDirectory.Endpoint endpoint(int serverId) {
                return copy.get(serverId);
            }
        };
    }

    @Test
    public void test_B1_T7_GM_LocalShortcut_RoundTrip() throws Exception {
        File root = tmpDir("dsdb_p7_b1_t7_");
        DsDatabaseLocal.forceResetAllIndexesForTest(root);
        int sid = 7;
        int pid = 11;
        DsDatabaseLocal db = new DsDatabaseLocal(root, sid, pid);
        Assertions.assertEquals(sid, db.serverId());
        Assertions.assertEquals(pid, db.partitionId());
        GenericManager<B1User> gm = new GenericManager<>(B1User.class, db, sid, pid);
        B1User u = new B1User();
        u.name = "alice-t7";
        u.age = 25;
        int inserted = gm.saveOrUpdate(u);
        Assertions.assertEquals(1, inserted);
        Long id = u.getId();
        Assertions.assertNotNull(id);
        Assertions.assertTrue(id > 0L);
        Assertions.assertTrue(gm.existsById(id));
        B1User loaded = gm.getById(id);
        Assertions.assertNotNull(loaded);
        Assertions.assertEquals("alice-t7", loaded.name);
        Assertions.assertEquals(25, loaded.age);
        loaded.age = 26;
        int updated = gm.updateById(loaded);
        Assertions.assertEquals(1, updated);
        Assertions.assertEquals(26, gm.getById(id).age);
        int removed = gm.removeById(id);
        Assertions.assertEquals(1, removed);
        Assertions.assertFalse(gm.existsById(id));
        db.close();
    }

    @Test
    public void test_B1_T8_IsLocal_TrueAndFalse() throws Exception {
        File root = tmpDir("dsdb_p7_b1_t8_");
        final int LOCAL_SID = 5;
        final int LOCAL_PID = 3;
        Map<Integer,DsServerDirectory.Endpoint> eps = new HashMap<>();
        eps.put(LOCAL_SID, new DsServerDirectory.Endpoint("127.0.0.1", 19000+LOCAL_SID, 128));
        eps.put(6, new DsServerDirectory.Endpoint("127.0.0.1", 19006, 128));
        DsServerDirectory dir = mapDir(eps);
        DsDatabaseServer server = new DsDatabaseServer(LOCAL_SID, LOCAL_PID, dir, root);
        long localRow = DsIdLayout.compose(LOCAL_SID, LOCAL_PID, 100L);
        long remoteRow = DsIdLayout.compose(6, LOCAL_PID, 100L);
        long remotePid = DsIdLayout.compose(LOCAL_SID, 7, 100L);
        Assertions.assertTrue(server.isLocal(localRow));
        Assertions.assertFalse(server.isLocal(remoteRow));
        Assertions.assertFalse(server.isLocal(remotePid));
        Assertions.assertEquals(LOCAL_SID, server.localServerId());
        Assertions.assertEquals(LOCAL_PID, server.localPartitionId());
        Assertions.assertNotNull(server.localInstanceOrNull());
        server.close();
    }

    @Test
    public void test_B1_T9_LegacyMode_Zero_NoRemoteClients() throws Exception {
        NoopMockP2PClient noop = new NoopMockP2PClient();
        DsDatabaseServer server = new DsDatabaseServer(noop, null);
        Field dm = DsDatabaseServer.class.getDeclaredField("distributedMode");
        dm.setAccessible(true);
        boolean distributedMode = (boolean) dm.get(server);
        Field rc = DsDatabaseServer.class.getDeclaredField("remoteClients");
        rc.setAccessible(true);
        Object remoteClientsVal = rc.get(server);
        Assertions.assertFalse(distributedMode, "legacy mode must have distributedMode=false");
        Assertions.assertNull(remoteClientsVal, "legacy mode must have null remoteClients (no overhead)");
        Assertions.assertSame(noop, server.legacyClientOrNull());
        server.close();
    }

    @Test
    public void test_B1_T10_LegacyClient_CalledExactlyOnce() throws Exception {
        P2PWrapper okReply = P2PWrapper.build(javax.net.p2p.api.P2PCommand.R_OK_DB_ENTITY_EXISTS,
                new javax.net.p2p.model.DbEntityExistsResponse(true));
        CountingMockP2PClient counting = new CountingMockP2PClient(okReply);
        DsDatabaseServer server = new DsDatabaseServer(counting, null);
        boolean exists = server.exists(B1User.class, 1234L);
        Assertions.assertTrue(exists);
        Assertions.assertEquals(1, counting.count.get(),
                "legacy single-client routing must invoke client.excute exactly once per ORM exists call");
        server.close();
    }

    @Test
    public void test_B1_T11_GM_DistMode_Put_RoutedToLocalDb() throws Exception {
        File root = tmpDir("dsdb_p7_b1_t11_");
        DsDatabaseLocal.forceResetAllIndexesForTest(root);
        final int SID = 3;
        final int PID = 5;
        Map<Integer,DsServerDirectory.Endpoint> eps = new HashMap<>();
        eps.put(SID, new DsServerDirectory.Endpoint("127.0.0.1", 19003, 256));
        DsServerDirectory dir = mapDir(eps);
        DsDatabaseServer distServer = new DsDatabaseServer(SID, PID, dir, root);
        DsDatabaseLocal localDb = distServer.localInstanceOrNull();
        Assertions.assertNotNull(localDb);
        GenericManager<B1User> gmDist = new GenericManager<>(B1User.class, distServer);
        B1User u = new B1User();
        u.name = "carol-t11";
        u.age = 30;
        int inserted = gmDist.saveOrUpdate(u);
        Assertions.assertEquals(1, inserted);
        Assertions.assertNotNull(u.getId());
        Assertions.assertTrue(u.getId() > 0L);
        Assertions.assertTrue(gmDist.existsById(u.getId()));
        B1User fromLocal = localDb.getTable(B1User.class, u.getId(), Long.MAX_VALUE);
        Assertions.assertNotNull(fromLocal);
        Assertions.assertEquals("carol-t11", fromLocal.name);
        Assertions.assertEquals(30, fromLocal.age);
        B1User viaGm = gmDist.getById(u.getId());
        Assertions.assertNotNull(viaGm);
        Assertions.assertEquals(fromLocal.name, viaGm.name);
        distServer.close();
    }

    @Test
    public void test_B1_T12_LegacyConstructor_BackwardCompat_TenOpsEquivalent() throws Exception {
        File root = tmpDir("dsdb_p7_b1_t12_");
        DsDatabaseLocal.forceResetAllIndexesForTest(root);
        String oldHome = System.getProperty("p2p.db.home");
        try {
            System.setProperty("p2p.db.home", root.getAbsolutePath());
            GenericManager<B1User> legacy = new GenericManager<>(B1User.class);
            for (int i = 0; i < 10; i++) {
                B1User u = new B1User();
                u.name = "legacy_user_" + i;
                u.age = 18 + i;
                Assertions.assertEquals(1, legacy.saveOrUpdate(u));
                Assertions.assertNotNull(u.getId());
                Assertions.assertTrue(legacy.existsById(u.getId()));
                B1User loaded = legacy.getById(u.getId());
                Assertions.assertEquals(u.name, loaded.name);
                Assertions.assertEquals(u.age, loaded.age);
                if (i % 2 == 0) {
                    loaded.age += 1;
                    Assertions.assertEquals(1, legacy.updateById(loaded));
                    Assertions.assertEquals(u.age + 1, legacy.getById(u.getId()).age);
                }
            }
            DsDatabaseLocal directDb = DsDatabaseLocal.load();
            GenericManager<B1User> viaDb = new GenericManager<>(B1User.class, directDb, 0, 0);
            int total = 0;
            for (int i = 0; i < 10; i++) {
                String name = "legacy_user_" + i;
                com.q3lives.ds.database.integration.QueryWrapper<B1User> w = viaDb.buildQueryWrapper();
                w.eq("name", name);
                if (!viaDb.findList(w).isEmpty()) total++;
            }
            Assertions.assertEquals(10, total,
                    "legacy constructor insertions must be visible via new local-shortcut GM");
            directDb.close();
        } finally {
            if (oldHome == null) System.clearProperty("p2p.db.home");
            else System.setProperty("p2p.db.home", oldHome);
        }
    }
}
