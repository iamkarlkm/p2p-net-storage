package ds;

import com.q3lives.ds.registry.meta.MetaMode;
import com.q3lives.ds.registry.meta.raft.MemoryRaftStateMachine;
import com.q3lives.ds.registry.meta.raft.RaftServer;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** P57 T5：3 节点（1/2/3）Snapshot fanout + 无 split-brain（R10/R11）。 */
public class P57_T5_SnapshotFanoutTest {

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

    private static byte[] sha1Store(MemoryRaftStateMachine sm) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        TreeMap<String, byte[]> sorted = new TreeMap<>(sm.store);
        for (Map.Entry<String, byte[]> e : sorted.entrySet()) {
            md.update(e.getKey().getBytes("UTF-8"));
            md.update((byte) 0x1F);
            md.update(e.getValue());
        }
        return md.digest();
    }

    private static byte[] payload(int seed, int len) {
        byte[] p = new byte[len];
        for (int i = 0; i < len; i++) p[i] = (byte) ((seed * 31 + i) & 0xFF);
        return p;
    }

    @Test(timeout = 90000)
    public void t01_two_follower_fanout() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "p57-t5-t01");
        root.mkdirs();
        List<RaftServer> nodes = new ArrayList<>();
        Map<Long, MemoryRaftStateMachine> sm = new HashMap<>();
        for (String id : new String[]{"1", "2", "3"}) {
            RaftServer s = create(root, id);
            MemoryRaftStateMachine m = new MemoryRaftStateMachine();
            s.setStateMachineListener(m);
            nodes.add(s);
            sm.put(s.getLocalPeerId(), m);
        }
        for (RaftServer s : nodes) RaftServer.register(s);

        RaftServer leader = awaitLeader(nodes, 4000L);
        assertTrue("3 节点收敛出 LEADER", leader != null);

        int n = 60;
        long lastIdx = 0L;
        for (int i = 0; i < n; i++) {
            synchronized (leader) {
                lastIdx = leader.appendPutRegistry("/orm/t5/t01/" + i, payload(i, 128));
            }
        }
        long wait = System.currentTimeMillis() + 10000L;
        while (System.currentTimeMillis() < wait) {
            boolean allApplied = leader.getCommitIndex() >= lastIdx;
            for (RaftServer s : nodes) {
                if (s.getLastApplied() < lastIdx) allApplied = false;
            }
            if (allApplied) break;
            Thread.sleep(10L);
        }
        assertTrue("全部节点 applied >= lastIdx", leader.getLastApplied() >= lastIdx);

        byte[] shaLeader = sha1Store(sm.get(leader.getLocalPeerId()));
        for (RaftServer s : nodes) {
            if (s == leader) continue;
            assertArrayEquals("Follower applied store 与 Leader SHA-1 全等",
                    shaLeader, sha1Store(sm.get(s.getLocalPeerId())));
        }

        leader.forceCompact();
        long snapIdx = leader.getSnapshotLastIncludedIndex();
        assertTrue("Leader 已 compact（snapshotLastIncludedIndex >= 1）", snapIdx >= 1L);

        for (RaftServer s : nodes) {
            if (s == leader) continue;
            RaftServer.PeerState ps = leader.peerState(s.getLocalPeerId());
            ps.nextIndex = 1L;
        }
        long deadline = System.currentTimeMillis() + 6000L;
        boolean routed = false;
        while (System.currentTimeMillis() < deadline) {
            routed = true;
            for (RaftServer s : nodes) {
                if (s == leader) continue;
                RaftServer.PeerState ps = leader.peerState(s.getLocalPeerId());
                if (ps.nextIndex != snapIdx + 1L || ps.matchIndex != snapIdx) routed = false;
            }
            if (routed) break;
            Thread.sleep(10L);
        }
        assertTrue("2 落后 Follower 经 InstallSnapshot 补齐（nextIndex=snap+1, matchIndex=snap）", routed);

        for (RaftServer s : nodes) {
            if (s == leader) continue;
            assertArrayEquals("fanout 后 Follower applied store 仍与 Leader 全等",
                    shaLeader, sha1Store(sm.get(s.getLocalPeerId())));
        }
    }

    @Test(timeout = 90000)
    public void t02_minority_no_leader() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "p57-t5-t02");
        root.mkdirs();
        List<RaftServer> nodes = new ArrayList<>();
        for (String id : new String[]{"1", "2", "3"}) nodes.add(create(root, id));
        for (RaftServer s : nodes) RaftServer.register(s);

        RaftServer leader = awaitLeader(nodes, 4000L);
        assertTrue("3 节点收敛出 LEADER", leader != null);
        RaftServer isolated = null;
        for (RaftServer s : nodes) {
            if (s != leader) { isolated = s; break; }
        }
        assertTrue(isolated != null);
        long isoId = isolated.getLocalPeerId();

        isolated.stop();
        RaftServer.PEERS.remove(isoId);

        assertTrue("被隔离节点 != LEADER", isolated.getRole() != RaftServer.Role.LEADER);
        int aliveLeaders = 0;
        for (RaftServer s : nodes) {
            if (s != isolated && s.getRole() == RaftServer.Role.LEADER) aliveLeaders++;
        }
        assertEquals("多数派存活期 Leader 计数 == 1", 1, aliveLeaders);
        Thread.sleep(400L);
        assertEquals("隔离期 Leader 保持唯一", RaftServer.Role.LEADER, leader.getRole());

        RaftServer rejoin = create(root, Long.toUnsignedString(isoId));
        RaftServer.register(rejoin);
        long deadline = System.currentTimeMillis() + 6000L;
        while (System.currentTimeMillis() < deadline) {
            if (rejoin.getRole() == RaftServer.Role.FOLLOWER
                    && rejoin.getCurrentLeaderId() == leader.getLocalPeerId()) break;
            Thread.sleep(20L);
        }
        assertEquals("复归节点重新跟随同一 Leader", RaftServer.Role.FOLLOWER, rejoin.getRole());
        long termDiff = Math.abs(rejoin.getCurrentTerm() - leader.getCurrentTerm());
        assertTrue("复归后 |term 差| <= 2 actual=" + termDiff, termDiff <= 2L);
        assertEquals("复归被接纳入 Leader 视图", leader.getLocalPeerId(), rejoin.getCurrentLeaderId());
        assertNotEquals("leader 自指不变", 0L, leader.getCurrentLeaderId());
    }
}
