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
import static org.junit.Assert.assertTrue;

/** P57 T3：props 驱动 3 节点（1/2/3）选举收敛（R5/R6）。 */
public class P57_T3_ThreeNodeElectionTest {

    private final List<RaftServer> created = new ArrayList<>();

    @After
    public void cleanup() {
        for (RaftServer s : created) {
            try { s.stop(); } catch (Exception ignored) {}
        }
        created.clear();
        RaftServer.PEERS.clear();
    }

    private RaftServer create(File root, String members, String local) {
        Map<String, String> m = new HashMap<>();
        m.put("p2p.orm.raft.members", members);
        m.put("p2p.orm.raft.local", local);
        RaftServer s = RaftServer.create(root, MetaMode.REGISTRY, m);
        if (s != null) created.add(s);
        return s;
    }

    private static int leaderCount(List<RaftServer> nodes) {
        int c = 0;
        for (RaftServer s : nodes) {
            if (s.getRole() == RaftServer.Role.LEADER) c++;
        }
        return c;
    }

    private static RaftServer theLeader(List<RaftServer> nodes) {
        for (RaftServer s : nodes) {
            if (s.getRole() == RaftServer.Role.LEADER) return s;
        }
        return null;
    }

    @Test(timeout = 20000)
    public void t01_three_peers_converge_single_leader() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "p57-t3-t01");
        root.mkdirs();
        RaftServer s1 = create(root, "1,2,3", "1");
        RaftServer s2 = create(root, "1,2,3", "2");
        RaftServer s3 = create(root, "1,2,3", "3");
        List<RaftServer> nodes = new ArrayList<>();
        nodes.add(s1); nodes.add(s2); nodes.add(s3);
        RaftServer.register(s1);
        RaftServer.register(s2);
        RaftServer.register(s3);

        long[] lastSeen = new long[]{0L, 0L, 0L};
        long deadline = System.currentTimeMillis() + 4000L;
        RaftServer leader = null;
        while (System.currentTimeMillis() < deadline) {
            for (int i = 0; i < nodes.size(); i++) {
                long t = nodes.get(i).getCurrentTerm();
                assertTrue("term 单调不回退 node" + i + ": " + t + " < " + lastSeen[i], t >= lastSeen[i]);
                lastSeen[i] = t;
            }
            if (leaderCount(nodes) == 1) {
                RaftServer l = theLeader(nodes);
                if (s1.getCurrentLeaderId() == l.getLocalPeerId()
                        && s2.getCurrentLeaderId() == l.getLocalPeerId()
                        && s3.getCurrentLeaderId() == l.getLocalPeerId()) {
                    leader = l;
                    break;
                }
            }
            Thread.sleep(20L);
        }

        assertTrue("3 节点在 4000ms 内收敛出恰 1 个 LEADER", leader != null);
        assertEquals("LEADER 计数必须 == 1", 1, leaderCount(nodes));
        long lid = leader.getLocalPeerId();
        assertTrue("leaderId ∈ {1,2,3}", lid == 1L || lid == 2L || lid == 3L);
        for (RaftServer s : nodes) {
            if (s.getLocalPeerId() == lid) {
                assertEquals("LEADER 角色自洽", RaftServer.Role.LEADER, s.getRole());
                assertEquals("LEADER currentLeaderId 自指", lid, s.getCurrentLeaderId());
            } else {
                assertEquals("其余节点 FOLLOWER", RaftServer.Role.FOLLOWER, s.getRole());
                assertEquals("FOLLOWER currentLeaderId 指向 leader", lid, s.getCurrentLeaderId());
            }
        }
        assertTrue("选举后 term >= 1", leader.getCurrentTerm() >= 1L);
    }

    @Test(timeout = 20000)
    public void t02_two_of_three_online_still_elects() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "p57-t3-t02");
        root.mkdirs();
        RaftServer s1 = create(root, "1,2,3", "1");
        RaftServer s2 = create(root, "1,2,3", "2");
        List<RaftServer> nodes = new ArrayList<>();
        nodes.add(s1); nodes.add(s2);
        RaftServer.register(s1);
        RaftServer.register(s2);
        // 第 3 个成员未进 PEERS：PEERS.get(3) == null，2/3 >= quorum 2 仍可收敛
        assertTrue("peer3 未注册", RaftServer.PEERS.get(3L) == null);

        long deadline = System.currentTimeMillis() + 4000L;
        RaftServer leader = null;
        while (System.currentTimeMillis() < deadline) {
            if (leaderCount(nodes) == 1) {
                RaftServer l = theLeader(nodes);
                if (s1.getCurrentLeaderId() == l.getLocalPeerId()
                        && s2.getCurrentLeaderId() == l.getLocalPeerId()) {
                    leader = l;
                    break;
                }
            }
            Thread.sleep(20L);
        }

        assertTrue("2/3 在线仍能在 4000ms 内选出 LEADER", leader != null);
        assertEquals("LEADER 计数必须 == 1", 1, leaderCount(nodes));
        assertTrue("选举后 term >= 1", leader.getCurrentTerm() >= 1L);
    }
}
