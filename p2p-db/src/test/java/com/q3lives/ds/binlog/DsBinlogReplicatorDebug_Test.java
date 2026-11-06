package com.q3lives.ds.binlog;

import java.io.File;
import java.nio.file.Files;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.integration.GenericManager;

public class DsBinlogReplicatorDebug_Test {

    public static class DebugUser extends DsTableAdapter {
        @DsField(name = "name", length = 48)
        public String name;
        public DebugUser() {}
    }

    private static File tmpDir(String p) throws Exception {
        File f = Files.createTempDirectory(p).toFile();
        f.deleteOnExit();
        return f;
    }

    @Test
    public void test_Debug_DirectDoTick() throws Exception {
        File rootA = tmpDir("dbug_a_");
        File rootB = tmpDir("dbug_b_");
        File cursor = tmpDir("dbug_c_");
        DsDatabaseLocal.forceResetAllIndexesForTest(rootA);
        DsDatabaseLocal.forceResetAllIndexesForTest(rootB);
        DsDatabaseLocal a = new DsDatabaseLocal(rootA, 11, 0);
        DsDatabaseLocal b = new DsDatabaseLocal(rootB, 12, 0);
        GenericManager<DebugUser> gmA = new GenericManager<>(DebugUser.class, a, 11, 0);
        GenericManager<DebugUser> gmB = new GenericManager<>(DebugUser.class, b, 12, 0);
        for (int i = 0; i < 3; i++) {
            DebugUser u = new DebugUser();
            u.name = "row_" + i;
            gmA.saveOrUpdate(u);
        }
        long seq = a.getBinlogStore().lastSequence();
        DsBinlogPosition end = a.getBinlogStore().getCurrentPosition();
        java.util.List<String> dayKeys = a.getBinlogStore().listDayKeys();

        // Force targetDb registerTable via a dummy insert+remove (ensure tableId→Class mapping exists before replay)
        DebugUser warm = new DebugUser();
        warm.name = "warm_target";
        gmB.saveOrUpdate(warm);
        gmB.removeById(warm.getId());
        int emptySize = gmB.findList(gmB.buildQueryWrapper()).size();
        Assertions.assertEquals(0, emptySize);

        // Print tableId side-by-side for debug
        java.lang.reflect.Method stableTableIdM = DsDatabaseLocal.class.getDeclaredMethod("stableTableId", Class.class);
        stableTableIdM.setAccessible(true);
        int tA = (int) stableTableIdM.invoke(a, DebugUser.class);
        int tB = (int) stableTableIdM.invoke(b, DebugUser.class);

        DsBinlogPosition actualStart = new DsBinlogPosition("", 0, 0);
        int applied;
        final int[] success = {0};
        final long[] lastEncoded = {0L};
        try {
            DsBinlogContext.setInReplay(true);
            final java.lang.reflect.Field rowLengthField = Class.forName("com.q3lives.ds.database.DsDatabaseLocal$TableMeta").getDeclaredField("rowLength");
            rowLengthField.setAccessible(true);
            java.lang.reflect.Method metaOfM = DsDatabaseLocal.class.getDeclaredMethod("metaOf", Class.class);
            metaOfM.setAccessible(true);
            Object bMeta = metaOfM.invoke(b, DebugUser.class);
            int bRowLen = (int) rowLengthField.get(bMeta);
            Object aMeta = metaOfM.invoke(a, DebugUser.class);
            int aRowLen = (int) rowLengthField.get(aMeta);
            final int expectedRL = aRowLen;
            final int bRL = bRowLen;
            applied = a.getBinlogStore().replayBinlog(actualStart, end, (offset, ent) -> {
                boolean ok = b.applyBinlogEntry(offset, ent);
                if (ent.opType() == DsBinlogOpType.INSERT) {
                    long rId = com.q3lives.ds.bucket.DsFixedBucketStore.encodeRowId(
                            ent.rowKey() & 0xFFFFFFFFL, expectedRL);
                    lastEncoded[0] = rId;
                    try {
                        Object v = b.getTable(DebugUser.class, rId, Long.MAX_VALUE);
                        DebugUser dv = (DebugUser) v;
                        int fields = 0;
                        if (dv != null) {
                            if (dv.name != null && !dv.name.isEmpty()) fields++;
                        }
                        System.err.println("[D] INSERT rk=" + ent.rowKey() + " rId=" + rId
                                + " entity=" + (dv == null ? "NULL" : ("name=" + dv.name))
                                + " ok=" + ok + " fields=" + fields);
                        if (dv != null && dv.name != null && !dv.name.isEmpty()) success[0]++;
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                return ok;
            });
            java.util.List<Long> allRowIds = new java.util.ArrayList<>();
            for (int i = 0; i < 50; i++) {
                long rId = com.q3lives.ds.bucket.DsFixedBucketStore.encodeRowId((long)i, aRowLen);
                DebugUser dv = b.getTable(DebugUser.class, rId, Long.MAX_VALUE);
                if (dv != null && dv.getId() != null && dv.getId() != 0L) allRowIds.add(rId);
            }
            GenericManager<DebugUser> gmBFresh = new GenericManager<>(DebugUser.class, b, 12, 0);
            int rowsBeforeList = gmB.findList(gmB.buildQueryWrapper()).size();
            int rowsFresh = gmBFresh.findList(gmBFresh.buildQueryWrapper()).size();
            int directCount = allRowIds.size();
            String msg = "lastSeq=" + seq + " endPos=" + end + " dayKeys=" + dayKeys
                    + " tableIdA=" + tA + " tableIdB=" + tB
                    + " aRowLen=" + aRowLen + " bRowLen=" + bRowLen
                    + " warmSize=" + emptySize + " applied=" + applied
                    + " success=" + success[0]
                    + " directCount=" + directCount + " rowsFresh=" + rowsFresh
                    + " rowsBeforeList=" + rowsBeforeList + " lastRId=" + lastEncoded[0]
                    + "\nallRowIds=" + allRowIds;
            Assertions.assertEquals(3, directCount, msg);
        } finally {
            DsBinlogContext.clear();
        }
        a.close();
        b.close();
    }
}
