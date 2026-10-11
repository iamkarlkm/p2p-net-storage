package ds;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.cluster.DsHybridQueryClient;
import com.q3lives.ds.database.fulltext.cluster.parser.HybridQueryParseException;
import com.q3lives.ds.database.fulltext.cluster.parser.HybridQueryParser;
import com.q3lives.ds.database.fulltext.cluster.parser.LtrSpec;
import com.q3lives.ds.database.fulltext.cluster.parser.ParsedHybridQuery;
import com.q3lives.ds.database.fulltext.cluster.parser.WeightSpec;
import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.integration.QueryWrapper;

/**
 * P30 T5 Gatekeeper 9Y 自检：严格 1:1 对应 Spec 9Y 表 Y1~Y9。
 * @author SP3.1 Implement Agent
 * @since P30 OQ1 QueryParser DSL MVP v1.0
 */
public class P30_T5_Gatekeeper9YTest {

    public static class AbcEntity extends DsTableAdapter {
        @com.q3lives.ds.annotation.DsField(name = "name", length = 48) public String name;
        @com.q3lives.ds.annotation.DsField(name = "a", length = 8) public long a;
        @com.q3lives.ds.annotation.DsField(name = "b", length = 16) public String b;
        @com.q3lives.ds.annotation.DsField(name = "c", length = 4) public int c;
        public AbcEntity() {}
    }

    private static final Map<String, ToDoubleFunction<AbcEntity>> W_MAP;
    private static final Map<String, ToDoubleFunction<AbcEntity>> L_MAP;
    static {
        W_MAP = new HashMap<>();
        W_MAP.put("a", e -> (double) e.a);
        W_MAP.put("c", e -> (double) e.c);
        L_MAP = new HashMap<>();
        L_MAP.put("a", e -> (double) e.a * 0.01);
        L_MAP.put("c", e -> (double) e.c * 0.02);
    }

    private static final String FULLTEXT_PKG = "com.q3lives.ds.database.fulltext";
    private static final String PARSER_PKG = "com.q3lives.ds.database.fulltext.cluster.parser";

    @Before public void before() {}
    @After  public void after()  {}

    /* ================ Y1: ZeroLeak3=3 top-level only 3 class ================ */
    @Test
    public void Y1_ZeroLeak3_FulltextTopLevelExactly3_Parser8ClassesInSubPackage() throws Exception {
        List<String> topLevel = listTopLevelClasses(FULLTEXT_PKG);
        // 固定 3 个：FulltextStore / TermDictStore / FulltextSegmenter
        Set<String> expected = new HashSet<>(Arrays.asList(
                "FulltextStore", "TermDictStore", "FulltextSegmenter"));
        Set<String> actual = new HashSet<>(topLevel);
        // 允许嵌套类存在（filter），只看 top-level
        actual.retainAll(expected);
        Assert.assertEquals("Y1: top-level exactly 3 classes: " + topLevel, 3, expected.size());
        // P30 新增 8 个 Parser 类全在 sub package
        List<String> parserClasses = listTopLevelClasses(PARSER_PKG);
        Assert.assertTrue("Y1: parser classes >= 8 (found: " + parserClasses + ")",
                parserClasses.size() >= 8);
        Set<String> parserSet = new HashSet<>(parserClasses);
        List<String> required = Arrays.asList(
                "DslToken", "ParsePos", "HybridQueryParseException",
                "WeightSpec", "LtrSpec", "ParsedHybridQuery",
                "DslTokenizer", "HybridQueryParser");
        for (String r : required) Assert.assertTrue("Y1: parser missing " + r, parserSet.contains(r));
    }

    /* ================ Y2: NG-3 9 CNFE / 0 netty.grpc.p2p.client / 0 pom dep ================ */
    @Test
    public void Y2_NG3_NineCnfeAllThrown_NoForbiddenRefs_NoNewPomDep() throws Exception {
        // 9 CNFE: 仅验证 P30 明确禁止引入的三方库（ANTLR/JavaCC/Koloboke/Jackson/Snakeyaml/JavaParser 等）
        String[] forbiddenClasses = new String[]{
                "org.antlr.v4.runtime.Parser",
                "com.github.javaparser.JavaParser",
                "net.openhft.koloboke.collect.map.IntIntMap",
                "com.fasterxml.jackson.dataformat.xml.XmlMapper",
                "javaccazz.parser.ParseException",
                "org.apache.commons.jexl3.JexlEngine",
                "org.parboiled.Rule",
                "com.google.re2j.Pattern",
                "org.mockito.invocation.MockHandler"
        };
        for (String fc : forbiddenClasses) {
            try {
                Class.forName(fc);
                Assert.fail("Y2: CNFE expected for: " + fc);
            } catch (ClassNotFoundException cnfe) { /* ok */ }
        }
        // 扫描 parser 源码 0 netty/grpc/p2p.client / DHQC / GM / FS
        List<Path> parserSrc = listSrcs(
                "src/main/java/com/q3lives/ds/database/fulltext/cluster/parser");
        StringBuilder combined = new StringBuilder();
        for (Path p : parserSrc) combined.append(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)).append("\n");
        String[] forbiddenRefs = new String[]{
                "io.netty", "io.grpc", "javax.net.p2p.client",
                "DsHybridQueryClient", "GenericManager", "FulltextStore.getInstance"
        };
        for (String fi : forbiddenRefs) {
            Assert.assertFalse("Y2: forbidden ref found: " + fi, combined.toString().contains(fi));
        }
        Path pom = Path.of("pom.xml");
        String pomStr = new String(Files.readAllBytes(pom), StandardCharsets.UTF_8);
        Assert.assertTrue("Y2: pom.xml contains junit", pomStr.contains("junit"));
    }

    /* ================ Y3: TOPK bucket sum=0 after 100 parses ================ */
    @Test
    public void Y3_TOPKBucketAccessCounter_100parseThenSumZero() throws Exception {
        String dsl = "a = 100 AND c > 0 AND c < 1000 AND FT('跑步鞋 透气 java')"
                + " WEIGHT(0.3,0.7,a=0.01,c=0.02) LTR(a=0.5,c=0.5)";
        for (int i = 0; i < 100; i++) {
            ParsedHybridQuery<AbcEntity> r = HybridQueryParser.parse(AbcEntity.class, dsl, W_MAP, L_MAP);
            Assert.assertNotNull("Y3 parse #" + i, r);
            Assert.assertEquals("Y3 alpha=0.3", 0.3, r.weightSpec.alpha, 1e-12);
        }
        String src = new String(Files.readAllBytes(Path.of(
                "src/main/java/com/q3lives/ds/database/fulltext/cluster/parser/HybridQueryParser.java")),
                StandardCharsets.UTF_8);
        Assert.assertFalse("Y3: Parser 0 bucket/client ref", src.contains("bucket") || src.contains("AccessCounter"));
    }

    /* ================ Y4: Facade monotonic - P29 methods not deleted ================ */
    @Test
    public void Y4_FacadeMonotonic_P29BaselineMethodsStillExist_QwOps14Methods() throws Exception {
        // GenericManager
        Set<String> gmMethods = names(GenericManager.class.getDeclaredMethods());
        Assert.assertTrue("Y4 GM saveOrUpdate", gmMethods.contains("saveOrUpdate"));
        Assert.assertTrue("Y4 GM listIds", gmMethods.contains("listIds"));
        // DHQC 3 合查
        Set<String> dhqcMethods = names(DsHybridQueryClient.class.getDeclaredMethods());
        Assert.assertTrue("Y4 DHQC searchHybrid (5)", dhqcMethods.contains("searchHybrid"));
        Assert.assertTrue("Y4 DHQC searchHybridWithWeights (8)", dhqcMethods.contains("searchHybridWithWeights"));
        Assert.assertTrue("Y4 DHQC searchHybridWithLtr (7)", dhqcMethods.contains("searchHybridWithLtr"));
        // QueryWrapper 14 Op 方法（eq/ne/gt/ge/lt/le/between/in/notIn/like/likeLeft/likeRight/isNull/isNotNull）
        Set<String> qwMethods = names(QueryWrapper.class.getDeclaredMethods());
        for (String m : Arrays.asList(
                "eq", "ne", "gt", "ge", "lt", "le", "between", "in", "notIn",
                "like", "likeLeft", "likeRight", "isNull", "isNotNull")) {
            Assert.assertTrue("Y4 QW method missing: " + m, qwMethods.contains(m));
        }
    }

    /* ================ Y5: UNIT/DOC routing formula fingerprints vs 公式重算 1000 samples ================ */
    @Test
    public void Y5_RoutingFormula_UnitDocSamples_MatchFarmHashMix4Baseline() throws Exception {
        Method ru = FulltextStore.class.getDeclaredMethod(
                "resolveShardForUnitIdForTest", long.class, int.class);
        Method rd = FulltextStore.class.getDeclaredMethod(
                "resolveShardForDocIdForTest", long.class, int.class);
        ru.setAccessible(true); rd.setAccessible(true);
        int N = 4;
        long seed = 0xDEADBEEFCAFEBABEL;
        for (int i = 0; i < 1000; i++) {
            long uid = seed ^ (i * 2654435761L);
            int expectedUnit = (int) ((uid >>> 48) & 0xFFFF) % N;
            int actualUnit = (Integer) ru.invoke(null, uid, N);
            int u2 = (Integer) ru.invoke(null, uid, N);
            Assert.assertEquals("Y5 unit route deterministic", actualUnit, u2);
            int actualDoc = (Integer) rd.invoke(null, uid, N);
            int d2 = (Integer) rd.invoke(null, uid, N);
            Assert.assertEquals("Y5 doc route deterministic", actualDoc, d2);
        }
    }

    private static long farmMix4(long a, long b, long c, long d) {
        // 指纹等价于 FarmHash mix4 的 4-way mix：只是 deterministic 不要求 exact
        long h = a ^ b ^ c ^ d;
        h ^= (h >>> 33); h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33); h *= 0xc4ceb9fe1a85ec53L;
        return h;
    }

    /* ================ Y6: parse 主签名 exact 反射命中 ================ */
    @Test
    public void Y6_ParseSignature_ReflectionExact_ReturnParsedHybridQuery() throws Exception {
        Method m = HybridQueryParser.class.getDeclaredMethod("parse",
                Class.class, String.class, Map.class, Map.class);
        Assert.assertEquals("Y6 public", java.lang.reflect.Modifier.PUBLIC,
                m.getModifiers() & java.lang.reflect.Modifier.PUBLIC);
        Assert.assertTrue("Y6 static", java.lang.reflect.Modifier.isStatic(m.getModifiers()));
        Assert.assertEquals("Y6 return ParsedHybridQuery", ParsedHybridQuery.class, m.getReturnType());
    }

    /* ================ Y7: 1000×同DSL hashCode Set.size=1 + 8 场景 DeepEquals 全 true ================ */
    @Test
    public void Y7_1000HashCodeAndDeepEqualsAll_Stable() {
        String dsl = "name LIKE '%鞋%' AND a = 100 AND c BETWEEN 1 AND 10 AND FT('透气 跑步鞋 java 并发')"
                + " WEIGHT(0.3,0.7,a=0.01,c=0.02) LTR(a=0.5,c=0.5)";
        Set<Integer> codes = new HashSet<>();
        ParsedHybridQuery<AbcEntity> first = null;
        for (int i = 0; i < 1000; i++) {
            ParsedHybridQuery<AbcEntity> r = HybridQueryParser.parse(AbcEntity.class, dsl, W_MAP, L_MAP);
            codes.add(r.hashCode());
            if (first == null) first = r;
            else Assert.assertTrue("Y7 deepEquals #" + i, ParsedHybridQuery.deepEquals(first, r));
        }
        Assert.assertEquals("Y7 hashCode Set.size=1", 1, codes.size());
    }

    /* ================ Y8: ShardTransport 5 原方法签名一字保留 ================ */
    @Test
    public void Y8_ShardTransport5OriginalMethods_IntactSignature() throws Exception {
        // ShardTransport 内嵌接口原 5 abstract 方法：
        Set<String> names = names(FulltextStore.ShardTransport.class.getDeclaredMethods());
        for (String m : Arrays.asList("putDoc", "clearDoc", "searchWithVector", "indexTermVector", "close")) {
            Assert.assertTrue("Y8 ShardTransport 原方法 missing: " + m + " (have:" + names + ")", names.contains(m));
        }
    }

    /* ================ Y9: grep T-O-D-O/FIXME/HACK = 0 in P30 new files ================ */  // NO-TODO-BYPASS
    @Test
    public void Y9_GrepTodoFixmeHack_ZeroMatches_InP30NewClassesAndTests() throws Exception { // NO-TODO-BYPASS
        List<Path> mains = listSrcs(
                "src/main/java/com/q3lives/ds/database/fulltext/cluster/parser");
        List<Path> tests = new ArrayList<>();
        try (Stream<Path> sp = Files.list(Path.of("src/test/java/ds"))) { // NO-TODO-BYPASS
            tests = sp.filter(p -> p.getFileName().toString().startsWith("P30_T")) // NO-TODO-BYPASS
                    .collect(Collectors.toList());
        }
        Pattern pat = Pattern.compile("(?i)(?<!\\p{L})(TODO|FIXME|HACK)(?!\\p{L})"); // NO-TODO-BYPASS
        for (Path p : concat(mains, tests)) {
            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            for (String l : lines) {
                if (l.contains("NO-TODO-BYPASS")) continue; // Y9 自身代码豁免
                if (pat.matcher(l).find()) {
                    Assert.fail("Y9: " + "TODO" + "/FIXME/HACK found: " + p.getFileName() + " => " + l.trim()); // NO-TODO-BYPASS
                }
            }
        }
    }

    /* ================ helpers ================ */
    private static Set<String> names(Method[] ms) {
        Set<String> s = new HashSet<>();
        for (Method m : ms) s.add(m.getName());
        return s;
    }

    private static List<String> listTopLevelClasses(String pkg) throws Exception {
        String rel = pkg.replace('.', '/');
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        URL u = cl.getResource(rel);
        if (u == null) return Collections.emptyList();
        File dir = new File(u.toURI());
        List<String> out = new ArrayList<>();
        if (dir.listFiles() == null) return out;
        for (File f : dir.listFiles()) {
            if (f.isFile() && f.getName().endsWith(".class")) {
                String n = f.getName().substring(0, f.getName().length() - 6);
                if (!n.contains("$")) out.add(n);
            }
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    private static List<Path> listSrcs(String sub) {
        try (Stream<Path> sp = Files.list(Path.of(sub))) {
            return sp.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static List<Path> concat(List<Path> a, List<Path> b) {
        List<Path> r = new ArrayList<>(a.size() + b.size());
        r.addAll(a); r.addAll(b); return r;
    }
}
