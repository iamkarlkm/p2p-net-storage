package ds;

import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHit;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.FulltextStore.ShardRouter;
import com.q3lives.ds.database.fulltext.cluster.raft.FulltextRaftWire;
import com.q3lives.ds.database.fulltext.cluster.raft.RaftFulltextCommitter;
import com.q3lives.ds.database.fulltext.cluster.raft.RaftFulltextReplica;
import com.q3lives.ds.registry.meta.MetaMode;
import com.q3lives.ds.registry.meta.raft.RaftServer;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * P58 T2：fulltext 分片接入 P57 Raft 共识后的副本一致性（4 TR / 0F0E）。
 *
 * <p>
 * TR2.1 线格式编解码往返 + 非法入参 IAE；TR2.2 3 节点共识主路径：Leader 经
 * {@link RaftFulltextCommitter} 写 M 条，三副本 {@code appliedCount} 与搜索结果逐条一致； TR2.3
 * 重放幂等 + 非 {@code /ft/} 条目被忽略 + {@code clearDoc} 三副本同步；TR2.4 质量审计：新子包 顶级类恒 3、零
 * TODO/FIXME/HACK、零裸打印。</p>
 */
public class P58_T2_FulltextRaftReplicaConsistencyTest {

    private static final String[] CORPUS = {
        "人民民主是社会主义的生命 全过程人民民主",
        "科技创新推动高质量发展 新质生产力",
        "绿水青山就是金山银山 生态环境保护",
        "人类命运共同体 全球治理 国际合作共赢"
    };

    private static final String RAFT_PKG_REL
            = "p2p-db/src/main/java/com/q3lives/ds/database/fulltext/cluster/raft";

    private final List<RaftServer> nodes = new ArrayList<>();
    private final List<ShardRouter> routers = new ArrayList<>();
    private final List<RaftFulltextReplica> replicas = new ArrayList<>();
    private File root;

    @After
    public void cleanup() {
        for (RaftServer s : nodes) {
            try {
                s.stop();
            } catch (Exception ignore) {
            }
        }
        for (RaftFulltextReplica rep : replicas) {
            try {
                rep.close();
            } catch (Exception ignore) {
            }
        }
        for (ShardRouter r : routers) {
            try {
                r.close();
            } catch (Exception ignore) {
            }
        }
        nodes.clear();
        routers.clear();
        replicas.clear();
        RaftServer.PEERS.clear();
        if (root != null) {
            deleteRecursive(root);
        }
    }

    // ===================== TR2.1 线格式编解码 + IAE =====================
    @Test
    public void tr1_wireCodecRoundTripAndIaeGuards() {
        Assert.assertEquals("/ft/doc/42", FulltextRaftWire.docPath(42L));
        Assert.assertEquals("/ft/clr/42", FulltextRaftWire.clearPath(42L));
        Assert.assertEquals("/ft/vec/42", FulltextRaftWire.vecPath(42L));
        Assert.assertTrue(FulltextRaftWire.isFulltextPath("/ft/doc/1"));
        Assert.assertFalse(FulltextRaftWire.isFulltextPath("/orm/raft"));
        Assert.assertFalse(FulltextRaftWire.isFulltextPath(null));

        Assert.assertEquals(42L, FulltextRaftWire.parseId("/ft/doc/42", FulltextRaftWire.OP_PUT_DOC));

        String text = "人民民主 中国特色社会主义";
        Assert.assertEquals(text, FulltextRaftWire.decodeText(FulltextRaftWire.encodeText(text)));
        Assert.assertEquals("", FulltextRaftWire.decodeText(null));

        float[] vec = {1.5f, -2.25f, 0f};
        Assert.assertArrayEquals(vec, FulltextRaftWire.decodeFloats(FulltextRaftWire.encodeFloats(vec)), 0f);

        assertIae(() -> FulltextRaftWire.docPath(0L));
        assertIae(() -> FulltextRaftWire.clearPath(-1L));
        assertIae(() -> FulltextRaftWire.vecPath(-9L));
        assertIae(() -> FulltextRaftWire.encodeText(null));
        assertIae(() -> FulltextRaftWire.encodeFloats(null));
        assertIae(() -> FulltextRaftWire.encodeFloats(new float[0]));
        assertIae(() -> FulltextRaftWire.decodeFloats(new byte[]{1, 2, 3}));
        assertIae(() -> FulltextRaftWire.decodeFloats(null));
        assertIae(() -> FulltextRaftWire.parseId("/ft/doc/abc", FulltextRaftWire.OP_PUT_DOC));
        assertIae(() -> FulltextRaftWire.parseId("/ft/clr/7", FulltextRaftWire.OP_PUT_DOC));
        assertIae(() -> new RaftFulltextReplica(null));
        assertIae(() -> new RaftFulltextCommitter(null));
        assertIae(() -> new RaftFulltextCommitter(null, 1000L));
    }

    // ===================== TR2.2 3 节点副本一致性主路径 =====================
    @Test(timeout = 120000)
    public void tr2_threeNodesReplicaConsistency() throws Exception {
        bootstrapThreeNodes();
        RaftServer leader = awaitLeader(nodes, 6000L);
        Assert.assertNotNull("3 节点必须收敛出 LEADER", leader);
        RaftFulltextCommitter committer = new RaftFulltextCommitter(leader, 5000L);

        final int m = 12;
        for (int i = 0; i < m; i++) {
            committer.putDoc(1001L + i, CORPUS[i % CORPUS.length]);
        }
        awaitAllApplied(m, 10000L);

        for (int n = 0; n < nodes.size(); n++) {
            RaftFulltextReplica rep = replicas.get(n);
            Assert.assertEquals("副本 " + n + " 应用条数", m, rep.appliedCount());
            Assert.assertNull("副本 " + n + " 重放必须零失败", rep.lastFailure());
        }

        List<Long> base = searchIds(routers.get(0), "人民");
        Assert.assertFalse("基准副本必须有命中", base.isEmpty());
        for (int n = 1; n < routers.size(); n++) {
            Assert.assertEquals("副本 " + n + " 搜索结果必须与副本 0 逐条一致",
                    base, searchIds(routers.get(n), "人民"));
        }
    }

    // ===================== TR2.3 幂等 + 非 FT 忽略 + clear 一致 =====================
    @Test(timeout = 120000)
    public void tr3_replayIdempotentNonFtIgnoredAndClearConsistent() throws Exception {
        bootstrapThreeNodes();
        RaftServer leader = awaitLeader(nodes, 6000L);
        Assert.assertNotNull("3 节点必须收敛出 LEADER", leader);
        RaftFulltextCommitter committer = new RaftFulltextCommitter(leader, 5000L);

        committer.putDoc(2001L, "人民民主 全过程人民民主");
        awaitAllApplied(1L, 10000L);

        // 幂等：同一内容重复提交，三副本仍一致且零失败
        committer.putDoc(2001L, "人民民主 全过程人民民主");
        awaitAllApplied(2L, 10000L);
        for (int n = 0; n < nodes.size(); n++) {
            Assert.assertNull("副本 " + n + " 重复提交零失败", replicas.get(n).lastFailure());
        }

        // 非 /ft/ 条目必须被副本忽略（不与 P57 registry/meta 状态机混淆）
        long before = replicas.get(0).appliedCount();
        long idx;
        synchronized (leader) {
            idx = leader.appendPutRegistry("/orm/p58/ignore", new byte[]{1, 2, 3});
        }
        Assert.assertTrue("非 FT 条目仍应正常提交", leader.awaitApplied(idx, 5000L));
        Thread.sleep(200L);
        for (int n = 0; n < nodes.size(); n++) {
            Assert.assertEquals("副本 " + n + " 必须忽略非 /ft/ 条目", before, replicas.get(n).appliedCount());
            Assert.assertNull("副本 " + n + " 忽略非 FT 条目不得产生失败", replicas.get(n).lastFailure());
        }

        // clearDoc：三副本同步清理
        committer.clearDoc(2001L);
        awaitAllApplied(3L, 10000L);
        for (int n = 0; n < routers.size(); n++) {
            Assert.assertTrue("副本 " + n + " clearDoc 后不得再命中",
                    searchIds(routers.get(n), "人民民主").isEmpty());
        }
    }

    // ===================== TR2.4 质量审计 =====================
    @Test
    public void tr4_packageAuditThreeClassesZeroMarkersZeroBarePrint() throws Exception {
        Path dir = resolveRaftSrcDir();
        Assert.assertTrue("raft 子包源码目录必须存在: " + dir, Files.isDirectory(dir));

        List<Path> javaFiles = new ArrayList<>();
        try (Stream<Path> st = Files.walk(dir)) {
            st.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".java"))
                    .forEach(javaFiles::add);
        }
        Assert.assertEquals("fulltext/cluster/raft 顶级类恒 3（Wire/Replica/Committer）", 3, javaFiles.size());

        for (Path jf : javaFiles) {
            String src = new String(Files.readAllBytes(jf), StandardCharsets.UTF_8);
            Assert.assertFalse(jf.getFileName() + " 禁止 TODO", src.contains("TODO"));
            Assert.assertFalse(jf.getFileName() + " 禁止 FIXME", src.contains("FIXME"));
            Assert.assertFalse(jf.getFileName() + " 禁止 HACK", src.contains("HACK"));
            Assert.assertFalse(jf.getFileName() + " 禁止裸打印", src.contains("System.out.print"));
            Assert.assertFalse(jf.getFileName() + " 禁止裸打印", src.contains("System.err.print"));
        }
    }

    // ===================== helpers =====================
    private void bootstrapThreeNodes() throws IOException {
        root = new File(System.getProperty("java.io.tmpdir"), "p58-t2-" + System.nanoTime());
        root.mkdirs();
        // 先建本地分片存储（重磁盘 I/O），再启动 Raft，避免慢启动拖垮选举心跳节奏
        for (int i = 0; i < 3; i++) {
            File shardRoot = new File(root, "shard_node" + (i + 1));
            shardRoot.mkdirs();
            routers.add(new ShardRouter(2, shardRoot.getAbsolutePath()));
        }
        for (String id : new String[]{"1", "2", "3"}) {
            Map<String, String> props = new HashMap<>();
            props.put("p2p.orm.raft.members", "1,2,3");
            props.put("p2p.orm.raft.local", id);
            File nodeRoot = new File(root, "node" + id);
            nodeRoot.mkdirs();
            RaftServer server = RaftServer.create(nodeRoot, MetaMode.REGISTRY, props);
            Assert.assertNotNull("RaftServer 必须创建成功", server);
            nodes.add(server);
        }
        for (RaftServer s : nodes) {
            RaftServer.register(s);
        }
        for (int i = 0; i < nodes.size(); i++) {
            replicas.add(RaftFulltextReplica.attach(nodes.get(i), routers.get(i)));
        }
    }

    /**
     * 等待一个"稳定"Leader：观察连续 250ms 内同一节点保持 LEADER，避开选举抖动窗口。
     */
    private static RaftServer awaitLeader(List<RaftServer> list, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        RaftServer last = null;
        long since = 0L;
        while (System.currentTimeMillis() < deadline) {
            RaftServer cur = null;
            for (RaftServer s : list) {
                if (s.getRole() == RaftServer.Role.LEADER) {
                    cur = s;
                    break;
                }
            }
            if (cur != null && cur == last) {
                if (System.currentTimeMillis() - since >= 250L) {
                    return cur;
                }
            } else {
                last = cur;
                since = System.currentTimeMillis();
            }
            Thread.sleep(10L);
        }
        return last;
    }

    private void awaitAllApplied(long target, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            boolean all = true;
            for (RaftFulltextReplica r : replicas) {
                if (r.appliedCount() < target) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return;
            }
            Thread.sleep(10L);
        }
    }

    private static List<Long> searchIds(ShardRouter router, String q) throws IOException {
        List<Long> ids = new ArrayList<>();
        for (ScoredDocHit hit : router.searchCluster(q, null, 20, SearchOptions.defaults())) {
            ids.add(hit.docId);
        }
        return ids;
    }

    private static Path resolveRaftSrcDir() {
        Path prj = new File(System.getProperty("user.dir")).toPath().toAbsolutePath();
        String rel = RAFT_PKG_REL;
        Path a = prj.resolve(rel);
        if (Files.isDirectory(a)) {
            return a;
        }
        String stripped = rel.startsWith("p2p-db/") ? rel.substring("p2p-db/".length()) : rel;
        Path b = prj.resolve(stripped);
        if (Files.isDirectory(b)) {
            return b;
        }
        Path c = prj.resolve("p2p-db/" + stripped);
        if (Files.isDirectory(c)) {
            return c;
        }
        return a;
    }

    private static void assertIae(Runnable r) {
        try {
            r.run();
            Assert.fail("必须抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null) {
            return;
        }
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) {
                for (File c : ch) {
                    deleteRecursive(c);
                }
            }
        }
        f.delete();
    }
}
