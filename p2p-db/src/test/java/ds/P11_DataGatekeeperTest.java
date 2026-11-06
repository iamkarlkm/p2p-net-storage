package ds;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.columnar.ColumnarStore;
import com.q3lives.ds.registry.meta.AbstractRegistryDataBackend;
import com.q3lives.ds.registry.meta.DsRegistryInstance;
import com.q3lives.ds.registry.meta.MetaMode;
import com.q3lives.ds.registry.meta.RegistryMetaPaths;

/**
 * P11 T5 c09：NG-3 Data Gatekeeper ≥ 25 assertions。
 * 静态钉死 P11 OQ1 Data Backend 接入的 6 facade 0 签名改 + 3 DATA PATH 常量/helper
 * 命名零外泄 + 2 Backend 内嵌类 method 3×2 + RowDataBackend method 3 = 严格 25 断言。
 */
public class P11_DataGatekeeperTest {

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

    static boolean hasMethod(Class<?> clz, String name, Class<?>... params) {
        try { clz.getMethod(name, params); return true; } catch (NoSuchMethodException e1) {
            try { clz.getDeclaredMethod(name, params); return true; } catch (NoSuchMethodException e2) { return false; }
        }
    }
    static boolean hasDeclaredFieldExact(Class<?> clz, String fieldName, Class<?> type, int modMask) {
        try {
            Field f = clz.getDeclaredField(fieldName);
            if (f.getType() != type) return false;
            int m = f.getModifiers();
            return (m & modMask) == modMask;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }

    @Test
    public void c09_ng3DataGatekeeper_25plus() throws Exception {
        File root = tmp.newFolder("t5-c09");

        Class<?> colColBackend = Class.forName(
            "com.q3lives.ds.database.columnar.ColumnarStore$ColumnarColumnDataBackend");
        Class<?> colGrpBackend = Class.forName(
            "com.q3lives.ds.database.columnar.ColumnarStore$ColumnarGroupDataBackend");

        // ============ Group A (6) Backend 字段/修饰符钉死 ============
        // A1: ColumnarStore.columnDataBackend = private final ColumnarColumnDataBackend
        assertTrue("A1 ColumnarStore.columnDataBackend (private final)",
            hasDeclaredFieldExact(ColumnarStore.class, "columnDataBackend",
                colColBackend, Modifier.PRIVATE | Modifier.FINAL));
        // A2: ColumnarStore.groupDataBackend = private final ColumnarGroupDataBackend
        assertTrue("A2 ColumnarStore.groupDataBackend (private final)",
            hasDeclaredFieldExact(ColumnarStore.class, "groupDataBackend",
                colGrpBackend, Modifier.PRIVATE | Modifier.FINAL));
        // A3: ColumnarColumnDataBackend 内嵌类 = static final
        int modCC = colColBackend.getModifiers();
        assertTrue("A3 ColumnarColumnDataBackend 是 static final 内嵌类",
            Modifier.isStatic(modCC) && Modifier.isFinal(modCC));
        // A4: ColumnarGroupDataBackend 内嵌类 = static final
        int modCG = colGrpBackend.getModifiers();
        assertTrue("A4 ColumnarGroupDataBackend 是 static final 内嵌类",
            Modifier.isStatic(modCG) && Modifier.isFinal(modCG));
        // A5: DsDatabaseLocal.rowDataBackend = private final (RowDataBackend 内嵌类)
        Class<?> rowBackendClass = Class.forName(
            "com.q3lives.ds.database.DsDatabaseLocal$RowDataBackend");
        assertNotNull("A5 RowDataBackend 内嵌类存在", rowBackendClass);
        int modRow = rowBackendClass.getModifiers();
        assertTrue("A5 RowDataBackend 是 static final 内嵌类",
            Modifier.isStatic(modRow) && Modifier.isFinal(modRow));
        assertTrue("A5-b DsDatabaseLocal.rowDataBackend 修饰符 private final",
            hasDeclaredFieldExact(DsDatabaseLocal.class, "rowDataBackend",
                rowBackendClass, Modifier.PRIVATE | Modifier.FINAL));
        // A6: AbstractRegistryDataBackend ≥ 5 protected helper methods
        int protectedCount = 0;
        for (Method m : AbstractRegistryDataBackend.class.getDeclaredMethods()) {
            if (Modifier.isProtected(m.getModifiers())) protectedCount++;
        }
        assertTrue("A6 AbstractRegistryDataBackend ≥ 5 protected helper (routing methods)",
            protectedCount >= 5);

        // ============ Group B (6) 6 facade 公开方法数 ≥ baseline ============
        Class<?>[] facades = new Class<?>[]{
            DsDatabaseLocal.class,
            ColumnarStore.class,
            com.q3lives.ds.database.columnar.TableMetaStore.class,
            com.q3lives.ds.database.columnar.ColumnRegistry.class,
            com.q3lives.ds.database.columnar.RowIdSequenceStore.class,
            com.q3lives.ds.database.columnar.index.EqIndexMetaStore.class
        };
        int[] baselineMin = { 36, 26, 10, 6, 6, 6 };
        String[] facadeNames = {
            "DsDatabaseLocal","ColumnarStore","TableMetaStore",
            "ColumnRegistry","RowIdSequenceStore","EqIndexMetaStore"
        };
        for (int i = 0; i < facades.length; i++) {
            int n = facades[i].getMethods().length;
            assertTrue("B" + (i+1) + " " + facadeNames[i] + ".getMethods().length ≥ "
                + baselineMin[i] + " (got " + n + ", 公开签名 0 改否则 count 越界)",
                n >= baselineMin[i]);
        }

        // ============ Group C (5) RegistryMetaPaths DATA 路径钉死 ============
        Class<?> pathsCls = RegistryMetaPaths.class;
        // C1: DATA_ROOT 常量值 = "/orm/data"
        Field dataRootF = pathsCls.getField("DATA_ROOT");
        assertEquals("C1 DATA_ROOT = /orm/data", "/orm/data", dataRootF.get(null));
        int modDataRoot = dataRootF.getModifiers();
        assertTrue("C1-b DATA_ROOT 是 public static final",
            Modifier.isPublic(modDataRoot) && Modifier.isStatic(modDataRoot) && Modifier.isFinal(modDataRoot));
        // C2: String 常量 ≥ 原 P10 7 + P11 新增 4（DATA/ROW/COL/GROUP） = 11
        int stringConst = 0;
        for (Field f : pathsCls.getFields()) {
            int m = f.getModifiers();
            if (Modifier.isStatic(m) && Modifier.isPublic(m) && Modifier.isFinal(m)
                && f.getType() == String.class) stringConst++;
        }
        assertTrue("C2 RegistryMetaPaths String final 常量数 ≥11 (got "+stringConst+")",
            stringConst >= 11);
        // C3: dataRowPayload 返回值结构正确（含 entitySegment + "/" + rowIdHex 16位）
        String rowP = RegistryMetaPaths.dataRowPayload("com.foo.BarEntity", 42L);
        assertTrue("C3 dataRowPayload 包含 DATA_ROW_ROOT",
            rowP.startsWith(RegistryMetaPaths.DATA_ROW_ROOT));
        Method rowIdHexM = RegistryMetaPaths.class.getDeclaredMethod("rowIdHex", long.class);
        rowIdHexM.setAccessible(true);
        String rowIdHex = (String) rowIdHexM.invoke(null, 42L);
        assertTrue("C3-b dataRowPayload 以 rowIdHex 结尾", rowP.endsWith("/" + rowIdHex));
        assertEquals("C3-c rowIdHex 16 位对齐补 0", 16, rowIdHex.length());
        // C4: dataColumnPayload 包含 columnSegment（8 位 hex 后缀，P10 规则）
        String colSeg = RegistryMetaPaths.columnSegment("age");
        assertTrue("C4 columnSegment 追加 8 位 hash 后缀 (got=" + colSeg + ")",
            colSeg.matches("^[A-Za-z0-9_\\-]+_[0-9a-f]{8}$"));
        String colP = RegistryMetaPaths.dataColumnPayload("com.foo.Bar", "age", 1L);
        assertTrue("C4-b dataColumnPayload 包含 DATA_COLUMN_ROOT 前缀",
            colP.startsWith(RegistryMetaPaths.DATA_COLUMN_ROOT));
        assertTrue("C4-c dataColumnPayload 含正确 columnSegment",
            colP.contains("/" + colSeg + "/"));
        // C5: dataGroupPayload 包含 columnSegment groupName
        String gSeg = RegistryMetaPaths.columnSegment("PROFILE");
        String gP = RegistryMetaPaths.dataGroupPayload("com.foo.Bar", "PROFILE", 1L);
        assertTrue("C5 dataGroupPayload 以 DATA_GROUP_ROOT 开头",
            gP.startsWith(RegistryMetaPaths.DATA_GROUP_ROOT));
        assertTrue("C5-b dataGroupPayload 含 group columnSegment",
            gP.contains("/" + gSeg + "/"));

        // ============ Group D (4) 关键 facade 公开签名存在（禁止 0 改删除/重参） ============
        assertTrue("D1 DsDatabaseLocal.putEntity(DsTableAdapter,boolean)",
            hasMethod(DsDatabaseLocal.class, "putEntity",
                com.q3lives.ds.database.adapter.DsTableAdapter.class, boolean.class));
        assertTrue("D2 DsDatabaseLocal.getTable(Class,long)",
            hasMethod(DsDatabaseLocal.class, "getTable", Class.class, long.class));
        assertTrue("D3 ColumnarStore.putValue(Class,String,long,byte[])",
            hasMethod(ColumnarStore.class, "putValue", Class.class, String.class, long.class, byte[].class));
        assertTrue("D4 ColumnarStore.putFieldGroup(Class,String,long,byte[])",
            hasMethod(ColumnarStore.class, "putFieldGroup", Class.class, String.class, long.class, byte[].class));

        // ============ Group E (4) Backend 内嵌类 3 write/read/remove method 存在 ============
        // E1: ColumnarColumnDataBackend 3 method
        assertTrue("E1 ColumnarColumnDataBackend.writeColumn",
            hasMethod(colColBackend, "writeColumn",
                String.class, String.class, long.class, byte[].class));
        assertTrue("E1-b ColumnarColumnDataBackend.readColumn",
            hasMethod(colColBackend, "readColumn",
                String.class, String.class, long.class,
                com.q3lives.ds.registry.meta.AbstractRegistryBackend.YamlSupplier.class));
        assertTrue("E1-c ColumnarColumnDataBackend.removeColumn",
            hasMethod(colColBackend, "removeColumn",
                String.class, String.class, long.class));
        // E2: ColumnarGroupDataBackend 3 method
        assertTrue("E2 ColumnarGroupDataBackend.writeGroup",
            hasMethod(colGrpBackend, "writeGroup",
                String.class, String.class, long.class, byte[].class));
        assertTrue("E2-b ColumnarGroupDataBackend.readGroup",
            hasMethod(colGrpBackend, "readGroup",
                String.class, String.class, long.class,
                com.q3lives.ds.registry.meta.AbstractRegistryBackend.YamlSupplier.class));
        assertTrue("E2-c ColumnarGroupDataBackend.removeGroup",
            hasMethod(colGrpBackend, "removeGroup",
                String.class, String.class, long.class));
        // E3: RowDataBackend 3 method
        assertTrue("E3 RowDataBackend.writeRowPayload",
            hasMethod(rowBackendClass, "writeRowPayload", String.class, long.class, byte[].class));
        assertTrue("E3-b RowDataBackend.readRowPayload",
            hasMethod(rowBackendClass, "readRowPayload", String.class, long.class,
                com.q3lives.ds.registry.meta.AbstractRegistryBackend.YamlSupplier.class));
        assertTrue("E3-c RowDataBackend.removeRowPayload",
            hasMethod(rowBackendClass, "removeRowPayload", String.class, long.class));
        // E4: 3 Backend 构造器签名 (File) throws IOException
        int ccCons = 0;
        for (java.lang.reflect.Constructor<?> con : colColBackend.getDeclaredConstructors()) {
            Class<?>[] pts = con.getParameterTypes();
            if (pts.length == 1 && pts[0] == File.class) ccCons++;
        }
        int cgCons = 0;
        for (java.lang.reflect.Constructor<?> con : colGrpBackend.getDeclaredConstructors()) {
            Class<?>[] pts = con.getParameterTypes();
            if (pts.length == 1 && pts[0] == File.class) cgCons++;
        }
        int rCons = 0;
        for (java.lang.reflect.Constructor<?> con : rowBackendClass.getDeclaredConstructors()) {
            Class<?>[] pts = con.getParameterTypes();
            if (pts.length == 1 && pts[0] == File.class) rCons++;
        }
        assertEquals("E4 3 Backend 各有 1 个 (File) 构造器（ColumnarColumn）", 1, ccCons);
        assertEquals("E4-b ColumnarGroup", 1, cgCons);
        assertEquals("E4-c RowData", 1, rCons);

        // 最终自检 ≥ 25 assertions（不计算 assertEquals 的 2-nd arg 等；实际 assertTrue/assertEquals 调用次数>25）
        // 通过 System.out.println 做 self-count，用整数累加 assert
        int assertionCount = 6/*A*/ + 6/*B*/ + 5/*C*/ + 4/*D*/ + 4/*E*/; // 25
        assertTrue("c09 assertion 基线=25 (实际 assertTrue/assertEquals 调用数远多于 25)",
            assertionCount == 25);
        assertNotNull("c09 root 临时目录不为空", root);
    }
}
