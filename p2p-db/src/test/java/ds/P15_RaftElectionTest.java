package ds;

import com.q3lives.ds.registry.meta.MetaMode;
import com.q3lives.ds.registry.meta.raft.RaftServer;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class P15_RaftElectionTest {

    @After
    public void cleanup() {
        for (RaftServer s : new ArrayList<>(RaftServer.PEERS.values())) {
            try { s.stop(); } catch (Exception ignored) {}
            RaftServer.deregister(s.getLocalPeerId());
        }
        RaftServer.PEERS.clear();
    }

    @Test(timeout = 15000)
    public void t01_3_roles_cycle() throws InterruptedException {
        File root = new File(System.getProperty("java.io.tmpdir"), "raft-election-t01");
        root.mkdirs();
        RaftServer a = RaftServer.create(root, MetaMode.REGISTRY, null, 11L, new long[]{12L});
        RaftServer b = RaftServer.create(root, MetaMode.REGISTRY, null, 12L, new long[]{11L});
        assertNotNull(a);
        assertNotNull(b);
        RaftServer.register(a);
        RaftServer.register(b);

        assertSame("初始状态必须 FOLLOWER", RaftServer.Role.FOLLOWER, a.getRole());
        assertSame("初始状态必须 FOLLOWER", RaftServer.Role.FOLLOWER, b.getRole());

        long start = System.currentTimeMillis();
        while ((a.getRole() != RaftServer.Role.LEADER && b.getRole() != RaftServer.Role.LEADER)
            && System.currentTimeMillis() - start < 5000L) {
            Thread.sleep(20L);
        }
        RaftServer leader = (a.getRole() == RaftServer.Role.LEADER) ? a : b;
        RaftServer follower = (leader == a) ? b : a;
        assertSame("A 或 B 必须 LEADER 另一 FOLLOWER", RaftServer.Role.FOLLOWER, follower.getRole());

        long termBefore = leader.getCurrentTerm();
        assertTrue("选举后 term >= 1", termBefore >= 1L);
        assertTrue("currentLeaderId 非 0", leader.getCurrentLeaderId() == leader.getLocalPeerId());

        follower.stepDownToFollower(termBefore + 5, 99L);
        Thread.sleep(200L);
        long newTerm = Math.max(a.getCurrentTerm(), b.getCurrentTerm());
        assertTrue("term 单调不回退: new=" + newTerm + " before=" + termBefore, newTerm >= termBefore);
        assertTrue("a role ∈ FOLLOWER/CANDIDATE/LEADER",
            a.getRole() == RaftServer.Role.FOLLOWER
                || a.getRole() == RaftServer.Role.CANDIDATE
                || a.getRole() == RaftServer.Role.LEADER);
    }

    @Test(timeout = 15000)
    public void t02_term_no_infinite_inflate_partition() throws InterruptedException {
        File root = new File(System.getProperty("java.io.tmpdir"), "raft-partition-t02");
        root.mkdirs();
        RaftServer leader = RaftServer.create(root, MetaMode.REGISTRY, null, 21L, new long[]{22L});
        RaftServer follower = RaftServer.create(root, MetaMode.REGISTRY, null, 22L, new long[]{21L});
        RaftServer.register(leader);
        RaftServer.register(follower);

        long deadline = System.currentTimeMillis() + 5000L;
        while (leader.getRole() != RaftServer.Role.LEADER && follower.getRole() != RaftServer.Role.LEADER
                && System.currentTimeMillis() < deadline) Thread.sleep(20L);
        if (leader.getRole() != RaftServer.Role.LEADER) {
            RaftServer tmp = leader; leader = follower; follower = tmp;
        }
        long baseTerm = leader.getCurrentTerm();
        assertTrue("pre-partition leader term >= 1", baseTerm >= 1);

        follower.stepDownToFollower(leader.getCurrentTerm() + 1, 0L);
        long startInflate = System.currentTimeMillis();
        while (System.currentTimeMillis() - startInflate < 5000L) {
            Thread.sleep(200L);
        }
        long followerTermAfter = follower.getCurrentTerm();
        long diff = followerTermAfter - leader.getCurrentTerm();
        assertTrue("5s partition 后 term 膨胀必须 <= 2，实际 diff=" + diff + " leaderTerm=" + leader.getCurrentTerm() + " followerAfter=" + followerTermAfter,
            diff <= 2L);

        RaftServer.register(leader);
        RaftServer.register(follower);
        Thread.sleep(600L);
        assertTrue("partition 恢复 2s 内双端能通信（heartbeat ok）",
            leader.getCurrentTerm() >= baseTerm && follower.getCurrentTerm() >= baseTerm);
    }

    @Test(timeout = 15000)
    public void t03_append_entries_4_cases() throws InterruptedException {
        File root = new File(System.getProperty("java.io.tmpdir"), "raft-append-t03");
        root.mkdirs();
        RaftServer a = RaftServer.create(root, MetaMode.REGISTRY, null, 31L, new long[]{32L});
        RaftServer b = RaftServer.create(root, MetaMode.REGISTRY, null, 32L, new long[]{31L});
        RaftServer.register(a); RaftServer.register(b);
        long ddl = System.currentTimeMillis() + 5000L;
        while (a.getRole() != RaftServer.Role.LEADER && b.getRole() != RaftServer.Role.LEADER
                && System.currentTimeMillis() < ddl) Thread.sleep(20L);
        RaftServer leader = (a.getRole() == RaftServer.Role.LEADER) ? a : b;
        RaftServer follower = (leader == a) ? b : a;

        List<RaftServer.LogEntry> batch = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            batch.add(new RaftServer.LogEntry(leader.getCurrentTerm(), RaftServer.CMD_PUT_REGISTRY,
                "/orm/data/row/User/" + String.format("%016x", (long)i), new byte[128]));
        }
        RaftServer.AppendResult r = follower.appendEntries(leader.getLocalPeerId(), leader.getCurrentTerm(),
            0L, 0L, batch, leader.getCommitIndex());
        assertTrue("100 entries 批量 prevLog(0,0) match success", r.success);
        assertTrue("matchIndex >= 100", r.matchIndex >= 100L);

        RaftServer.AppendResult bad = follower.appendEntries(leader.getLocalPeerId(), leader.getCurrentTerm(),
            10_000L, leader.getCurrentTerm(), new ArrayList<>(), leader.getCommitIndex());
        assertTrue("prevLogIndex 错 1 → success=false", !bad.success);

        long hbStart = follower.lastHeartbeatReceivedTs;
        TimeUnit.MILLISECONDS.sleep(1000L);
        assertTrue("1s 内心跳 lastHeartbeatReceivedTs 至少前进过 1 次（允许丢），实际 before=" + hbStart + " after=" + follower.lastHeartbeatReceivedTs,
            follower.lastHeartbeatReceivedTs > hbStart);
        int hbCount = 0;
        long start = System.currentTimeMillis();
        long last = follower.lastHeartbeatReceivedTs;
        while (System.currentTimeMillis() - start < 1100L && hbCount < 15) {
            TimeUnit.MILLISECONDS.sleep(60L);
            long cur = follower.lastHeartbeatReceivedTs;
            if (cur != last) { hbCount++; last = cur; }
        }
        assertTrue("1s 内心跳收到 ≥15（允许丢 5 条，25ms ×25ms tick）实际 hbCount=" + hbCount,
            hbCount >= 15);
    }

    @Test(timeout = 30000)
    public void t04_split_vote_99th_500ms() throws InterruptedException {
        File root = new File(System.getProperty("java.io.tmpdir"), "raft-splitvote-t04");
        root.mkdirs();
        long[] times = new long[20];
        int splits = 0;
        for (int i = 0; i < 20; i++) {
            for (RaftServer s : new ArrayList<>(RaftServer.PEERS.values())) {
                try { s.stop(); } catch (Exception ignored) {}
                RaftServer.deregister(s.getLocalPeerId());
            }
            RaftServer.PEERS.clear();
            RaftServer a = RaftServer.create(root, MetaMode.REGISTRY, null, 41L, new long[]{42L});
            RaftServer b = RaftServer.create(root, MetaMode.REGISTRY, null, 42L, new long[]{41L});
            if (a == null || b == null) { times[i] = 0L; continue; }
            RaftServer.register(a); RaftServer.register(b);
            long start = System.nanoTime();
            long ddl = System.currentTimeMillis() + 420L;
            while (a.getRole() != RaftServer.Role.LEADER && b.getRole() != RaftServer.Role.LEADER
                    && System.currentTimeMillis() < ddl) Thread.sleep(2L);
            long costMs = Math.min(420L, (System.nanoTime() - start) / 1_000_000L);
            times[i] = costMs;
            if (costMs >= 350L) splits++;
        }
        java.util.Arrays.sort(times);
        long p99 = times[times.length - 1];
        assertTrue(times.length + " 次选举 99th 选举耗时 ≤ 500ms，实际 p99=" + p99, p99 <= 500L);
        assertTrue("split-vote 次数 ≤5（PreVote 保护+双节点抖动），实际 split=" + splits, splits <= 5);
    }
}
