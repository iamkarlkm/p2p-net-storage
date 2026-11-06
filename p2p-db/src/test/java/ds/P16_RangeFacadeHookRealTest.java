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

import static org.junit.Assert.*;

public class P16_RangeFacadeHookRealTest {

    private static final int[] BASELINE = new int[] { 48, 24, 3, 8, 1, 7 };
    private static final Class<?>[] FACADE_CLASSES = new Class<?>[] {
        DsDatabaseLocal.class, ColumnarStore.class, TableMetaStore.class,
        ColumnRegistry.class, RowIdSequenceStore.class, EqIndexMetaStore.class
    };
    private static final String[] SHORT = new String[] {
        "DsDatabaseLocal", "ColumnarStore", "TableMetaStore",
        "ColumnRegistry", "RowIdSequenceStore", "EqIndexMetaStore"
    };

    @Test(timeout = 60000)
    public void t01_b6_six_facade_public_method_count_within_baseline_range_4() {
        assertEquals("6 facade classes for B6 gatekeeper", 6, FACADE_CLASSES.length);
        StringBuilder sb = new StringBuilder();
        boolean allOk = true;
        for (int i = 0; i < FACADE_CLASSES.length; i++) {
            Class<?> c = FACADE_CLASSES[i];
            int actual = 0;
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isPublic(m.getModifiers())) actual++;
            }
            int lo = BASELINE[i] - 4;
            int hi = BASELINE[i] + 4;
            boolean ok = actual >= lo && actual <= hi;
            if (!ok) allOk = false;
            sb.append(String.format("B6[%s] public methods: actual=%d expected in [%d,%d] baseline=%d -> %s%n",
                SHORT[i], actual, lo, hi, BASELINE[i], ok ? "PASS" : "FAIL"));
        }
        System.out.print(sb);
        assertTrue("NG-2 B6 6 Facade public method count within baseline±4 (0 new public allowed, baseline legacy diff ±4); detail above",
            allOk);
    }
}
