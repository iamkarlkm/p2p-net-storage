package ds;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.registry.meta.RegistryMetaPaths;

/**
 * P17 T1: 复合多列 Composite Range KeySpace 测试（3 TR）。
 *
 * <p>硬约束：
 * <ul>
 *   <li>t01: COMPOSITE_RANGE_ROOT + 9 compositeRangeIndex* helper 反射可调用，首段前缀 /orm/index</li>
 *   <li>t02: 非法参数 10 条 IAE 全实锤（entityClass blank / indexName blank / segment<0 / kind blank /
 *           payloadSize<0 / bucketId<0 / bloomFilterId<0 / statType blank / metaKey blank）</li>
 *   <li>t03: 零外泄，main 源码 grep COMPOSITE_RANGE_ROOT / compositeRangeIndex 仅 RegistryMetaPaths 命中</li>
 * </ul>
 */
public class P17_CompositeKeySpaceTest {

    private static final String ENTITY = "com.example.CompositeOrderUser";
    private static final String INDEX = "idx_abc";

    private File tmpRoot;
    private String oldHome;

    @Before
    public void before() throws IOException {
        tmpRoot = Files.createTempDirectory("dsdb_p17_composite_").toFile();
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());
    }

    @After
    public void after() {
        try {
            deleteRecursive(tmpRoot);
        } catch (Exception ignore) {}
        if (oldHome == null) System.clearProperty("p2p.db.home");
        else System.setProperty("p2p.db.home", oldHome);
    }

    private static void deleteRecursive(File f) {
        if (f == null) return;
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) for (File c : ch) deleteRecursive(c);
        }
        f.delete();
    }

    // ---- TR-1.1 t01: 10 个反射 项 + 路径段 /orm/index ----
    @Test
    public void t01_compositeHelpersExistAndStartWithOrmIndex() throws Exception {
        Assert.assertTrue("COMPOSITE_RANGE_ROOT exists",
                RegistryMetaPaths.COMPOSITE_RANGE_ROOT.startsWith("/orm/index"));
        List<String> helpers = Arrays.asList(
                "compositeRangeIndexRoot",        // 1
                "compositeRangeIndexDir",         // 2
                "compositeRangeIndexSegment",     // 3
                "compositeRangeIndexKind",        // 4
                "compositeRangeIndexPayload",     // 5
                "compositeRangeIndexBucket",      // 6
                "compositeRangeIndexBloom",       // 7
                "compositeRangeIndexStat",        // 8
                "compositeRangeIndexMeta"         // 9
        );
        Assert.assertEquals("helpers count=9", 9, helpers.size());

        Class<?> c = RegistryMetaPaths.class;
        for (String name : helpers) {
            Method m = findMethod(c, name);
            Assert.assertNotNull("helper 存在: " + name, m);
            Object result = invokeHelper(m);
            Assert.assertNotNull("helper 返回非空: " + name, result);
            String s = (String) result;
            Assert.assertTrue("首段前缀 /orm/index: " + name + "=" + s,
                    s.startsWith("/orm/index"));
        }
        // 路径段数 与 列存 range 平级（差≤1）
        int colRangeSegCount = segments(RegistryMetaPaths.columnRangeIndexDir(ENTITY, "col")).length;
        int compDirSegCount = segments(RegistryMetaPaths.compositeRangeIndexDir(ENTITY, INDEX)).length;
        Assert.assertTrue("compositeRangeIndexDir 段数 与 columnRangeIndexDir 平级: col=" + colRangeSegCount
                + " comp=" + compDirSegCount, Math.abs(colRangeSegCount - compDirSegCount) <= 1);
    }

    private static Method findMethod(Class<?> c, String name) {
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name)) return m;
        }
        return null;
    }

    private static Object invokeHelper(Method m) throws Exception {
        int pc = m.getParameterCount();
        Object[] args = new Object[pc];
        Class<?>[] pts = m.getParameterTypes();
        for (int i = 0; i < pc; i++) {
            if (pts[i] == String.class) args[i] = (i == 0) ? ENTITY : (i == 1 ? INDEX : "sample");
            else if (pts[i] == long.class || pts[i] == Long.class) args[i] = 100L;
            else if (pts[i] == int.class || pts[i] == Integer.class) args[i] = 16;
        }
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    private static String[] segments(String path) {
        return path.split("/");
    }

    // ---- TR-1.2 t02: 非法参数 10 条 IAE ----
    @Test
    public void t02_tenIllegalArgumentExceptions() {
        int count = 0;
        try {
            RegistryMetaPaths.compositeRangeIndexRoot(null);
        } catch (IllegalArgumentException e) { if (e.getMessage().contains("entityClass") || e.getMessage().contains("blank")) count++; }
        try {
            RegistryMetaPaths.compositeRangeIndexRoot("  ");
        } catch (IllegalArgumentException e) { if (e.getMessage().contains("entityClass") || e.getMessage().contains("blank")) count++; }
        try {
            RegistryMetaPaths.compositeRangeIndexDir(ENTITY, "");
        } catch (IllegalArgumentException e) { if (e.getMessage().contains("indexName")) count++; }
        try {
            RegistryMetaPaths.compositeRangeIndexSegment(ENTITY, INDEX, -1);
        } catch (IllegalArgumentException e) { if (e.getMessage().contains("segment")) count++; }
        try {
            RegistryMetaPaths.compositeRangeIndexKind(ENTITY, INDEX, null);
        } catch (IllegalArgumentException e) { if (e.getMessage().contains("kind")) count++; }
        try {
            RegistryMetaPaths.compositeRangeIndexPayload(ENTITY, INDEX, -1);
        } catch (IllegalArgumentException e) { if (e.getMessage().contains("payloadSize")) count++; }
        try {
            RegistryMetaPaths.compositeRangeIndexBucket(ENTITY, INDEX, -1);
        } catch (IllegalArgumentException e) { if (e.getMessage().contains("bucketId")) count++; }
        try {
            RegistryMetaPaths.compositeRangeIndexBloom(ENTITY, INDEX, -1);
        } catch (IllegalArgumentException e) { if (e.getMessage().contains("bloomFilterId")) count++; }
        try {
            RegistryMetaPaths.compositeRangeIndexStat(ENTITY, INDEX, "");
        } catch (IllegalArgumentException e) { if (e.getMessage().contains("statType")) count++; }
        try {
            RegistryMetaPaths.compositeRangeIndexMeta(ENTITY, INDEX, "   ");
        } catch (IllegalArgumentException e) { if (e.getMessage().contains("metaKey")) count++; }
        Assert.assertEquals("IAE 非法参数 ≥10 条（实锤每类一条，参数名 带）", 10, count);
    }

    // ---- TR-1.3 t03: 零外泄 grep 仅 RegistryMetaPaths ----
    @Test
    public void t03_zeroLeakOnlyInRegistryMetaPaths() throws Exception {
        // 优先取 user.dir（项目标准根）
        File userDir = new File(System.getProperty("user.dir"));
        File src = new File(userDir, "p2p-db/src/main/java");
        if (!src.exists()) {
            // 再 退回 绝对 项目 根
            src = new File("I:/2026/code/p2p-net-storage/p2p-db/src/main/java");
        }
        Assert.assertTrue("src main java 目录 存在: " + src.getAbsolutePath(), src.exists() && src.isDirectory());
        List<File> matched = new ArrayList<>();
        grepRecursive(src, Pattern.compile("COMPOSITE_RANGE_ROOT|compositeRangeIndex"), matched);
        long regCount = matched.stream()
                .filter(f -> f.getName().equals("RegistryMetaPaths.java")).count();
        Assert.assertTrue("至少 1 处 RegistryMetaPaths 命中（实际 matched=" + matched.size()
                + " files: " + matchedFilesName(matched) + "）", regCount >= 1);
        Assert.assertEquals("零外泄：非测试 main 源码仅 RegistryMetaPaths 命中（实际="
                + matched.size() + " files）", regCount, matched.size());
    }

    private static String matchedFilesName(List<File> files) {
        StringBuilder sb = new StringBuilder();
        for (File f : files) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(f.getName());
        }
        return sb.toString();
    }

    private static void grepRecursive(File f, Pattern pat, List<File> out) throws IOException {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) for (File c : ch) grepRecursive(c, pat, out);
            return;
        }
        if (!f.getName().endsWith(".java")) return;
        String content = new String(Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        if (pat.matcher(content).find()) out.add(f);
    }
}
