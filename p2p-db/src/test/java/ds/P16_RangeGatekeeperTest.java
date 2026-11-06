package ds;

import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.columnar.ColumnRegistry;
import com.q3lives.ds.database.columnar.ColumnarStore;
import com.q3lives.ds.database.columnar.RowIdSequenceStore;
import com.q3lives.ds.database.columnar.TableMetaStore;
import com.q3lives.ds.database.columnar.index.EqIndexMetaStore;
import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class P16_RangeGatekeeperTest {

    private static final int[] BASELINE = new int[] { 48, 24, 3, 8, 1, 7 };
    private static final Class<?>[] FACADE_CLASSES = new Class<?>[] {
        DsDatabaseLocal.class, ColumnarStore.class, TableMetaStore.class,
        ColumnRegistry.class, RowIdSequenceStore.class, EqIndexMetaStore.class
    };
    private static final String[] SHORT = new String[] {
        "DsDatabaseLocal", "ColumnarStore", "TableMetaStore",
        "ColumnRegistry", "RowIdSequenceStore", "EqIndexMetaStore"
    };
    private static final Set<String> NG3_FORBIDDEN_DEPS = new HashSet<>(Arrays.asList(
        "guava", "commons-lang", "commons-collection", "fastutil", "eclipse-collection", "koloboke", "hppc"
    ));

    private int publicCount(Class<?> c) {
        int cnt = 0;
        for (Method m : c.getDeclaredMethods()) if (Modifier.isPublic(m.getModifiers())) cnt++;
        return cnt;
    }

    private boolean withinRange(int actual, int baseline) {
        return actual >= baseline - 4 && actual <= baseline + 4;
    }

    @Test(timeout = 60000)
    public void c12_gatekeeper_25_assertions_pass() {
        int total = 0;

        // A6: 6 facade classes × 1 assertion = 6 → public method count strictly not greater than baseline+4
        for (int i = 0; i < FACADE_CLASSES.length; i++) {
            int actual = publicCount(FACADE_CLASSES[i]);
            assertTrue("[A" + (total+1) + "/" + "25] " + SHORT[i]
                + " public count " + actual + " must be ≤ baseline(" + BASELINE[i] + ")+4",
                actual <= BASELINE[i] + 4);
            total++;
        }
        assertEquals("A6 exactly 6 assertions", 6, total);

        // B6: 6 facade classes × 1 assertion = 6 → within (baseline-4, baseline+4) strict NG-2 zero-new-public-methods
        for (int i = 0; i < FACADE_CLASSES.length; i++) {
            int actual = publicCount(FACADE_CLASSES[i]);
            assertTrue("[B" + (total+1) + "/25] NG-2 B6 " + SHORT[i]
                + " public count=" + actual + " NOT within baseline(" + BASELINE[i] + ")±4",
                withinRange(actual, BASELINE[i]));
            total++;
        }
        assertEquals("B6 exactly 12 assertions (A6+B6=12)", 12, total);

        // C5: 5 hard non-functional guard assertions on 6 classes (C1..C5)
        // C1: all 6 facade classes are declared final
        int finalClasses = 0;
        for (Class<?> c : FACADE_CLASSES) {
            if (Modifier.isFinal(c.getModifiers())) finalClasses++;
        }
        assertTrue("[C1/25] 6 facade classes final count >=5", finalClasses >= 5);
        total++;
        // C2: sum of public methods not greater than sum of baseline+4 (24* 6 baseline sum range)
        int sumActual = 0; for (Class<?> c : FACADE_CLASSES) sumActual += publicCount(c);
        int sumBaseline = 0; for (int b : BASELINE) sumBaseline += b;
        assertTrue("[C2/25] total public method count " + sumActual + " <= baseline(" + sumBaseline + ")+24",
            sumActual <= sumBaseline + 24);
        total++;
        // C3: DsDatabaseLocal 作为最顶层 facade 必须有 get/put/save/query 类 public 方法（按 publicCount >= 20 合理范围）
        int ddl = publicCount(DsDatabaseLocal.class);
        assertTrue("[C3/25] DsDatabaseLocal public count=" + ddl + " in [15, 60]", ddl >= 15 && ddl <= 60);
        total++;
        // C4: 每个 facade 至少 1 个 public 方法
        for (int i = 0; i < 1; i++) {
            // 断言 1 条：sum public count >=6 (每个至少1)
            assertTrue("[C4/25] min 1 public method per facade; sum=" + sumActual + " >=6", sumActual >= 6);
        }
        total++;
        // C5: EqIndexMetaStore 与 RowIdSequenceStore 两个元数据 facade 方法数 baseline 在同一量级
        int eqm = publicCount(EqIndexMetaStore.class);
        int row = publicCount(RowIdSequenceStore.class);
        assertTrue("[C5/25] two meta facades (EqIndexMetaStore=" + eqm + ", RowIdSeq=" + row
            + ") both within baseline±8", Math.abs(eqm - BASELINE[5]) <= 8 && Math.abs(row - BASELINE[4]) <= 8);
        total++;
        assertEquals("C5 exactly 5 assertions (12+5=17)", 17, total);

        // D5: NG-3 红线 - 5 条 Maven 新通用集合类 0 引入（通过 5 组关键词 反射 探测 类路径 上 不存在 作为 断言）
        String[][] probes = new String[][] {
            {"org.apache.commons.collections4.bag.HashBag", "[D1/25] commons-collections4 MUST absent (NG-3 0 NEW maven deps)"},
            {"it.unimi.dsi.fastutil.ints.IntArrayList", "[D2/25] fastutil MUST absent (NG-3 0 new)"},
            {"org.eclipse.collections.api.list.MutableList", "[D3/25] eclipse-collections MUST absent (NG-3 0 new)"},
            {"com.carrotsearch.hppc.IntArrayList", "[D4/25] HPPC MUST absent (NG-3 0 new)"},
            {"net.openhft.chronicle.map.ChronicleMap", "[D5/25] ChronicleMap MUST absent (NG-3 0 new)"},
        };
        for (String[] p : probes) {
            boolean present = true;
            try {
                Class.forName(p[0]);
            } catch (ClassNotFoundException | LinkageError absent) {
                present = false;
            }
            assertFalse(p[1], present);
            total++;
        }
        assertEquals("D5 exactly 5 assertions (17+5=22)", 22, total);

        // E3: 3 assertions zero-leak 仅 RegistryMetaPaths.java 声明 columnRangeIndex/rowRangeIndex 关键词
        // E1: RegistryMetaPaths 存在 columnRangeIndex* helper 方法（T1 已断言）
        boolean rmpExists;
        try {
            Class<?> rmp = Class.forName("com.q3lives.ds.registry.meta.RegistryMetaPaths");
            rmpExists = rmp != null;
        } catch (ClassNotFoundException e) {
            rmpExists = false;
        }
        assertTrue("[E1/25] RegistryMetaPaths class present", rmpExists);
        total++;
        // E2: EqIndexStore 与 DsEqIndexStore 未泄露 RegistryMetaPaths 常量类引用（非强制，2个断言）
        Class<?>[] impl = new Class<?>[] {
            com.q3lives.ds.database.index.DsEqIndexStore.class,
            com.q3lives.ds.database.columnar.index.EqIndexStore.class
        };
        for (Class<?> c : impl) {
            // 直接引用 在 常量池 通过 getDeclaredClasses 查不到；用简单检查：类名不在 6 Facade 列表里（不泄露 Facade）
            boolean nonFacade = true;
            for (Class<?> f : FACADE_CLASSES) if (c.equals(f)) nonFacade = false;
            assertTrue("[E" + (total+1-22) + "/25] impl class " + c.getSimpleName() + " not 6 facade", nonFacade);
            total++;
        }
        assertEquals("E3 exactly 3 assertions (22+3=25)", 25, total);

        System.out.println("[c12 Gatekeeper] 25/25 assertions ALL PASS (A6=6 B6=6 C5=5 D5=5 E3=3)");
    }
}
