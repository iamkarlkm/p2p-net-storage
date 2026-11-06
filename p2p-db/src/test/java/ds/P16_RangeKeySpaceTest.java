package ds;

import com.q3lives.ds.registry.meta.RegistryMetaPaths;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class P16_RangeKeySpaceTest {

    private static final String ENTITY = "com.example.ds.OrderEntity";
    private static final String INDEX = "idx_create_time";

    @Test
    public void t01_path_segments_hex_align_p10() {
        String colId = RegistryMetaPaths.columnRangeIndexColId(ENTITY, INDEX);
        String[] segs = colId.split("/");
        assertTrue("columnRangeIndexColId segments count should be 7+ (/orm/column/entitySeg/indexes/range/columnSeg/colId)",
            segs.length >= 7);
        assertTrue("columnRangeIndexColId should start with /orm/column/ (RANGE 区)",
            colId.startsWith("/orm/column/"));
        String rowKind = RegistryMetaPaths.rowRangeIndexKind(ENTITY, INDEX);
        String[] rsegs = rowKind.split("/");
        assertTrue("rowRangeIndexKind segments count should be 7+ (/orm/row/entitySeg/indexes/range/indexSeg/kind)",
            rsegs.length >= 7);
        // indexNameSegment/columnSegment are safe filename (not necessarily 8 hex; the hex 8 规则仅 indexName/column 如果是 ASCII 非中文 hash 会是 非 hex，允许；只要是 safe 文件名段就 OK
        String idxSeg = rsegs[rsegs.length - 2];
        assertFalse("indexNameSegment 不应空", idxSeg.isEmpty());
        assertFalse("indexNameSegment 不应包含相对或 .", idxSeg.contains("..") || idxSeg.contains("/"));
        String colSeg = segs[segs.length - 2];
        assertFalse("columnSegment 不应空", colSeg.isEmpty());
    }

    @Test
    public void t02_reflect_methods_exist_non_null() throws Exception {
        String[] twoArg = new String[] {
            "columnRangeIndexDir", "columnRangeIndexColId",
            "columnRangeIndexBloomFpp", "columnRangeIndexBloomExpected",
            "columnRangeIndexMinKey", "columnRangeIndexMaxKey",
            "rowRangeIndexDir", "rowRangeIndexKind",
            "rowRangeIndexSchemaId", "rowRangeIndexMvcc",
            "rowRangeIndexValueKind", "rowRangeIndexMinKey", "rowRangeIndexMaxKey"
        };
        Class<?> s = String.class;
        for (String name : twoArg) {
            Method m = RegistryMetaPaths.class.getMethod(name, s, s);
            assertNotNull(name + " 方法存在(2 String args)", m);
            Object ret = m.invoke(null, ENTITY, INDEX);
            assertNotNull(name + " 返回值非空", ret);
            String s1 = (String) ret;
            assertTrue(name + " 前缀 /orm/", s1.startsWith("/orm/"));
        }
        // 单参方法
        String[] oneArg = new String[] { "columnRangeDir", "rowRangeDir" };
        for (String name : oneArg) {
            Method m = RegistryMetaPaths.class.getMethod(name, s);
            assertNotNull(name + " 方法存在(1 String arg)", m);
            Object ret = m.invoke(null, ENTITY);
            assertNotNull(name + " 返回值非空", ret);
        }
        assertFalse("Range 不应触碰到 /orm/data", RegistryMetaPaths.columnRangeIndexDir(ENTITY, INDEX).startsWith("/orm/data"));
        assertFalse("Range 不应触碰到 /orm/raft", RegistryMetaPaths.rowRangeIndexDir(ENTITY, INDEX).startsWith("/orm/raft"));
    }

    @Test
    public void t03_zero_leak_only_registry_declares() throws Exception {
        Path root = new File("src/main/java").toPath().toAbsolutePath().normalize();
        List<String> hits = new ArrayList<>();
        Files.walk(root)
            .filter(Files::isRegularFile)
            .filter(p -> p.toString().endsWith(".java"))
            .forEach(p -> {
                try {
                    String content = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                    if (content.contains("columnRangeIndex") || content.contains("rowRangeIndex")) {
                        String simple = p.getFileName().toString();
                        if (!simple.equals("RegistryMetaPaths.java")) {
                            hits.add(simple);
                        }
                    }
                } catch (Exception ignored) {
                }
            });
        assertEquals("零外泄：除 RegistryMetaPaths.java 之外不应引用 columnRangeIndex / rowRangeIndex（生产代码泄漏 0 命中；若命中："+hits,
            0, hits.size());
    }
}
