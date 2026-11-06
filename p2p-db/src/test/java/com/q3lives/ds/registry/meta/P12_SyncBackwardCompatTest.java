package com.q3lives.ds.registry.meta;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.registry.DsRegistry;
import com.q3lives.ds.registry.meta.AbstractRegistryReplicator.DsRegistrySyncFrame;
import com.q3lives.ds.registry.meta.AbstractRegistryReplicator.OldFrameMarkerException;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * P12 T2 Step2 测试类 A：BackwardCompat（R2 Frame magic=0x52 向后兼容）
 * TRs:
 *   - ac_r1_magic_0x52_apply_pass       ：magic=0x52 frame → applyRegistryFrame 执行，Registry payload byte-for-byte 相等
 *   - ac_r2_magic_non_0x52_throw_marker ：magic=0x01 → applyRegistryFrame 抛 OldFrameMarkerException
 *   - ac_r3_raw_bytes_no_magic_throw    ：裸 byte[] 首字节 0x00 → applyRegistryFrame 抛 OldFrameMarkerException
 *   - ac_r4_outoforder_version_high_win ：乱序 2 帧（先发 v=2，再发 v=1）→ 最终 payload 取 v=2 值，peerOverwrites≥1
 *   - ac_r5_outoforder_100_frames       ：乱序 100 帧 → peerOverwrites≥10（验证乱序幂等 CAS 高 version 赢）
 */
public class P12_SyncBackwardCompatTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File dbRoot;
    private AbstractRegistryReplicator replicator;
    private DsRegistry registry;

    @Before
    public void setUp() throws Exception {
        dbRoot = tmp.newFolder("p12-sync-bc").getCanonicalFile();
        replicator = AbstractRegistryReplicator.create(dbRoot, MetaMode.REGISTRY);
        registry = replicator._getRegistryForTests();
    }

    @After
    public void tearDown() throws Exception {
        if (registry != null) try { registry.close(); } catch (Exception ignored) {}
    }

    @Test
    public void ac_r1_magic_0x52_apply_pass() throws Exception {
        String path = RegistryMetaPaths.dataRowPayload("com.p12.User", 1L);
        byte[] payload = "hello-p12-bc".getBytes(StandardCharsets.UTF_8);
        long version = 100L;
        long peerId = 0xABCDL;
        byte[] frame = DsRegistrySyncFrame.encode(
            (byte) 0x52, 1L, version, path, payload, (byte) 0);
        replicator.applyRegistryFrame(frame, peerId);
        byte[] actual = replicator._getRegistryForTests().get(path).asBytes();
        assertArrayEquals("magic=0x52 frame apply 后 payload 必须 byte-for-byte 相等", payload, actual);
    }

    @Test
    public void ac_r2_magic_non_0x52_throw_marker() throws Exception {
        byte[] frame = new byte[32];
        frame[0] = 0x01;
        try {
            replicator.applyRegistryFrame(frame, 1L);
            fail("magic=0x01 必须抛 OldFrameMarkerException 触发旧 bucket 分支");
        } catch (OldFrameMarkerException expected) {
            assertTrue("OldFrameMarkerException message 应含 magic 或 0x52 提示",
                expected.getMessage().contains("0x52") || expected.getMessage().contains("magic"));
        }
    }

    @Test
    public void ac_r3_raw_bytes_no_magic_throw() throws Exception {
        byte[] raw = new byte[8];
        raw[0] = 0x00;
        try {
            replicator.applyRegistryFrame(raw, 2L);
            fail("裸 byte[]（首字节 0x00，无 magic=0x52）必须抛 OldFrameMarkerException 向后兼容");
        } catch (OldFrameMarkerException expected) {
            assertTrue("OldFrameMarkerException 作为向后兼容 marker 抛出来了",
                expected != null);
        }
    }

    @Test
    public void ac_r4_outoforder_version_high_win() throws Exception {
        String path = RegistryMetaPaths.dataColumnPayload("com.p12.Order", "status", 42L);
        byte[] payloadV1 = "v1-pending".getBytes(StandardCharsets.UTF_8);
        byte[] payloadV2 = "v2-paid".getBytes(StandardCharsets.UTF_8);
        long peerId = 0xBEEFL;
        byte[] fv2 = DsRegistrySyncFrame.encode((byte) 0x52, 2L, 200L, path, payloadV2, (byte) 0);
        byte[] fv1 = DsRegistrySyncFrame.encode((byte) 0x52, 1L, 100L, path, payloadV1, (byte) 0);
        replicator.applyRegistryFrame(fv2, peerId);
        replicator.applyRegistryFrame(fv1, peerId);
        byte[] actual = registry.get(path).asBytes();
        assertArrayEquals("乱序 v=1 不能覆盖 v=2，最终 payload 必须是 v=2（高 version 赢）", payloadV2, actual);
        assertTrue("乱序至少 1 次 overwrite 计数", replicator.getPeerOverwritesForTests(peerId) >= 1L);
    }

    @Test
    public void ac_r5_outoforder_100_frames() throws Exception {
        String path = RegistryMetaPaths.dataGroupPayload("com.p12.BigTable", "cgMeasure", 7L);
        long peerId = 0xCAFEL;
        int N = 100;
        List<byte[]> frames = new ArrayList<>(N);
        for (int i = 1; i <= N; i++) {
            byte[] payload = ("frame-v" + i).getBytes(StandardCharsets.UTF_8);
            frames.add(DsRegistrySyncFrame.encode((byte) 0x52, i, (long) i, path, payload, (byte) 0));
        }
        Collections.shuffle(frames, new SecureRandom(new byte[]{0x11, 0x22, 0x33}));
        for (byte[] f : frames) replicator.applyRegistryFrame(f, peerId);
        byte[] expectedV100 = "frame-v100".getBytes(StandardCharsets.UTF_8);
        byte[] actual = registry.get(path).asBytes();
        assertArrayEquals("100 乱序帧最终 payload 必须是 v=100（最高 version）", expectedV100, actual);
        assertTrue("100 乱序帧 peerOverwrites 计数≥10（实际: " + replicator.getPeerOverwritesForTests(peerId) + "）",
            replicator.getPeerOverwritesForTests(peerId) >= 10L);
    }
}
