package ds;

import static org.junit.Assert.*;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.q3lives.ds.collections.DsHashMap;
import ds.DsDatabaseLocalMvccTest.MvccUser;
import com.q3lives.ds.enums.DataType;
import com.q3lives.ds.registry.DsRegistry;
import com.q3lives.ds.registry.RegistryEntry;
import com.q3lives.ds.registry.RegistryWalker;
import org.junit.Test;

public class DsRegistry_P52_LegacyMigrationTest {

    private static final long ROOT_DIR_NODE_ID = 1L;
    private static final int TYPE_REGISTRY_DIR_META = 205;
    private static final int POWER_NODE_BLOCK_DIR = 7;
    private static final int TYPE_REGISTRY_KEY = 200;

    private static long encodeMT(int type, int power, long idx) {
        long mtype = type & 0xFFFFL;
        long mpow = power & 0xFFL;
        long midx = idx & 0xFFFFFFFFFFL;
        return (mtype << 48) | (mpow << 40) | midx;
    }

    private static long decodeIndexId(long typedId) {
        return typedId & 0xFFFFFFFFFFL;
    }

    private static long rootDirTypedId() {
        return encodeMT(TYPE_REGISTRY_DIR_META, POWER_NODE_BLOCK_DIR, 0L);
    }

    private static long makeDirTypedId(long keyId) {
        long idx = decodeIndexId(keyId);
        return encodeMT(TYPE_REGISTRY_DIR_META, POWER_NODE_BLOCK_DIR, idx + 1L);
    }

    @Test
    public void testEmptyDbMigration() throws Exception {
        File tmp = Files.createTempDirectory("dsregp52-empty-").toFile();
        tmp.deleteOnExit();
        try (DsRegistry r = new DsRegistry(new File(tmp, "db").getAbsolutePath())) {
            int n = r.migrateLegacyIds();
            assertEquals(0, n);
            int n2 = r.migrateLegacyIds();
            assertEquals(0, n2);
            assertTrue(r.exists("/"));
        }
    }

    @Test
    public void testFullP4FormatIdempotent() throws Exception {
        File tmp = Files.createTempDirectory("dsregp52-full-").toFile();
        tmp.deleteOnExit();
        try (DsRegistry r = new DsRegistry(new File(tmp, "db").getAbsolutePath())) {
            r.mkdirs("/a/b/c");
            r.putLong("/a/v", DataType.REG_DWORD, 9L);
            int n0 = r.migrateLegacyIds();
            assertEquals(0, n0);
            List<String> before = r.listKeys("/", Integer.MAX_VALUE);
            Set<String> beforeSet = new HashSet<>(before);
            int n1 = r.migrateLegacyIds();
            assertEquals(0, n1);
            List<String> after = r.listKeys("/", Integer.MAX_VALUE);
            assertEquals(beforeSet, new HashSet<>(after));
        }
    }

    @Test
    public void testHybridLegacy() throws Exception {
        File tmp = Files.createTempDirectory("dsregp52-hybrid-").toFile();
        tmp.deleteOnExit();
        try (DsRegistry r = new DsRegistry(new File(tmp, "db").getAbsolutePath())) {
            r.mkdirs("/a/b");
            r.putLong("/a/b/v", DataType.REG_DWORD, 37L);
            Long kA = r.keyStrings().getIndexId("/a", false);
            Long kAB = r.keyStrings().getIndexId("/a/b", false);
            assertNotNull(kA);
            assertNotNull(kAB);
            Field keyIdToDirIdF = DsRegistry.class.getDeclaredField("keyIdToDirId");
            Field dirIdToRingIdF = DsRegistry.class.getDeclaredField("dirIdToRingId");
            keyIdToDirIdF.setAccessible(true);
            dirIdToRingIdF.setAccessible(true);
            DsHashMap keyIdToDirId = (DsHashMap) keyIdToDirIdF.get(r);
            DsHashMap dirIdToRingId = (DsHashMap) dirIdToRingIdF.get(r);
            Long ringAB = dirIdToRingId.get(makeDirTypedId(kAB));
            assertNotNull(ringAB);
            long fakeDirNodeAB = 9001L;
            keyIdToDirId.put(kAB.longValue(), fakeDirNodeAB);
            dirIdToRingId.put(fakeDirNodeAB, ringAB.longValue());
            Long rootRingOld = dirIdToRingId.get(rootDirTypedId());
            assertNotNull(rootRingOld);
            dirIdToRingId.put(ROOT_DIR_NODE_ID, rootRingOld.longValue());
            int migrated = r.migrateLegacyIds();
            assertEquals(2, migrated);
            assertNull(dirIdToRingId.get(ROOT_DIR_NODE_ID));
            assertNull(keyIdToDirId.get(kAB.longValue()));
            assertNotNull(dirIdToRingId.get(makeDirTypedId(kAB)));
            assertNotNull(dirIdToRingId.get(rootDirTypedId()));
            RegistryEntry e = r.get("/a/b/v");
            assertNotNull(e);
            assertEquals(37L, e.asLong());
            int again = r.migrateLegacyIds();
            assertEquals(0, again);
        }
    }

    @Test
    public void testMigrationKeepsP21_ListKeysEqualsWalk() throws Exception {
        File tmp = Files.createTempDirectory("dsregp52-p21-").toFile();
        tmp.deleteOnExit();
        try (DsRegistry r = new DsRegistry(new File(tmp, "db").getAbsolutePath())) {
            for (int i = 0; i < 5; i++) {
                r.mkdirs("/n" + i);
                for (int j = 0; j < 3; j++) {
                    r.putLong("/n" + i + "/item-" + j, DataType.REG_DWORD, (long) (i * 100 + j));
                }
            }
            Field keyIdToDirIdF = DsRegistry.class.getDeclaredField("keyIdToDirId");
            Field dirIdToRingIdF = DsRegistry.class.getDeclaredField("dirIdToRingId");
            keyIdToDirIdF.setAccessible(true);
            dirIdToRingIdF.setAccessible(true);
            DsHashMap keyIdToDirId = (DsHashMap) keyIdToDirIdF.get(r);
            DsHashMap dirIdToRingId = (DsHashMap) dirIdToRingIdF.get(r);
            for (int i = 0; i < 5; i++) {
                Long k = r.keyStrings().getIndexId("/n" + i, false);
                Long ring = dirIdToRingId.get(makeDirTypedId(k));
                assertNotNull(ring);
                long fake = 10_000L + i;
                keyIdToDirId.put(k.longValue(), fake);
                dirIdToRingId.put(fake, ring.longValue());
            }
            int migrated = r.migrateLegacyIds();
            assertEquals(5, migrated);
            Set<String> walkSet = new HashSet<>();
            RegistryWalker.RegistryEntryVisitor collect = (en, dep) -> { walkSet.add(en.key()); return true; };
            r.walk(collect, Integer.MAX_VALUE, null);
            Set<String> listSet = new HashSet<>(r.listKeys("/", Integer.MAX_VALUE));
            assertEquals(walkSet, listSet);
        }
    }

    @Test
    public void testMigrationKeepsP33_OrmBridgeBidirectional() throws Exception {
        File tmp = Files.createTempDirectory("dsregp52-p33-").toFile();
        tmp.deleteOnExit();
        Class<?> mvccUserK = Class.forName("ds.DsDatabaseLocalMvccTest$MvccUser");
        try (DsRegistry r = new DsRegistry(new File(tmp, "db").getAbsolutePath())) {
            Class<?> bridgeK = Class.forName("com.q3lives.ds.registry.DsRegistryOrmBridge");
            Object bridge = bridgeK.getDeclaredConstructor(DsRegistry.class).newInstance(r);
            java.lang.reflect.Method regEntity = bridgeK.getMethod("registerEntity", Class.class, String.class);
            regEntity.invoke(bridge, mvccUserK, "/db");
            Field keyIdToDirIdF = DsRegistry.class.getDeclaredField("keyIdToDirId");
            Field dirIdToRingIdF = DsRegistry.class.getDeclaredField("dirIdToRingId");
            keyIdToDirIdF.setAccessible(true);
            dirIdToRingIdF.setAccessible(true);
            DsHashMap keyIdToDirId = (DsHashMap) keyIdToDirIdF.get(r);
            DsHashMap dirIdToRingId = (DsHashMap) dirIdToRingIdF.get(r);
            int injected = 0;
            for (String dirKey : new String[]{"/db",
                    "/db/" + mvccUserK.getName().replace('.', '/')}) {
                Long k = r.keyStrings().getIndexId(dirKey, false);
                if (k == null) continue;
                Long ring = dirIdToRingId.get(makeDirTypedId(k));
                if (ring == null) continue;
                long fake = 100_000L + injected;
                keyIdToDirId.put(k.longValue(), fake);
                dirIdToRingId.put(fake, ring.longValue());
                injected++;
            }
            assertTrue(injected > 0);
            int migrated = r.migrateLegacyIds();
            assertEquals(injected, migrated);
            java.lang.reflect.Method listM = bridgeK.getMethod("listEntitySchemas", String.class);
            Object list = listM.invoke(bridge, "/db");
            assertNotNull(list);
            java.lang.reflect.Method sizeM = list.getClass().getMethod("size");
            int sz = (int) sizeM.invoke(list);
            assertTrue(sz >= 1);
            java.lang.reflect.Method loadM = bridgeK.getMethod("loadEntitySchemasFromRegistry", String.class);
            Object loaded = loadM.invoke(bridge, "/db");
            assertNotNull(loaded);
            int sz2 = (int) sizeM.invoke(loaded);
            assertTrue(sz2 >= 1);
        }
    }
}
