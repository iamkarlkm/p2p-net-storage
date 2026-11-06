package ds;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import com.q3lives.ds.registry.meta.RegistryMetaPaths;

import java.io.BufferedReader;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * P12 T1 SyncKeySpaceTest
 * TR-T1-R1 [rule]: SYNC_ROOT="/orm/sync" 字面量，5 helper 返回正确段前缀，peerId16hex/txId 对齐；
 * TR-T1-R2 [rule]: grep "\"/orm/sync\"" p2p-db/src/main/java → 仅 RegistryMetaPaths.java SYNC_ROOT 声明行 1 处命中；
 * TR-T1-R3 [rule]: RegistryMetaPaths public static final String 常量数 ≥ 15（P10 7 + P11 4 + P12 4 = 15）且 public fields 100% String。
 */
public class P12_SyncKeySpaceTest {

    @Test
    public void ac_r1_sync_paths_exist() {
        // SYNC_ROOT 必须字面量 "/orm/sync"（不能拼接，这样 grep 才能 1 处声明行命中）
        assertEquals("/orm/sync", RegistryMetaPaths.SYNC_ROOT);

        // 3 棵 sync 子树根互不包含，且与 ROW/COLUMN/DBS/DATA 前缀零包含
        String peer = RegistryMetaPaths.SYNC_PEER_ROOT;
        String tx = RegistryMetaPaths.SYNC_TX_ROOT;
        assertNotNull(peer);
        assertNotNull(tx);
        assertTrue(peer.startsWith(RegistryMetaPaths.SYNC_ROOT + "/"));
        assertTrue(tx.startsWith(RegistryMetaPaths.SYNC_ROOT + "/"));
        assertFalse(RegistryMetaPaths.ROW_ROOT.startsWith(RegistryMetaPaths.SYNC_ROOT));
        assertFalse(RegistryMetaPaths.COLUMN_ROOT.startsWith(RegistryMetaPaths.SYNC_ROOT));
        assertFalse(RegistryMetaPaths.DBS_ROOT.startsWith(RegistryMetaPaths.SYNC_ROOT));
        assertFalse(RegistryMetaPaths.DATA_ROOT.startsWith(RegistryMetaPaths.SYNC_ROOT));

        // syncPeerState / syncPeerLastAck / syncPeerOverwrites 格式断言：peerId16hex = 0 补 16 位小写
        long peerId = 0x1234L;
        String peerHex16 = "0000000000001234";
        String ps = RegistryMetaPaths.syncPeerState(peerId);
        String pl = RegistryMetaPaths.syncPeerLastAck(peerId);
        String po = RegistryMetaPaths.syncPeerOverwrites(peerId);
        assertTrue(ps, ps.contains("/sync/peer/" + peerHex16 + "/state"));
        assertTrue(pl, pl.contains("/sync/peer/" + peerHex16 + "/lastAckSeq"));
        assertTrue(po, po.endsWith("/overwrites") && po.contains(peerHex16));

        // peerId 很大（22bit max = 2^22-1 = 4194303），peerIdHex 仍 ≤16 字符且全小写 hex
        long bigPeer = (1L << 22) - 1L;
        String bigState = RegistryMetaPaths.syncPeerState(bigPeer);
        String bigHex = bigState.substring(bigState.lastIndexOf("peer/") + 5, bigState.lastIndexOf("/state"));
        assertEquals("peerIdHex 长度 16", 16, bigHex.length());
        for (int i = 0; i < bigHex.length(); i++) {
            char c = bigHex.charAt(i);
            assertTrue("peerIdHex 只能小写 hex: " + bigHex,
                (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'));
        }

        // syncTxState(42L) 格式断言：txId16hex = 0 补 16 位小写
        long txId = 42L;
        String txHex16 = "000000000000002a";
        String ts = RegistryMetaPaths.syncTxState(txId);
        assertTrue(ts, ts.contains("/sync/tx/" + txHex16 + "/state"));

        // syncTxLeaf(txId, registryPath) 格式：/leaves/<sha1Hex40 40 字符 全小写 hex>
        String rPath = RegistryMetaPaths.dataRowPayload("com.foo.User", 1234L);
        String tl = RegistryMetaPaths.syncTxLeaf(txId, rPath);
        assertTrue(tl, tl.contains("/sync/tx/" + txHex16 + "/leaves/"));
        String leafName = tl.substring(tl.lastIndexOf('/') + 1);
        assertEquals("syncTxLeaf sha1 必须 40 字符", 40, leafName.length());
        for (int i = 0; i < leafName.length(); i++) {
            char c = leafName.charAt(i);
            assertTrue("syncTxLeaf 只能小写 hex: " + leafName,
                (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'));
        }
        // 同一 registryPath 多次调用 sha1 必须稳定（deterministic）
        String tl2 = RegistryMetaPaths.syncTxLeaf(txId, rPath);
        assertEquals("syncTxLeaf sha1 不稳定", tl, tl2);

        // 边界：peerId ≤0 必须抛 IllegalArgumentException（防止段名 0000000000000000 冲突）
        int threw = 0;
        try { RegistryMetaPaths.syncPeerState(0L); } catch (IllegalArgumentException e) { threw++; }
        try { RegistryMetaPaths.syncPeerState(-5L); } catch (IllegalArgumentException e) { threw++; }
        try { RegistryMetaPaths.syncPeerLastAck(0L); } catch (IllegalArgumentException e) { threw++; }
        try { RegistryMetaPaths.syncPeerOverwrites(0L); } catch (IllegalArgumentException e) { threw++; }
        try { RegistryMetaPaths.syncTxLeaf(txId, null); } catch (IllegalArgumentException e) { threw++; }
        try { RegistryMetaPaths.syncTxLeaf(txId, ""); } catch (IllegalArgumentException e) { threw++; }
        assertEquals("非法参数应抛 6 次", 6, threw);
    }

    @Test
    public void ac_r2_sync_zero_leak_assert() throws IOException {
        // 内部 grep 实现（Java NIO walk，非系统命令，跨平台；等价于 c10 C5 子断言）
        Path srcRoot = Paths.get("src", "main", "java").toAbsolutePath();
        final List<String> hits = new ArrayList<>();
        final String target = "\"/orm/sync\"";
        try (Stream<Path> walk = Files.walk(srcRoot)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                .forEach(p -> {
                    try (BufferedReader br = Files.newBufferedReader(p)) {
                        String line;
                        int ln = 0;
                        while ((line = br.readLine()) != null) {
                            ln++;
                            if (line.contains(target)) {
                                String fileName = p.getFileName().toString();
                                hits.add(fileName + ":" + ln + " -> " + line.trim());
                            }
                        }
                    } catch (IOException ignored) {}
                });
        }
        // 只允许 RegistryMetaPaths.java 1 处（SYNC_ROOT 常量声明行）
        assertEquals("\"/orm/sync\" 命中数必须 = 1，实际: " + hits, 1, hits.size());
        String only = hits.get(0);
        assertTrue("命中必须在 RegistryMetaPaths.java，实际: " + only,
            only.startsWith("RegistryMetaPaths.java:"));
        assertTrue("命中行必须包含 public static final String SYNC_ROOT，实际: " + only,
            only.contains("SYNC_ROOT"));
    }

    @Test
    public void ac_r3_const_count_ge_15_and_public_string_only() {
        Field[] fields = RegistryMetaPaths.class.getDeclaredFields();
        int constCount = 0;
        for (Field f : fields) {
            int mod = f.getModifiers();
            // 只看 public 字段（R10 C 组 public const 统计）
            if (!Modifier.isPublic(mod)) continue;
            // public 字段 100% 必须是 String（零侵入，不能有 public int/long 等类型混进来）
            assertEquals("public 字段必须是 String 类型: " + f.getName(),
                String.class, f.getType());
            // 统计 public static final String 常量
            if (Modifier.isStatic(mod) && Modifier.isFinal(mod)) {
                constCount++;
            }
        }
        assertTrue("public static final String 常量数必须 ≥15（P10 7+P11 4+P12 4），实际=" + constCount,
            constCount >= 15);
    }
}
