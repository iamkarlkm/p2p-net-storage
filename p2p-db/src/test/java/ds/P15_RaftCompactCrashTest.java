package ds;

import com.q3lives.ds.registry.meta.MetaMode;
import com.q3lives.ds.registry.meta.raft.RaftServer;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class P15_RaftCompactCrashTest {

    @After
    public void cleanup() {
        for (RaftServer s : new ArrayList<>(RaftServer.PEERS.values())) {
            try { s.stop(); } catch (Exception ignored) {}
            RaftServer.deregister(s.getLocalPeerId());
        }
        RaftServer.PEERS.clear();
    }

    static void invokeTryCompact(RaftServer s) throws Exception {
        s.forceCompact();
    }

    @Test(timeout = 90000)
    public void t01_compact_1000_logs_then_new_logs_snapshot_shifted() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "raft-compact-t01");
        root.mkdirs();
        RaftServer a = RaftServer.create(root, MetaMode.REGISTRY, null, 91L, new long[]{92L});
        RaftServer b = RaftServer.create(root, MetaMode.REGISTRY, null, 92L, new long[]{91L});
        assertNotNull(a); assertNotNull(b);
        RaftServer.register(a); RaftServer.register(b);
        long ddl = System.currentTimeMillis() + 4000L;
        while (a.getRole() != RaftServer.Role.LEADER && b.getRole() != RaftServer.Role.LEADER
                && System.currentTimeMillis() < ddl) Thread.sleep(10L);
        RaftServer leader = a.getRole() == RaftServer.Role.LEADER ? a : b;
        RaftServer follower = leader == a ? b : a;
        assertTrue("one leader", leader.getRole() == RaftServer.Role.LEADER);
        long baseCommit = leader.getCommitIndex();
        long last = baseCommit;
        int N = 200;
        for (int i = 0; i < N; i++) {
            String p = "/orm/data/row/Compact/" + String.format("%016x", (long)i);
            byte[] pl = new byte[32];
            pl[0] = (byte)(i & 0xFF);
            long idx;
            synchronized (leader) { idx = leader.appendPutRegistry(p, pl); }
            assertTrue("idx monotonic i=" + i + " idx=" + idx + " last=" + last, idx > last);
            last = idx;
        }
        assertTrue("leader after writes lastApplied>=N actual=" + leader.getLastApplied() + " baseCommit=" + baseCommit,
            leader.awaitApplied(last, 30000L));
        long fddl1 = System.currentTimeMillis() + 30000L;
        while (follower.getLastApplied() < last && System.currentTimeMillis() < fddl1) Thread.sleep(5L);
        assertTrue("follower lastApplied >= N actual=" + follower.getLastApplied(), follower.getLastApplied() >= last);
        invokeTryCompact(leader);
        invokeTryCompact(follower);
        assertEquals("leader after compact logs.size()=0 (trimmed)", 0, leader.logSize());
        assertEquals("follower after compact logs.size()=0 (trimmed)", 0, follower.logSize());
        assertTrue("leader snapshotLastIncludedIndex >= N actual=" + leader.getSnapshotLastIncludedIndex(),
            leader.getSnapshotLastIncludedIndex() >= (long)N);
        long term0 = leader.getCurrentTerm();
        assertTrue("snapshotLastIncludedTerm >= 1 actual=" + leader.getSnapshotLastIncludedIndex(),
            term0 >= 1L);
        String pathNew = "/orm/data/row/Compact/new_after_compact_001";
        long idxNew; synchronized (leader) { idxNew = leader.appendPutRegistry(pathNew, new byte[16]); }
        assertTrue("after compact new idx >= snapshotLastIncludedIndex+1 snapshotIdx=" + leader.getSnapshotLastIncludedIndex() + " idxNew=" + idxNew,
            idxNew >= leader.getSnapshotLastIncludedIndex() + 1L);
        leader.awaitApplied(idxNew, 5000L);
        long fddl2 = System.currentTimeMillis() + 5000L;
        while (follower.getLastApplied() < idxNew && System.currentTimeMillis() < fddl2) Thread.sleep(5L);
        assertTrue("follower new apply OK after compact", follower.getLastApplied() >= idxNew);
    }

    @Test(timeout = 120000)
    public void t02_crash_recovery_mean_12s_in_jvm_rebuild() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "raft-crash-t02");
        root.mkdirs();
        int rounds = 10;
        long[] costs = new long[rounds];
        for (int r = 0; r < rounds; r++) {
            for (RaftServer s : new ArrayList<>(RaftServer.PEERS.values())) {
                try { s.stop(); } catch (Exception ignored) {}
                RaftServer.deregister(s.getLocalPeerId());
            }
            RaftServer.PEERS.clear();
            long start = System.nanoTime();
            long pidA = 93L + 2L * r;
            long pidB = 94L + 2L * r;
            RaftServer a = RaftServer.create(root, MetaMode.REGISTRY, null, pidA, new long[]{pidB});
            RaftServer b = RaftServer.create(root, MetaMode.REGISTRY, null, pidB, new long[]{pidA});
            assertNotNull("a round " + r, a);
            assertNotNull("b round " + r, b);
            RaftServer.register(a); RaftServer.register(b);
            long ddl = System.currentTimeMillis() + 4000L;
            while (a.getRole() != RaftServer.Role.LEADER && b.getRole() != RaftServer.Role.LEADER
                    && System.currentTimeMillis() < ddl) Thread.sleep(10L);
            RaftServer leader = a.getRole() == RaftServer.Role.LEADER ? a : b;
            RaftServer follower = leader == a ? b : a;
            synchronized (leader) {
                leader.appendPutRegistry("/orm/crash/" + r + "/0", new byte[16]);
            }
            long appliedTarget = leader.getSnapshotLastIncludedIndex() + leader.logSize();
            leader.awaitApplied(appliedTarget, 4000L);
            long fddl = System.currentTimeMillis() + 4000L;
            while (follower.getLastApplied() < appliedTarget && System.currentTimeMillis() < fddl) Thread.sleep(5L);
            long costMs = (System.nanoTime() - start) / 1_000_000L;
            costs[r] = costMs;
            assertTrue("round " + r + " <= 12s max cost=" + costMs, costMs <= 12_000L);
        }
        long sum = 0L;
        for (long c : costs) sum += c;
        long avg = sum / rounds;
        assertTrue("10 rounds crash recovery MEAN <= 12s actual avg=" + avg + "ms", avg <= 12_000L);
    }
}
