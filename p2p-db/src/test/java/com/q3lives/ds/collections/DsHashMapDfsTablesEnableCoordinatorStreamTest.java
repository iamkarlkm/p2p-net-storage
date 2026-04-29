package com.q3lives.ds.collections;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import javax.net.p2p.dfsmap.model.DfsMapGetReq;
import javax.net.p2p.dfsmap.model.DfsMapGetResp;
import javax.net.p2p.dfsmap.model.DfsMapPutReq;
import javax.net.p2p.dfsmap.model.DfsMapPutResp;
import javax.net.p2p.dfsmap.model.DfsMapStatusCodes;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class DsHashMapDfsTablesEnableCoordinatorStreamTest {
    private File rootDir;

    @Before
    public void setUp() throws Exception {
        File base = new File("target");
        assertTrue(base.exists() || base.mkdirs());
        rootDir = Files.createTempDirectory(base.toPath(), "test_dshashmap_dfs_coord_stream_").toFile();
    }

    @After
    public void tearDown() {
        deleteRecursively(rootDir);
    }

    @Test
    public void testCoordinatorOnlyCallCompletesClusterStreamMigration() {
        DsHashMapDfs[] cluster = createCluster(false);

        int total = 240;
        Map<Long, Long> expected = new HashMap<>();
        for (int i = 0; i < total; i++) {
            int owner = i % 4;
            long key = composeKey(owner, i + 1);
            long value = key * 13 + 9;
            DfsMapPutReq putReq = new DfsMapPutReq();
            putReq.setKey(key);
            putReq.setValue(value);
            putReq.setReturnOldValue(false);
            DfsMapPutResp putResp = cluster[0].handlePut(putReq);
            assertEquals(DfsMapStatusCodes.OK, putResp.getStatus());
            expected.put(key, value);
        }

        long migrationId = 4242L;
        var enableResp = cluster[0].enableTablesStepByStepDistributed(migrationId, true, 128);
        assertEquals(DfsMapStatusCodes.OK, enableResp.getStatus());
        assertTrue(enableResp.isTablesEnabled());

        for (int sid = 0; sid < cluster.length; sid++) {
            var ping = cluster[sid].handlePing(new javax.net.p2p.dfsmap.model.DfsMapPingReq());
            assertEquals(DfsMapStatusCodes.OK, ping.getStatus());
            assertTrue(ping.isTablesEnabled());
            assertNotNull(ping.getTableSizes());
            assertEquals(256, ping.getTableSizes().length);
        }

        for (Map.Entry<Long, Long> entry : expected.entrySet()) {
            DfsMapGetReq getReq = new DfsMapGetReq();
            getReq.setKey(entry.getKey());
            DfsMapGetResp getResp = cluster[0].handleGet(getReq);
            assertEquals(DfsMapStatusCodes.OK, getResp.getStatus());
            assertTrue(getResp.isFound());
            assertEquals(entry.getValue().longValue(), getResp.getValue());
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

    private static int ownerServerId(long key) {
        return (int) ((key >>> 52) & 0xFFFL);
    }

    private static long composeKey(int ownerServerId, long nodeId) {
        long sid = ((long) ownerServerId & 0xFFFL) << 52;
        long nid = nodeId & ((1L << 52) - 1);
        return sid | nid;
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
