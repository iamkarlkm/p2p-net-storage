package ds;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import com.q3lives.ds.cluster.DsIdLayout;
import com.q3lives.ds.enums.DataType;
import com.q3lives.ds.registry.DsRegistry;
import com.q3lives.ds.registry.RegistryEntry;
import com.q3lives.ds.registry.RegistryWalker;

public class DsRegistry_P7_A1_DistributedNodeIdTest {

    private static File tmpDir(String prefix) throws IOException {
        File f = Files.createTempDirectory("dsregp7a1-" + prefix + "-").toFile();
        f.deleteOnExit();
        return f;
    }

    private static void putN(DsRegistry r, String baseDir, int n, int start) throws IOException {
        for (int i = 0; i < n; i++) {
            int idx = start + i;
            String key = baseDir + "/item-" + idx;
            r.putString(key, "value-" + idx, DataType.REG_SZ);
        }
    }

    @Test
    void test_A1_T1_listChildrenGlobalIds() throws Exception {
        File tmp = tmpDir("t1");
        try (DsRegistry r = new DsRegistry(new File(tmp, "r").getAbsolutePath(), 123, 42)) {
            r.mkdirs("/a/b");
            for (int i = 0; i < 8; i++) {
                r.putString("/a/b/k" + i, "v" + i, DataType.REG_SZ);
            }
            Map<String, Long> globals = r.listChildrenGlobalIds("/a/b");
            assertEquals(8, globals.size(), "listChildrenGlobalIds size");
            for (Map.Entry<String, Long> e : globals.entrySet()) {
                long g = e.getValue();
                assertNotEquals(0L, g, "key " + e.getKey() + " globalNodeId != 0");
                assertEquals(123, DsIdLayout.serverId(g));
                assertEquals(42, DsIdLayout.partitionId(g));
                assertTrue(DsIdLayout.localAddress(g) > 0);
            }
        }
    }

    @Test
    void test_A1_T2_listKeys_withGlobals_100entries() throws Exception {
        File tmp = tmpDir("t2");
        try (DsRegistry r = new DsRegistry(new File(tmp, "r").getAbsolutePath(), 7, 3)) {
            r.mkdirs("/n");
            putN(r, "/n", 100, 0);
            Map<String, Long> km = r.listKeys("/n", 1, true);
            // listKeys(String,int) legacy semantics includes base dir "/n" itself when maxDepth>=0,
            // so we filter base dir and assert 100 children plus 1 base total expected.
            assertTrue(km.size() >= 100, "listKeys with maxDepth=1 includes base dir");
            Set<Long> seen = new HashSet<>();
            int childCount = 0;
            for (Map.Entry<String, Long> e : km.entrySet()) {
                String k = e.getKey();
                if ("/n".equals(k)) continue;
                long g = e.getValue();
                assertTrue(g != 0L, "globalNodeId != 0 for " + k);
                assertEquals(7, DsIdLayout.serverId(g));
                assertEquals(3, DsIdLayout.partitionId(g));
                RegistryEntry entry = r.get(k);
                assertTrue(entry.isPresent());
                long expectedG = r.toGlobalNodeId(entry.nodeId());
                assertEquals(expectedG, g);
                seen.add(g);
                childCount++;
            }
            assertEquals(100, childCount, "exactly 100 leaf children");
            assertEquals(100, seen.size(), "all 100 children globalNodeId unique");
        }
    }

    @Test
    void test_A1_T3_findByPrefixKeys_withGlobals() throws Exception {
        File tmp = tmpDir("t3");
        try (DsRegistry r = new DsRegistry(new File(tmp, "r").getAbsolutePath(), 100, 7)) {
            for (int i = 0; i < 30; i++) {
                r.putString("/pf/a/x" + i, "v" + i, DataType.REG_SZ);
            }
            Map<String, Long> result = r.findByPrefixKeys("/pf/a", 2, 10, null, true);
            assertEquals(10, result.size(), "limit=10");
            for (Map.Entry<String, Long> e : result.entrySet()) {
                long g = e.getValue();
                assertNotEquals(0L, g);
                assertEquals(100, DsIdLayout.serverId(g));
                assertEquals(7, DsIdLayout.partitionId(g));
                RegistryEntry entry = r.get(e.getKey());
                assertTrue(entry.isPresent());
                assertEquals(r.toGlobalNodeId(entry.nodeId()), g);
            }
        }
    }

    @Test
    void test_A1_T4_walk_withGlobals_50entries() throws Exception {
        File tmp = tmpDir("t4");
        try (DsRegistry r = new DsRegistry(new File(tmp, "r").getAbsolutePath(), 42, 11)) {
            r.mkdirs("/d1");
            putN(r, "/d1", 25, 0);
            r.mkdirs("/d2");
            putN(r, "/d2", 25, 0);
            List<RegistryEntry> entries = new ArrayList<>();
            r.walk(new RegistryWalker.RegistryEntryVisitor() {
                @Override
                public boolean onEntry(RegistryEntry entry, int depth) {
                    entries.add(entry);
                    return true;
                }
            }, 2, null, true);
            int counted = 0;
            for (RegistryEntry e : entries) {
                if (e.key().startsWith("/d1/item-") || e.key().startsWith("/d2/item-")) {
                    counted++;
                    assertNotEquals(0L, e.globalNodeId(), "leaf has globalNodeId set, key=" + e.key());
                    assertEquals(42, DsIdLayout.serverId(e.globalNodeId()));
                    assertEquals(11, DsIdLayout.partitionId(e.globalNodeId()));
                    assertEquals(r.toGlobalNodeId(e.nodeId()), e.globalNodeId());
                }
            }
            assertEquals(50, counted, "walked 50 leaves");
        }
    }

    @Test
    void test_A1_T5_crossInstance_toGlobal_fromGlobal_roundtrip() throws Exception {
        int sid = 888;
        int pid = 123;
        File rootDir1 = new File(tmpDir("t5a"), "r");
        File rootDir2 = new File(tmpDir("t5b"), "r");
        try (DsRegistry r1 = new DsRegistry(rootDir1.getAbsolutePath(), sid, pid);
             DsRegistry r2 = new DsRegistry(rootDir2.getAbsolutePath(), sid, pid)) {
            for (int i = 0; i < 5; i++) {
                r1.putString("/r1k" + i, "r1v" + i, DataType.REG_SZ);
                r2.putString("/r2k" + i, "r2v" + i, DataType.REG_SZ);
            }
            // Pick 2 entries from each instance
            RegistryEntry e1_0 = r1.get("/r1k0");
            RegistryEntry e2_3 = r2.get("/r2k3");
            RegistryEntry e1_4 = r1.get("/r1k4");
            RegistryEntry e2_0 = r2.get("/r2k0");
            long g1_0 = r1.toGlobalNodeId(e1_0.nodeId());
            long g2_3 = r2.toGlobalNodeId(e2_3.nodeId());
            long g1_4 = r1.toGlobalNodeId(e1_4.nodeId());
            long g2_0 = r2.toGlobalNodeId(e2_0.nodeId());
            // Reverse fromGlobal on r2 (same sid/pid as r1 per spec round-trip)
            assertEquals(e1_0.nodeId(), r2.fromGlobalNodeId(g1_0, false));
            assertEquals(e2_3.nodeId(), r1.fromGlobalNodeId(g2_3, false));
            assertEquals(e1_4.nodeId(), r2.fromGlobalNodeId(g1_4, false));
            assertEquals(e2_0.nodeId(), r1.fromGlobalNodeId(g2_0, false));
        }
    }

    @Test
    void test_A1_T6_backwardCompat_listKeys_findByPrefix_listChildren_identicalToBaseline() throws Exception {
        File tmp = tmpDir("t6");
        try (DsRegistry r = new DsRegistry(new File(tmp, "r").getAbsolutePath(), 0, 0)) {
            // Use classic single-param constructor semantics.
            for (int i = 0; i < 20; i++) {
                r.putString("/basex/a" + i, "v" + i, DataType.REG_SZ);
            }
            r.mkdirs("/basex/sub1");
            for (int i = 0; i < 5; i++) {
                r.putString("/basex/sub1/b" + i, "sv" + i, DataType.REG_SZ);
            }
            // listKeys(String,int) legacy signatures.
            List<String> keys1 = r.listKeys("/basex", 2);
            Map<String, Long> kmap = r.listKeys("/basex", 2, false);
            assertEquals(new HashSet<>(keys1), kmap.keySet(), "legacy listKeys equals keySet of withGlobals=false");
            // findByPrefix(String,4) legacy → withGlobals=false
            List<RegistryEntry> listOld = r.findByPrefix("/basex", 2, 100, null);
            List<RegistryEntry> listNew = r.findByPrefix("/basex", 2, 100, null, false);
            assertEquals(listOld.size(), listNew.size(), "findByPrefix size matches");
            Set<String> oldKeys = new HashSet<>();
            for (RegistryEntry e : listOld) oldKeys.add(e.key());
            Set<String> newKeys = new HashSet<>();
            for (RegistryEntry e : listNew) newKeys.add(e.key());
            assertEquals(oldKeys, newKeys, "findByPrefix keySet matches");
            // findByPrefixKeys legacy (5 args Predicate 版返回 List<String>)
            List<String> pkOld = r.findByPrefixKeys("/basex", 2, 100, null);
            Map<String, Long> pkMap = r.findByPrefixKeys("/basex", 2, 100, null, false);
            assertEquals(new HashSet<>(pkOld), pkMap.keySet(), "findByPrefixKeys keySet matches");
            // listChildren(String) legacy count
            List<String> sub1Children = r.listChildren("/basex/sub1");
            Map<String, Long> sub1Global = r.listChildrenGlobalIds("/basex/sub1");
            assertEquals(new HashSet<>(sub1Children), sub1Global.keySet(), "listChildren vs listChildrenGlobalIds keys match");
        }
    }
}
