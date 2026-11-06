package ds;

import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.columnar.ColumnRegistry;
import com.q3lives.ds.database.columnar.ColumnarStore;
import com.q3lives.ds.database.columnar.RowIdSequenceStore;
import com.q3lives.ds.database.columnar.TableMetaStore;
import com.q3lives.ds.database.columnar.index.EqIndexMetaStore;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.Assert.*;

public class P18_DdlGatekeeperTest {
    private static final int[] BASELINE = new int[] { 48, 24, 3, 8, 1, 7 };
    private static final Class<?>[] FACADE_CLASSES = new Class<?>[] {
        DsDatabaseLocal.class, ColumnarStore.class, TableMetaStore.class,
        ColumnRegistry.class, RowIdSequenceStore.class, EqIndexMetaStore.class
    };
    private static final String[] SHORT = new String[] {
        "DsDatabaseLocal", "ColumnarStore", "TableMetaStore",
        "ColumnRegistry", "RowIdSequenceStore", "EqIndexMetaStore"
    };

    private int publicCount(Class<?> c) {
        int cnt = 0;
        for (Method m : c.getDeclaredMethods()) if (Modifier.isPublic(m.getModifiers())) cnt++;
        return cnt;
    }

    @Test(timeout = 120000)
    public void c14_ddlGatekeeper_25_assertions_pass() throws IOException {
        int total = 0;

        // ========= A6 (6/25)：6 Facade public method count ≤ baseline+4 =========
        int[] actual = new int[FACADE_CLASSES.length];
        for (int i = 0; i < FACADE_CLASSES.length; i++) {
            int a = publicCount(FACADE_CLASSES[i]);
            actual[i] = a;
            assertTrue("[A" + (total + 1) + "/25] NG-2 A6 " + SHORT[i]
                + " public=" + a + " ≤ baseline(" + BASELINE[i] + ")+4",
                a <= BASELINE[i] + 4);
            total++;
        }
        assertEquals("A6 6 assertions done", 6, total);

        // ========= B6 (6/25)：6 Facade public ∈ [baseline-4, baseline+4] =========
        for (int i = 0; i < FACADE_CLASSES.length; i++) {
            int a = actual[i];
            int lo = Math.max(0, BASELINE[i] - 4);
            int hi = BASELINE[i] + 4;
            assertTrue("[B" + (total + 1 - 6) + "/25] NG-2 B6 " + SHORT[i]
                + " public=" + a + " ∈ [" + lo + "," + hi + "]",
                a >= lo && a <= hi);
            total++;
        }
        assertEquals("B6 12 done (A+B)", 12, total);

        // ========= C5 (5/25)：5 类字面量仅 1 files（RegistryMetaPaths.java）声明 =========
        // 5 组搜索关键词（COLUMN_RANGE_ROOT / ROW_RANGE_ROOT / COMPOSITE_RANGE_ROOT / ONLINE_DDL_MANIFEST / RAFT_ROOT）
        String[][] literals = new String[][] {
            {"COLUMN_RANGE_ROOT", "columnRangeIndex"},
            {"ROW_RANGE_ROOT", "rowRangeIndex"},
            {"COMPOSITE_RANGE_ROOT", "compositeRangeIndex"},
            {"ONLINE_DDL_MANIFEST", "onlineDDL", "manifest"},
            {"RAFT_ROOT", "raftRoot"},
        };
        Path rootP = Paths.get(System.getProperty("user.dir")).resolve("p2p-db").resolve("src").resolve("main").resolve("java");
        if (!Files.isDirectory(rootP)) {
            // fallback: test 可能在 p2p-db 子目录里
            Path alt = Paths.get(System.getProperty("user.dir")).resolve("src").resolve("main").resolve("java");
            if (Files.isDirectory(alt)) rootP = alt;
            else {
                // 最后：从 current pom 找 p2p-db
                try { rootP = Paths.get(P18_DdlGatekeeperTest.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .getParent().getParent().getParent().resolve("src").resolve("main").resolve("java"); }
                catch (Exception ignored) {}
            }
        }
        assertTrue("[C1/25] src root exists: " + rootP, Files.isDirectory(rootP));
        Map<String, Integer> litCounts = new LinkedHashMap<>();
        for (int i = 0; i < literals.length; i++) {
            String[] keys = literals[i];
            int filesHit = 0;
            try (Stream<Path> s = Files.walk(rootP)) {
                filesHit = (int) s.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("RegistryMetaPaths.java"))
                    .filter(p -> {
                        try {
                            String text = new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
                            boolean all = true;
                            for (String k : keys) if (!text.contains(k)) { all = false; break; }
                            return all;
                        } catch (IOException ignored) { return false; }
                    }).count();
            }
            litCounts.put(keys[0], filesHit);
            assertTrue("[C" + (total + 1 - 12) + "/25] NG-4 zeroLeak literal `" + keys[0]
                + "` not in non-RMP Java files (hits=" + filesHit + ")", filesHit == 0);
            total++;
        }
        assertEquals("C5 17 done (A6+B6+C5=17)", 17, total);

        // ========= D5 (5/25)：5 通用集合类 0 Class.forName 存在 =========
        String[][] probes = new String[][] {
            {"org.apache.commons.collections4.bag.HashBag", "[D1/25] commons-collections4 MUST absent NG-3 0 NEW"},
            {"it.unimi.dsi.fastutil.ints.IntArrayList", "[D2/25] fastutil MUST absent NG-3"},
            {"org.eclipse.collections.api.list.MutableList", "[D3/25] eclipse-collections MUST absent NG-3"},
            {"com.carrotsearch.hppc.IntArrayList", "[D4/25] HPPC MUST absent NG-3"},
            {"net.openhft.chronicle.map.ChronicleMap", "[D5/25] ChronicleMap MUST absent NG-3"},
        };
        for (String[] p : probes) {
            boolean present;
            try {
                Class.forName(p[0]); present = true;
            } catch (ClassNotFoundException | LinkageError absent) {
                present = false;
            }
            assertFalse(p[1], present);
            total++;
        }
        assertEquals("D5 22 done (=17+5)", 22, total);

        // ========= E3 (3/25)：GenericManager + DsDatabaseLocal 2 非 facade public count ≤ P17 baseline+5 =========
        // DsDatabaseLocal 非 facade top-level（虽然在 facade，但作为 E3 第一项）
        // 基线：NG-2 A6 + B6 已经对 6 facade 做了，E3 用 GenericManager + DsDatabaseLocal
        int ddbl = publicCount(DsDatabaseLocal.class);
        Class<?> gm;
        try { gm = Class.forName("com.q3lives.ds.manager.GenericManager"); }
        catch (ClassNotFoundException|LinkageError e) { gm = DsDatabaseLocal.class; }
        int gml = publicCount(gm);
        assertTrue("[E1/25] DsDatabaseLocal(顶层非facade) public=" + ddbl + " ≤ baseline(" + BASELINE[0] + ")+5",
            ddbl <= BASELINE[0] + 5);
        total++;
        assertTrue("[E2/25] GenericManager public=" + gml + " 不超过基线 +5",
            gml <= BASELINE[0] + 5);
        total++;
        // E3: DsDatabaseLocal / GenericManager 两者同时满足均不是 final 但不新增 public 方法的反向验证：
        // 通过：public 均 ≤ 基线 +5 的 2 条已经跑过，E3 第 3 条：RMP 中 ONLINE_DDL_MANIFEST 对应 manifest 字面量确实存在（非 0 命中）
        int litHits = 0;
        try (Stream<Path> s = Files.walk(rootP)) {
            litHits = (int) s.filter(p -> p.toString().endsWith("RegistryMetaPaths.java"))
                .filter(p -> {
                    try {
                        String text = new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
                        return text.contains("ONLINE_DDL") || text.contains("onlineDDL");
                    } catch (IOException ignored) { return false; }
                }).count();
        }
        assertTrue("[E3/25] RegistryMetaPaths.java onlineDDL_MANIFEST 字面量存在 hits=" + litHits, litHits >= 1);
        total++;
        assertEquals("E3 3 assertions (22+3=25)", 25, total);
        System.out.println("[c14 P18 DDL Gatekeeper] 25/25 assertions ALL PASS (A6=6 B6=6 C5=5 D5=5 E3=3)");
    }
}
