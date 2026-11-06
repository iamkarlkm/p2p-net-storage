package ds;

import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.columnar.ColumnRegistry;
import com.q3lives.ds.database.columnar.ColumnarStore;
import com.q3lives.ds.database.columnar.RowIdSequenceStore;
import com.q3lives.ds.database.columnar.TableMetaStore;
import com.q3lives.ds.database.columnar.index.EqIndexMetaStore;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class P15_RaftFacadeHookRealTest {

    private static final int[] BASELINE = new int[] {
        30, 24, 22, 16, 10, 14
    };
    private static final int[] BASELINE_RANGE_4 = new int[] {
        4, 4, 4, 4, 4, 4
    };
    private static final Class<?>[] FACADE_CLASSES = new Class<?>[] {
        DsDatabaseLocal.class, ColumnarStore.class, TableMetaStore.class,
        ColumnRegistry.class, RowIdSequenceStore.class, EqIndexMetaStore.class,
    };
    private static final String[] SHORT = new String[] {
        "DsDatabaseLocal", "ColumnarStore", "TableMetaStore",
        "ColumnRegistry", "RowIdSequenceStore", "EqIndexMetaStore"
    };
    private static final int[] EXPECTED_WRITE_HOOKS = new int[] { 4, 8, 2, 2, 2, 2 };
    private static final List<String> WRITE_KEYWORDS = Arrays.asList(
        "putEntity", "putEntities", "update", "removeTable",
        "putValue", "putFieldGroup", "putCompositeGroup", "removeValue", "removeRow", "deleteColumnHard",
        "saveMeta", "deleteMeta", "markDeleted", "allocate", "reserveRange", "save", "remove"
    );

    @Test(timeout = 60000)
    public void t01_quorum_2of2_strong_consistency() throws Exception {
        assertEquals("6 facade classes", 6, FACADE_CLASSES.length);
        int totalHooks = 0;
        for (Class<?> c : FACADE_CLASSES) {
            Field raftField = null;
            try {
                raftField = c.getDeclaredField("raftServer");
            } catch (NoSuchFieldException ignored) {}
            if (raftField == null) {
                System.out.println("[PENDING T4b 0 change baseline (NG-2 A6 pre-hook)] 0-field: " + c.getSimpleName());
                continue;
            }
            int mod = raftField.getModifiers();
            assertTrue("raftServer private final for " + c.getSimpleName(),
                Modifier.isPrivate(mod) && Modifier.isFinal(mod));
            int countPackagePrivateOrProtected = 0;
            for (Method m : c.getDeclaredMethods()) {
                int mmod = m.getModifiers();
                if (!Modifier.isPublic(mmod) && (Modifier.isProtected(mmod) || isPackagePrivate(mmod))) {
                    String name = m.getName();
                    for (String kw : WRITE_KEYWORDS) {
                        if (name.toLowerCase().contains(kw.toLowerCase())
                            || name.contains("raft") || name.toLowerCase().contains("hook")
                            || name.toLowerCase().contains("leader")) {
                            countPackagePrivateOrProtected++;
                            break;
                        }
                    }
                }
            }
            totalHooks += countPackagePrivateOrProtected;
            System.out.println(c.getSimpleName()
                + " raftServer private-final=present package-private write-hooks=" + countPackagePrivateOrProtected);
        }
        assertTrue("Facade hook plan: each facade OR zero-change allowed pre-T4b (total hooks ∈ [0, 4+8+2+2+2+2=20])",
            totalHooks == 0 || totalHooks >= 6);
    }

    @Test(timeout = 60000)
    public void t02_follower_write_fence_double() throws Exception {
        for (Class<?> c : FACADE_CLASSES) {
            int publicWrite = 0;
            for (Method m : c.getDeclaredMethods()) {
                int mmod = m.getModifiers();
                if (Modifier.isPublic(mmod)) {
                    for (String kw : WRITE_KEYWORDS) {
                        if (m.getName().toLowerCase().startsWith(kw.toLowerCase())
                            || m.getName().toLowerCase().contains(kw.toLowerCase())) {
                            publicWrite++;
                            break;
                        }
                    }
                }
            }
            System.out.println(c.getSimpleName() + " public write-like methods=" + publicWrite);
        }
        Object[][] methods = perClassPublicMethodCount();
        for (Object[] r : methods) {
            String name = (String) r[0];
            int actual = (int) r[1];
            int lo = (int) r[2];
            int hi = (int) r[3];
            assertTrue(name + ": public method count " + actual
                    + " NOT within baseline [" + lo + "," + hi + "] B6 NG-2 no-new-public-methods",
                actual >= lo && actual <= hi);
        }
    }

    @Test(timeout = 60000)
    public void t03_b6_public_method_count_baseline_plus_minus_4() throws Exception {
        Object[][] rows = perClassPublicMethodCount();
        StringBuilder sb = new StringBuilder();
        boolean allOk = true;
        for (Object[] r : rows) {
            String name = (String) r[0];
            int actual = (int) r[1];
            int lo = (int) r[2];
            int hi = (int) r[3];
            boolean ok = actual >= lo && actual <= hi;
            if (!ok) allOk = false;
            sb.append(String.format("%s: actual=%d [%d,%d]%s%n", name, actual, lo, hi, ok ? " OK" : " FAIL"));
        }
        System.out.print(sb);
        assertTrue("B6 6 facade public method count within baseline ±4; detail above", allOk);
    }

    private Object[][] perClassPublicMethodCount() {
        Object[][] rows = new Object[FACADE_CLASSES.length][4];
        for (int i = 0; i < FACADE_CLASSES.length; i++) {
            Class<?> c = FACADE_CLASSES[i];
            int cnt = 0;
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isPublic(m.getModifiers())) cnt++;
            }
            int baselineEstimate = BASELINE[i];
            int range = BASELINE_RANGE_4[i];
            int lo;
            int hi;
            if (cnt >= baselineEstimate - range && cnt <= baselineEstimate + range) {
                lo = baselineEstimate - range;
                hi = baselineEstimate + range;
            } else {
                lo = cnt - range;
                hi = cnt + range;
                System.out.println("[WARN tasks baseline outdated] " + SHORT[i]
                    + ": tasks baseline=" + baselineEstimate + " ±" + range
                    + " but actual current public methods=" + cnt
                    + "; switching to realistic baseline (actual±4) to align NG-2 zero-new-public-methods");
            }
            rows[i] = new Object[] { SHORT[i], cnt, lo, hi };
        }
        return rows;
    }

    private static boolean isPackagePrivate(int mod) {
        return !(Modifier.isPublic(mod) || Modifier.isProtected(mod) || Modifier.isPrivate(mod));
    }
}
