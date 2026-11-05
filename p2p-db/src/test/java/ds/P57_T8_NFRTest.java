package ds;

import com.q3lives.ds.registry.meta.MetaMode;
import com.q3lives.ds.registry.meta.raft.MemoryRaftStateMachine;
import com.q3lives.ds.registry.meta.raft.RaftServer;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * P57 T8：NFR 性能 rubric（U2 3 节点/2 节点写提交 P99 退化比 + NFR-P1 选举收敛）。
 */
public class P57_T8_NFRTest {

    private static final int ROUNDS = 5;
    private static final int N_PER_ROUND = 300;
    private static final double RATIO_2PT = 1.05;
    private static final double RATIO_1PT = 1.20;
    private static final long CONVERGE_HARD_MS = 3000L;
    private static final long PER_ROUND_CEIL_MS = 8000L;

    private final List<RaftServer> created = new ArrayList<>();

    @After
    public void cleanup() {
        closeAll();
    }

    private void closeAll() {
        for (RaftServer s : created) {
            try {
                s.stop();
            } catch (Exception ignored) {
            }
        }
        created.clear();
        RaftServer.PEERS.clear();
    }

    private RaftServer create2(File root, long id, long other) {
        RaftServer s = RaftServer.create(root, MetaMode.REGISTRY, null, id, new long[]{other});
        if (s != null) {
            created.add(s);
        }
        return s;
    }

    private RaftServer create3(File root, long id) {
        RaftServer s = RaftServer.create(root, MetaMode.REGISTRY, null, id, new long[]{1L, 2L, 3L});
        if (s != null) {
            created.add(s);
        }
        return s;
    }

    private List<RaftServer> buildCluster(File root, int clusterSize) {
        List<RaftServer> nodes = new ArrayList<>();
        if (clusterSize == 2) {
            nodes.add(create2(root, 1L, 2L));
            nodes.add(create2(root, 2L, 1L));
        } else {
            nodes.add(create3(root, 1L));
            nodes.add(create3(root, 2L));
            nodes.add(create3(root, 3L));
        }
        for (RaftServer s : nodes) {
            RaftServer.register(s);
        }
        return nodes;
    }

    private static RaftServer awaitLeader(List<RaftServer> nodes, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (RaftServer s : nodes) {
                if (s.getRole() == RaftServer.Role.LEADER) {
                    return s;
                }
            }
            Thread.sleep(5L);
        }
        return null;
    }

    private double[] p99PerRound(File root, int clusterSize) throws Exception {
        double[] p99 = new double[ROUNDS];
        byte[] pay = new byte[128];
        Arrays.fill(pay, (byte) 0x5A);
        for (int r = 0; r < ROUNDS; r++) {
            List<RaftServer> nodes = null;
            RaftServer leader = null;
            for (int attempt = 0; attempt < 3 && leader == null; attempt++) {
                closeAll();
                nodes = buildCluster(root, clusterSize);
                leader = awaitLeader(nodes, 4000L);
            }
            assertTrue("cluster(" + clusterSize + ") round " + r + " leader elected", leader != null);

            for (int w = 0; w < 50; w++) {
                long i;
                synchronized (leader) {
                    i = leader.appendPutRegistry("/nfr/warm/" + r + "/" + w, pay);
                }
                leader.awaitApplied(i, 5000L);
            }

            long[] lat = new long[N_PER_ROUND];
            for (int i = 0; i < N_PER_ROUND; i++) {
                long s0 = System.nanoTime();
                long idx;
                synchronized (leader) {
                    idx = leader.appendPutRegistry("/nfr/run/" + r + "/" + i, pay);
                }
                boolean ok = leader.awaitApplied(idx, 5000L);
                lat[i] = System.nanoTime() - s0;
                assertTrue("cluster(" + clusterSize + ") round " + r + " write " + i + " committed", ok);
            }
            Arrays.sort(lat);
            // nearest-rank P99：ceil(0.99*N)-1 (0-based)，N 足够大时不退化为 max
            int idx = Math.min(N_PER_ROUND - 1, (int) Math.ceil(N_PER_ROUND * 0.99) - 1);
            p99[r] = lat[idx] / 1_000_000.0;
        }
        return p99;
    }

    private static double mean(double[] v) {
        double s = 0;
        for (double x : v) {
            s += x;
        }
        return s / v.length;
    }

    @Test(timeout = 240000)
    public void t01_u2_p99_ratio_3node_over_2node() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "p57-t8-ratio");
        root.mkdirs();

        double[] p99_2 = p99PerRound(root, 2);
        double[] p99_3 = p99PerRound(root, 3);
        double m2 = mean(p99_2);
        double m3 = mean(p99_3);
        double ratio = m3 / m2;

        int score = ratio <= RATIO_2PT ? 2 : (ratio <= RATIO_1PT ? 1 : 0);
        System.out.println("[U2] 2-node P99 mean=" + String.format("%.4f", m2) + "ms rounds=" + Arrays.toString(p99_2));
        System.out.println("[U2] 3-node P99 mean=" + String.format("%.4f", m3) + "ms rounds=" + Arrays.toString(p99_3));
        System.out.println("[U2] ratio=" + String.format("%.3f", ratio)
                + " 2pt<=" + RATIO_2PT + " 1pt<=" + RATIO_1PT + " score=" + score + "/2");

        assertTrue("U2 ratio=" + String.format("%.3f", ratio) + " ≤ " + RATIO_1PT
                + "（验收标准：≤1.05 记2分 / ≤1.20 记≥1分；本次 score=" + score + "/2）", ratio <= RATIO_1PT);
    }

    @Test(timeout = 120000)
    public void t02_nfr_p1_election_convergence_le_3000ms() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "p57-t8-converge");
        root.mkdirs();

        long[] costs = new long[ROUNDS];
        for (int r = 0; r < ROUNDS; r++) {
            closeAll();
            List<RaftServer> nodes = buildCluster(root, 3);

            long start = System.nanoTime();
            RaftServer leader = null;
            long deadline = System.currentTimeMillis() + PER_ROUND_CEIL_MS;
            while (System.currentTimeMillis() < deadline) {
                if (leader == null) {
                    leader = awaitLeaderStep(nodes);
                }
                if (leader != null) {
                    long lid = leader.getLocalPeerId();
                    boolean allAgree = true;
                    int leaders = 0;
                    for (RaftServer s : nodes) {
                        if (s.getRole() == RaftServer.Role.LEADER) {
                            leaders++;
                        }
                        if (s.getCurrentLeaderId() != lid) {
                            allAgree = false;
                        }
                    }
                    if (leaders == 1 && allAgree) {
                        break;
                    }
                    leader = null;
                }
                Thread.sleep(2L);
            }
            costs[r] = (System.nanoTime() - start) / 1_000_000L;
            assertTrue("round " + r + " 3 节点收敛出稳定单 Leader，cost=" + costs[r] + "ms",
                    leader != null && leader.getRole() == RaftServer.Role.LEADER);
        }
        long sum = 0;
        for (long c : costs) {
            sum += c;
        }
        long meanMs = sum / ROUNDS;
        System.out.println("[NFR-P1] 3-node election convergence rounds=" + Arrays.toString(costs)
                + " mean=" + meanMs + "ms HARD<=" + CONVERGE_HARD_MS + "ms");
        assertTrue("NFR-P1 3 节点选举收敛 5 轮均值 " + meanMs + "ms ≤ " + CONVERGE_HARD_MS + "ms",
                meanMs <= CONVERGE_HARD_MS);
    }

    private static RaftServer awaitLeaderStep(List<RaftServer> nodes) {
        for (RaftServer s : nodes) {
            if (s.getRole() == RaftServer.Role.LEADER) {
                return s;
            }
        }
        return null;
    }
}
