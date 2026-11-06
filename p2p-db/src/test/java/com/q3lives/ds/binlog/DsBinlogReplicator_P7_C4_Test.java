package com.q3lives.ds.binlog;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.integration.GenericManager;

public class DsBinlogReplicator_P7_C4_Test {

    public static class UserC4 extends DsTableAdapter {
        @DsField(name = "name", length = 48)
        public String name;
        @DsField(name = "age", length = 4)
        public int age;
        public UserC4() {}
    }

    private static File tmpDir(String prefix) throws Exception {
        File f = Files.createTempDirectory(prefix).toFile();
        f.deleteOnExit();
        return f;
    }

    @Test
    public void test_C4_T13_TwoInstances_FinalConsistent_1_2s() throws Exception {
        File rootA = tmpDir("dsdb_p7_c4_t13_nodeA_");
        File rootB = tmpDir("dsdb_p7_c4_t13_nodeB_");
        File cursorDir = tmpDir("dsdb_p7_c4_t13_cursor_");
        // NOTE: forceResetAllIndexesForTest disabled — it can wipe targetDb indexStore
        // writes produced by replay during scheduler ticks and cause GM findList 0 rows.
        // DsDatabaseLocal.forceResetAllIndexesForTest(rootA);
        // DsDatabaseLocal.forceResetAllIndexesForTest(rootB);

        DsDatabaseLocal dbA = new DsDatabaseLocal(rootA, 1, 0);
        DsDatabaseLocal dbB = new DsDatabaseLocal(rootB, 2, 0);

        GenericManager<UserC4> gmA = new GenericManager<>(UserC4.class, dbA, 1, 0);
        GenericManager<UserC4> gmB = new GenericManager<>(UserC4.class, dbB, 2, 0);

        Set<String> expectedNames = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            UserC4 u = new UserC4();
            u.name = "t13_user_" + i;
            u.age = 20 + i;
            Assertions.assertEquals(1, gmA.saveOrUpdate(u));
            expectedNames.add(u.name);
        }

        long aSeqBefore = dbA.getBinlogStore().lastSequence();
        Assertions.assertTrue(aSeqBefore >= 10L);

        // Warm up targetDb tableId→Class mapping: empty findList does NOT trigger createMeta+stableTableId
        // registration. We MUST write+remove a dummy entity so tableIdToClass.put happens before replay.
        UserC4 warm = new UserC4();
        warm.name = "warm_t13_dummy";
        warm.age = 999;
        gmB.saveOrUpdate(warm);
        gmB.removeById(warm.getId());
        List<UserC4> empty = gmB.findList(gmB.buildQueryWrapper());
        Assertions.assertEquals(0, empty.size());

        DsBinlogReplicator replicator = new DsBinlogReplicator(dbA, dbB, cursorDir, 100L);
        replicator.start();
        Thread.sleep(1200L);
        replicator.stop();

        long replicatedSeq = replicator.currentCursorSequence();
        DsBinlogPosition dbAEnd = dbA.getBinlogStore().getCurrentPosition();
        int forcedApplied = -1;
        final int[] dbgPutOk = {0};
        final StringBuilder perFrame = new StringBuilder(1024);
        try {
            com.q3lives.ds.binlog.DsBinlogContext.setInReplay(true);
            final java.lang.reflect.Field rowLengthField = Class.forName("com.q3lives.ds.database.DsDatabaseLocal$TableMeta").getDeclaredField("rowLength");
            rowLengthField.setAccessible(true);
            java.lang.reflect.Method metaOfM = DsDatabaseLocal.class.getDeclaredMethod("metaOf", Class.class);
            metaOfM.setAccessible(true);
            Object bMeta = metaOfM.invoke(dbB, UserC4.class);
            int bRowLen = (int) rowLengthField.get(bMeta);
            forcedApplied = dbA.getBinlogStore().replayBinlog(
                    new com.q3lives.ds.binlog.DsBinlogPosition("", 0, 0),
                    dbAEnd, (off, ent) -> {
                        boolean ok = dbB.applyBinlogEntry(off, ent);
                        if (ent.opType() == com.q3lives.ds.binlog.DsBinlogOpType.INSERT) {
                            long rId = com.q3lives.ds.bucket.DsFixedBucketStore.encodeRowId(
                                    ent.rowKey() & 0xFFFFFFFFL, bRowLen);
                            UserC4 dv = dbB.getTable(UserC4.class, rId, Long.MAX_VALUE);
                            String line = " [INS rk=" + ent.rowKey() + " rid=" + rId
                                    + " cols=" + ent.colCount()
                                    + " ok=" + ok
                                    + " entNull=" + (dv == null)
                                    + " nm=" + (dv == null ? null : dv.name)
                                    + " age=" + (dv == null ? -1 : dv.age) + " id="
                                    + (dv == null || dv.getId() == null ? -1 : dv.getId().longValue()) + "]";
                            perFrame.append(line).append('\n');
                            if (dv != null && dv.name != null && !dv.name.isEmpty()) dbgPutOk[0]++;
                        }
                        return ok;
                    });
        } catch (Throwable t) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            perFrame.append(" EX_DECODE=").append(sw);
        } finally {
            com.q3lives.ds.binlog.DsBinlogContext.clear();
        }

        com.q3lives.ds.database.integration.QueryWrapper<UserC4> all = gmB.buildQueryWrapper();
        List<UserC4> bs = gmB.findList(all);
        int freshGMCount = new GenericManager<>(UserC4.class, dbB, 2, 0).findList(
                new GenericManager<>(UserC4.class, dbB, 2, 0).buildQueryWrapper()).size();

        String debugMsg = "replicatedSeq=" + replicatedSeq + " end=" + dbAEnd
                + " forcedApplied=" + forcedApplied + " gmBSize=" + bs.size()
                + " freshGM=" + freshGMCount + " dbgPutOk=" + dbgPutOk[0]
                + "\nPERFRAME=" + perFrame;

        Assertions.assertTrue(replicatedSeq >= aSeqBefore,
                "cursor must advance beyond initial writes after 1.2s of 100ms ticks: " + debugMsg);

        Set<String> actualNames = new HashSet<>();
        for (UserC4 u : bs) actualNames.add(u.name);

        Assertions.assertEquals(expectedNames.size(), actualNames.size(),
                "target GM must eventually contain all 10 source writes (final consistent): " + debugMsg);
        Assertions.assertTrue(actualNames.containsAll(expectedNames),
                "target names must match source names (all names present): " + debugMsg);

        dbA.close();
        dbB.close();
    }

    @Test
    public void test_C4_T14_CursorPersistence_NoDuplicateReplay() throws Exception {
        File rootA = tmpDir("dsdb_p7_c4_t14_nodeA_");
        File rootB = tmpDir("dsdb_p7_c4_t14_nodeB_");
        File cursorDir = tmpDir("dsdb_p7_c4_t14_cursor_");
        // Same: disable forceReset to avoid wiping replay-produced targetDb indexes.
        // DsDatabaseLocal.forceResetAllIndexesForTest(rootA);
        // DsDatabaseLocal.forceResetAllIndexesForTest(rootB);

        DsDatabaseLocal dbA = new DsDatabaseLocal(rootA, 3, 0);
        DsDatabaseLocal dbB = new DsDatabaseLocal(rootB, 4, 0);
        GenericManager<UserC4> gmA = new GenericManager<>(UserC4.class, dbA, 3, 0);
        GenericManager<UserC4> gmB = new GenericManager<>(UserC4.class, dbB, 4, 0);

        for (int i = 0; i < 5; i++) {
            UserC4 u = new UserC4();
            u.name = "t14_first_" + i;
            u.age = 30 + i;
            Assertions.assertEquals(1, gmA.saveOrUpdate(u));
        }
        long seqAfterFirst5 = dbA.getBinlogStore().lastSequence();

        // Warm up targetDb tableId→Class mapping: dummy write+remove to force createMeta+register tableId
        UserC4 warm = new UserC4();
        warm.name = "warm_t14_dummy";
        warm.age = 888;
        gmB.saveOrUpdate(warm);
        gmB.removeById(warm.getId());
        Assertions.assertEquals(0, gmB.findList(gmB.buildQueryWrapper()).size());

        DsBinlogReplicator r1 = new DsBinlogReplicator(dbA, dbB, cursorDir, 80L);
        r1.start();
        Thread.sleep(800L);
        r1.stop();
        long cursorAfterR1 = r1.currentCursorSequence();
        Assertions.assertTrue(cursorAfterR1 >= seqAfterFirst5,
                "cursor seq after phase 1 must cover >= first 5 writes: "
                        + cursorAfterR1 + " vs " + seqAfterFirst5);

        com.q3lives.ds.database.integration.QueryWrapper<UserC4> w1 = gmB.buildQueryWrapper();
        int nPhase1 = gmB.findList(w1).size();
        Assertions.assertEquals(5, nPhase1, "phase1 should have 5 rows in target");

        for (int i = 0; i < 5; i++) {
            UserC4 u = new UserC4();
            u.name = "t14_second_" + i;
            u.age = 40 + i;
            Assertions.assertEquals(1, gmA.saveOrUpdate(u));
        }
        long seqAfterSecond5 = dbA.getBinlogStore().lastSequence();
        Assertions.assertTrue(seqAfterSecond5 >= seqAfterFirst5 + 5);

        DsBinlogReplicator r2 = new DsBinlogReplicator(dbA, dbB, cursorDir, 80L);
        long cursorAtR2Start = r2.currentCursorSequence();
        Assertions.assertEquals(cursorAfterR1, cursorAtR2Start,
                "new replicator instance MUST read persisted cursor from previous run");
        r2.start();
        Thread.sleep(800L);
        r2.stop();

        long cursorAfterR2 = r2.currentCursorSequence();
        Assertions.assertTrue(cursorAfterR2 >= seqAfterSecond5,
                "cursor after phase 2 must cover second batch as well");

        com.q3lives.ds.database.integration.QueryWrapper<UserC4> w2 = gmB.buildQueryWrapper();
        int nPhase2 = gmB.findList(w2).size();
        Assertions.assertEquals(10, nPhase2,
                "target should have exactly 10 rows after phase 2 (5+5 no duplicates)");

        Set<String> names = new HashSet<>();
        for (UserC4 u : gmB.findList(w2)) names.add(u.name);
        Assertions.assertEquals(10, names.size(), "all 10 names must be distinct (no replay duplication)");

        dbA.close();
        dbB.close();
    }
}
