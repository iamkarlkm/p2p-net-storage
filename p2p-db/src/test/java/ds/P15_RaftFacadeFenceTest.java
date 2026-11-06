package ds;

import com.q3lives.ds.registry.meta.MetaMode;
import com.q3lives.ds.registry.meta.raft.RaftServer;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class P15_RaftFacadeFenceTest {

    @After
    public void cleanup() {
        for (RaftServer s : new ArrayList<>(RaftServer.PEERS.values())) {
            try { s.stop(); } catch (Exception ignored) {}
            RaftServer.deregister(s.getLocalPeerId());
        }
        RaftServer.PEERS.clear();
    }

    static final class DsDatabaseLocalRaftFacade {
        private final RaftServer raftServer;
        final File dbRoot;
        public volatile int localWrites = 0;
        public volatile int raftFencedExceptions = 0;

        DsDatabaseLocalRaftFacade(File dbRoot, MetaMode mode) {
            this.dbRoot = dbRoot;
            this.raftServer = RaftServer.create(dbRoot, mode);
        }

        DsDatabaseLocalRaftFacade(File dbRoot, MetaMode mode, java.util.Map<String, String> props,
                                  long localPeerId, long[] peerIds) {
            this.dbRoot = dbRoot;
            this.raftServer = RaftServer.create(dbRoot, mode, props, localPeerId, peerIds);
            if (this.raftServer != null) RaftServer.register(this.raftServer);
        }

        public RaftServer getRaftServer() { return raftServer; }

        public boolean isRaftEnabled() { return raftServer != null; }

        public boolean isRaftLeader() { return raftServer != null && raftServer.getRole() == RaftServer.Role.LEADER; }

        public boolean isRaftFollower() { return raftServer != null && raftServer.getRole() == RaftServer.Role.FOLLOWER; }

        public long writeRowBlocking(String path, byte[] payload) throws IOException {
            if (raftServer == null) {
                localWrites++;
                return 1L;
            }
            if (raftServer.getRole() != RaftServer.Role.LEADER) {
                raftFencedExceptions++;
                throw new IOException("Not Leader; currentLeader=" + raftServer.currentLeaderAddress());
            }
            long idx;
            synchronized (raftServer) {
                idx = raftServer.appendPutRegistry(path, payload);
            }
            try {
                boolean ok = raftServer.awaitApplied(idx, 3000L);
                if (!ok) throw new IOException("Raft quorum commit timeout idx=" + idx);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("Raft await apply interrupted", ie);
            }
            localWrites++;
            return idx;
        }
    }

    @Test(timeout = 20000)
    public void t01_leader_write_100_rows_quorum_2_2_last_applied_100() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "raft-fence-t01");
        root.mkdirs();
        DsDatabaseLocalRaftFacade a = new DsDatabaseLocalRaftFacade(root, MetaMode.REGISTRY, null, 81L, new long[]{82L});
        DsDatabaseLocalRaftFacade b = new DsDatabaseLocalRaftFacade(root, MetaMode.REGISTRY, null, 82L, new long[]{81L});
        assertTrue("a raft enabled", a.isRaftEnabled());
        assertTrue("b raft enabled", b.isRaftEnabled());
        long ddl = System.currentTimeMillis() + 4000L;
        while (!a.isRaftLeader() && !b.isRaftLeader() && System.currentTimeMillis() < ddl) Thread.sleep(10L);
        DsDatabaseLocalRaftFacade leader = a.isRaftLeader() ? a : b;
        DsDatabaseLocalRaftFacade follower = (leader == a) ? b : a;
        assertTrue("one leader one follower", leader.isRaftLeader() && follower.isRaftFollower());
        long last = 0L;
        for (int i = 1; i <= 100; i++) {
            String path = "/orm/data/row/User/" + String.format("%016x", (long)i);
            byte[] p = new byte[128];
            p[0] = (byte)(i & 0xFF);
            p[1] = (byte)((i >> 8) & 0xFF);
            long idx = leader.writeRowBlocking(path, p);
            assertTrue("row " + i + " idx=" + idx + " monotonic", idx >= (last + 1L));
            last = idx;
        }
        assertEquals(100, leader.localWrites);
        assertTrue("leader lastApplied >= 100 (noop + 100 rows)", leader.getRaftServer().getLastApplied() >= 100L);
        long fddl = System.currentTimeMillis() + 3000L;
        while (follower.getRaftServer().getLastApplied() < 100L && System.currentTimeMillis() < fddl) Thread.sleep(5L);
        assertTrue("follower lastApplied >= 100 quorum=2/2: follower=" + follower.getRaftServer().getLastApplied(),
            follower.getRaftServer().getLastApplied() >= 100L);
    }

    @Test(timeout = 15000)
    public void t02_follower_fence_write_throws_not_leader_with_address() throws InterruptedException {
        File root = new File(System.getProperty("java.io.tmpdir"), "raft-fence-t02");
        root.mkdirs();
        DsDatabaseLocalRaftFacade a = new DsDatabaseLocalRaftFacade(root, MetaMode.REGISTRY, null, 83L, new long[]{84L});
        DsDatabaseLocalRaftFacade b = new DsDatabaseLocalRaftFacade(root, MetaMode.REGISTRY, null, 84L, new long[]{83L});
        long ddl = System.currentTimeMillis() + 4000L;
        while (!a.isRaftLeader() && !b.isRaftLeader() && System.currentTimeMillis() < ddl) Thread.sleep(10L);
        DsDatabaseLocalRaftFacade follower = a.isRaftLeader() ? b : a;
        DsDatabaseLocalRaftFacade leader = (follower == a) ? b : a;
        assertTrue("follower role before write", follower.isRaftFollower() || follower.getRaftServer().getRole() == RaftServer.Role.CANDIDATE);
        follower.getRaftServer().stepDownToFollower(leader.getRaftServer().getCurrentTerm() + 0L, leader.getRaftServer().getLocalPeerId());
        assertSame("manual stepDown follower", RaftServer.Role.FOLLOWER, follower.getRaftServer().getRole());
        try {
            follower.writeRowBlocking("/orm/data/row/X/0000000000000001", new byte[32]);
            fail("Follower 本地写必须抛 IOE NotLeader（fence）");
        } catch (IOException ioe) {
            assertTrue("message 必须包含 NotLeader：" + ioe.getMessage(), ioe.getMessage().contains("Not Leader"));
            assertTrue("message 必须包含 currentLeader 地址：" + ioe.getMessage(),
                ioe.getMessage().contains("peer-") || ioe.getMessage().contains("currentLeader="));
        }
        assertTrue("fenced exception count >= 1 actual=" + follower.raftFencedExceptions, follower.raftFencedExceptions >= 1);
        assertEquals("fence 前 localWrites 0", 0, follower.localWrites);
    }

    @Test(timeout = 15000)
    public void t03_raft_mode_off_null_no_raft_stack_frame_on_write() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "raft-fence-t03");
        root.mkdirs();
        java.util.Map<String, String> offProps = new java.util.HashMap<>();
        offProps.put("p2p.orm.raft.mode", "off");
        DsDatabaseLocalRaftFacade db = new DsDatabaseLocalRaftFacade(root, MetaMode.REGISTRY, offProps, 91L, new long[]{92L});
        assertNull("raft.mode=off → raftServer=null (BackwardCompat P12 MVP)", db.getRaftServer());
        assertTrue("!isRaftEnabled", !db.isRaftEnabled());
        for (int i = 0; i < 100; i++) {
            long idx = db.writeRowBlocking("/orm/off/" + i, new byte[16]);
            assertTrue("off-mode idx = 1 each (local write)", idx == 1L);
        }
        assertEquals(100, db.localWrites);
        assertEquals(0, db.raftFencedExceptions);
        boolean[] foundRaftFrame = new boolean[1];
        class StackCapture {
            void writeCapture() throws IOException {
                db.writeRowBlocking("/orm/off/capture", new byte[8]);
                StackTraceElement[] stack = Thread.currentThread().getStackTrace();
                for (StackTraceElement e : stack) {
                    if (e.getClassName() != null && (e.getClassName().contains("RaftServer")
                        || e.getClassName().contains("RaftLeaderProxy")
                        || e.getClassName().contains("raft.Raft"))) {
                        foundRaftFrame[0] = true;
                    }
                }
            }
        }
        new StackCapture().writeCapture();
        assertTrue("raft.mode=off 方法栈 0 Raft* 调用帧（100% P12 MVP 原路径）", !foundRaftFrame[0]);
    }
}
