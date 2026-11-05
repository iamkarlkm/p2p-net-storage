package ds;

import com.q3lives.ds.registry.meta.MetaMode;
import com.q3lives.ds.registry.meta.raft.RaftServer;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** P57 T4：3 节点（1/2/3）多数提交 + 容错边界（R7/R8/R9 + U1）。 */
public class P57_T4_CommitMajorityTest {

    private final List<RaftServer> created = new ArrayList<>();

    @After
    public void cleanup() {
        for (RaftServer s : created) {
            try { s.stop(); } catch (Exception ignored) {}
        }
        created.clear();
        RaftServer.PEERS.clear();
    }

    private RaftServer create(File root, String local) {
        Map<String, String> m = new HashMap<>();
        m.put("p2p.orm.raft.members", "1,2,3");
        m.put("p2p.orm.raft.local", local);
        RaftServer s = RaftServer.create(root, MetaMode.REGISTRY, m);
        if (s != null) created.add(s);
        return s;
    }

    private static RaftServer awaitLeader(List<RaftServer> nodes, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (RaftServer s : nodes) {
                if (s.getRole() == RaftServer.Role.LEADER) return s;
            }
            Thread.sleep(20L);
        }
        return null;
    }

    private static RaftServer aFollower(List<RaftServer> nodes, RaftServer leader) {
        for (RaftServer s : nodes) {
            if (s != leader) return s;
        }
        return null;
    }

    private static byte[] payload128(int seed) {
        byte[] p = new byte[128];
        for (int i = 0; i < p.length; i++) {
            p[i] = (byte) ((seed + i) & 0xFF);
        }
        return p;
    }

    @Test(timeout = 90000)
    public void t01_commit_2of3() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "p57-t4-t01");
        root.mkdirs();
        List<RaftServer> nodes = new ArrayList<>();
        for (String id : new String[]{"1", "2", "3"}) nodes.add(create(root, id));
        for (RaftServer s : nodes) RaftServer.register(s);

        RaftServer leader = awaitLeader(nodes, 4000L);
        assertTrue("3 节点收敛出 LEADER", leader != null);
        RaftServer follower = aFollower(nodes, leader);

        int committed = 0;
        for (int i = 0; i < 100; i++) {
            long idx;
            synchronized (leader) {
                idx = leader.appendPutRegistry("/orm/t4/t01/" + i, payload128(i));
            }
            if (leader.awaitApplied(idx, 3000L)) committed++;
        }
        assertTrue("提交必须前进", follower != null);
        assertTrue("2/3 多数提交 commitIndex >= 100, actual=" + leader.getCommitIndex(),
                leader.getCommitIndex() >= 100L);
        assertTrue("100 条全 awaitApplied 成功 actual=" + committed, committed == 100);
        boolean anyFollowerMatch = false;
        for (RaftServer s : nodes) {
            if (s == leader) continue;
            RaftServer.PeerState ps = leader.peerState(s.getLocalPeerId());
            if (ps != null && ps.matchIndex >= 100L) anyFollowerMatch = true;
        }
        assertTrue("至少 1 个 Follower matchIndex >= 100", anyFollowerMatch);
    }

    @Test(timeout = 90000)
    public void t02_one_down_still_commits() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "p57-t4-t02");
        root.mkdirs();
        List<RaftServer> nodes = new ArrayList<>();
        for (String id : new String[]{"1", "2", "3"}) nodes.add(create(root, id));
        for (RaftServer s : nodes) RaftServer.register(s);

        RaftServer leader = awaitLeader(nodes, 4000L);
        assertTrue("3 节点收敛出 LEADER", leader != null);
        RaftServer down = aFollower(nodes, leader);
        assertTrue(down != null);

        long before = leader.getCommitIndex();
        down.stop();
        RaftServer.deregister(down.getLocalPeerId());
        RaftServer.PEERS.remove(down.getLocalPeerId());

        int committed = 0;
        for (int i = 0; i < 100; i++) {
            long idx;
            synchronized (leader) {
                idx = leader.appendPutRegistry("/orm/t4/t02/" + i, payload128(i));
            }
            if (leader.awaitApplied(idx, 3000L)) committed++;
        }
        assertTrue("1 节点宕机后 100 条全提交成功 actual=" + committed, committed == 100);
        assertTrue("commitIndex 前进 before=" + before + " after=" + leader.getCommitIndex(),
                leader.getCommitIndex() > before);
    }

    @Test(timeout = 90000)
    public void t03_two_down_no_commit() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "p57-t4-t03");
        root.mkdirs();
        List<RaftServer> nodes = new ArrayList<>();
        for (String id : new String[]{"1", "2", "3"}) nodes.add(create(root, id));
        for (RaftServer s : nodes) RaftServer.register(s);

        RaftServer leader = awaitLeader(nodes, 4000L);
        assertTrue("3 节点收敛出 LEADER", leader != null);

        long seedIdx;
        synchronized (leader) {
            seedIdx = leader.appendPutRegistry("/orm/t4/t03/seed", payload128(0));
        }
        assertTrue("基线条目已提交", leader.awaitApplied(seedIdx, 3000L));
        Thread.sleep(200L);

        for (RaftServer s : nodes) {
            if (s == leader) continue;
            s.stop();
            RaftServer.deregister(s.getLocalPeerId());
            RaftServer.PEERS.remove(s.getLocalPeerId());
        }
        Thread.sleep(300L);
        long commitBefore = leader.getCommitIndex();

        long idx;
        synchronized (leader) {
            idx = leader.appendPutRegistry("/orm/t4/t03/orphan", payload128(1));
        }
        assertFalse("仅 self 在线无法达成 quorum，awaitApplied 必须 false",
                leader.awaitApplied(idx, 1500L));
        assertEquals("无多数时 commitIndex 不得前进", commitBefore, leader.getCommitIndex());
    }
}
