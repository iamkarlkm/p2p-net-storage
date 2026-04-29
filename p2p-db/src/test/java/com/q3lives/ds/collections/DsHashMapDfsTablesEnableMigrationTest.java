package com.q3lives.ds.collections;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.dfsmap.model.DfsMapGetReq;
import javax.net.p2p.dfsmap.model.DfsMapGetResp;
import javax.net.p2p.dfsmap.model.DfsMapPutReq;
import javax.net.p2p.dfsmap.model.DfsMapPutResp;
import javax.net.p2p.dfsmap.model.DfsMapRangeReq;
import javax.net.p2p.dfsmap.model.DfsMapRangeResp;
import javax.net.p2p.dfsmap.model.DfsMapStatusCodes;
import javax.net.p2p.dfsmap.model.DfsMapTablesEnableReq;
import javax.net.p2p.dfsmap.model.DfsMapTablesEnableResp;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class DsHashMapDfsTablesEnableMigrationTest {
    private File rootDir;

    @Before
    public void setUp() throws Exception {
        File base = new File("target");
        assertTrue(base.exists() || base.mkdirs());
        rootDir = Files.createTempDirectory(base.toPath(), "test_dshashmap_dfs_migrate_").toFile();
    }

    @After
    public void tearDown() {
        deleteRecursively(rootDir);
    }

    @Test
    public void testEnableTablesMigratesAllServersAndKeepsData() {
        DsHashMapDfs[] cluster = createCluster(false);

        int total = 300;
        Map<Long, Long> expectedValues = new HashMap<>();
        List<Long> keys = new ArrayList<>(total);

        for (int i = 0; i < total; i++) {
            int owner = i % 4;
            long key = composeKey(owner, i + 1);
            long value = key * 17 + 5;
            DfsMapPutReq putReq = new DfsMapPutReq();
            putReq.setKey(key);
            putReq.setValue(value);
            putReq.setReturnOldValue(false);
            DfsMapPutResp putResp = cluster[0].handlePut(putReq);
            assertEquals(DfsMapStatusCodes.OK, putResp.getStatus());
            expectedValues.put(key, value);
            keys.add(key);
        }

        for (int sid = 0; sid < cluster.length; sid++) {
            assertFalse(cluster[sid].handlePing(null).isTablesEnabled());
        }

        DfsMapTablesEnableReq enableReq = new DfsMapTablesEnableReq();
        enableReq.setApiVersion(1);
        enableReq.setEpoch(0L);
        enableReq.setMigrationId(777L);
        enableReq.setCoordinatorServerId(0);
        enableReq.setForwarded(false);
        enableReq.setForce(true);
        DfsMapTablesEnableResp enableResp = cluster[0].handleTablesEnable(P2PCommand.DFS_MAP_INT_TABLES_ENABLE_BEGIN, enableReq);
        assertEquals(DfsMapStatusCodes.OK, enableResp.getStatus());
        assertTrue(enableResp.isTablesEnabled());

        for (int sid = 0; sid < cluster.length; sid++) {
            var ping = cluster[sid].handlePing(new javax.net.p2p.dfsmap.model.DfsMapPingReq());
            assertEquals(DfsMapStatusCodes.OK, ping.getStatus());
            assertTrue(ping.isTablesEnabled());
            assertNotNull(ping.getTableSizes());
            assertEquals(256, ping.getTableSizes().length);
        }

        for (long key : keys) {
            DfsMapGetReq getReq = new DfsMapGetReq();
            getReq.setKey(key);
            DfsMapGetResp getResp = cluster[0].handleGet(getReq);
            assertEquals(DfsMapStatusCodes.OK, getResp.getStatus());
            assertTrue(getResp.isFound());
            assertEquals(expectedValues.get(key).longValue(), getResp.getValue());
        }

        List<Long> expectedOrder = buildExpectedOrder(keys);
        DfsMapRangeReq rangeReq = new DfsMapRangeReq();
        rangeReq.setStart(0L);
        rangeReq.setCount(keys.size());
        rangeReq.setKeysOnly(true);
        DfsMapRangeResp rangeResp = cluster[0].handleRange(rangeReq);
        assertEquals(DfsMapStatusCodes.OK, rangeResp.getStatus());
        assertEquals(keys.size(), rangeResp.getEmitted());
        assertNotNull(rangeResp.getKeys());
        assertEquals(keys.size(), rangeResp.getKeys().length);
        for (int i = 0; i < expectedOrder.size(); i++) {
            assertEquals(expectedOrder.get(i).longValue(), rangeResp.getKeys()[i]);
        }
    }

    private DsHashMapDfs[] createCluster(boolean tablesEnabled) {
        int[] serverIds = new int[]{0, 1, 2, 3};
        DsHashMapDfs.ServerDirectory directory = new DsHashMapDfs.ServerDirectory() {
            @Override
            public int[] listServerIds() {
                return serverIds;
            }

            @Override
            public DsHashMapDfs.Endpoint endpoint(int serverId) {
                return new DsHashMapDfs.Endpoint("127.0.0.1", 0, 0);
            }
        };

        DsHashMapDfs[] cluster = new DsHashMapDfs[4];
        InProcessRemoteCaller caller = new InProcessRemoteCaller(cluster);
        for (int i = 0; i < cluster.length; i++) {
            File baseDir = new File(rootDir, "s" + i);
            assertTrue(baseDir.mkdirs());
            cluster[i] = new DsHashMapDfs(baseDir, i, directory, tablesEnabled, caller);
        }
        return cluster;
    }

    private static List<Long> buildExpectedOrder(List<Long> keys) {
        int[] serverOrder = orderedServerIds(new int[]{0, 1, 2, 3});
        int[] tableOrder = orderedTableIds();
        List<Long> expected = new ArrayList<>(keys.size());
        for (int sid : serverOrder) {
            for (int tid : tableOrder) {
                List<Long> group = new ArrayList<>();
                for (long key : keys) {
                    if (ownerServerId(key) == sid && tableId(key) == tid) {
                        group.add(key);
                    }
                }
                group.sort(DsHashMapDfsTablesEnableMigrationTest::compareKeyOrder);
                expected.addAll(group);
            }
        }
        return expected;
    }

    private static int ownerServerId(long key) {
        return (int) ((key >>> 52) & 0xFFFL);
    }

    private static int tableId(long key) {
        return (int) (key & 0xFFL);
    }

    private static int compareKeyOrder(long keyA, long keyB) {
        long orderA = keyA ^ 0x0080808080808080L;
        long orderB = keyB ^ 0x0080808080808080L;
        int c = Long.compare(orderA, orderB);
        if (c != 0) {
            return c;
        }
        return Long.compare(keyA, keyB);
    }

    private static long composeKey(int ownerServerId, long nodeId) {
        long sid = ((long) ownerServerId & 0xFFFL) << 52;
        long nid = nodeId & ((1L << 52) - 1);
        return sid | nid;
    }

    private static int[] orderedTableIds() {
        int[] out = new int[256];
        int index = 0;
        for (int i = 128; i < 256; i++) {
            out[index++] = i;
        }
        for (int i = 0; i < 128; i++) {
            out[index++] = i;
        }
        return out;
    }

    private static int[] orderedServerIds(int[] ids) {
        long[] ranked = new long[ids.length];
        for (int i = 0; i < ids.length; i++) {
            int id = ids[i] & 0xFFF;
            int rank = (id ^ 0x808) & 0xFFF;
            ranked[i] = (((long) rank) << 32) | (id & 0xFFFF_FFFFL);
        }
        java.util.Arrays.sort(ranked);
        int[] out = new int[ranked.length];
        for (int i = 0; i < ranked.length; i++) {
            out[i] = (int) ranked[i];
        }
        return out;
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        try {
            file.delete();
        } catch (Exception ignored) {
        }
    }

    private static final class InProcessRemoteCaller implements DsHashMapDfs.RemoteCaller {
        private final DsHashMapDfs[] backends;

        private InProcessRemoteCaller(DsHashMapDfs[] backends) {
            this.backends = backends;
        }

        @Override
        public javax.net.p2p.dfsmap.model.DfsMapExecKvResp execKv(int serverId, javax.net.p2p.dfsmap.model.DfsMapExecKvReq req) {
            return backends[serverId].handleExecKv(req);
        }

        @Override
        public javax.net.p2p.dfsmap.model.DfsMapRangeLocalResp rangeLocal(int serverId, javax.net.p2p.dfsmap.model.DfsMapRangeLocalReq req) {
            return backends[serverId].handleRangeLocal(req);
        }

        @Override
        public javax.net.p2p.dfsmap.model.DfsMapPingResp ping(int serverId, javax.net.p2p.dfsmap.model.DfsMapPingReq req) {
            return backends[serverId].handlePing(req);
        }

        @Override
        public javax.net.p2p.dfsmap.model.DfsMapTablesEnableResp tablesEnable(int serverId, javax.net.p2p.api.P2PCommand command, javax.net.p2p.dfsmap.model.DfsMapTablesEnableReq req) {
            return backends[serverId].handleTablesEnable(command, req);
        }
    }
}
