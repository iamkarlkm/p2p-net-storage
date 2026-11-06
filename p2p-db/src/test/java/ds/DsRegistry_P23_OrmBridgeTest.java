package ds;

import java.io.File;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import com.q3lives.ds.database.schema.EntitySchemaUtil;
import com.q3lives.ds.enums.DataType;
import com.q3lives.ds.registry.DsRegistry;
import com.q3lives.ds.registry.DsRegistryOrmBridge;
import com.q3lives.ds.registry.RegistryEntry;

import ds.DsDatabaseLocalMvccTest.ColumnMvccUser;
import ds.DsDatabaseLocalMvccTest.MvccUser;
import ds.DsDatabaseLocalMvccTest.NonMvccUser;

public class DsRegistry_P23_OrmBridgeTest {

    @Test
    public void testRegisterEntity_CreatesDirectoryAndColumnStructure() throws Exception {
        File tmp = Files.createTempDirectory("dsregp23-").toFile();
        tmp.deleteOnExit();
        try (DsRegistry r = new DsRegistry(new File(tmp, "orm").getAbsolutePath())) {
            DsRegistryOrmBridge bridge = new DsRegistryOrmBridge(r);
            String base = bridge.registerEntity(MvccUser.class, "/orm");
            EntitySchemaUtil.SchemaDef def = EntitySchemaUtil.schemaOf(MvccUser.class);
            assertEquals("/orm/" + def.schemaId + "/MvccUser", base, "base 路径不对");
            List<String> cols = r.listChildren(base + "/columns");
            assertNotNull(cols, "columns 目录没建");
            Set<String> colSet = new HashSet<>(cols);
            List<? extends EntitySchemaUtil.ColumnDef> expectedCols = def.getColumns();
            for (EntitySchemaUtil.ColumnDef c : expectedCols) {
                assertTrue(colSet.contains(c.name), "缺少列:" + c.name);
                RegistryEntry typeE = r.get(base + "/columns/" + c.name + "/type");
                assertTrue(typeE.isPresent(), "type 不存在, col=" + c.name);
                assertEquals(DataType.REG_SZ, typeE.dataType(), "type DataType 不对 col=" + c.name);
                assertEquals(c.typeName, typeE.asString(), "typeName 不对 col=" + c.name);
                RegistryEntry idx = r.get(base + "/columns/" + c.name + "/indexed");
                assertTrue(idx.isPresent(), "indexed 不存在 col=" + c.name);
                assertEquals(c.indexed ? 1L : 0L, idx.asLong(), "indexed 值不对 col=" + c.name);
                RegistryEntry mvccE = r.get(base + "/columns/" + c.name + "/mvcc");
                assertTrue(mvccE.isPresent());
                assertEquals(c.mvcc ? 1L : 0L, mvccE.asLong(), "mvcc 值不对 col=" + c.name);
            }
        }
    }

    @Test
    public void testGetEntityRootKeyPath_MatchesRegister() throws Exception {
        File tmp = Files.createTempDirectory("dsregp23-").toFile();
        tmp.deleteOnExit();
        try (DsRegistry r = new DsRegistry(new File(tmp, "orm2").getAbsolutePath())) {
            DsRegistryOrmBridge bridge = new DsRegistryOrmBridge(r);
            String p1 = bridge.getEntityRootKeyPath(ColumnMvccUser.class, "/db");
            String p2 = bridge.registerEntity(ColumnMvccUser.class, "/db");
            assertEquals(p1, p2, "getEntityRootKeyPath 与 registerEntity 不一致");
            assertTrue(r.exists(p2 + "/.className"), "className 叶子没建");
            assertEquals(ColumnMvccUser.class.getName(), r.get(p2 + "/.className").asString());
        }
    }

    @Test
    public void testListEntitySchemas_ListsAllSchemaDirs() throws Exception {
        File tmp = Files.createTempDirectory("dsregp23-").toFile();
        tmp.deleteOnExit();
        try (DsRegistry r = new DsRegistry(new File(tmp, "orm3").getAbsolutePath())) {
            DsRegistryOrmBridge bridge = new DsRegistryOrmBridge(r);
            bridge.registerEntity(MvccUser.class, "/db");
            bridge.registerEntity(ColumnMvccUser.class, "/db");
            bridge.registerEntity(NonMvccUser.class, "/db");
            List<String> schemas = bridge.listEntitySchemas("/db");
            Set<String> actual = new HashSet<>(schemas);
            Set<String> expected = new HashSet<>();
            expected.add(EntitySchemaUtil.schemaOf(MvccUser.class).schemaId);
            expected.add(EntitySchemaUtil.schemaOf(ColumnMvccUser.class).schemaId);
            expected.add(EntitySchemaUtil.schemaOf(NonMvccUser.class).schemaId);
            assertEquals(expected, actual, "listEntitySchemas 结果不对");
        }
    }

    @Test
    public void testRegisterEntity_Idempotent_DoesNotOverwriteExisting() throws Exception {
        File tmp = Files.createTempDirectory("dsregp23-").toFile();
        tmp.deleteOnExit();
        try (DsRegistry r = new DsRegistry(new File(tmp, "orm4").getAbsolutePath())) {
            DsRegistryOrmBridge bridge = new DsRegistryOrmBridge(r);
            bridge.registerEntity(MvccUser.class, "/db");
            String nameKey = "/db/" + EntitySchemaUtil.schemaOf(MvccUser.class).schemaId + "/MvccUser/columns/name/type";
            RegistryEntry before = r.get(nameKey);
            assertTrue(before.isPresent());
            String beforeVal = before.asString();
            bridge.registerEntity(MvccUser.class, "/db");
            RegistryEntry after = r.get(nameKey);
            assertTrue(after.isPresent());
            assertEquals(beforeVal, after.asString(), "幂等调用不应覆盖现有值");
        }
    }
}
