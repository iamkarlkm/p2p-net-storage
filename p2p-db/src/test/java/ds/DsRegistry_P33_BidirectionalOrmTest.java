package ds;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.schema.EntitySchemaUtil;
import com.q3lives.ds.registry.DsRegistry;
import com.q3lives.ds.registry.DsRegistryOrmBridge;
import com.q3lives.ds.registry.RegistryEntry;

import ds.DsDatabaseLocalMvccTest.ColumnMvccUser;
import ds.DsDatabaseLocalMvccTest.MvccUser;
import ds.DsDatabaseLocalMvccTest.NonMvccUser;

public class DsRegistry_P33_BidirectionalOrmTest {

    @Test
    void testRoundtripLoadColumns_MatchOriginalSchemaOf() throws Exception {
        File tmp = Files.createTempDirectory("dsregp33a-").toFile();
        tmp.deleteOnExit();
        try (DsRegistry r = new DsRegistry(new File(tmp, "orm").getAbsolutePath())) {
            DsRegistryOrmBridge bridge = new DsRegistryOrmBridge(r);
            EntitySchemaUtil.SchemaDef orig = EntitySchemaUtil.schemaOf(MvccUser.class);
            bridge.registerEntity(MvccUser.class, "/db");
            List<EntitySchemaUtil.SchemaDef> loaded = bridge.loadEntitySchemasFromRegistry("/db");
            assertEquals(1, loaded.size());
            EntitySchemaUtil.SchemaDef def = loaded.get(0);
            assertEquals(orig.schemaId, def.schemaId);
            assertEquals(orig.rowLength, def.rowLength);
            List<? extends EntitySchemaUtil.ColumnDef> origCols = orig.getColumns();
            List<? extends EntitySchemaUtil.ColumnDef> newCols = def.getColumns();
            assertEquals(origCols.size(), newCols.size());
            Map<String, EntitySchemaUtil.ColumnDef> origMap = new HashMap<>();
            for (EntitySchemaUtil.ColumnDef c : origCols) origMap.put(c.name, c);
            for (EntitySchemaUtil.ColumnDef c : newCols) {
                EntitySchemaUtil.ColumnDef o = origMap.get(c.name);
                assertNotNull(o, "加载列不存在: " + c.name);
                assertEquals(o.typeName, c.typeName, "typeName 不对 col=" + c.name);
                assertEquals(o.length, c.length, "length 不对 col=" + c.name);
                assertEquals(o.indexed, c.indexed, "indexed 不对 col=" + c.name);
                assertEquals(o.mvcc, c.mvcc, "mvcc 不对 col=" + c.name);
                assertEquals(o.precision, c.precision, "precision 不对 col=" + c.name);
                assertEquals(o.scale, c.scale, "scale 不对 col=" + c.name);
            }
        }
    }

    @Test
    void testResolveEntityClass_DynamicClassForName() throws Exception {
        File tmp = Files.createTempDirectory("dsregp33b-").toFile();
        tmp.deleteOnExit();
        try (DsRegistry r = new DsRegistry(new File(tmp, "orm").getAbsolutePath())) {
            DsRegistryOrmBridge bridge = new DsRegistryOrmBridge(r);
            bridge.registerEntity(MvccUser.class, "/db");
            bridge.registerEntity(ColumnMvccUser.class, "/db");
            List<EntitySchemaUtil.SchemaDef> loaded = bridge.loadEntitySchemasFromRegistry("/db");
            assertEquals(2, loaded.size());
            for (EntitySchemaUtil.SchemaDef def : loaded) {
                Class<? extends DsTableAdapter> c = bridge.resolveEntityClass("/db", def);
                assertNotNull(c, "class 未 resolve schemaId=" + def.schemaId);
                assertTrue(DsTableAdapter.class.isAssignableFrom(c));
                EntitySchemaUtil.SchemaDef def2 = EntitySchemaUtil.schemaOf(c);
                assertNotNull(def2);
                assertEquals(def.schemaId, def2.schemaId, "schemaId 不匹配: " + c.getName());
            }
        }
    }

    @Test
    void testLoadEntitySchemas_3Entities_DistinctSchemas() throws Exception {
        File tmp = Files.createTempDirectory("dsregp33c-").toFile();
        tmp.deleteOnExit();
        try (DsRegistry r = new DsRegistry(new File(tmp, "orm").getAbsolutePath())) {
            DsRegistryOrmBridge bridge = new DsRegistryOrmBridge(r);
            bridge.registerEntity(MvccUser.class, "/db");
            bridge.registerEntity(ColumnMvccUser.class, "/db");
            bridge.registerEntity(NonMvccUser.class, "/db");
            List<String> schemaDirNames = bridge.listEntitySchemas("/db");
            Set<String> expected = new HashSet<>(schemaDirNames);
            List<EntitySchemaUtil.SchemaDef> loaded = bridge.loadEntitySchemasFromRegistry("/db");
            Set<String> actual = new HashSet<>();
            for (EntitySchemaUtil.SchemaDef def : loaded) actual.add(def.schemaId);
            assertEquals(expected, actual);
        }
    }

    @Test
    void testRoundtrip_Idempotent_DoesNotDuplicateOrOverwrite() throws Exception {
        File tmp = Files.createTempDirectory("dsregp33d-").toFile();
        tmp.deleteOnExit();
        try (DsRegistry r = new DsRegistry(new File(tmp, "orm").getAbsolutePath())) {
            DsRegistryOrmBridge bridge = new DsRegistryOrmBridge(r);
            bridge.registerEntity(MvccUser.class, "/db");
            String typeKey = "/db/" + EntitySchemaUtil.schemaOf(MvccUser.class).schemaId
                    + "/MvccUser/columns/name/type";
            RegistryEntry before = r.get(typeKey);
            assertTrue(before.isPresent());
            String beforeVal = before.asString();
            bridge.registerEntity(MvccUser.class, "/db");
            bridge.registerEntity(MvccUser.class, "/db");
            RegistryEntry after = r.get(typeKey);
            assertTrue(after.isPresent());
            assertEquals(beforeVal, after.asString(), "幂等反不应覆盖值");
            List<EntitySchemaUtil.SchemaDef> loaded1 = bridge.loadEntitySchemasFromRegistry("/db");
            List<EntitySchemaUtil.SchemaDef> loaded2 = bridge.loadEntitySchemasFromRegistry("/db");
            assertEquals(1, loaded1.size());
            assertEquals(1, loaded2.size());
            assertEquals(loaded1.get(0).schemaId, loaded2.get(0).schemaId);
            assertEquals(loaded1.get(0).getColumns().size(), loaded2.get(0).getColumns().size());
        }
    }
}
