package ds;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.annotation.DsFieldGroup;
import com.q3lives.ds.annotation.DsTable;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.columnar.ColumnarStore;
import com.q3lives.ds.registry.DsRegistry;
import com.q3lives.ds.registry.meta.DsDatabaseMetaMigrator;
import com.q3lives.ds.registry.meta.DsRegistryInstance;
import com.q3lives.ds.registry.meta.MetaMode;
import com.q3lives.ds.registry.meta.RegistryMetaPaths;

/**
 * P11 T4 ColumnarStore 列 + 复合组接入 DataBackend 5 TRs：
 *   TR-T4-R1：HYBRID 列 roundtrip（putValue → getValue → 字节一致）
 *   TR-T4-R2：HYBRID FieldGroup roundtrip（putFieldGroup → getFieldGroup → 字节一致）
 *   TR-T4-R3：HYBRID 列回填清 Registry 后重新 exists
 *   TR-T4-R4：HYBRID Group 回填清 Registry 后重新 exists
 *   TR-T4-R5：removeRow 双删（bucket null/全0 且 Registry 不存在）
 */
public class P11_ColumnarDataTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @Before
    public void setUp() {
        try {
            Field f = DsRegistryInstance.class.getDeclaredField("INSTANCES");
            f.setAccessible(true);
            Object m = f.get(null);
            if (m instanceof ConcurrentHashMap) ((ConcurrentHashMap<?, ?>) m).clear();
        } catch (Exception ignored) {}
        System.clearProperty(MetaMode.PROPERTY);
    }

    @DsTable(name = "t_col_row_t4")
    public static class ColRow extends DsTableAdapter {
        @DsField(name = "payload", length = 64)
        public String payload;
        @DsField(name = "flag", length = 4)
        public int flag;

        public ColRow() {}
    }

    @DsTable(name = "t_grp_row_t4")
    public static class GroupRow extends DsTableAdapter {
        @DsField(name = "firstName", length = 32)
        @DsFieldGroup(groupName = "PROFILE", groupIndex = 0)
        public String firstName;
        @DsField(name = "ageAlias", length = 4)
        @DsFieldGroup(groupName = "PROFILE", groupIndex = 1)
        public Integer ageAlias;
        @DsField(name = "code", length = 8)
        @DsFieldGroup(groupName = "PROFILE", groupIndex = 2)
        public String code;

        public GroupRow() {}
    }

    static final class Bytes {
        static byte[] rnd(int len) {
            byte[] b = new byte[len];
            new Random(System.nanoTime()).nextBytes(b);
            return b;
        }
        static boolean allZero(byte[] b) {
            if (b == null) return true;
            for (byte x : b) if (x != 0) return false;
            return true;
        }
    }

    @Test
    public void r1_hybrid_column_roundtrip() throws Exception {
        File root = tmp.newFolder("t4-r1");
        new DsDatabaseMetaMigrator(root, false).migrateAll();
        try (ColumnarStore cs = new ColumnarStore(root)) {
            byte[] payload = Bytes.rnd(64);
            long rowId = 1001L;
            cs.putValue(ColRow.class, "payload", rowId, payload);
            cs.putValue(ColRow.class, "flag", rowId, new byte[]{0x00, 0x00, 0x00, 0x2A});
            for (int i = 0; i < 3; i++) {
                byte[] backPayload = cs.getValue(ColRow.class, "payload", rowId);
                assertNotNull("i=" + i + " payload 不应 null", backPayload);
                assertArrayEquals("i=" + i + " payload bytes 不一致", payload, backPayload);
                byte[] backFlag = cs.getValue(ColRow.class, "flag", rowId);
                assertNotNull(backFlag);
                assertEquals(4, backFlag.length);
                assertEquals(0x2A, backFlag[3] & 0xFF);
            }
        }
    }

    @Test
    public void r2_hybrid_fieldgroup_roundtrip() throws Exception {
        File root = tmp.newFolder("t4-r2");
        new DsDatabaseMetaMigrator(root, false).migrateAll();
        try (ColumnarStore cs = new ColumnarStore(root)) {
            byte[] groupBytes = Bytes.rnd(44);
            long rowId = 2002L;
            cs.putFieldGroup(GroupRow.class, "PROFILE", rowId, groupBytes);
            for (int i = 0; i < 3; i++) {
                byte[] back = cs.getFieldGroup(GroupRow.class, "PROFILE", rowId);
                assertNotNull("i=" + i + " group 不应 null", back);
                assertArrayEquals("i=" + i + " group bytes 不一致", groupBytes, back);
            }
        }
    }

    @Test
    public void r3_hybrid_column_backfill() throws Exception {
        File root = tmp.newFolder("t4-r3");
        new DsDatabaseMetaMigrator(root, false).migrateAll();
        try (ColumnarStore cs = new ColumnarStore(root)) {
            byte[] payload = Bytes.rnd(64);
            long rowId = 3003L;
            cs.putValue(ColRow.class, "payload", rowId, payload);
            DsRegistry registry = DsRegistryInstance.get(root);
            String colPath = RegistryMetaPaths.dataColumnPayload(ColRow.class.getName(), "payload", rowId);
            assertTrue("putValue 写后 Registry 列 payload 存在", registry.exists(colPath));
            registry.remove(colPath, false);
            assertFalse("清 Registry 后列 payload 不存在", registry.exists(colPath));
            byte[] back = cs.getValue(ColRow.class, "payload", rowId);
            assertNotNull("回读后值不应 null", back);
            assertArrayEquals(payload, back);
            assertTrue("读回填后列 payload Registry 重新存在", registry.exists(colPath));
        }
    }

    @Test
    public void r4_hybrid_group_backfill() throws Exception {
        File root = tmp.newFolder("t4-r4");
        new DsDatabaseMetaMigrator(root, false).migrateAll();
        try (ColumnarStore cs = new ColumnarStore(root)) {
            byte[] groupBytes = Bytes.rnd(44);
            long rowId = 4004L;
            cs.putFieldGroup(GroupRow.class, "PROFILE", rowId, groupBytes);
            DsRegistry registry = DsRegistryInstance.get(root);
            String gPath = RegistryMetaPaths.dataGroupPayload(GroupRow.class.getName(), "PROFILE", rowId);
            assertTrue("putFieldGroup 写后 Registry group 存在", registry.exists(gPath));
            registry.remove(gPath, false);
            assertFalse("清 Registry 后 group 不存在", registry.exists(gPath));
            byte[] back = cs.getFieldGroup(GroupRow.class, "PROFILE", rowId);
            assertNotNull("回填后 group 非 null", back);
            assertArrayEquals(groupBytes, back);
            assertTrue("读回填后 group Registry 重新存在", registry.exists(gPath));
        }
    }

    @Test
    public void r5_removeRow_double_deleted() throws Exception {
        File root = tmp.newFolder("t4-r5");
        new DsDatabaseMetaMigrator(root, false).migrateAll();
        try (ColumnarStore cs = new ColumnarStore(root)) {
            long rowId = 5005L;
            cs.putValue(ColRow.class, "payload", rowId, Bytes.rnd(64));
            cs.putValue(ColRow.class, "flag", rowId, new byte[]{(byte)0xCA,(byte)0xFE,(byte)0xBA,(byte)0xBE});
            cs.putFieldGroup(GroupRow.class, "PROFILE", rowId, Bytes.rnd(44));
            DsRegistry registry = DsRegistryInstance.get(root);
            String colPayload = RegistryMetaPaths.dataColumnPayload(ColRow.class.getName(), "payload", rowId);
            String colFlag = RegistryMetaPaths.dataColumnPayload(ColRow.class.getName(), "flag", rowId);
            String gPath = RegistryMetaPaths.dataGroupPayload(GroupRow.class.getName(), "PROFILE", rowId);
            assertTrue("payload registry 存在", registry.exists(colPayload));
            assertTrue("flag registry 存在", registry.exists(colFlag));
            assertTrue("group registry 存在", registry.exists(gPath));

            cs.removeRow(ColRow.class.getName(), rowId);
            cs.removeRow(GroupRow.class.getName(), rowId);

            byte[] backPayload = cs.getValue(ColRow.class, "payload", rowId);
            byte[] backFlag = cs.getValue(ColRow.class, "flag", rowId);
            byte[] backGroup = cs.getFieldGroup(GroupRow.class, "PROFILE", rowId);
            assertTrue("payload bucket 应为 null 或全0", backPayload == null || Bytes.allZero(backPayload));
            assertTrue("flag bucket 应为 null 或全0", backFlag == null || Bytes.allZero(backFlag));
            assertNull("PROFILE group bucket 读为 null", backGroup);
            assertFalse("removeRow 后 payload Registry 不存在", registry.exists(colPayload));
            assertFalse("removeRow 后 flag Registry 不存在", registry.exists(colFlag));
            assertFalse("removeRow 后 group Registry 不存在", registry.exists(gPath));
        }
    }
}
