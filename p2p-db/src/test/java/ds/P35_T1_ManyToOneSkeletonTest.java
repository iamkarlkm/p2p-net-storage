package ds;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.annotation.DsManyToOne;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.cluster.join.JoinSpec;
import com.q3lives.ds.database.fulltext.cluster.join.v2.JoinMode;
import com.q3lives.ds.database.fulltext.cluster.join.v2.ManyToOneJoinResult;
import com.q3lives.ds.database.integration.QueryWrapper;

/**
 * P35 T1：@DsManyToOne 注解 + JoinMode enum + JoinSpec 6 字段 + ManyToOneJoinResult
 * 4 TR。
 *
 * <p>
 * TR1.1 @DsManyToOne 注解字段：joinProp/props/fetchSize 存在
 * <br>TR1.2 JoinMode enum 2 值 + fromCode/fromString + IAE
 * <br>TR1.3 JoinSpec 6 字段扩展 + 8 IAE（4 参 P31 字节级 + 6 参 P35 扩展）
 * <br>TR1.4 ManyToOneJoinResult 不可变 + 5 IAE + deepEquals
 */
public class P35_T1_ManyToOneSkeletonTest {

    /* 实体类 for 反射测试 */
    @com.q3lives.ds.annotation.DsTable(name = "p35_t1_main")
    public static class MainT1 extends DsTableAdapter {

        @DsField(name = "title", length = 128)
        public String title;
        @DsManyToOne(joinProp = "mainId")
        public SubT1 sub1;
    }

    @com.q3lives.ds.annotation.DsTable(name = "p35_t1_sub")
    public static class SubT1 extends DsTableAdapter {
        @DsField(name = "content", length = 1024)
        public String content;
        @DsField(name = "mainId", length = 8)
        public Long mainId;
    }

    @com.q3lives.ds.annotation.DsTable(name = "p35_t1_sub_no_dsfield")
    public static class SubT1NoDsField extends DsTableAdapter {

        public Long mainId;  // 缺 @DsField
    }

    @com.q3lives.ds.annotation.DsTable(name = "p35_t1_sub_no_lf")
    public static class SubT1NoJoinProp extends DsTableAdapter {

        @DsField(name = "content", length = 128)
        public String content;
    }

    /* -------- TR1.1 @DsManyToOne 注解字段 -------- */
    @Test
    public void tr11_dsManyToOneAnnotation() throws Exception {
        Field f = MainT1.class.getDeclaredField("sub1");
        DsManyToOne ann = f.getAnnotation(DsManyToOne.class);
        Assert.assertNotNull("@DsManyToOne 注解必须存在", ann);
        Assert.assertEquals("joinProp", "mainId", ann.joinProp());
        Assert.assertEquals("props default", "", ann.props());
        Assert.assertEquals("fetchSize default", 256, ann.fetchSize());
    }

    /* -------- TR1.2 JoinMode enum 2 值 + fromCode -------- */
    @Test
    public void tr12_joinModeEnum() {
        Assert.assertEquals(JoinMode.ONE_TO_ONE, JoinMode.fromCode('O'));
        Assert.assertEquals(JoinMode.MANY_TO_ONE, JoinMode.fromCode('M'));
        Assert.assertEquals(JoinMode.ONE_TO_ONE, JoinMode.fromString("O"));
        Assert.assertNull(JoinMode.fromString(null));
        try {
            JoinMode.fromCode('X');
            Assert.fail("fromCode('X') IAE");
        } catch (IllegalArgumentException ignore) {
        }
        try {
            JoinMode.fromString("AB");
            Assert.fail("fromString 多字符 IAE");
        } catch (IllegalArgumentException ignore) {
        }
        // 2 个值
        Assert.assertEquals(2, JoinMode.values().length);
    }

    /* -------- TR1.3 JoinSpec 6 字段 + 8 IAE -------- */
    @Test
    public void tr13_joinSpec6Fields() {
        // P31 4 参构造器：mode 默认 ONE_TO_ONE，batchSize=0
        JoinSpec<SubT1> j1 = new JoinSpec<>(SubT1.class, "id", "mainId", null);
        Assert.assertEquals(JoinMode.ONE_TO_ONE, j1.mode);
        Assert.assertEquals(0, j1.batchSize);

        // P35 6 参构造器
        JoinSpec<SubT1> j2 = new JoinSpec<>(SubT1.class, "id", "mainId", null,
                JoinMode.MANY_TO_ONE, 256);
        Assert.assertEquals(JoinMode.MANY_TO_ONE, j2.mode);
        Assert.assertEquals(256, j2.batchSize);

        // IAE 1: MANY_TO_ONE batchSize=0
        try {
            new JoinSpec<SubT1>(SubT1.class, "id", "mainId", null, JoinMode.MANY_TO_ONE, 0);
            Assert.fail("MANY_TO_ONE batchSize=0 IAE");
        } catch (IllegalArgumentException ignore) {
        }
        // IAE 2: MANY_TO_ONE batchSize>4096
        try {
            new JoinSpec<SubT1>(SubT1.class, "id", "mainId", null, JoinMode.MANY_TO_ONE, 4097);
            Assert.fail("MANY_TO_ONE batchSize=4097 IAE");
        } catch (IllegalArgumentException ignore) {
        }
        // IAE 3: mode null
        try {
            new JoinSpec<SubT1>(SubT1.class, "id", "mainId", null, null, 0);
            Assert.fail("mode null IAE");
        } catch (IllegalArgumentException ignore) {
        }
        // IAE 4-7: 沿用 P31 (relatedClass null/outerField blank/relatedField blank/未继承)
        try {
            new JoinSpec<>(null, "id", "x", null);
            Assert.fail("relatedClass null IAE");
        } catch (NullPointerException ignore) {
        }
        try {
            new JoinSpec<>(SubT1.class, "", "x", null);
            Assert.fail("outerField blank IAE");
        } catch (IllegalArgumentException ignore) {
        }
        try {
            new JoinSpec<>(SubT1.class, "id", "", null);
            Assert.fail("relatedField blank IAE");
        } catch (IllegalArgumentException ignore) {
        }
        try {
            @SuppressWarnings({"rawtypes", "unchecked"})
            JoinSpec bad = new JoinSpec(String.class, "id", "x", null);
            Assert.fail("未继承 DsTableAdapter IAE");
        } catch (IllegalArgumentException ignore) {
        }
        // IAE 8: ONE_TO_ONE mode + batchSize != 0 → 强制 0（不抛）
        JoinSpec<SubT1> j3 = new JoinSpec<>(SubT1.class, "id", "mainId", null,
                JoinMode.ONE_TO_ONE, 999);
        Assert.assertEquals(0, j3.batchSize);

        // equals / hashCode 含 mode + batchSize
        JoinSpec<SubT1> j4 = new JoinSpec<>(SubT1.class, "id", "mainId", null,
                JoinMode.MANY_TO_ONE, 256);
        Assert.assertEquals(j2, j4);
        Assert.assertEquals(j2.hashCode(), j4.hashCode());
        Assert.assertNotEquals(j1, j2);  // mode 不同
    }

    /* -------- TR1.4 ManyToOneJoinResult 不可变 + 5 IAE + deepEquals -------- */
    @Test
    public void tr14_manyToOneJoinResult() {
        Map<Long, java.util.List<SubT1>> byId = new LinkedHashMap<>();
        SubT1 r1 = new SubT1();
        r1.setId(100L);
        r1.mainId = 1L;
        SubT1 r2 = new SubT1();
        r2.setId(101L);
        r2.mainId = 1L;
        SubT1 r3 = new SubT1();
        r3.setId(200L);
        r3.mainId = 2L;
        byId.put(1L, Arrays.asList(r1, r2));
        byId.put(2L, Collections.singletonList(r3));
        java.util.Set<Long> matched = new java.util.LinkedHashSet<>(Arrays.asList(1L, 2L));
        ManyToOneJoinResult<SubT1> m = new ManyToOneJoinResult<>(SubT1.class, byId, matched);
        Assert.assertEquals(SubT1.class, m.relatedClass);
        Assert.assertEquals(2, m.matchedOuterIds.size());
        Assert.assertEquals(2, m.byOuterId.get(1L).size());
        // 不可变
        try {
            m.byOuterId.clear();
            Assert.fail("byOuterId 不可变");
        } catch (UnsupportedOperationException ignore) {
        }
        try {
            m.matchedOuterIds.clear();
            Assert.fail("matchedOuterIds 不可变");
        } catch (UnsupportedOperationException ignore) {
        }
        // IAE 1-3
        try {
            new ManyToOneJoinResult<>(null, byId, matched);
            Assert.fail("relatedClass null IAE");
        } catch (IllegalArgumentException ignore) {
        }
        try {
            new ManyToOneJoinResult<>(SubT1.class, null, matched);
            Assert.fail("byOuterId null IAE");
        } catch (IllegalArgumentException ignore) {
        }
        try {
            new ManyToOneJoinResult<>(SubT1.class, byId, null);
            Assert.fail("matched null IAE");
        } catch (IllegalArgumentException ignore) {
        }
        // IAE 4: byOuterId value null
        Map<Long, java.util.List<SubT1>> badMap = new HashMap<>();
        badMap.put(1L, null);
        try {
            new ManyToOneJoinResult<>(SubT1.class, badMap, java.util.Collections.singleton(1L));
            Assert.fail("value null IAE");
        } catch (IllegalArgumentException ignore) {
        }
        // IAE 5: matchedOuterIds != byOuterId.keySet
        try {
            new ManyToOneJoinResult<>(SubT1.class, byId, java.util.Collections.singleton(1L));
            Assert.fail("keys mismatch IAE");
        } catch (IllegalArgumentException ignore) {
        }
        // deepEquals
        ManyToOneJoinResult<SubT1> m2 = new ManyToOneJoinResult<>(SubT1.class, byId, matched);
        Assert.assertTrue(ManyToOneJoinResult.deepEquals(m, m2));
        // 不等：matched 不同
        java.util.Set<Long> matchedLess = new java.util.LinkedHashSet<>(Collections.singleton(1L));
        Map<Long, java.util.List<SubT1>> byIdLess = new LinkedHashMap<>();
        byIdLess.put(1L, Arrays.asList(r1, r2));
        ManyToOneJoinResult<SubT1> m3 = new ManyToOneJoinResult<>(SubT1.class, byIdLess, matchedLess);
        Assert.assertFalse(ManyToOneJoinResult.deepEquals(m, m3));
    }

    /* -------- 额外: fromDsManyToOneAnnotations 反射工厂 -------- */
    @Test
    public void tr15_fromManyToOneAnnotations() {
        List<JoinSpec<?>> specs = JoinSpec.fromDsManyToOneAnnotations(MainT1.class);
        Assert.assertEquals(1, specs.size());
        JoinSpec<?> j = specs.get(0);
        Assert.assertEquals(SubT1.class, j.relatedClass);
        Assert.assertEquals("id", j.outerField);
        Assert.assertEquals("mainId", j.relatedField);
        Assert.assertEquals(JoinMode.MANY_TO_ONE, j.mode);
        Assert.assertEquals(256, j.batchSize);
    }
}
