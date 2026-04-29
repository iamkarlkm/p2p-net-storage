package com.q3lives.ds.collections;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.net.p2p.dfsmap.model.DfsMapPutReq;
import javax.net.p2p.dfsmap.model.DfsMapPutResp;
import javax.net.p2p.dfsmap.model.DfsMapRangeReq;
import javax.net.p2p.dfsmap.model.DfsMapRangeResp;
import javax.net.p2p.dfsmap.model.DfsMapStatusCodes;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class DsHashMapDfsRangeTest {
    private File rootDir;
    private DsHashMapDfs[] servers;

    @Before
    public void setUp() throws Exception {
        File base = new File("target");
        assertTrue(base.exists() || base.mkdirs());
        rootDir = Files.createTempDirectory(base.toPath(), "test_dshashmap_dfs_").toFile();
        servers = createCluster("a", false);
    }

    @After
    public void tearDown() {
        deleteRecursively(rootDir);
    }

    @Test
    public void testRangeIsGlobalHashOrderAndPaginates() {
        DsHashMapDfs client = servers[0];

        int total = 200;
        Map<Long, Long> expectedValues = new HashMap<>();
        List<Long> keys = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            int owner = i % 4;
            long key = composeKey(owner, i + 1);
            long value = key * 10 + 7;
            DfsMapPutReq putReq = new DfsMapPutReq();
            putReq.setKey(key);
            putReq.setValue(value);
            putReq.setReturnOldValue(false);
            DfsMapPutResp putResp = client.handlePut(putReq);
            assertEquals(DfsMapStatusCodes.OK, putResp.getStatus());
            expectedValues.put(key, value);
            keys.add(key);
        }
        List<Long> expectedKeys = buildExpectedOrderNoTables(keys, new int[]{0, 1, 2, 3});

        DfsMapRangeReq reqAll = new DfsMapRangeReq();
        reqAll.setStart(0);
        reqAll.setCount(total);
        reqAll.setKeysOnly(false);
        DfsMapRangeResp respAll = client.handleRange(reqAll);
        assertEquals(DfsMapStatusCodes.OK, respAll.getStatus());
        assertEquals(total, respAll.getEmitted());
        assertNotNull(respAll.getKeys());
        assertNotNull(respAll.getValues());
        assertEquals(total, respAll.getKeys().length);
        assertEquals(total, respAll.getValues().length);
        for (int i = 0; i < total; i++) {
            long key = respAll.getKeys()[i];
            long value = respAll.getValues()[i];
            assertEquals(expectedKeys.get(i).longValue(), key);
            assertEquals(expectedValues.get(key).longValue(), value);
        }

        for (int start = 0; start < total; start += 37) {
            int count = Math.min(25, total - start);
            DfsMapRangeReq req = new DfsMapRangeReq();
            req.setStart(start);
            req.setCount(count);
            req.setKeysOnly(true);
            DfsMapRangeResp resp = client.handleRange(req);
            assertEquals(DfsMapStatusCodes.OK, resp.getStatus());
            assertEquals(count, resp.getEmitted());
            assertNotNull(resp.getKeys());
            assertEquals(count, resp.getKeys().length);
            assertNull(resp.getValues());
            for (int i = 0; i < count; i++) {
                assertEquals(expectedKeys.get(start + i).longValue(), resp.getKeys()[i]);
            }
        }
    }

    @Test
    public void testRangeTablesEnabled_ServerThenTableOrderAndPaginates() {
        DsHashMapDfs[] cluster = createCluster("b", true);
        DsHashMapDfs client = cluster[0];

        int total = 240;
        Map<Long, Long> expectedValues = new HashMap<>();
        List<Long> keys = new ArrayList<>(total);
        boolean[] usedTables = new boolean[256];
        int usedTableCount = 0;
        long seq = 1;
        while (keys.size() < total || usedTableCount < 8) {
            int owner = (int) (seq % 4);
            int tableId = (int) (seq & 0xFF);
            long key = composeKeyWithTable(owner, seq, tableId);
            if (!usedTables[tableId]) {
                usedTables[tableId] = true;
                usedTableCount++;
            }
            long value = key * 31 + 11;
            DfsMapPutReq putReq = new DfsMapPutReq();
            putReq.setKey(key);
            putReq.setValue(value);
            putReq.setReturnOldValue(false);
            DfsMapPutResp putResp = client.handlePut(putReq);
            assertEquals(DfsMapStatusCodes.OK, putResp.getStatus());
            expectedValues.put(key, value);
            if (keys.size() < total) {
                keys.add(key);
            }
            seq++;
        }

        int[] serverOrder = orderedServerIds(new int[]{0, 1, 2, 3});
        int[] tableOrder = orderedTableIds();
        List<Long> expectedKeys = new ArrayList<>(keys.size());
        for (int sid : serverOrder) {
            for (int tid : tableOrder) {
                List<Long> group = new ArrayList<>();
                for (long key : keys) {
                    if (ownerServerId(key) == sid && tableId(key) == tid) {
                        group.add(key);
                    }
                }
                group.sort(DsHashMapDfsRangeTest::compareKeyOrder);
                expectedKeys.addAll(group);
            }
        }
        assertEquals(keys.size(), expectedKeys.size());

        DfsMapRangeReq reqAll = new DfsMapRangeReq();
        reqAll.setStart(0);
        reqAll.setCount(keys.size());
        reqAll.setKeysOnly(false);
        DfsMapRangeResp respAll = client.handleRange(reqAll);
        assertEquals(DfsMapStatusCodes.OK, respAll.getStatus());
        assertEquals(keys.size(), respAll.getEmitted());
        assertNotNull(respAll.getKeys());
        assertNotNull(respAll.getValues());
        for (int i = 0; i < keys.size(); i++) {
            long key = respAll.getKeys()[i];
            long value = respAll.getValues()[i];
            assertEquals(expectedKeys.get(i).longValue(), key);
            assertEquals(expectedValues.get(key).longValue(), value);
        }

        for (int start = 0; start < keys.size(); start += 41) {
            int count = Math.min(17, keys.size() - start);
            DfsMapRangeReq req = new DfsMapRangeReq();
            req.setStart(start);
            req.setCount(count);
            req.setKeysOnly(true);
            DfsMapRangeResp resp = client.handleRange(req);
            assertEquals(DfsMapStatusCodes.OK, resp.getStatus());
            assertEquals(count, resp.getEmitted());
            assertNotNull(resp.getKeys());
            assertEquals(count, resp.getKeys().length);
            assertNull(resp.getValues());
            for (int i = 0; i < count; i++) {
                assertEquals(expectedKeys.get(start + i).longValue(), resp.getKeys()[i]);
            }
        }
    }

    private DsHashMapDfs[] createCluster(String prefix, boolean tablesEnabled) {
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
            File baseDir = new File(rootDir, prefix + "_s" + i);
            assertTrue(baseDir.mkdirs());
            cluster[i] = new DsHashMapDfs(baseDir, i, directory, tablesEnabled, caller);
        }
        return cluster;
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

    private static List<Long> buildExpectedOrderNoTables(List<Long> keys, int[] serverIds) {
        int[] serverOrder = orderedServerIds(serverIds);
        List<Long> expected = new ArrayList<>(keys.size());
        for (int sid : serverOrder) {
            List<Long> group = new ArrayList<>();
            for (long key : keys) {
                if (ownerServerId(key) == sid) {
                    group.add(key);
                }
            }
            group.sort(DsHashMapDfsRangeTest::compareKeyOrder);
            expected.addAll(group);
        }
        return expected;
    }

    private static long composeKey(int ownerServerId, long nodeId) {
        long sid = ((long) ownerServerId & 0xFFFL) << 52;
        long nid = nodeId & ((1L << 52) - 1);
        return sid | nid;
    }

    private static long composeKeyWithTable(int ownerServerId, long seq, int tableId) {
        long sid = ((long) ownerServerId & 0xFFFL) << 52;
        long nodePart = ((seq & ((1L << 44) - 1)) << 8) | ((long) tableId & 0xFFL);
        return sid | nodePart;
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
