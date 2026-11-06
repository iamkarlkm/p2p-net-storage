package ds;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.annotation.DsCompositeIndex;
import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.schema.EntityIndexUtil;
import com.q3lives.ds.registry.DsRegistry;
import com.q3lives.ds.registry.RegistryEntry;
import com.q3lives.ds.registry.meta.DsDatabaseMetaMigrator;
import com.q3lives.ds.registry.meta.DsRegistryInstance;
import com.q3lives.ds.registry.meta.RegistryMetaPaths;

/**
 * P10 T10 RowMetaTest(6)
 */
public class P10_RowMetaTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @Before
    public void setUp() {
        try {
            Field f = Class.forName("com.q3lives.ds.registry.meta.DsRegistryInstance").getDeclaredField("INSTANCES");
            f.setAccessible(true);
            Object m = f.get(null);
            if (m instanceof ConcurrentHashMap) ((ConcurrentHashMap<?, ?>) m).clear();
        } catch (Exception ignored) {}
    }

    private static void callMetaOf(DsDatabaseLocal db, Class<? extends DsTableAdapter> clazz) throws Exception {
        Method m = DsDatabaseLocal.class.getDeclaredMethod("metaOf", Class.class);
        m.setAccessible(true);
        m.invoke(db, clazz);
    }

    @DsCompositeIndex(name = "idx_name_age", columns = {"name", "age"})
    public static class UserRow extends DsTableAdapter {
        @DsField(name = "uid", length = 8, indexed = true)
        public long uid;
        @DsField(name = "name", length = 32)
        public String name;
        @DsField(name = "age", length = 4, indexed = true)
        public int age;
        public UserRow() {}
    }

    @Test
    public void t01_classIdSetPointerWritten() throws Exception {
        File root = tmp.newFolder("row-t01");
        try (DsDatabaseLocal db = new DsDatabaseLocal(root)) {
            UserRow row = new UserRow();
            row.uid = 1L;
            row.name = "alice";
            row.age = 30;
            db.putEntity(row, true);
        }
        DsRegistry r = DsRegistryInstance.get(root);
        String entity = UserRow.class.getName();
        RegistryEntry legE = r.get(RegistryMetaPaths.rowIdSetLegacySchemaId(entity));
        assertTrue("legacySchemaId 应写入", legE.isPresent());
        assertNotNull(legE.asString());
        RegistryEntry fE = r.get(RegistryMetaPaths.rowIdSetAbsolutePath(entity));
        assertTrue("idsFileAbsolutePath 应写入", fE.isPresent());
        assertNotNull(fE.asString());
        RegistryEntry creE = r.get(RegistryMetaPaths.rowIdSetCreatedAtMs(entity));
        assertTrue("createdAtEpochMs 应写入", creE.isPresent());
        long createdAt = creE.asLong();
        assertTrue(createdAt > 0L);
    }

    @Test
    public void t02_eqIndexRegistryFiveLeaf() throws Exception {
        File root = tmp.newFolder("row-t02");
        try (DsDatabaseLocal db = new DsDatabaseLocal(root)) {
            UserRow row = new UserRow();
            row.uid = 1L;
            row.name = "alice";
            row.age = 30;
            db.putEntity(row, true);
        }
        DsRegistry r = DsRegistryInstance.get(root);
        String entity = UserRow.class.getName();
        String indexKey = "age";
        assertTrue("eq index kind 应该存在", r.exists(RegistryMetaPaths.rowIndexKind(entity, indexKey)));
        RegistryEntry kindE = r.get(RegistryMetaPaths.rowIndexKind(entity, indexKey));
        assertEquals("eq", kindE.asString());
        RegistryEntry dirE = r.get(RegistryMetaPaths.rowIndexDirAbsPath(entity, indexKey));
        assertTrue("dirAbsolutePath 存在", dirE.isPresent());
        assertNotNull(dirE.asString());
    }

    @Test
    public void t03_hybridBackfillFromYaml() throws Exception {
        File root = tmp.newFolder("row-t03");
        File idsSet = new File(root, UserRow.class.getName() + ".ids.set");
        if (!idsSet.exists()) java.nio.file.Files.createFile(idsSet.toPath());
        File idsMeta = new File(root, UserRow.class.getName() + ".ids.meta.yaml");
        String idsSetAbs = idsSet.getAbsolutePath().replace("\\", "/");
        String fakeYaml = "legacySchemaId: \"FAKE_LEG_0001\"\n" +
            "idsFileAbsolutePath: \"" + idsSetAbs + "\"\n" +
            "createdAtEpochMs: 1710000000000\n";
        java.nio.file.Files.write(idsMeta.toPath(), fakeYaml.getBytes());
        new DsDatabaseMetaMigrator(root, false).migrateAll();

        DsRegistry r = DsRegistryInstance.get(root);
        String entity = UserRow.class.getName();
        RegistryEntry legE = r.get(RegistryMetaPaths.rowIdSetLegacySchemaId(entity));
        assertTrue("Registry 应被回填", legE.isPresent());
        assertEquals("YAML 回填 legacySchemaId", "FAKE_LEG_0001", legE.asString());
        RegistryEntry creE = r.get(RegistryMetaPaths.rowIdSetCreatedAtMs(entity));
        assertTrue("createdAt 存在", creE.isPresent());
    }

    @Test
    public void t04_registryMissFallbackYaml() throws Exception {
        File root = tmp.newFolder("row-t04");
        File indexesDir = new File(root, "indexes/" + UserRow.class.getName().replace('.', '/'));
        indexesDir.mkdirs();
        File idsMeta = new File(indexesDir, "ids.meta.yaml");
        File idsSet = new File(indexesDir, "ids.set");
        if (!idsSet.exists()) java.nio.file.Files.createFile(idsSet.toPath());
        String idsSetAbs = idsSet.getAbsolutePath().replace("\\", "/");
        java.nio.file.Files.write(idsMeta.toPath(),
            ("legacySchemaId: \"legacy\"\n" +
                "idsFileAbsolutePath: \"" + idsSetAbs + "\"\n" +
                "createdAtEpochMs: 1712000000000\n").getBytes());
        setUp();
        EntityIndexUtil.IndexDef def = EntityIndexUtil.indexOf(root, UserRow.class);
        assertNotNull(def);
        assertTrue("legacy=true 说明 fallback 到 YAML 的 legacy schema", def.legacy);
    }

    @Test
    public void t05_hybridRenameMigratedIdempotent() throws Exception {
        File root = tmp.newFolder("row-t05");
        File idsSet = new File(root, UserRow.class.getName() + ".ids.set");
        if (!idsSet.exists()) java.nio.file.Files.createFile(idsSet.toPath());
        File idsMeta = new File(root, UserRow.class.getName() + ".ids.meta.yaml");
        String idsSetAbs = idsSet.getAbsolutePath().replace("\\", "/");
        java.nio.file.Files.write(idsMeta.toPath(),
            ("legacySchemaId: \"MIG_001\"\n" +
                "idsFileAbsolutePath: \"" + idsSetAbs + "\"\n" +
                "createdAtEpochMs: 1713000000000\n").getBytes());
        new DsDatabaseMetaMigrator(root, false).migrateAll();
        assertFalse("rename 后 yaml 不应存在", idsMeta.exists());
        File migrated = new File(idsMeta.getAbsolutePath() + ".migrated");
        assertTrue(".migrated 应存在（第一次）", migrated.exists());

        setUp();
        try (DsDatabaseMetaMigrator m2 = new DsDatabaseMetaMigrator(root, false)) {
            m2.rollbackAll();
        }
        try (DsDatabaseMetaMigrator m3 = new DsDatabaseMetaMigrator(root, false)) {
            m3.migrateAll();
        }
        DsRegistry r = DsRegistryInstance.get(root);
        String entity = UserRow.class.getName();
        RegistryEntry legE = r.get(RegistryMetaPaths.rowIdSetLegacySchemaId(entity));
        assertTrue("Registry 应保留", legE.isPresent());
        assertEquals("MIG_001", legE.asString());
    }

    @Test
    public void t06_hybridModeRegistryIsWrittenBaseline() throws Exception {
        File root = tmp.newFolder("row-t06");
        try (DsDatabaseLocal db = new DsDatabaseLocal(root)) {
            UserRow row = new UserRow();
            row.uid = 5L;
            row.name = "eve";
            row.age = 45;
            db.putEntity(row, true);
        }
        DsRegistry r = DsRegistryInstance.get(root);
        String entity = UserRow.class.getName();
        RegistryEntry legE = r.get(RegistryMetaPaths.rowIdSetLegacySchemaId(entity));
        assertTrue("Baseline: HYBRID 模式下 Registry 必有 legacySchemaId", legE.isPresent());
        assertNotNull(legE.asString());
    }
}
