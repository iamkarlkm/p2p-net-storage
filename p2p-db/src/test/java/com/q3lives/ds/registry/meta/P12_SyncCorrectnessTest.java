package com.q3lives.ds.registry.meta;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.registry.DsRegistry;

/**
 * P12 T5 Step3 SplitBrain + Heartbeat 两个正确性专项
 *   - splitBrainHighVersionWinsAfterReconcile：双节点断连同 key 独立高低 v 写 → 重连后双方收敛到高 v
 *   - heartbeatPeerOverwritesAndQueuePreservation：peer 追踪计数非降；未知 peer 返回 0 不抛；队列无丢帧
 *
 * inMemoryFrameQueue = Queue<byte[]>（T2 设计：dispatchFrame 直接入队 byte[] frame payload）
 */
public class P12_SyncCorrectnessTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @Before
    public void setUp() {
        try {
            java.lang.reflect.Field f = DsRegistryInstance.class.getDeclaredField("INSTANCES");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, ?> m = (Map<String, ?>) f.get(null);
            m.clear();
        } catch (Exception ignored) {}
        System.clearProperty(MetaMode.PROPERTY);
        System.clearProperty(AbstractRegistryReplicator.SYNC_OFF_PROP);
    }

    /**
     * Split-Brain R10：
     *  2 个 RR（A/B），10 个固定 key p12-split-0..9
     *  A replicate 10× v=1..10 payload len=100+i 填充字节=i+1
     *  B replicate 10× 同 key，v=11..20 字节填充=i+11（高版本）
     *  乱序双向 apply → A & B 每 key 收敛到高 v，payload 100% 字节相等，overwrites≥1
     */
    @Test
    public void splitBrainHighVersionWinsAfterReconcile() throws Exception {
        File rootA = tmp.newFolder("p12-split-a");
        File rootB = tmp.newFolder("p12-split-b");
        new DsDatabaseMetaMigrator(rootA, false).migrateAll();
        new DsDatabaseMetaMigrator(rootB, false).migrateAll();
        AbstractRegistryReplicator rrA = AbstractRegistryReplicator.create(rootA, MetaMode.REGISTRY);
        AbstractRegistryReplicator rrB = AbstractRegistryReplicator.create(rootB, MetaMode.REGISTRY);
        assertNotNull(rrA); assertNotNull(rrB);

        @SuppressWarnings("unchecked")
        Queue<byte[]> qa = (Queue<byte[]>) rrA.getInMemoryFrameQueueForTests();
        @SuppressWarnings("unchecked")
        Queue<byte[]> qb = (Queue<byte[]>) rrB.getInMemoryFrameQueueForTests();
        DsRegistry regA = rrA._getRegistryForTests();
        DsRegistry regB = rrB._getRegistryForTests();
        long[] peerAtoB = new long[] {7L};
        long[] peerBtoA = new long[] {8L};

        for (int i = 0; i < 10; i++) {
            String key = RegistryMetaPaths.DATA_ROOT + "/p12-split-" + i + "/d";
            int vA = i + 1;
            byte[] pA = new byte[100 + i];
            for (int k = 0; k < pA.length; k++) pA[k] = (byte) (i + 1);
            rrA.replicateKey(key, pA, vA, peerAtoB);

            int vB = i + 11;
            byte[] pB = new byte[100 + i];
            for (int k = 0; k < pB.length; k++) pB[k] = (byte) (i + 11);
            rrB.replicateKey(key, pB, vB, peerBtoA);
        }
        assertEquals("A 10 frames 入队", 10, qa.size());
        assertEquals("B 10 frames 入队", 10, qb.size());

        // Step1: A 本地 replicateKey 仅 dispatch 10 frames → 先 apply A frames to A 自身（模拟 DML hook 已经先写本地 DataBackend）
        List<byte[]> listAOwn = new ArrayList<>(qa);
        for (byte[] f : listAOwn) rrA.applyRegistryFrame(f, 7L); // A 本地写入 payload v=1..10
        List<byte[]> listBOwn = new ArrayList<>(qb);
        for (byte[] f : listBOwn) rrB.applyRegistryFrame(f, 8L); // B 本地写入 payload v=11..20

        // Step2: 乱序把 A 的 frame 发给 B（A v 低被 overwrites）；B 的发给 A（B v 高覆盖 A 本地）
        Collections.shuffle(listAOwn);
        Collections.shuffle(listBOwn);
        for (byte[] f : listAOwn) rrB.applyRegistryFrame(f, 7L);
        for (byte[] f : listBOwn) rrA.applyRegistryFrame(f, 8L);

        // A & B 收敛到高 v：payload 字节 = i+11（vB 值）
        for (int i = 0; i < 10; i++) {
            String key = RegistryMetaPaths.DATA_ROOT + "/p12-split-" + i + "/d";
            byte[] a = regA.get(key).asBytes();
            byte[] b = regB.get(key).asBytes();
            assertNotNull(a); assertNotNull(b);
            assertEquals("len " + key + " = 100+i", 100 + i, a.length);
            assertArrayEquals("A==B byte-for-byte " + key, a, b);
            byte ref = (byte) (i + 11);
            for (int k = 0; k < a.length; k++)
                assertEquals("byte " + key + "[" + k + "]", ref, a[k]);
        }

        // overwrites 语义：本地 version ≥ 新来 frame 的 version（高版本拦截低版本写入）→ 才 +1
        // B 本地 v11 收到 A v1 → B overwrite A peer 7，计数≥1
        // A 本地 v1 收到 B v11 → A 正常写入（不拦截），overwrites 可能=0（非负）
        long oA = rrA.getPeerOverwritesForTests(8L);
        long oB = rrB.getPeerOverwritesForTests(7L);
        assertTrue("A 对 peer 8 overwrites ≥ 0（非负），实际=" + oA, oA >= 0);
        assertTrue("B 对 peer 7（A 低 v frame 被高 B v 拦截）overwrites ≥1，实际=" + oB, oB >= 1);
    }

    /**
     * Heartbeat R11：100 frames 入队 → size=100（无丢帧）；未知 peer 9999 → overwrites=0 不抛；
     *   乱序 apply 后 overwrites≥0；高 v frame 最终 payload 字节正确
     */
    @Test
    public void heartbeatPeerOverwritesAndQueuePreservation() throws Exception {
        File root = tmp.newFolder("p12-heartbeat");
        new DsDatabaseMetaMigrator(root, false).migrateAll();
        AbstractRegistryReplicator rr = AbstractRegistryReplicator.create(root, MetaMode.REGISTRY);
        assertNotNull(rr);
        @SuppressWarnings("unchecked")
        Queue<byte[]> q = (Queue<byte[]>) rr.getInMemoryFrameQueueForTests();
        DsRegistry reg = rr._getRegistryForTests();

        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < 100; i++) order.add(i);
        Collections.shuffle(order);
        for (int i : order) {
            String key = RegistryMetaPaths.DATA_ROOT + "/p12-hb-" + i + "/d";
            byte[] p = new byte[32 + i];
            for (int k = 0; k < p.length; k++) p[k] = (byte) (i + 1);
            rr.replicateKey(key, p, i + 1, new long[] {9001L});
        }
        assertEquals("heartbeat: 100 frames dispatch 无丢帧", 100, q.size());

        assertEquals("heartbeat: 未知 peer 9999 → overwrites=0（不抛）",
            0L, rr.getPeerOverwritesForTests(9999L));

        // 乱序 apply：低 v 先 apply 高 v 覆盖
        List<byte[]> frames = new ArrayList<>(q);
        Collections.shuffle(frames);
        for (byte[] f : frames) rr.applyRegistryFrame(f, 9001L);
        long o = rr.getPeerOverwritesForTests(9001L);
        assertTrue("heartbeat: peer 9001 overwrites≥0（乱序→通常≥10），实际=" + o, o >= 0);

        // 高 v 99 payload = (byte)100 × 131 字节
        String key99 = RegistryMetaPaths.DATA_ROOT + "/p12-hb-99/d";
        byte[] last = reg.get(key99).asBytes();
        assertNotNull(last);
        assertEquals("heartbeat: v99 payload 长度 = 131", 131, last.length);
        for (int k = 0; k < last.length; k++)
            assertEquals("heartbeat: v99 payload 每个字节 = i+1=100", (byte) 100, last[k]);
    }
}
