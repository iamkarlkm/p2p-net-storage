package ds;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.net.p2p.server.fulltext.FulltextShardTcpServer;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.FulltextStore.IngestStat;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.cluster.DsFulltextClusterClient;
import com.q3lives.ds.database.fulltext.cluster.DsHybridQueryClient;
import com.q3lives.ds.database.fulltext.cluster.HybridHit;
import com.q3lives.ds.database.fulltext.cluster.join.HybridJoinClient;
import com.q3lives.ds.database.fulltext.cluster.join.JoinSpec;
import com.q3lives.ds.database.fulltext.cluster.join.v2.HybridJoinClientV2Adapter;
import com.q3lives.ds.database.fulltext.cluster.join.v2.JoinMode;
import com.q3lives.ds.database.fulltext.cluster.join.v2.ManyToOneJoinResult;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.integration.QueryWrapper;

/**
 * P35 T4：跨 Process TCP ManyToOne 指纹稳定，3 TR。
 *
 * <p>
 * TR4.1 跨进程 J=1 N:1 命中稳定：sub-process 启动 FulltextShardTcpServer，本地客户端走 TCP →
 * ManyToOne 命中
 * <br>TR4.2 2 次重跑排序指纹稳定（跨进程）
 * <br>TR4.3 ManyToOneJoinResult 跨进程序列化回传 fingerprint 稳定（joinFetch 序列化检查）
 */
public class P35_T4_TcpCrossProcessJoinV2Test {

    @Rule
    public TemporaryFolder tf = new TemporaryFolder();

    @com.q3lives.ds.annotation.DsTable(name = "p35_t4_main")
    public static class MainT4 extends DsTableAdapter {

        @DsField(name = "title", length = 96)
        public String title;
        @DsField(name = "ftText", length = 512)
        public String ftText;
    }

    @com.q3lives.ds.annotation.DsTable(name = "p35_t4_item")
    public static class ItemT4 extends DsTableAdapter {

        @DsField(name = "sku", length = 64)
        public String sku;
        @DsField(name = "qty", length = 8)
        public Long qty;
        @DsField(name = "mainId", length = 8)
        public Long mainId;
    }

    private static final String[] CORPUS = new String[]{
        "人工智能 大模型 深度学习 神经网络 机器学习 自然语言处理",
        "科技创新 推动 高质量发展 新质生产力 突破 攻关",
        "绿水青山 生态环境保护 可持续发展 低碳经济",
        "人类命运共同体 全球治理 国际合作 共赢",
        "数字经济 实体经济 深度融合 大数据 云计算 边缘计算"
    };

    private static final int N = 8;
    private static final int SHARDS = 1;

    private File tmpRoot;
    private File ftRoot;
    private File serverWorkDir;
    private List<Integer> freePorts;
    private List<Process> serverProcesses;
    private DsFulltextClusterClient ftClient;
    private GenericManager<MainT4> mainGm;
    private GenericManager<ItemT4> itemGm;
    private Map<Class<?>, GenericManager<?>> relatedGms;
    private Map<Long, String> rawDocTexts;
    private List<Long> allMainIds;
    private String oldHome;

    private static int findFreePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d2 = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d2.length * 2);
            for (byte b : d2) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String fingerprint(List<HybridHit<MainT4>> hits) {
        StringBuilder sb = new StringBuilder();
        for (HybridHit<MainT4> h : hits) {
            sb.append(h.docId).append('|');
            sb.append(String.format("%.6f", h.finalHybridScore)).append('|');
            sb.append(String.format("%.6f", h.ftx.core.finalScore)).append('|');
            sb.append(h.ftx.core.shardId).append('|');
            Object f = h.joinFetch.get(ItemT4.class);
            if (f instanceof ManyToOneJoinResult) {
                ManyToOneJoinResult m = (ManyToOneJoinResult) f;
                sb.append(m.matchedOuterIds.size()).append(',');
            }
        }
        return md5(sb.toString());
    }

    @Before
    public void before() throws Exception {
        tmpRoot = tf.newFolder("p35t4-" + UUID.randomUUID());
        DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());

        ftRoot = new File(tmpRoot, "ft_tcp");
        ftRoot.mkdirs();
        serverWorkDir = new File(tmpRoot, "server_root");
        serverWorkDir.mkdirs();
        freePorts = new ArrayList<>();
        serverProcesses = new ArrayList<>();
        String classpath = System.getProperty("java.class.path");
        String javaExe = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";

        Map<Integer, InetSocketAddress> eps = new HashMap<>();
        for (int i = 0; i < SHARDS; i++) {
            File shardRoot = new File(serverWorkDir, "shard_" + i);
            shardRoot.mkdirs();
            ProcessBuilder pb = new ProcessBuilder(
                    javaExe, "-Xmx1024m",
                    "-Djava.io.tmpdir=" + tmpRoot.getAbsolutePath(),
                    "-Dfile.encoding=UTF-8",
                    "-cp", classpath,
                    "javax.net.p2p.server.fulltext.FulltextShardTcpServer",
                    "0",
                    shardRoot.getAbsolutePath(),
                    String.valueOf(SHARDS)
            );
            pb.redirectErrorStream(true);
            pb.directory(serverWorkDir);
            Process p = pb.start();
            serverProcesses.add(p);
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            String line;
            int actualPort = -1;
            while (System.nanoTime() < deadline && (line = br.readLine()) != null) {
                int idx = line.indexOf("FulltextShardTcpServer listen at 127.0.0.1:");
                if (idx >= 0) {
                    String portStr = line.substring(idx + "FulltextShardTcpServer listen at 127.0.0.1:".length()).trim();
                    StringBuilder digits = new StringBuilder();
                    for (char c : portStr.toCharArray()) {
                        if (Character.isDigit(c)) {
                            digits.append(c);
                        }
                    }
                    actualPort = Integer.parseInt(digits.toString());
                    break;
                }
            }
            final int srvIdx = i;
            Thread drain = new Thread(() -> {
                try {
                    char[] buf = new char[512];
                    while (br.read(buf) >= 0) {
                    }
                } catch (Exception ignore) {
                }
            }, "p35-t4-drain-" + srvIdx);
            drain.setDaemon(true);
            drain.start();
            if (actualPort <= 0) {
                try {
                    p.destroyForcibly().waitFor(3, TimeUnit.SECONDS);
                } catch (Exception ig) {
                }
                Assert.fail("Process[" + i + "]未输出 listen at");
            }
            freePorts.add(actualPort);
            eps.put(i, new InetSocketAddress("127.0.0.1", actualPort));
        }

        ftClient = new DsFulltextClusterClient(SHARDS, eps, ftRoot.getAbsolutePath(), "shard");
        mainGm = new GenericManager<>(MainT4.class);
        itemGm = new GenericManager<>(ItemT4.class);
        relatedGms = new HashMap<>();
        relatedGms.put(ItemT4.class, itemGm);

        rawDocTexts = new LinkedHashMap<>();
        allMainIds = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            MainT4 m = new MainT4();
            int seed = i % CORPUS.length;
            String text = CORPUS[seed] + " doc_" + String.format("%02d", i)
                    + " 扩展 关键词 人民 科技 绿色 国际 现代化 民主 共赢 规划 大模型";
            m.title = "M" + i;
            m.ftText = text;
            mainGm.saveOrUpdate(m);
            long mid = m.getId();
            // 给前 N/2 main 挂 item
            if (i < N / 2) {
                ItemT4 it = new ItemT4();
                it.sku = "SKU-" + i;
                it.qty = 10L;
                it.mainId = mid;
                itemGm.saveOrUpdate(it);
            }
            IngestStat st = ftClient.putDocCluster(mid, text);
            Assert.assertNotNull("putDocCluster 非null", st);
            rawDocTexts.put(mid, text);
            allMainIds.add(mid);
        }
        Thread.sleep(800);
    }

    @After
    public void after() {
        try {
            if (ftClient != null) {
                ftClient.close();

            }
        } catch (Exception ignore) {
        }
        for (Process p : serverProcesses) {
            try {
                p.destroyForcibly().waitFor(2, TimeUnit.SECONDS);
            } catch (Exception ignore) {
            }
            try {
                if (p.isAlive()) {
                    p.destroyForcibly();

                }
            } catch (Exception ignore) {
            }
        }
        try {
            DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        } catch (Exception ignore) {
        }
        try {
            DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        } catch (Exception ignore) {
        }
        if (oldHome == null) {
            System.clearProperty("p2p.db.home");
        } else {
            System.setProperty("p2p.db.home", oldHome);
        }
    }

    private HybridJoinClient<MainT4> newHjc() throws Exception {
        DsHybridQueryClient<MainT4> dhqc = DsHybridQueryClient.ofLocal(MainT4.class, mainGm, ftClient);
        return new HybridJoinClient<>(dhqc, relatedGms, mainGm);
    }

    /* -------- TR4.1 跨进程 J=1 N:1 命中稳定 -------- */
    @Test(timeout = 180000)
    public void tr41_crossProcessN1Stable() throws Exception {
        @SuppressWarnings({"unchecked", "rawtypes"})
        QueryWrapper sub = new QueryWrapper();
        sub.ge("qty", 5L);
        QueryWrapper<ItemT4> subTyped = (QueryWrapper) sub;
        JoinSpec<ItemT4> j = new JoinSpec<>(ItemT4.class, "id", "mainId",
                sub,
                JoinMode.MANY_TO_ONE, 256);
        HybridJoinClient<MainT4> hjc = newHjc();
        try {
            List<HybridHit<MainT4>> hits = HybridJoinClientV2Adapter.searchHybridJoinWithManyToOne(
                    hjc, new QueryWrapper<>(), j, "人工智能", rawDocTexts, 10,
                    SearchOptions.defaults(), mainGm, relatedGms);
            Assert.assertTrue("跨进程 N:1 hits≥1, got " + hits.size(), hits.size() >= 1);
            // 验证每个 hit 的 joinFetch 包含 ItemT4
            for (HybridHit<MainT4> h : hits) {
                Assert.assertTrue("joinFetch 必须装 ItemT4",
                        h.joinFetch.containsKey(ItemT4.class));
                Object f = h.joinFetch.get(ItemT4.class);
                Assert.assertTrue("f 应是 ManyToOneJoinResult", f instanceof ManyToOneJoinResult);
                Assert.assertTrue("hit docId ∈ seed main", allMainIds.contains(h.docId));
            }
        } finally {
            hjc.close();
        }
    }

    /* -------- TR4.2 2 次重跑排序指纹稳定 -------- */
    @Test(timeout = 180000)
    public void tr42_repeatFingerprint() throws Exception {
        @SuppressWarnings({"unchecked", "rawtypes"})
        QueryWrapper sub = new QueryWrapper();
        sub.ge("qty", 5L);
        QueryWrapper<ItemT4> subTyped = (QueryWrapper) sub;
        JoinSpec<ItemT4> j = new JoinSpec<>(ItemT4.class, "id", "mainId",
                sub,
                JoinMode.MANY_TO_ONE, 256);
        HybridJoinClient<MainT4> hjc = newHjc();
        try {
            Set<String> fps = new HashSet<>();
            for (int t = 0; t < 2; t++) {
                List<HybridHit<MainT4>> hits = HybridJoinClientV2Adapter.searchHybridJoinWithManyToOne(
                        hjc, new QueryWrapper<>(), j, "人工智能", rawDocTexts, 10,
                        SearchOptions.defaults(), mainGm, relatedGms);
                fps.add(fingerprint(hits));
            }
            Assert.assertEquals("2 次重跑排序指纹一致", 1, fps.size());
        } finally {
            hjc.close();
        }
    }

    /* -------- TR4.3 ManyToOneJoinResult 深 Equals 跨 run 一致 -------- */
    @Test(timeout = 180000)
    public void tr43_manyToOneJoinResultDeepEq() throws Exception {
        @SuppressWarnings({"unchecked", "rawtypes"})
        QueryWrapper sub = new QueryWrapper();
        sub.ge("qty", 5L);
        QueryWrapper<ItemT4> subTyped = (QueryWrapper) sub;
        JoinSpec<ItemT4> j = new JoinSpec<>(ItemT4.class, "id", "mainId",
                sub,
                JoinMode.MANY_TO_ONE, 256);
        HybridJoinClient<MainT4> hjc = newHjc();
        try {
            List<HybridHit<MainT4>> hits1 = HybridJoinClientV2Adapter.searchHybridJoinWithManyToOne(
                    hjc, new QueryWrapper<>(), j, "人工智能", rawDocTexts, 10,
                    SearchOptions.defaults(), mainGm, relatedGms);
            List<HybridHit<MainT4>> hits2 = HybridJoinClientV2Adapter.searchHybridJoinWithManyToOne(
                    hjc, new QueryWrapper<>(), j, "人工智能", rawDocTexts, 10,
                    SearchOptions.defaults(), mainGm, relatedGms);
            Assert.assertEquals("hits 数一致", hits1.size(), hits2.size());
            for (int i = 0; i < hits1.size(); i++) {
                HybridHit<MainT4> h1 = hits1.get(i);
                HybridHit<MainT4> h2 = hits2.get(i);
                Assert.assertEquals("docId 一致", h1.docId, h2.docId);
                ManyToOneJoinResult f1 = (ManyToOneJoinResult) h1.joinFetch.get(ItemT4.class);
                ManyToOneJoinResult f2 = (ManyToOneJoinResult) h2.joinFetch.get(ItemT4.class);
                Assert.assertNotNull(f1);
                Assert.assertNotNull(f2);
                Assert.assertEquals("byOuterId 大小一致", f1.byOuterId.size(), f2.byOuterId.size());
                Assert.assertEquals("matchedOuterIds 一致", f1.matchedOuterIds, f2.matchedOuterIds);
                // 跨 run 同 outerId 的 List<R> 大小一致
                for (Object oOid : f1.matchedOuterIds) {
                    Long oid = (Long) oOid;
                    java.util.List<?> l1 = (java.util.List<?>) f1.byOuterId.get(oid);
                    java.util.List<?> l2 = (java.util.List<?>) f2.byOuterId.get(oid);
                    Assert.assertNotNull("跨 run 都有 oid=" + oid, l2);
                    Assert.assertEquals("oid=" + oid + " 跨 run List 大小一致", l1.size(), l2.size());
                }
            }
        } finally {
            hjc.close();
        }
    }
}
