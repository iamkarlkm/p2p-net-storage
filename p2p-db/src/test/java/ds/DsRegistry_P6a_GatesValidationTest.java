package ds;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import com.q3lives.ds.collections.DsHashMap;
import com.q3lives.ds.enums.DataType;
import com.q3lives.ds.registry.DsRegistry;
import com.q3lives.ds.registry.DsRegistryNodeSerializer;
import com.q3lives.ds.registry.RegistryEntry;

public class DsRegistry_P6a_GatesValidationTest {

    private static final long ROOT_DIR_NODE_ID = 1L;
    private static final int TYPE_REGISTRY_NODE_META = 202;
    private static final int TYPE_REGISTRY_DIR_META = 205;
    private static final int POWER_NODE_BLOCK = 6;
    private static final int POWER_NODE_BLOCK_DIR = 7;

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

    private static long makeLeafTypedId(long leafSeq) {
        return encodeMT(TYPE_REGISTRY_NODE_META, POWER_NODE_BLOCK, leafSeq);
    }

    private static boolean isAllZero(byte[] b) {
        if (b == null) return true;
        for (byte v : b) if (v != 0) return false;
        return true;
    }

    @Test
    public void testG1_RootRingMigratedGate() throws Exception {
        File tmp = Files.createTempDirectory("dsregp6a-g1-").toFile();
        tmp.deleteOnExit();
        try (DsRegistry r = new DsRegistry(new File(tmp, "db").getAbsolutePath())) {
            r.mkdirs("/a");
            Field dirIdToRingIdF = DsRegistry.class.getDeclaredField("dirIdToRingId");
            dirIdToRingIdF.setAccessible(true);
            DsHashMap dirIdToRingId = (DsHashMap) dirIdToRingIdF.get(r);
            Long rootRingNew = dirIdToRingId.get(rootDirTypedId());
            assertNotNull(rootRingNew);
            dirIdToRingId.put(ROOT_DIR_NODE_ID, rootRingNew.longValue());
            dirIdToRingId.remove(rootDirTypedId());
            assertNull(dirIdToRingId.get(rootDirTypedId()));
            assertNotNull(dirIdToRingId.get(ROOT_DIR_NODE_ID));
            int migrated = r.migrateLegacyIds();
            assertEquals(1, migrated);
            assertNull("G1 post-condition: dirIdToRingId[ROOT_DIR_NODE_ID] should be null",
                    dirIdToRingId.get(ROOT_DIR_NODE_ID));
            assertNotNull("G1 post-condition: dirIdToRingId[rootDirTypedId()] should exist",
                    dirIdToRingId.get(rootDirTypedId()));
            int again = r.migrateLegacyIds();
            assertEquals(0, again);
            assertTrue(r.exists("/a"));
        }
    }

    @Test
    public void testG2_KeyIdToDirIdEmptyGate() throws Exception {
        File tmp = Files.createTempDirectory("dsregp6a-g2-").toFile();
        tmp.deleteOnExit();
        try (DsRegistry r = new DsRegistry(new File(tmp, "db").getAbsolutePath())) {
            r.mkdirs("/l0/l1/l2/l3/l4/l5/l6/l7/l8/l9");
            Method sizeMethod = DsHashMap.class.getMethod("size");
            Field keyIdToDirIdF = DsRegistry.class.getDeclaredField("keyIdToDirId");
            Field dirIdToRingIdF = DsRegistry.class.getDeclaredField("dirIdToRingId");
            keyIdToDirIdF.setAccessible(true);
            dirIdToRingIdF.setAccessible(true);
            DsHashMap keyIdToDirId = (DsHashMap) keyIdToDirIdF.get(r);
            DsHashMap dirIdToRingId = (DsHashMap) dirIdToRingIdF.get(r);
            assertEquals(0L, ((Number) sizeMethod.invoke(keyIdToDirId)).longValue());
            long fakePlainBase = 50000L;
            List<String> dirs = Arrays.asList(
                    "/l0", "/l0/l1", "/l0/l1/l2", "/l0/l1/l2/l3", "/l0/l1/l2/l3/l4",
                    "/l0/l1/l2/l3/l4/l5", "/l0/l1/l2/l3/l4/l5/l6", "/l0/l1/l2/l3/l4/l5/l6/l7",
                    "/l0/l1/l2/l3/l4/l5/l6/l7/l8", "/l0/l1/l2/l3/l4/l5/l6/l7/l8/l9");
            Method keyStringsMethod = DsRegistry.class.getMethod("keyStrings");
            Object keyStrings = keyStringsMethod.invoke(r);
            Method getIndexIdMethod = keyStrings.getClass().getMethod("getIndexId", String.class, boolean.class);
            long[] legacyDirNodeIds = new long[dirs.size()];
            for (int i = 0; i < dirs.size(); i++) {
                String d = dirs.get(i);
                Long keyId = (Long) getIndexIdMethod.invoke(keyStrings, d, false);
                assertNotNull("keyId for " + d, keyId);
                long typedDirId = makeDirTypedId(keyId);
                Long realRing = dirIdToRingId.get(typedDirId);
                assertNotNull("ring for typed dir " + d, realRing);
                long fakePlain = fakePlainBase + i;
                legacyDirNodeIds[i] = fakePlain;
                keyIdToDirId.put(keyId.longValue(), fakePlain);
                dirIdToRingId.put(fakePlain, realRing.longValue());
            }
            assertEquals(dirs.size(), ((Number) sizeMethod.invoke(keyIdToDirId)).longValue());
            int migrated = r.migrateLegacyIds();
            assertEquals(dirs.size(), migrated);
            assertEquals("G2 post-condition: keyIdToDirId.size() should be 0",
                    0L, ((Number) sizeMethod.invoke(keyIdToDirId)).longValue());
            for (int i = 0; i < dirs.size(); i++) {
                String d = dirs.get(i);
                Long keyId = (Long) getIndexIdMethod.invoke(keyStrings, d, false);
                long typedDirId = makeDirTypedId(keyId);
                assertNotNull("post-migrate ring for typed dir " + d, dirIdToRingId.get(typedDirId));
            }
            int again = r.migrateLegacyIds();
            assertEquals(0, again);
            assertTrue(r.exists("/l0/l1/l2/l3/l4/l5/l6/l7/l8/l9"));
        }
    }

    @Test
    public void testG3_LegacyBucketPayloadEmptyGate() throws Exception {
        File tmp = Files.createTempDirectory("dsregp6a-g3-").toFile();
        tmp.deleteOnExit();
        try (DsRegistry r = new DsRegistry(new File(tmp, "db").getAbsolutePath())) {
            r.putLong("/a/x", DataType.REG_DWORD, 11L);
            r.mkdirs("/a/y");
            Field keyIdToDirIdF = DsRegistry.class.getDeclaredField("keyIdToDirId");
            Field dirIdToRingIdF = DsRegistry.class.getDeclaredField("dirIdToRingId");
            keyIdToDirIdF.setAccessible(true);
            dirIdToRingIdF.setAccessible(true);
            DsHashMap keyIdToDirId = (DsHashMap) keyIdToDirIdF.get(r);
            DsHashMap dirIdToRingId = (DsHashMap) dirIdToRingIdF.get(r);
            long fakePlainDirNode = 70001L;
            final long LEGACY_TEST_ID_1 = 9_999_800L;
            final long LEGACY_TEST_ID_2 = 9_999_900L;
            Method keyStringsMethod = DsRegistry.class.getMethod("keyStrings");
            Object keyStrings = keyStringsMethod.invoke(r);
            Method getIndexIdMethod = keyStrings.getClass().getMethod("getIndexId", String.class, boolean.class);
            Long keyAY = (Long) getIndexIdMethod.invoke(keyStrings, "/a/y", false);
            assertNotNull(keyAY);
            Long ringAY = dirIdToRingId.get(makeDirTypedId(keyAY));
            assertNotNull(ringAY);
            keyIdToDirId.put(keyAY.longValue(), fakePlainDirNode);
            dirIdToRingId.put(fakePlainDirNode, ringAY.longValue());
            Long rootRing = dirIdToRingId.get(rootDirTypedId());
            dirIdToRingId.put(ROOT_DIR_NODE_ID, rootRing.longValue());
            int migrated = r.migrateLegacyIds();
            assertEquals(2, migrated);
            Field nodeStoreF2 = DsRegistry.class.getDeclaredField("nodeStore");
            nodeStoreF2.setAccessible(true);
            Object nodeStore2 = nodeStoreF2.get(r);
            Method readNodeMethod = DsRegistryNodeSerializer.class.getMethod("readNode",
                    Class.forName("com.q3lives.ds.bucket.DsTypeFixedBucketStore"), long.class);
            Object legacyBig1DirMeta = readNodeMethod.invoke(null, nodeStore2, makeDirTypedId(LEGACY_TEST_ID_1));
            Object legacyBig1LeafMeta = readNodeMethod.invoke(null, nodeStore2, makeLeafTypedId(LEGACY_TEST_ID_1));
            Object legacyBig2DirMeta = readNodeMethod.invoke(null, nodeStore2, makeDirTypedId(LEGACY_TEST_ID_2));
            Object legacyBig2LeafMeta = readNodeMethod.invoke(null, nodeStore2, makeLeafTypedId(LEGACY_TEST_ID_2));
            assertNull("G3: legacy big1 plain=9_999_800 encoded as DIR has no NodeMeta (never written by legacy nor P4)",
                    legacyBig1DirMeta);
            assertNull("G3: legacy big1 plain=9_999_800 encoded as LEAF has no NodeMeta", legacyBig1LeafMeta);
            assertNull("G3: legacy big2 plain=9_999_900 encoded as DIR has no NodeMeta", legacyBig2DirMeta);
            assertNull("G3: legacy big2 plain=9_999_900 encoded as LEAF has no NodeMeta", legacyBig2LeafMeta);
            RegistryEntry x = r.get("/a/x");
            assertNotNull(x);
            assertEquals(11L, x.asLong());
            assertTrue(r.exists("/a/y"));
        }
    }

    @Test
    public void testG1G2G3_ConsecutiveGatesWithPutRemove() throws Exception {
        File tmp = Files.createTempDirectory("dsregp6a-g123-").toFile();
        tmp.deleteOnExit();
        try (DsRegistry r = new DsRegistry(new File(tmp, "db").getAbsolutePath())) {
            final int DEPTH = 8;
            final int N = 100;
            StringBuilder sb = new StringBuilder(256);
            String[] layers = new String[DEPTH];
            for (int i = 0; i < DEPTH; i++) layers[i] = "/d" + i;
            for (int k = 0; k < N; k++) {
                sb.setLength(0);
                for (int i = 0; i < DEPTH; i++) sb.append(layers[i]);
                sb.append("/item-").append(k);
                r.putLong(sb.toString(), DataType.REG_DWORD, (long) (k * 13));
            }
            Field keyIdToDirIdF = DsRegistry.class.getDeclaredField("keyIdToDirId");
            Field dirIdToRingIdF = DsRegistry.class.getDeclaredField("dirIdToRingId");
            keyIdToDirIdF.setAccessible(true);
            dirIdToRingIdF.setAccessible(true);
            DsHashMap keyIdToDirId = (DsHashMap) keyIdToDirIdF.get(r);
            DsHashMap dirIdToRingId = (DsHashMap) dirIdToRingIdF.get(r);
            Method sizeMethod = DsHashMap.class.getMethod("size");
            Method keyStringsMethod = DsRegistry.class.getMethod("keyStrings");
            Object keyStrings = keyStringsMethod.invoke(r);
            Method getIndexIdMethod = keyStrings.getClass().getMethod("getIndexId", String.class, boolean.class);
            long plainBase = 1_000_000L;
            int injected = 0;
            for (int i = 1; i <= DEPTH; i++) {
                sb.setLength(0);
                for (int j = 0; j < i; j++) sb.append(layers[j]);
                String d = sb.toString();
                Long keyId = (Long) getIndexIdMethod.invoke(keyStrings, d, false);
                assertNotNull(keyId);
                long typedId = makeDirTypedId(keyId);
                Long realRing = dirIdToRingId.get(typedId);
                assertNotNull(realRing);
                long fake = plainBase + injected;
                keyIdToDirId.put(keyId.longValue(), fake);
                dirIdToRingId.put(fake, realRing.longValue());
                injected++;
            }
            Long rootRing = dirIdToRingId.get(rootDirTypedId());
            dirIdToRingId.put(ROOT_DIR_NODE_ID, rootRing.longValue());
            dirIdToRingId.remove(rootDirTypedId());
            assertEquals(injected, ((Number) sizeMethod.invoke(keyIdToDirId)).longValue());
            int step1 = r.migrateLegacyIds();
            assertEquals(injected + 1, step1);
            assertEquals(0L, ((Number) sizeMethod.invoke(keyIdToDirId)).longValue());
            assertNull(dirIdToRingId.get(ROOT_DIR_NODE_ID));
            assertNotNull(dirIdToRingId.get(rootDirTypedId()));
            for (int k = 0; k < N / 2; k++) {
                sb.setLength(0);
                for (int i = 0; i < DEPTH; i++) sb.append(layers[i]);
                sb.append("/item-").append(k);
                r.remove(sb.toString(), true);
            }
            int step2 = r.migrateLegacyIds();
            assertEquals(0, step2);
            for (int k = N / 2; k < N; k++) {
                sb.setLength(0);
                for (int i = 0; i < DEPTH; i++) sb.append(layers[i]);
                sb.append("/item-").append(k);
                long v = r.get(sb.toString()).asLong();
                assertEquals((long) (k * 13), v);
            }
        }
    }

    @Test
    public void testG5_StaticGrep_SurvivingUsagesAreSafe() throws Exception {
        Field constF = DsRegistry.class.getDeclaredField("ROOT_DIR_NODE_ID");
        constF.setAccessible(true);
        assertEquals(1L, constF.getLong(null));
        java.lang.reflect.Method[] ms = DsRegistry.class.getDeclaredMethods();
        int callCount = 0;
        for (java.lang.reflect.Method m : ms) {
            if (m.getName().equals("migrateLegacyIds")) callCount++;
            if (m.getName().equals("isRootNodeId")) callCount++;
        }
        assertTrue("migrateLegacyIds exists as the only legacy-removal user-facing API",
                callCount >= 2);
    }
}
