package ds;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import com.q3lives.ds.enums.DataType;
import com.q3lives.ds.registry.DsRegistry;
import com.q3lives.ds.registry.RegistryEntry;
import com.q3lives.ds.registry.RegistryWalker;

public class DsRegistry_P21_PrefixFindTest {

    @Test
    public void testFindByPrefix_LimitAndMaxDepth() throws Exception {
        File tmp = Files.createTempDirectory("dsregp21-").toFile();
        tmp.deleteOnExit();
        try (DsRegistry r = new DsRegistry(new File(tmp, "r").getAbsolutePath())) {
            for (int l1 = 0; l1 < 5; l1++) {
                for (int l2 = 0; l2 < 5; l2++) {
                    for (int l3 = 0; l3 < 5; l3++) {
                        String k = "/a" + l1 + "/b" + l2 + "/c" + l3;
                        r.putLong(k, DataType.REG_DWORD, (long) (l1 * 100 + l2 * 10 + l3));
                    }
                }
            }
            List<RegistryEntry> list = r.findByPrefix("/a0", 2, 100, null);
            assertTrue(list.size() <= 100, "limit=100 实际:" + list.size());
            for (RegistryEntry e : list) {
                assertTrue(e.key().startsWith("/a0"), "前缀不符:" + e.key());
                int depth = DsRegistry.parseKey(e.key()).size();
                assertTrue(depth <= 1 + 2, "maxDepth=2 超过: depth=" + depth + " key=" + e.key());
            }
        }
    }

    @Test
    public void testFindByPrefix_PathFilterWorks() throws Exception {
        File tmp = Files.createTempDirectory("dsregp21-").toFile();
        tmp.deleteOnExit();
        try (DsRegistry r = new DsRegistry(new File(tmp, "r2").getAbsolutePath())) {
            for (int i = 0; i < 10; i++) {
                r.putString("/x/a" + i, "aa-" + i, DataType.REG_SZ);
                r.putString("/x/b" + i, "bb-" + i, DataType.REG_SZ);
            }
            Predicate<String> onlyX = p -> p.contains("/a") || p.equals("/x");
            List<RegistryEntry> list = r.findByPrefix("/x", 1, 1000, onlyX);
            for (RegistryEntry e : list) {
                if ("/x".equals(e.key())) continue;
                assertTrue(e.key().contains("/a"), "pathFilter 未生效，key=" + e.key());
            }
        }
    }

    @Test
    public void testListKeys_EqualsToWalkSet() throws Exception {
        File tmp = Files.createTempDirectory("dsregp21-").toFile();
        tmp.deleteOnExit();
        Path dir = new File(tmp, "r3").toPath().toAbsolutePath();
        try (DsRegistry r = new DsRegistry(dir.toString())) {
            for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
                r.putLong("/t/" + i + "/" + j, DataType.REG_DWORD, (long) (i * 10 + j));
            }
        }
        try (DsRegistry r = new DsRegistry(dir.toString())) {
            List<String> keys = r.listKeys("/t", 10);
            final Set<String> walked = new HashSet<>();
            RegistryWalker.RegistryEntryVisitor v = (entry, depth) -> {
                if (entry.key().startsWith("/t")) walked.add(entry.key());
                return true;
            };
            r.walk(v, 10, null);
            assertEquals(walked, new HashSet<>(keys), "listKeys 集合与 walk 集合不一致");
        }
    }

    @Test
    public void testListChildrenEntries_DepthCut() throws Exception {
        File tmp = Files.createTempDirectory("dsregp21-").toFile();
        tmp.deleteOnExit();
        try (DsRegistry r = new DsRegistry(new File(tmp, "r4").getAbsolutePath())) {
            r.putLong("/lvl1/lvl2/lvl3/lvl4/x", DataType.REG_DWORD, 999L);
            List<RegistryEntry> max2 = r.findByPrefix("/lvl1", 2, 1000, null);
            for (RegistryEntry e : max2) {
                int d = DsRegistry.parseKey(e.key()).size() - DsRegistry.parseKey("/lvl1").size();
                assertTrue(d <= 2, "maxDepth=2, 实际 d=" + d + " key=" + e.key());
            }
            List<RegistryEntry> all = r.findByPrefix("/lvl1", 4, 1000, null);
            boolean foundLvl4x = false;
            for (RegistryEntry e : all) if ("/lvl1/lvl2/lvl3/lvl4/x".equals(e.key())) foundLvl4x = true;
            assertTrue(foundLvl4x, "depth4 应该找到 /lvl1/lvl2/lvl3/lvl4/x");
        }
    }
}
