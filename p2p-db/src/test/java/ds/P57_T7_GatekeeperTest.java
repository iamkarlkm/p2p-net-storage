package ds;

import com.q3lives.ds.registry.meta.MetaMode;
import com.q3lives.ds.registry.meta.raft.RaftServer;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P57 T7：Raft-3Node Gatekeeper A6+B5+C5+D5+E4=25（R12 / NFR-C3 / NFR-M3）。
 */
public class P57_T7_GatekeeperTest {

    private static final String REG_META_REL
            = "src/main/java/com/q3lives/ds/registry/meta/RegistryMetaPaths.java";
    private static final String MAIN_JAVA_REL = "src/main/java";
    private static final String RAFT_PKG_REL = "src/main/java/com/q3lives/ds/registry/meta/raft";

    private int asserts = 0;
    private final List<RaftServer> created = new ArrayList<>();

    @After
    public void cleanup() {
        for (RaftServer s : created) {
            try {
                s.stop();
            } catch (Exception ignored) {
            }
        }
        created.clear();
        RaftServer.PEERS.clear();
    }

    private void check(boolean cond, String label) {
        asserts++;
        assertTrue("[" + asserts + "] " + label, cond);
    }

    private RaftServer track(RaftServer s) {
        if (s != null) {
            created.add(s);
        }
        return s;
    }

    private static Map<String, String> props(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static long[] parseMembers(Map<String, String> props) throws Exception {
        Method m = RaftServer.class.getDeclaredMethod("parseMembers", Map.class);
        m.setAccessible(true);
        return (long[]) m.invoke(null, props);
    }

    private static long parseLocalPeer(Map<String, String> props, long def) throws Exception {
        Method m = RaftServer.class.getDeclaredMethod("parseLocalPeer", Map.class, long.class);
        m.setAccessible(true);
        return ((Number) m.invoke(null, props, def)).longValue();
    }

    private static int quorum(int n) throws Exception {
        Method m = RaftServer.class.getDeclaredMethod("quorumOf", int.class);
        m.setAccessible(true);
        return ((Number) m.invoke(null, n)).intValue();
    }

    private static Path resolve(String rel) {
        Path[] cands = {
            Paths.get(rel),
            Paths.get(System.getProperty("user.dir"), rel),
            Paths.get(System.getProperty("user.dir"), "p2p-db", rel)
        };
        for (Path p : cands) {
            if (Files.exists(p)) {
                return p;
            }
        }
        throw new IllegalStateException("path not found: " + rel + " from " + System.getProperty("user.dir"));
    }

    @Test(timeout = 30000)
    public void t01_25_assertions() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "p57-t7-gk");
        root.mkdirs();

        // ---------- A 防线（6 roster） ----------
        long[] a1 = parseMembers(props("p2p.orm.raft.members", "1,2,3"));
        check(a1.length == 3 && a1[0] == 1L && a1[1] == 2L && a1[2] == 3L,
                "A1 parseMembers(\"1,2,3\") 长度==3");

        long[] a2 = parseMembers(null);
        check(a2.length == 1 && a2[0] == 2L, "A2 parseMembers(缺省) → {2}");

        long a3 = parseLocalPeer(null, 1L);
        check(a3 == 1L, "A3 parseLocalPeer(null,1L)==1");

        long[] a4 = parseMembers(props("p2p.orm.raft.members", "0x2"));
        check(a4.length == 1 && a4[0] == 2L, "A4 parseMembers(\"0x2\") → {2}（十六进制 token）");

        RaftServer a5 = track(RaftServer.create(root, MetaMode.REGISTRY,
                props("p2p.orm.raft.members", "1,2,3", "p2p.orm.raft.local", "2")));
        check(a5 != null && a5.peerState(2L) == null && a5.peerState(1L) != null && a5.peerState(3L) != null,
                "A5 3 节点 local=2 → peerState(2)==null（自过滤）");

        RaftServer.class.getMethod("create", File.class, MetaMode.class, Map.class);
        RaftServer a6 = track(RaftServer.create(root, MetaMode.REGISTRY,
                props("p2p.orm.raft.members", "1,2,3", "p2p.orm.raft.local", "3")));
        check(a6 != null && a6.getLocalPeerId() == 3L, "A6 3 参 create 存在且 local 取自 props.local");

        // ---------- B 防线（5 quorum） ----------
        check(quorum(1) == 1, "B1 quorumOf(1)==1");
        check(quorum(2) == 2, "B2 quorumOf(2)==2");
        check(quorum(3) == 2, "B3 quorumOf(3)==2");
        check(quorum(4) == 3, "B4 quorumOf(4)==3");
        check(quorum(5) == 3, "B5 quorumOf(5)==3");

        // ---------- C 防线（5 容错边界） ----------
        check(2 >= quorum(3), "C1 3 节点 1 宕机 → 2>=2 满足");
        check(!(1 >= quorum(3)), "C2 3 节点 2 宕机 → 1<2 不满足");
        check(2 >= quorum(3), "C3 2 节点在线 → 2>=2 满足");
        check(quorum(4) == 3 && 3 >= quorum(4), "C4 4 节点 quorum==3（1 宕机 3>=3）");
        check(quorum(5) == 3 && 3 >= quorum(5), "C5 5 节点 quorum==3（2 宕机 3>=3）");

        // ---------- D 防线（5 零外泄 + 签名） ----------
        Path mainJava = resolve(MAIN_JAVA_REL);
        List<Path> javaFiles = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(mainJava)) {
            walk.filter(x -> x.toString().endsWith(".java")).forEach(javaFiles::add);
        }
        int hits = 0;
        String hitFile = null;
        for (Path p : javaFiles) {
            int fileHits = 0;
            for (String ln : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                if (ln.contains("\"/orm/raft\"")) {
                    fileHits++;
                }
            }
            if (fileHits > 0) {
                hits += fileHits;
                hitFile = p.getFileName().toString();
            }
        }
        check(hits == 1 && "RegistryMetaPaths.java".equals(hitFile),
                "D1 字面量 \"/orm/raft\" 全 main 仅 RegistryMetaPaths.java 常量声明 1 行（命中=" + hits + " file=" + hitFile + "）");

        check(RaftServer.class.getMethod("create", File.class, MetaMode.class) != null,
                "D2 create(File,MetaMode) 存在");
        check(RaftServer.class.getMethod("create", File.class, MetaMode.class, Map.class, long.class, long[].class) != null,
                "D3 create(File,MetaMode,Map,long,long[]) 存在");
        check(RaftServer.class.getMethod("create", File.class, MetaMode.class, Map.class) != null,
                "D4 create(File,MetaMode,Map) 重载存在");

        int topLevel = 0;
        try (Stream<Path> list = Files.list(resolve(RAFT_PKG_REL))) {
            topLevel = (int) list.filter(x -> x.toString().endsWith(".java")).count();
        }
        check(topLevel == 3, "D5 com.q3lives.ds.registry.meta.raft 顶级类数 == 3（实际=" + topLevel + "）");

        // ---------- E 防线（4 向后兼容） ----------
        RaftServer e1 = track(RaftServer.create(root, MetaMode.REGISTRY));
        check(e1 != null && e1.getLocalPeerId() == 1L && e1.peerState(2L) != null && e1.peerState(1L) == null,
                "E1 create(root,REGISTRY) → local=1 peers={2}");
        RaftServer e2 = track(RaftServer.create(root, MetaMode.REGISTRY, null));
        check(e2 != null && e2.getLocalPeerId() == 1L && e2.peerState(2L) != null && e2.peerState(1L) == null,
                "E2 create(root,REGISTRY,null) → local=1 peers={2}");
        RaftServer e3 = track(RaftServer.create(root, MetaMode.REGISTRY, new HashMap<>()));
        check(e3 != null && e3.getLocalPeerId() == 1L && e3.peerState(2L) != null && e3.peerState(1L) == null,
                "E3 create(root,REGISTRY,{}) → local=1 peers={2}");
        RaftServer e4 = RaftServer.create(root, MetaMode.REGISTRY, props("p2p.orm.raft.mode", "off"));
        check(e4 == null, "E4 props{mode=off} → null");

        assertNotNull("D1 RegistryMetaPaths.java 常量真源存在", resolve(REG_META_REL));
        assertEquals("A6+B5+C5+D5+E4=25/25 OK", 25, asserts);
        System.out.println("A6+B5+C5+D5+E4=" + asserts + "/25 OK");
    }
}
