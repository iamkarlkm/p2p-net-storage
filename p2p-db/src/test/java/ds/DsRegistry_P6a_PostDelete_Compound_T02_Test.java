package ds;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;
import org.junit.Test;

import com.q3lives.ds.enums.DataType;
import com.q3lives.ds.registry.DsRegistry;
import com.q3lives.ds.registry.RegistryEntry;

public class DsRegistry_P6a_PostDelete_Compound_T02_Test {

    @Test
    public void testCompound10Layers50ItemsThenMigrateRemoveGet() throws Exception {
        File tmp = Files.createTempDirectory("dsreg-p6a-t02-").toFile();
        tmp.deleteOnExit();
        File root = new File(tmp, "registry");
        root.mkdirs();
        try (DsRegistry r = new DsRegistry(root.getAbsolutePath())) {
            StringBuilder sb = new StringBuilder(64);
            sb.append('/');
            List<String> allKeys = new ArrayList<>(50);
            List<Long> allValues = new ArrayList<>(50);
            for (int lvl = 0; lvl < 10; lvl++) {
                if (sb.length() > 1) sb.append('/');
                sb.append("d").append(lvl);
                r.mkdirs(sb.toString());
                for (int k = 0; k < 5; k++) {
                    String key = sb + "/item-" + k;
                    long v = lvl * 13L + k * 31L + 1L;
                    r.putLong(key, DataType.REG_DWORD, v);
                    allKeys.add(key);
                    allValues.add(v);
                }
            }
            assertEquals(50, allKeys.size());

            int m1 = r.migrateLegacyIds();
            int m2 = r.migrateLegacyIds();
            assertEquals("First migrate after fresh typed write: expected 0", 0, m1);
            assertEquals("Second migrate idempotent: expected 0", 0, m2);

            List<String> sortedKeys = new ArrayList<>(allKeys);
            Collections.sort(sortedKeys);
            for (int i = 0; i < 25; i++) {
                String key = sortedKeys.get(i);
                boolean removed = r.remove(key, false);
                assertTrue("remove[" + i + "] " + key + " failed", removed);
            }

            for (int i = 25; i < sortedKeys.size(); i++) {
                String key = sortedKeys.get(i);
                long expected = allValues.get(allKeys.indexOf(key));
                RegistryEntry e = r.get(key);
                assertTrue("get(" + key + ").isPresent() false", e.isPresent());
                assertEquals("get(" + key + ") value mismatch", expected, e.inlineValueRaw().orElse(0L));
            }

            String deepDir = "/d0/d1/d2/d3";
            List<String> ch = r.listChildren(deepDir);
            assertNotNull(ch);
            assertTrue("listChildren d0/d1/d2/d3 size<4 actual=" + ch.size(), ch.size() >= 4);

            List<String> prefixed = r.listKeys("/", Integer.MAX_VALUE);
            Collections.sort(prefixed);
            assertEquals("listKeys size mismatch", 36, prefixed.size());
            long d0d1Items = 0;
            for (String k : prefixed) if (k.startsWith("/d0/d1/item-")) d0d1Items++;
            assertEquals("listKeys / subpath d0/d1 item count mismatch", 5L, d0d1Items);

            for (int i = 25; i < sortedKeys.size(); i++) {
                String k = sortedKeys.get(i);
                RegistryEntry e = r.get(k);
                assertTrue("post-remove SORTED[" + i + "] " + k + " present", e.isPresent());
                long expected = allValues.get(allKeys.indexOf(k));
                assertEquals("post-remove get(" + k + ") value mismatch", expected, e.inlineValueRaw().orElse(0L));
            }

            long mkdirsId = r.mkdirs("/d0/d1/d2");
            assertTrue("mkdirs already existed dir should return id>0", mkdirsId > 0L);
            assertTrue("mkdirs result is dir typedId (type=205): got=" + (mkdirsId >>> 48), (int)((mkdirsId>>>48)&0xFFFFL) == 205);
        }
    }
}
