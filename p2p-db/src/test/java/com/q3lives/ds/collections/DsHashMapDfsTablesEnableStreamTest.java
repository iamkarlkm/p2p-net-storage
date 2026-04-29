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
import javax.net.p2p.dfsmap.model.DfsMapStatusCodes;
import javax.net.p2p.dfsmap.model.DfsMapTablesEnableReq;
import javax.net.p2p.dfsmap.model.DfsMapTablesEnableResp;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class DsHashMapDfsTablesEnableStreamTest {
    private File rootDir;

    @Before
    public void setUp() throws Exception {
        File base = new File("target");
        assertTrue(base.exists() || base.mkdirs());
        rootDir = Files.createTempDirectory(base.toPath(), "test_dshashmap_dfs_stream_").toFile();
    }

    @After
    public void tearDown() {
        deleteRecursively(rootDir);
    }

    @Test
    public void testStepByStepEnableViaStreamDumpApplyCommit() {
        DsHashMapDfs[] cluster = createCluster(false);

        int total = 260;
        Map<Long, Long> expectedValues = new HashMap<>();
        List<Long> keys = new ArrayList<>(total);

        for (int i = 0; i < total; i++) {
            int owner = i % 4;
            long key = composeKey(owner, i + 1);
            long value = key * 9 + 3;
            DfsMapPutReq putReq = new DfsMapPutReq();
            putReq.setKey(key);
            putReq.setValue(value);
            putReq.setReturnOldValue(false);
            DfsMapPutResp putResp = cluster[0].handlePut(putReq);
            assertEquals(DfsMapStatusCodes.OK, putResp.getStatus());
            expectedValues.put(key, value);
            keys.add(key);
        }

        long migrationId = 9009L;

        DfsMapTablesEnableReq begin = new DfsMapTablesEnableReq();
        begin.setApiVersion(1);
        begin.setEpoch(0L);
        begin.setMigrationId(migrationId);
        begin.setCoordinatorServerId(0);
        begin.setForwarded(false);
        begin.setForce(true);
        begin.setStepByStep(true);
        DfsMapTablesEnableResp beginResp = cluster[0].handleTablesEnable(P2PCommand.DFS_MAP_INT_TABLES_ENABLE_BEGIN, begin);
        assertEquals(DfsMapStatusCodes.OK, beginResp.getStatus());
        assertFalse(beginResp.isTablesEnabled());

        DfsMapTablesEnableReq prepare = new DfsMapTablesEnableReq();
        prepare.setApiVersion(1);
        prepare.setEpoch(0L);
        prepare.setMigrationId(migrationId);
        prepare.setCoordinatorServerId(0);
        prepare.setForwarded(false);
        prepare.setForce(true);
        prepare.setStepByStep(true);
        DfsMapTablesEnableResp prepareResp = cluster[0].handleTablesEnable(P2PCommand.DFS_MAP_INT_TABLES_ENABLE_PREPARE, prepare);
        assertEquals(DfsMapStatusCodes.OK, prepareResp.getStatus());
        assertFalse(prepareResp.isTablesEnabled());

        for (int sid = 0; sid < cluster.length; sid++) {
            long cursor = 0L;
            while (true) {
                DfsMapTablesEnableReq dump = new DfsMapTablesEnableReq();
                dump.setApiVersion(1);
                dump.setEpoch(0L);
                dump.setMigrationId(migrationId);
                dump.setCoordinatorServerId(0);
                dump.setForwarded(true);
                dump.setStepByStep(true);
                dump.setCursor(cursor);
                dump.setLimit(128);
                DfsMapTablesEnableResp dumpResp = cluster[sid].handleTablesEnable(P2PCommand.DFS_MAP_INT_TABLES_ENABLE_STREAM_DUMP, dump);
                assertEquals(DfsMapStatusCodes.OK, dumpResp.getStatus());
                int emitted = dumpResp.getEmitted();
                if (emitted == 0) {
                    break;
                }
                assertNotNull(dumpResp.getKeys());
                assertNotNull(dumpResp.getValues());
                assertEquals(emitted, dumpResp.getKeys().length);
                assertEquals(emitted, dumpResp.getValues().length);

                DfsMapTablesEnableReq apply = new DfsMapTablesEnableReq();
                apply.setApiVersion(1);
                apply.setEpoch(0L);
                apply.setMigrationId(migrationId);
                apply.setCoordinatorServerId(0);
                apply.setForwarded(true);
                apply.setStepByStep(true);
                apply.setKeys(dumpResp.getKeys());
                apply.setValues(dumpResp.getValues());
                DfsMapTablesEnableResp applyResp = cluster[sid].handleTablesEnable(P2PCommand.DFS_MAP_INT_TABLES_ENABLE_STREAM_APPLY, apply);
                assertEquals(DfsMapStatusCodes.OK, applyResp.getStatus());
                assertEquals(emitted, applyResp.getEmitted());

                cursor = dumpResp.getNextCursor();
            }
        }

        DfsMapTablesEnableReq commit = new DfsMapTablesEnableReq();
        commit.setApiVersion(1);
        commit.setEpoch(0L);
        commit.setMigrationId(migrationId);
        commit.setCoordinatorServerId(0);
        commit.setForwarded(false);
        commit.setStepByStep(true);
        DfsMapTablesEnableResp commitResp = cluster[0].handleTablesEnable(P2PCommand.DFS_MAP_INT_TABLES_ENABLE_COMMIT, commit);
        assertEquals(DfsMapStatusCodes.OK, commitResp.getStatus());
        assertTrue(commitResp.isTablesEnabled());

        for (long key : keys) {
            DfsMapGetReq getReq = new DfsMapGetReq();
            getReq.setKey(key);
            DfsMapGetResp getResp = cluster[0].handleGet(getReq);
            assertEquals(DfsMapStatusCodes.OK, getResp.getStatus());
            assertTrue(getResp.isFound());
            assertEquals(expectedValues.get(key).longValue(), getResp.getValue());
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
