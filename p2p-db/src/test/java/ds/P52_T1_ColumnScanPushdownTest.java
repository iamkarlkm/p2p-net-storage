package ds;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.database.columnar.pushdown.ColumnScanPushdown;

import javax.net.p2p.model.DbQuery;
import javax.net.p2p.model.DbQueryCriterion;
import javax.net.p2p.model.DbQueryOp;

/**
 * P52 T1：{@link ColumnScanPushdown} 结构与非法输入单测 5 TR 0F0E。
 *
 * <p>
 * TR1.1 类形态（{@code public final} + 唯一私有构造 + 0 非静态实例字段）；<br>TR1.2 dbRoot 为 null → IAE；<br>TR1.3
 * entityClassName 空/空白 → IAE；<br>TR1.4 谓词非法（{@code where[i]==null} / op 空 / 列名空白 / IN·NOT_IN 缺
 * list）→ IAE；<br>TR1.5 {@code @composite:} 列与未知列名 → IAE。
 *
 * <p>
 * 纯结构断言，IAE 分支在谓词前置校验阶段返回，无需真实列存数据。
 */
public class P52_T1_ColumnScanPushdownTest {

    private File tmpRoot;

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("p52-t1-pushdown").toFile();
    }

    @After
    public void after() {
        deleteRecursive(tmpRoot);
    }

    private static DbQuery query(DbQueryCriterion... cs) {
        DbQuery q = new DbQuery();
        q.where.addAll(Arrays.asList(cs));
        return q;
    }

    private static DbQueryCriterion crit(DbQueryOp op, String name, String a, String b, List<String> list) {
        return new DbQueryCriterion(op, name, a, b, list);
    }

    private static void assertIae(Runnable r) {
        try {
            r.run();
            Assert.fail("应抛出 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    private static void assertIaeThrowing(ThrowingRunnable r) {
        try {
            r.run();
            Assert.fail("应抛出 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        } catch (Exception other) {
            Assert.fail("应抛出 IllegalArgumentException，实际: " + other);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    // ============== TR1.1 类形态 ==============
    @Test(timeout = 30000)
    public void tr11_classShapeAndZeroInstanceState() throws Exception {
        Class<?> c = ColumnScanPushdown.class;
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
        assertIae(() -> ColumnScanPushdown.scan(null, "x.Y", null, 0, 10));
        assertIae(() -> ColumnScanPushdown.filter(null, "x.Y", null));
        assertIae(() -> ColumnScanPushdown.filter(null, "x.Y", query(crit(DbQueryOp.EQ, "tag", "x", null, null))));
    }

    // ============== TR1.3 entityClassName 空白 → IAE ==============
    @Test(timeout = 30000)
    public void tr13_blankEntityThrowsIae() {
        assertIae(() -> ColumnScanPushdown.filter(tmpRoot, null, null));
        assertIae(() -> ColumnScanPushdown.filter(tmpRoot, "", null));
        assertIae(() -> ColumnScanPushdown.filter(tmpRoot, "   ", null));
        assertIae(() -> ColumnScanPushdown.scan(tmpRoot, "  ", null, 0, 10));
    }

    // ============== TR1.4 谓词非法 → IAE ==============
    @Test(timeout = 30000)
    public void tr14_invalidCriteriaThrowsIae() {
        // where[i] == null
        DbQuery nullSlot = new DbQuery();
        nullSlot.where.add(null);
        assertIae(() -> ColumnScanPushdown.filter(tmpRoot, "x.Y", nullSlot));

        // op == null
        assertIae(() -> ColumnScanPushdown.filter(tmpRoot, "x.Y",
                query(crit(null, "tag", "x", null, null))));

        // 列名空白
        assertIae(() -> ColumnScanPushdown.filter(tmpRoot, "x.Y",
                query(crit(DbQueryOp.EQ, "  ", "x", null, null))));
        assertIae(() -> ColumnScanPushdown.filter(tmpRoot, "x.Y",
                query(crit(DbQueryOp.EQ, null, "x", null, null))));

        // IN / NOT_IN 缺 list（显式置 null）
        DbQueryCriterion inNoList = new DbQueryCriterion();
        inNoList.op = DbQueryOp.IN;
        inNoList.name = "tag";
        inNoList.list = null;
        assertIae(() -> ColumnScanPushdown.filter(tmpRoot, "x.Y", query(inNoList)));

        DbQueryCriterion notInNoList = new DbQueryCriterion();
        notInNoList.op = DbQueryOp.NOT_IN;
        notInNoList.name = "tag";
        notInNoList.list = null;
        assertIae(() -> ColumnScanPushdown.filter(tmpRoot, "x.Y", query(notInNoList)));
    }

    // ============== TR1.5 @composite: 列 / 未知列名 → IAE ==============
    @Test(timeout = 30000)
    public void tr15_compositeAndUnknownColumnThrowsIae() {
        // @composite: 在谓词前置校验阶段拦截
        assertIae(() -> ColumnScanPushdown.filter(tmpRoot, "x.Y",
                query(crit(DbQueryOp.EQ, "@composite:g", "x", null, null))));

        // 未知列名：残差校验阶段拦截（defs 为空 → get 返回 null，早于列值读取）
        assertIaeThrowing(() -> ColumnScanPushdown.matches(null, "x.Y", 1L,
                List.of(crit(DbQueryOp.EQ, "no_such_col", "x", null, null)), Map.of()));

        // @composite: 在残差校验阶段同样拦截
        assertIaeThrowing(() -> ColumnScanPushdown.matches(null, "x.Y", 1L,
                List.of(crit(DbQueryOp.EQ, "@composite:g", "x", null, null)), Map.of()));

        // null 条件 → 残差校验阶段拦截（有意加严）
        assertIaeThrowing(() -> ColumnScanPushdown.matches(null, "x.Y", 1L,
                Arrays.asList((DbQueryCriterion) null), Map.of()));
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
