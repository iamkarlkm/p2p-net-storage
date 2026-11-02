package ds;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.columnar.TableMetaStore;
import com.q3lives.ds.database.columnar.aggpushdown.ColumnAggPushdown;

import javax.net.p2p.model.DbAggFn;
import javax.net.p2p.model.DbAggItem;

/**
 * P53 T1：{@link ColumnAggPushdown} 结构与非法输入单测 5 TR 0F0E。
 *
 * <p>
 * TR1.1 类形态（{@code public final} + 唯一私有构造 + 0 非静态实例字段）；<br>
 * TR1.2 {@code dbRoot} 为 null → IAE；<br>
 * TR1.3 {@code entityClassName} null/空白 → IAE；<br>
 * TR1.4 {@code aggs} null/空 → IAE（含 {@link DbAggItem} 6 条 IAE 范式）；<br>
 * TR1.5 未知列（{@code groupBy} / 聚合列）与空分组列 → IAE。
 *
 * <p>
 * 全部守卫在谓词/列元前置阶段返回，除 TR1.5 需要写出一份真实列元（{@link TableMetaStore#ensureMeta}）
 * 以触发「未知列」判定外，其余无需真实列值数据。
 */
public class P53_T1_AggPushdownSkeletonTest {

    /**
     * 测试实体：tag 分组列 + num 聚合列；仅用于生成列元定义。
     */
    public static class Row extends DsTableAdapter {

        @DsField(name = "tag", length = 16)
        public String tag;

        @DsField(name = "num", length = 4)
        public int num;

        public Row() {
        }
    }

    private File tmpRoot;

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p53_t1_").toFile();
    }

    @After
    public void after() {
        deleteRecursive(tmpRoot);
    }

    private static DbAggItem agg(DbAggFn fn, String column) {
        return new DbAggItem(fn, column);
    }

    private static List<DbAggItem> aggList(DbAggItem... items) {
        return Arrays.asList(items);
    }

    private List<Object[]> run(String entity, List<DbAggItem> aggs, List<String> groupBy) {
        return ColumnAggPushdown.compute(tmpRoot, entity, null, aggs, groupBy, null, null, 0, 100);
    }

    private static void assertIae(Runnable r) {
        try {
            r.run();
            Assert.fail("应抛出 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    // ============== TR1.1 类形态 ==============
    @Test(timeout = 30000)
    public void tr11_classShapeAndZeroInstanceState() throws Exception {
        Class<?> c = ColumnAggPushdown.class;
        Assert.assertTrue("public", Modifier.isPublic(c.getModifiers()));
        Assert.assertTrue("final", Modifier.isFinal(c.getModifiers()));

        Constructor<?>[] ctors = c.getDeclaredConstructors();
        Assert.assertEquals("唯一构造器", 1, ctors.length);
        Assert.assertTrue("构造器私有", Modifier.isPrivate(ctors[0].getModifiers()));
        Assert.assertEquals("构造器 0 参数", 0, ctors[0].getParameterCount());

        int instanceFields = 0;
        for (Field f : c.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) {
                instanceFields++;
            }
        }
        Assert.assertEquals("0 非静态实例字段", 0, instanceFields);
    }

    // ============== TR1.2 dbRoot 为 null → IAE ==============
    @Test(timeout = 30000)
    public void tr12_nullDbRootThrowsIae() {
        List<DbAggItem> valid = aggList(agg(DbAggFn.COUNT, "num"));
        assertIae(() -> ColumnAggPushdown.compute(
                null, Row.class.getName(), null, valid, null, null, null, 0, 100));
        assertIae(() -> ColumnAggPushdown.compute(
                null, null, null, null, null, null, null, 0, 100));
    }

    // ============== TR1.3 entityClassName 空白 → IAE ==============
    @Test(timeout = 30000)
    public void tr13_blankEntityThrowsIae() {
        List<DbAggItem> valid = aggList(agg(DbAggFn.SUM, "num"));
        assertIae(() -> run(null, valid, null));
        assertIae(() -> run("", valid, null));
        assertIae(() -> run("   ", valid, null));
    }

    // ============== TR1.4 aggs null/空 + DbAggItem 6 IAE ==============
    @Test(timeout = 30000)
    public void tr14_aggsGuardAndAggItemSixIae() {
        assertIae(() -> run(Row.class.getName(), null, null));
        assertIae(() -> run(Row.class.getName(), List.of(), null));

        // DbAggItem 6 IAE（对齐 P38 AggSpec 口径）
        assertIae(() -> new DbAggItem(null, "num"));
        assertIae(() -> new DbAggItem(DbAggFn.COUNT, null));
        assertIae(() -> new DbAggItem(DbAggFn.COUNT, "   "));
        assertIae(() -> new DbAggItem(DbAggFn.COUNT, "a b"));
        assertIae(() -> new DbAggItem(DbAggFn.COUNT, "a-b"));
        assertIae(() -> new DbAggItem(DbAggFn.COUNT, "agg"));
    }

    // ============== TR1.5 未知列 / 空分组列 → IAE ==============
    @Test(timeout = 30000)
    public void tr15_unknownColumnAndBlankGroupThrowsIae() {
        new TableMetaStore(tmpRoot).ensureMeta(Row.class);

        // 聚合列未知
        assertIae(() -> run(Row.class.getName(),
                aggList(agg(DbAggFn.COUNT, "no_such_col")), null));
        // groupBy 列未知
        assertIae(() -> run(Row.class.getName(),
                aggList(agg(DbAggFn.COUNT, "num")), List.of("no_such_col")));
        // groupBy 列空白
        assertIae(() -> run(Row.class.getName(),
                aggList(agg(DbAggFn.COUNT, "num")), List.of("   ")));
        // 聚合项为 null 槽位
        assertIae(() -> run(Row.class.getName(),
                aggList((DbAggItem) null), null));

        // 反证：合法列不抛
        Assert.assertNotNull(run(Row.class.getName(),
                aggList(agg(DbAggFn.COUNT, "num")), List.of("tag")));
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursive(c);
            }
        }
        f.delete();
    }
}
