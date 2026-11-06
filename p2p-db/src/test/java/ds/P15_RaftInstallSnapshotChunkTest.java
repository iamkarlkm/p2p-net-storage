package ds;

import com.q3lives.ds.registry.meta.MetaMode;
import com.q3lives.ds.registry.meta.raft.RaftServer;
import com.q3lives.ds.registry.meta.raft.RaftServer.InstallSnapshotChunk;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.*;

public class P15_RaftInstallSnapshotChunkTest {

    private static final int CHUNK_SIZE = 64 * 1024;

    private static void tickServer(RaftServer s) {
        try {
            Method m = RaftServer.class.getDeclaredMethod("heartbeatOrElectionTick");
            m.setAccessible(true);
            m.invoke(s);
        } catch (Throwable ignored) {}
    }

    @After
    public void cleanup() {
        for (RaftServer s : new ArrayList<>(RaftServer.PEERS.values())) {
            try { s.stop(); } catch (Throwable ignored) {}
            try { RaftServer.deregister(s.getLocalPeerId()); } catch (Throwable ignored) {}
        }
        RaftServer.PEERS.clear();
    }

    @Test(timeout = 20000)
    public void t01_install_snapshot_64KB_chunks() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "raft-snap-t01");
        root.mkdirs();
        RaftServer leader = RaftServer.create(root, MetaMode.REGISTRY, null, 91L, new long[]{92L});
        RaftServer follower = RaftServer.create(root, MetaMode.REGISTRY, null, 92L, new long[]{91L});
        assertNotNull(leader); assertNotNull(follower);
        RaftServer.register(leader);
        RaftServer.register(follower);
        waitLeader(leader, follower, 8_000L);
        RaftServer realLeader = (leader.getRole() == RaftServer.Role.LEADER) ? leader : follower;
        RaftServer realFollower = (realLeader == leader) ? follower : leader;

        int totalBytes = CHUNK_SIZE * 3 + 333;
        byte[] bigPayload = new byte[totalBytes];
        Random rnd = new Random(0x52);
        rnd.nextBytes(bigPayload);
        assertEquals(totalBytes, bigPayload.length);

        int wantIdx = 4096;
        long term = realLeader.getCurrentTerm();
        long off = 0L;
        int sent = 0;
        while (sent < totalBytes) {
            int remain = totalBytes - sent;
            int sz = Math.min(CHUNK_SIZE, remain);
            byte[] chunk = Arrays.copyOfRange(bigPayload, sent, sent + sz);
            boolean done = sent + sz >= totalBytes;
            InstallSnapshotChunk c = new InstallSnapshotChunk(term, realLeader.getLocalPeerId(),
                wantIdx, term, off, chunk, done);
            RaftServer.SnapshotResult res = realFollower.installSnapshot(c);
            assertTrue("chunk at off=" + off + " success", res.success);
            assertEquals("term preserved", term, res.term);
            sent += sz;
            off += sz;
        }
        assertEquals("wantIdx snapshot applied", wantIdx, realFollower.getSnapshotLastIncludedIndex());
        assertEquals("follower logs cleared after snapshot", 0, realFollower.logSize());
    }

    @Test(timeout = 20000)
    public void t02_try_append_routes_snapshot_when_nextIndex_behind() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "raft-snap-t02");
        root.mkdirs();
        RaftServer leader = RaftServer.create(root, MetaMode.REGISTRY, null, 93L, new long[]{94L});
        RaftServer follower = RaftServer.create(root, MetaMode.REGISTRY, null, 94L, new long[]{93L});
        assertNotNull(leader); assertNotNull(follower);
        RaftServer.register(leader);
        RaftServer.register(follower);
        waitLeader(leader, follower, 8_000L);
        RaftServer realLeader = (leader.getRole() == RaftServer.Role.LEADER) ? leader : follower;
        RaftServer realFollower = (realLeader == leader) ? follower : leader;

        for (int i = 0; i < 200; i++) {
            realLeader.appendPutRegistry("/orm/data/r" + i, new byte[32]);
        }
        for (int i = 0; i < 10; i++) {
            Thread.sleep(50L);
            for (RaftServer s : new ArrayList<>(RaftServer.PEERS.values())) tickServer(s);
        }
        realLeader.forceCompact();
        assertTrue("leader has snapshot", realLeader.getSnapshotLastIncludedIndex() > 0);
        long si = realLeader.getSnapshotLastIncludedIndex();
        RaftServer.PeerState peerState = realLeader.peerState(realFollower.getLocalPeerId());
        peerState.nextIndex = Math.max(1L, si - 10L);
        assertTrue("peer.nextIndex <= snapshot idx (route snapshot)", peerState.nextIndex <= si);

        Method m = RaftServer.class.getDeclaredMethod("tryAppendEntries", long.class, boolean.class);
        m.setAccessible(true);
        m.invoke(realLeader, realFollower.getLocalPeerId(), true);
        assertEquals("peer nextIndex bumped via install local", si + 1L, peerState.nextIndex);
        assertEquals("peer matchIndex = snapshot idx", si, peerState.matchIndex);

        Object[] lA = leaderFollowerSnapshot(realLeader);
        assertEquals("leader snapshotIdx = si", si, (long) (Long) lA[0]);
    }

    private Object[] leaderFollowerSnapshot(RaftServer s) {
        return new Object[] {
            s.getSnapshotLastIncludedIndex(),
            s.getCommitIndex(),
            s.getLastApplied(),
            s.getCurrentTerm(),
            s.logSize(),
        };
    }

    private void waitLeader(RaftServer a, RaftServer b, long deadlineMs) throws InterruptedException {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < deadlineMs) {
            if ((a.getRole() == RaftServer.Role.LEADER || b.getRole() == RaftServer.Role.LEADER)
                && (a.getRole() == RaftServer.Role.FOLLOWER || b.getRole() == RaftServer.Role.FOLLOWER)) return;
            Thread.sleep(100L);
            tickServer(a);
            tickServer(b);
        }
    }
}
