package ds;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class OnlineSchemaCompatibilityTest {

    private File tmpRoot;

    @Before
    public void before() throws IOException {
        tmpRoot = Files.createTempDirectory("dsdb_online_schema_").toFile();
    }

    @After
    public void after() {
        if (tmpRoot != null && tmpRoot.exists()) {
            try { DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot); } catch (Exception ignore) {}
            deleteRecursive(tmpRoot);
        }
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) for (File c : ch) deleteRecursive(c);
        }
        f.delete();
    }

    /**
     * Schema V1（3个字段 total=28B）：id(8) + name(16 String) + age(4 int)
     */
    public static class SchemaV1 extends DsTableAdapter {
        @DsField(name = "name", length = 16)
        public String name;
        @DsField(name = "age", length = 4)
        public int age;
        public SchemaV1() {}
    }

    /**
     * Schema V2（5个字段 total=68B）：V1 + newCol(8 long defaultValue=42) + note(32 String defaultValue="hello-online")
     * 后面新增两个列，V1 数据读回来时这两个字段应该自动 fallback 到 default 值
     */
    public static class SchemaV2 extends DsTableAdapter {
        @DsField(name = "name", length = 16)
        public String name;
        @DsField(name = "age", length = 4)
        public int age;
        @DsField(name = "newCol", length = 8, defaultValue = "42")
        public long newCol;
        @DsField(name = "note", length = 32, defaultValue = "hello-online")
        public String note;
        public SchemaV2() {}
    }

    /**
     * 场景 ①：旧 schema V1 字节数据 → Schema V2 load() 读取 → 新增列自动 fallback @DsField.defaultValue
     */
    @Test
    public void testV1AddColumnV2ReadFallbackDefault() throws Exception {
        SchemaV1 v1 = new SchemaV1();
        v1.setId(1001L);
        v1.name = "alice";
        v1.age = 25;
        ByteBuffer v1Bytes = v1.toBytes();
        int v1Len = v1Bytes.remaining();

        Assert.assertEquals(28, v1Len);

        SchemaV2 v2fromV1 = new SchemaV2();
        v2fromV1.load(v1Bytes);

        Assert.assertEquals(Long.valueOf(1001L), v2fromV1.getId());
        Assert.assertEquals("alice", v2fromV1.name);
        Assert.assertEquals(25, v2fromV1.age);
        Assert.assertEquals(42L, v2fromV1.newCol);
        Assert.assertEquals("hello-online", v2fromV1.note);
    }

    /**
     * 场景 ②：Schema V2 写所有字段 → toBytes → Schema V2 load 回读 → 所有字段一致
     */
    @Test
    public void testV2WriteAndReadBackFull() throws Exception {
        SchemaV2 v2 = new SchemaV2();
        v2.setId(2002L);
        v2.name = "bob";
        v2.age = 30;
        v2.newCol = 999999999999L;
        v2.note = "this is v2 note";
        ByteBuffer bytes = v2.toBytes();
        int v2Len = bytes.remaining();
        Assert.assertEquals(68, v2Len);

        SchemaV2 restored = new SchemaV2();
        restored.load(bytes);
        Assert.assertEquals(Long.valueOf(2002L), restored.getId());
        Assert.assertEquals("bob", restored.name);
        Assert.assertEquals(30, restored.age);
        Assert.assertEquals(999999999999L, restored.newCol);
        Assert.assertEquals("this is v2 note", restored.note);
    }

    /**
     * 场景 ③：createMeta rowLength mismatch 不再直接 throw IllegalStateException
     */
    @Test
    public void testCreateMetaRowLengthMismatchNoThrow() throws Exception {
        DsDatabaseLocal db = new DsDatabaseLocal(tmpRoot);
        Throwable err = null;
        try {
            @SuppressWarnings("unchecked")
            Class<? extends DsTableAdapter> c1 = (Class<? extends DsTableAdapter>) Class.forName(SchemaV1.class.getName());
            Object m1 = invokePrivate(db, "metaOf", new Class[]{Class.class}, new Object[]{c1});
            Assert.assertNotNull(m1);

            @SuppressWarnings("unchecked")
            Class<? extends DsTableAdapter> c2 = (Class<? extends DsTableAdapter>) Class.forName(SchemaV2.class.getName());
            Object m2 = invokePrivate(db, "metaOf", new Class[]{Class.class}, new Object[]{c2});
            Assert.assertNotNull(m2);
        } catch (Throwable t) {
            err = t;
        } finally {
            DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        }
        Assert.assertNull("createMeta rowLength mismatch 不应该 throw IllegalStateException (兼容模式已启用)", err);
    }

    /**
     * 场景 ④：4 个人工重试按钮（forceApplyOnlineDDL / cancelOnlineDDL / forceRollbackSchema / forceResetSchemaMetaForTest）全部公开可调用，永不禁用无状态门禁
     */
    @Test
    public void testManualRetryButtonsEnabledNoGate() throws Exception {
        DsDatabaseLocal db = new DsDatabaseLocal(tmpRoot);
        Throwable err = null;
        try {
            db.forceApplyOnlineDDL(SchemaV2.class);
            db.cancelOnlineDDL(SchemaV2.class);
            db.forceRollbackSchema(SchemaV2.class);
            File indexDir = new File(tmpRoot, "indexes");
            if (!indexDir.exists()) indexDir.mkdirs();
            new File(indexDir, "ids_abcdef12.set").createNewFile();
            new File(indexDir, "schema_123456.dat").createNewFile();
            DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        } catch (Throwable t) {
            err = t;
        }
        Assert.assertNull("人工重试按钮 4 个全部 public 无门禁不应该 throw", err);
    }

    private static Object invokePrivate(Object target, String methodName, Class<?>[] types, Object[] args) throws Exception {
        java.lang.reflect.Method m = target.getClass().getDeclaredMethod(methodName, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }
}
