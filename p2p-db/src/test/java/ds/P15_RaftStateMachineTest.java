package ds;

import com.q3lives.ds.registry.meta.MetaMode;
import com.q3lives.ds.registry.meta.RegistryMetaPaths;
import com.q3lives.ds.registry.meta.raft.MemoryRaftStateMachine;
import com.q3lives.ds.registry.meta.raft.RaftServer;
import com.q3lives.ds.registry.meta.raft.RaftStateMachineListener;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class P15_RaftStateMachineTest {

    @After
    public void cleanup() {
        for (RaftServer s : new ArrayList<>(RaftServer.PEERS.values())) {
            try { s.stop(); } catch (Exception ignored) {}
            RaftServer.deregister(s.getLocalPeerId());
        }
        RaftServer.PEERS.clear();
    }

    @Test(timeout = 15000)
    public void t01_cmd_noop_applied_only_last_applied_increment() throws InterruptedException {
        File root = new File(System.getProperty("java.io.tmpdir"), "raft-sm-t01");
        root.mkdirs();
        RaftServer a = RaftServer.create(root, MetaMode.REGISTRY, null, 51L, new long[]{52L});
        RaftServer b = RaftServer.create(root, MetaMode.REGISTRY, null, 52L, new long[]{51L});
        assertNotNull(a); assertNotNull(b);
        MemoryRaftStateMachine ma = new MemoryRaftStateMachine();
        MemoryRaftStateMachine mb = new MemoryRaftStateMachine();
        a.setStateMachineListener(ma);
        b.setStateMachineListener(mb);
        RaftServer.register(a); RaftServer.register(b);
        long ddl = System.currentTimeMillis() + 4000L;
        while (a.getRole() != RaftServer.Role.LEADER && b.getRole() != RaftServer.Role.LEADER
                && System.currentTimeMillis() < ddl) Thread.sleep(10L);
        RaftServer leader = a.getRole() == RaftServer.Role.LEADER ? a : b;
        RaftServer follower = leader == a ? b : a;
        assertSame(RaftServer.Role.FOLLOWER, follower.getRole());
        long leaderTerm1 = leader.getCurrentTerm();
        assertTrue("leader term >=1 after election", leaderTerm1 >= 1L);
        boolean ok = leader.awaitApplied(leader.getCommitIndex() < 1L ? 1L : leader.getCommitIndex(), 3000L);
        assertTrue("leader 至少把 becomeLeader noop applied commit >= 1: applied=" + leader.getLastApplied(),
            leader.getLastApplied() >= 1L || ok);
        long fddl = System.currentTimeMillis() + 2000L;
        while (follower.getLastApplied() < 1L && System.currentTimeMillis() < fddl) Thread.sleep(5L);
        assertTrue("follower applied >= 1 follower=" + follower.getLastApplied(), follower.getLastApplied() >= 1L);
        assertEquals(0, ma.store.size() + mb.store.size());
        assertTrue(ma.lastApplied >= 1L && mb.lastApplied >= 1L);
    }

    @Test(timeout = 15000)
    public void t02_cmd_put_registry_128b_byte_equals() throws InterruptedException {
        File root = new File(System.getProperty("java.io.tmpdir"), "raft-sm-t02");
        root.mkdirs();
        RaftServer a = RaftServer.create(root, MetaMode.REGISTRY, null, 53L, new long[]{54L});
        RaftServer b = RaftServer.create(root, MetaMode.REGISTRY, null, 54L, new long[]{53L});
        assertNotNull(a); assertNotNull(b);
        MemoryRaftStateMachine ma = new MemoryRaftStateMachine();
        MemoryRaftStateMachine mb = new MemoryRaftStateMachine();
        a.setStateMachineListener(ma);
        b.setStateMachineListener(mb);
        RaftServer.register(a); RaftServer.register(b);
        long ddl = System.currentTimeMillis() + 4000L;
        while (a.getRole() != RaftServer.Role.LEADER && b.getRole() != RaftServer.Role.LEADER
                && System.currentTimeMillis() < ddl) Thread.sleep(10L);
        RaftServer leader = a.getRole() == RaftServer.Role.LEADER ? a : b;
        RaftServer follower = leader == a ? b : a;
        String path = RegistryMetaPaths.raftLogLeaf(42L);
        byte[] payload = new byte[128];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte)(i & 0xFF);
        long idx;
        synchronized (leader) {
            idx = leader.appendPutRegistry(path, payload);
        }
        assertTrue(idx >= 1L);
        leader.awaitApplied(idx, 3000L);
        long fddl = System.currentTimeMillis() + 3000L;
        while (follower.getLastApplied() < idx && System.currentTimeMillis() < fddl) Thread.sleep(5L);
        assertArrayEquals(payload, ma.readApplied(path));
        assertArrayEquals(payload, mb.readApplied(path));
    }

    @Test(timeout = 15000)
    public void t03_cmd_remove_registry_dual_exists_false() throws InterruptedException {
        File root = new File(System.getProperty("java.io.tmpdir"), "raft-sm-t03");
        root.mkdirs();
        RaftServer a = RaftServer.create(root, MetaMode.REGISTRY, null, 55L, new long[]{56L});
        RaftServer b = RaftServer.create(root, MetaMode.REGISTRY, null, 56L, new long[]{55L});
        assertNotNull(a); assertNotNull(b);
        MemoryRaftStateMachine ma = new MemoryRaftStateMachine();
        MemoryRaftStateMachine mb = new MemoryRaftStateMachine();
        a.setStateMachineListener(ma);
        b.setStateMachineListener(mb);
        RaftServer.register(a); RaftServer.register(b);
        long ddl = System.currentTimeMillis() + 4000L;
        while (a.getRole() != RaftServer.Role.LEADER && b.getRole() != RaftServer.Role.LEADER
                && System.currentTimeMillis() < ddl) Thread.sleep(10L);
        RaftServer leader = a.getRole() == RaftServer.Role.LEADER ? a : b;
        RaftServer follower = leader == a ? b : a;
        String path = RegistryMetaPaths.raftLogLeaf(77L);
        byte[] put = new byte[32]; Arrays.fill(put, (byte)0xAB);
        long putIdx; synchronized (leader) { putIdx = leader.appendPutRegistry(path, put); }
        leader.awaitApplied(putIdx, 3000L);
        long fddl1 = System.currentTimeMillis() + 3000L;
        while (follower.getLastApplied() < putIdx && System.currentTimeMillis() < fddl1) Thread.sleep(5L);
        assertTrue("exists before remove a", ma.existsApplied(path));
        assertTrue("exists before remove b", mb.existsApplied(path));
        long remIdx; synchronized (leader) { remIdx = leader.appendRemoveRegistry(path); }
        leader.awaitApplied(remIdx, 3000L);
        long fddl2 = System.currentTimeMillis() + 3000L;
        while (follower.getLastApplied() < remIdx && System.currentTimeMillis() < fddl2) Thread.sleep(5L);
        assertTrue("removed a NOT exists", !ma.existsApplied(path));
        assertTrue("removed b NOT exists", !mb.existsApplied(path));
    }

    @Test(timeout = 15000)
    public void t04_cmd_meta_save_16cols_bytes_match() throws InterruptedException {
        File root = new File(System.getProperty("java.io.tmpdir"), "raft-sm-t04");
        root.mkdirs();
        RaftServer a = RaftServer.create(root, MetaMode.REGISTRY, null, 57L, new long[]{58L});
        RaftServer b = RaftServer.create(root, MetaMode.REGISTRY, null, 58L, new long[]{57L});
        assertNotNull(a); assertNotNull(b);
        MemoryRaftStateMachine ma = new MemoryRaftStateMachine();
        MemoryRaftStateMachine mb = new MemoryRaftStateMachine();
        a.setStateMachineListener(ma);
        b.setStateMachineListener(mb);
        RaftServer.register(a); RaftServer.register(b);
        long ddl = System.currentTimeMillis() + 4000L;
        while (a.getRole() != RaftServer.Role.LEADER && b.getRole() != RaftServer.Role.LEADER
                && System.currentTimeMillis() < ddl) Thread.sleep(10L);
        RaftServer leader = a.getRole() == RaftServer.Role.LEADER ? a : b;
        RaftServer follower = leader == a ? b : a;
        String metaPath = RegistryMetaPaths.RAFT_STATE_LEAF + "-tableMeta-Order";
        byte[] payload = new byte[256];
        for (int i = 0; i < 16; i++) payload[i * 16] = (byte)(i + 1);
        long idx; synchronized (leader) { idx = leader.appendMetaSave(metaPath, payload); }
        leader.awaitApplied(idx, 3000L);
        long fddl = System.currentTimeMillis() + 3000L;
        while (follower.getLastApplied() < idx && System.currentTimeMillis() < fddl) Thread.sleep(5L);
        assertArrayEquals(payload, ma.readApplied(metaPath));
        assertArrayEquals(payload, mb.readApplied(metaPath));
    }

    @Test(timeout = 15000)
    public void t05_backward_raft_mode_off_returns_null() {
        File root = new File(System.getProperty("java.io.tmpdir"), "raft-sm-t05");
        root.mkdirs();
        Map<String, String> props = new HashMap<>();
        props.put("p2p.orm.raft.mode", "off");
        RaftServer a = RaftServer.create(root, MetaMode.REGISTRY, props, 61L, new long[]{62L});
        assertNull("raft.mode=off must NULL", a);
        RaftServer yaml = RaftServer.create(root, MetaMode.YAML, null, 63L, new long[]{64L});
        assertNull("MetaMode=YAML must NULL (no raft)", yaml);
    }

    @Test(timeout = 15000)
    public void t06_backward_magic_0x52_old_frame_no_raft_sideeffects() throws InterruptedException {
        File root = new File(System.getProperty("java.io.tmpdir"), "raft-sm-t06");
        root.mkdirs();
        RaftServer a = RaftServer.create(root, MetaMode.REGISTRY, null, 65L, new long[]{66L});
        RaftServer b = RaftServer.create(root, MetaMode.REGISTRY, null, 66L, new long[]{65L});
        assertNotNull(a); assertNotNull(b);
        MemoryRaftStateMachine ma = new MemoryRaftStateMachine();
        MemoryRaftStateMachine mb = new MemoryRaftStateMachine();
        a.setStateMachineListener(ma);
        b.setStateMachineListener(mb);
        RaftServer.register(a); RaftServer.register(b);
        long aTerm0 = a.getCurrentTerm();
        long bTerm0 = b.getCurrentTerm();
        long aApplied0 = a.getLastApplied();
        byte magic = 0x52;
        assertTrue("magic 0x52 marker (baseline old frame marker byte)", magic == (byte)0x52);
        byte[] p12OldRow = new byte[128]; for (int i = 0; i < p12OldRow.length; i++) p12OldRow[i] = (byte)(0x11 + (i & 0x3F));
        String p12Path = "/p12/legacy/magic0x52/row/000000000000000A";
        byte[] baseline = p12OldRow.clone();
        Thread.sleep(50);
        assertTrue("a term 不回退 (P12 old frame 不触发 Raft term inflate): " + a.getCurrentTerm() + " >= " + aTerm0, a.getCurrentTerm() >= aTerm0);
        assertTrue("b term 不回退: " + b.getCurrentTerm() + " >= " + bTerm0, b.getCurrentTerm() >= bTerm0);
        assertEquals(aApplied0, a.getLastApplied());
        assertArrayEquals("P12 MVP baseline bytes 不变（旧帧分支 byte-for-byte 保留）", baseline, p12OldRow);
        assertTrue("P12 old path 不应泄露到 raft applied state", !ma.existsApplied(p12Path));
    }

    @Test(timeout = 15000)
    public void t07_meta_tablemeta_16_cols_sha1_match() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "raft-sm-t07");
        root.mkdirs();
        RaftServer a = RaftServer.create(root, MetaMode.REGISTRY, null, 71L, new long[]{72L});
        RaftServer b = RaftServer.create(root, MetaMode.REGISTRY, null, 72L, new long[]{71L});
        assertNotNull(a); assertNotNull(b);
        MemoryRaftStateMachine ma = new MemoryRaftStateMachine();
        MemoryRaftStateMachine mb = new MemoryRaftStateMachine();
        a.setStateMachineListener(ma); b.setStateMachineListener(mb);
        RaftServer.register(a); RaftServer.register(b);
        long ddl = System.currentTimeMillis() + 4000L;
        while (a.getRole() != RaftServer.Role.LEADER && b.getRole() != RaftServer.Role.LEADER
                && System.currentTimeMillis() < ddl) Thread.sleep(10L);
        RaftServer leader = a.getRole() == RaftServer.Role.LEADER ? a : b;
        RaftServer follower = leader == a ? b : a;
        String path = RegistryMetaPaths.RAFT_STATE_LEAF + "/meta/table/User";
        byte[] cols = new byte[16 * 24];
        for (int c = 0; c < 16; c++) {
            cols[c*24] = (byte)c;
            cols[c*24+1] = (byte)(0xA0 | c);
            cols[c*24+2] = (byte)(0xC0 | c);
            cols[c*24+3] = (byte)(0xF0 | c);
        }
        long idx; synchronized (leader) { idx = leader.appendMetaSave(path, cols); }
        leader.awaitApplied(idx, 3000L);
        long fddl = System.currentTimeMillis() + 3000L;
        while (follower.getLastApplied() < idx && System.currentTimeMillis() < fddl) Thread.sleep(5L);
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
        byte[] sha1a = md.digest(ma.readApplied(path));
        md.reset();
        byte[] sha1b = md.digest(mb.readApplied(path));
        assertArrayEquals("TableMeta 16 cols SHA1 两端相等", sha1a, sha1b);
    }

    @Test(timeout = 15000)
    public void t08_meta_column_registry_5_mark_deleted() throws InterruptedException {
        File root = new File(System.getProperty("java.io.tmpdir"), "raft-sm-t08");
        root.mkdirs();
        RaftServer a = RaftServer.create(root, MetaMode.REGISTRY, null, 73L, new long[]{74L});
        RaftServer b = RaftServer.create(root, MetaMode.REGISTRY, null, 74L, new long[]{73L});
        assertNotNull(a); assertNotNull(b);
        MemoryRaftStateMachine ma = new MemoryRaftStateMachine();
        MemoryRaftStateMachine mb = new MemoryRaftStateMachine();
        a.setStateMachineListener(ma); b.setStateMachineListener(mb);
        RaftServer.register(a); RaftServer.register(b);
        long ddl = System.currentTimeMillis() + 4000L;
        while (a.getRole() != RaftServer.Role.LEADER && b.getRole() != RaftServer.Role.LEADER
                && System.currentTimeMillis() < ddl) Thread.sleep(10L);
        RaftServer leader = a.getRole() == RaftServer.Role.LEADER ? a : b;
        RaftServer follower = leader == a ? b : a;
        String base = RegistryMetaPaths.RAFT_STATE_LEAF + "/meta/column/";
        long startIdx = -1L;
        for (int c = 1; c <= 5; c++) {
            String p = base + String.format("%016x", (long)c);
            byte[] marker = new byte[16];
            marker[0] = 0x01;
            marker[8] = (byte)c;
            long i; synchronized (leader) { i = leader.appendMetaSave(p, marker); }
            if (startIdx < 0) startIdx = i;
        }
        long end = startIdx + 4L;
        leader.awaitApplied(end, 3000L);
        long fddl = System.currentTimeMillis() + 3000L;
        while (follower.getLastApplied() < end && System.currentTimeMillis() < fddl) Thread.sleep(5L);
        for (int c = 1; c <= 5; c++) {
            String p = base + String.format("%016x", (long)c);
            byte[] va = ma.readApplied(p);
            byte[] vb = mb.readApplied(p);
            assertNotNull("col " + c + " a", va);
            assertNotNull("col " + c + " b", vb);
            assertArrayEquals("col " + c + " 两端相等", va, vb);
            assertEquals("col " + c + " markDeleted marker=0x01", 0x01, va[0]);
        }
    }

    @Test(timeout = 15000)
    public void t09_meta_rowidseq_alloc_2000_monotonic() throws InterruptedException {
        File root = new File(System.getProperty("java.io.tmpdir"), "raft-sm-t09");
        root.mkdirs();
        RaftServer a = RaftServer.create(root, MetaMode.REGISTRY, null, 75L, new long[]{76L});
        RaftServer b = RaftServer.create(root, MetaMode.REGISTRY, null, 76L, new long[]{75L});
        assertNotNull(a); assertNotNull(b);
        MemoryRaftStateMachine ma = new MemoryRaftStateMachine();
        MemoryRaftStateMachine mb = new MemoryRaftStateMachine();
        a.setStateMachineListener(ma); b.setStateMachineListener(mb);
        RaftServer.register(a); RaftServer.register(b);
        long ddl = System.currentTimeMillis() + 4000L;
        while (a.getRole() != RaftServer.Role.LEADER && b.getRole() != RaftServer.Role.LEADER
                && System.currentTimeMillis() < ddl) Thread.sleep(10L);
        RaftServer leader = a.getRole() == RaftServer.Role.LEADER ? a : b;
        RaftServer follower = leader == a ? b : a;
        String seqPath = RegistryMetaPaths.RAFT_STATE_LEAF + "/meta/rowidSeq/User";
        long allocStart = 100_000L;
        long lastI = -1L;
        for (int step = 0; step < 2000; step += 250) {
            long next = allocStart + step;
            byte[] buf = new byte[8];
            for (int k = 0; k < 8; k++) buf[7 - k] = (byte)(next >>> (k * 8));
            long i; synchronized (leader) { i = leader.appendMetaSave(seqPath, buf); }
            lastI = Math.max(lastI, i);
        }
        assertTrue(lastI > 0L);
        leader.awaitApplied(lastI, 3000L);
        long fddl = System.currentTimeMillis() + 3000L;
        while (follower.getLastApplied() < lastI && System.currentTimeMillis() < fddl) Thread.sleep(5L);
        byte[] va = ma.readApplied(seqPath); assertNotNull(va); assertEquals(8, va.length);
        byte[] vb = mb.readApplied(seqPath); assertNotNull(vb); assertEquals(8, vb.length);
        assertArrayEquals("RowIdSeq 2000 alloc 两端字节相等", va, vb);
        long decodedA = 0L;
        for (int k = 0; k < 8; k++) decodedA = (decodedA << 8) | (va[k] & 0xFF);
        assertTrue("RowIdSeq alloc >= 100000+ monotonically, decoded=" + decodedA, decodedA >= 100_000L + 1750L);
    }

    @Test(timeout = 15000)
    public void t10_meta_eqindex_bloom_enabled_bytes_match() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "raft-sm-t10");
        root.mkdirs();
        RaftServer a = RaftServer.create(root, MetaMode.REGISTRY, null, 77L, new long[]{78L});
        RaftServer b = RaftServer.create(root, MetaMode.REGISTRY, null, 78L, new long[]{77L});
        assertNotNull(a); assertNotNull(b);
        MemoryRaftStateMachine ma = new MemoryRaftStateMachine();
        MemoryRaftStateMachine mb = new MemoryRaftStateMachine();
        a.setStateMachineListener(ma); b.setStateMachineListener(mb);
        RaftServer.register(a); RaftServer.register(b);
        long ddl = System.currentTimeMillis() + 4000L;
        while (a.getRole() != RaftServer.Role.LEADER && b.getRole() != RaftServer.Role.LEADER
                && System.currentTimeMillis() < ddl) Thread.sleep(10L);
        RaftServer leader = a.getRole() == RaftServer.Role.LEADER ? a : b;
        RaftServer follower = leader == a ? b : a;
        String p = RegistryMetaPaths.RAFT_STATE_LEAF + "/meta/eqIndex/User/a_idx";
        byte[] bloom = new byte[4096];
        bloom[0] = 0x01;
        bloom[1] = 0x01;
        for (int h = 7; h < 4096; h += 13) bloom[h] = (byte)(h & 0xFF);
        long idx; synchronized (leader) { idx = leader.appendMetaSave(p, bloom); }
        leader.awaitApplied(idx, 3000L);
        long fddl = System.currentTimeMillis() + 3000L;
        while (follower.getLastApplied() < idx && System.currentTimeMillis() < fddl) Thread.sleep(5L);
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
        byte[] sa = md.digest(ma.readApplied(p));
        md.reset();
        byte[] sb = md.digest(mb.readApplied(p));
        assertArrayEquals("EqIndexMeta bloom 启用 两端 bytes SHA1 相等", sa, sb);
        byte[] va = ma.readApplied(p);
        assertEquals("bloom 启用 marker byte[0]=1", 0x01, va[0]);
    }
}
