package ds;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import com.q3lives.ds.database.columnar.pushdown.DbQueryToQueryWrapper;
import com.q3lives.ds.database.integration.QueryWrapper;

import javax.net.p2p.model.DbQuery;
import javax.net.p2p.model.DbQueryCriterion;
import javax.net.p2p.model.DbQueryOp;
import javax.net.p2p.model.DbQueryOrGroup;

/**
 * P50 T1：{@link DbQueryToQueryWrapper} 转换器单测 5 TR 0F0E。
 *
 * <p>
 * TR1.1 非法输入（op==null / name 空白 / IN 缺 list / where[i]==null / 实体类不存在或非
 * {@code DsTableAdapter}）→ {@link IllegalArgumentException}；<br>TR1.2 {@link DbQuery} → {@link QueryWrapper}
 * 逐 op 映射全等（EQ/NE/GT/GE/LT/LE/BETWEEN/IN/NOT_IN/LIKE/LIKE_LEFT/LIKE_RIGHT/IS_NULL/IS_NOT_NULL）；<br>TR1.3
 * null / 空 where → 空 wrapper（全透传语义）+ 空 ids 原样返回；<br>TR1.4 边界值（BETWEEN a==b / IN 单元素 / 值
 * null）；<br>TR1.5 纯函数确定性（同输入同输出）。
 *
 * <p>
 * 纯结构断言，不依赖 ORM/DB；IAE 分支在 {@code Class.forName} 之前返回，故无需真实实体。
 */
public class P50_T1_DbQueryToQueryWrapperTest {

    private static DbQuery query(DbQueryCriterion... cs) {
        DbQuery q = new DbQuery();
        q.where.addAll(Arrays.asList(cs));
        return q;
    }

    private static DbQueryCriterion crit(DbQueryOp op, String name, String a, String b, List<String> list) {
        return new DbQueryCriterion(op, name, a, b, list);
    }

    private static QueryWrapper.Criterion at(QueryWrapper<?> w, int i) {
        return w.criteria().get(i);
    }

    private static String snapshot(QueryWrapper<?> w) {
        StringBuilder sb = new StringBuilder();
        for (QueryWrapper.Criterion c : w.criteria()) {
            sb.append(c.op).append('|').append(c.col).append('|').append(c.a).append('|').append(c.b).append(';');
        }
        for (QueryWrapper<?> b : w.orBranches()) {
            sb.append('#').append(snapshot(b));
        }
        return sb.toString();
    }

    // ============== TR1.1 非法输入 → IAE ==============
    @Test(timeout = 30000)
    public void tr11_illegalInputThrowsIae() {
        // op == null
        assertIae(() -> DbQueryToQueryWrapper.convert(query(crit(null, "tag", "x", null, null))));
        // name 空白
        assertIae(() -> DbQueryToQueryWrapper.convert(query(crit(DbQueryOp.EQ, "  ", "x", null, null))));
        // IN 缺 list（无参构造器保留 list==null）
        DbQueryCriterion noList = new DbQueryCriterion();
        noList.op = DbQueryOp.IN;
        noList.name = "tag";
        noList.list = null;
        assertIae(() -> DbQueryToQueryWrapper.convert(query(noList)));
        // where[i] == null
        DbQuery nullSlot = new DbQuery();
        nullSlot.where.add(null);
        assertIae(() -> DbQueryToQueryWrapper.convert(nullSlot));
        // 实体类不存在 / 非 DsTableAdapter
        DbQuery valid = query(crit(DbQueryOp.EQ, "tag", "x", null, null));
        assertIae(() -> DbQueryToQueryWrapper.filterIdsByPredicate("no.such.Entity", valid, List.of(1L)));
        assertIae(() -> DbQueryToQueryWrapper.filterIdsByPredicate(String.class.getName(), valid, List.of(1L)));
    }

    private static void assertIae(Runnable r) {
        try {
            r.run();
            Assert.fail("应抛出 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    // ============== TR1.2 逐 op 映射全等 ==============
    @Test(timeout = 30000)
    public void tr12_allOpsMappingEquivalence() {
        DbQueryOp[] ops = DbQueryOp.values();
        List<DbQueryCriterion> ws = new ArrayList<>();
        for (DbQueryOp op : ops) {
            List<String> list = (op == DbQueryOp.IN || op == DbQueryOp.NOT_IN) ? Arrays.asList("a", "b") : null;
            ws.add(crit(op, "col_" + op.name(), "A", "B", list));
        }
        QueryWrapper<?> w = DbQueryToQueryWrapper.convert(query(ws.toArray(new DbQueryCriterion[0])));
        Assert.assertEquals("14 个 op 全映射", ops.length, w.criteria().size());
        for (int i = 0; i < ops.length; i++) {
            QueryWrapper.Criterion c = at(w, i);
            Assert.assertEquals("op 映射 " + ops[i], QueryWrapper.Op.valueOf(ops[i].name()), c.op);
            Assert.assertEquals("列名映射 " + ops[i], "col_" + ops[i].name(), c.col);
            if (ops[i] == DbQueryOp.BETWEEN) {
                Assert.assertEquals("BETWEEN min", "A", c.a);
                Assert.assertEquals("BETWEEN max", "B", c.b);
            }
            if (ops[i] == DbQueryOp.IN || ops[i] == DbQueryOp.NOT_IN) {
                Assert.assertTrue("IN/NOT_IN 值为集合", c.a instanceof Collection);
                Assert.assertEquals("IN/NOT_IN 集合大小", 2, ((Collection<?>) c.a).size());
            }
            if (ops[i] == DbQueryOp.IS_NULL || ops[i] == DbQueryOp.IS_NOT_NULL) {
                Assert.assertNull("IS_[NOT_]NULL 无值", c.a);
            }
        }
        Assert.assertEquals("纯 where 无 orBranches", 0, w.orBranches().size());
    }

    // ============== TR1.3 null / 空 where → 空 wrapper ==============
    @Test(timeout = 30000)
    public void tr13_nullAndEmptyWhereYieldsEmptyWrapper() {
        QueryWrapper<?> a = DbQueryToQueryWrapper.convert(null);
        Assert.assertTrue("null → 空 wrapper", a.criteria().isEmpty() && a.orBranches().isEmpty());
        QueryWrapper<?> b = DbQueryToQueryWrapper.convert(new DbQuery());
        Assert.assertTrue("空 where/anyOf → 空 wrapper", b.criteria().isEmpty() && b.orBranches().isEmpty());
        Assert.assertFalse("hasPredicate(null)", DbQueryToQueryWrapper.hasPredicate(null));
        Assert.assertFalse("hasPredicate(空)", DbQueryToQueryWrapper.hasPredicate(new DbQuery()));

        DbQuery q = query(crit(DbQueryOp.EQ, "t", "a", null, null));
        Assert.assertTrue("hasPredicate(单条件)", DbQueryToQueryWrapper.hasPredicate(q));

        // 空 ids / null ids → 非 null 空列表（且不触发实体解析）
        List<Long> out1 = DbQueryToQueryWrapper.filterIdsByPredicate("no.such.Entity", q, new ArrayList<>());
        Assert.assertNotNull(out1);
        Assert.assertTrue(out1.isEmpty());
        List<Long> out2 = DbQueryToQueryWrapper.filterIdsByPredicate("no.such.Entity", q, null);
        Assert.assertNotNull(out2);
        Assert.assertTrue(out2.isEmpty());

        // 无谓词 → 原样返回（全透传，同引用）
        List<Long> src = Arrays.asList(5L, 7L);
        Assert.assertSame("无谓词原样返回", src, DbQueryToQueryWrapper.filterIdsByPredicate("no.such.Entity", null, src));
    }

    // ============== TR1.4 边界值 ==============
    @Test(timeout = 30000)
    public void tr14_boundaryValues() {
        QueryWrapper<?> w = DbQueryToQueryWrapper.convert(query(
                crit(DbQueryOp.BETWEEN, "p", "5", "5", null),
                crit(DbQueryOp.IN, "t", null, null, Arrays.asList("only")),
                crit(DbQueryOp.EQ, "t", null, null, null),
                crit(DbQueryOp.IS_NULL, "t", null, null, null)));
        Assert.assertEquals("BETWEEN a==b min", "5", at(w, 0).a);
        Assert.assertEquals("BETWEEN a==b max", "5", at(w, 0).b);
        Assert.assertEquals("IN 单元素", 1, ((Collection<?>) at(w, 1).a).size());
        Assert.assertNull("EQ 值 null 保留", at(w, 2).a);
        Assert.assertEquals("IS_NULL 无值", QueryWrapper.Op.IS_NULL, at(w, 3).op);
    }

    // ============== TR1.5 纯函数确定性 ==============
    @Test(timeout = 30000)
    public void tr15_pureFunctionDeterminism() {
        DbQuery q = query(
                crit(DbQueryOp.EQ, "a", "1", null, null),
                crit(DbQueryOp.BETWEEN, "b", "2", "9", null),
                crit(DbQueryOp.IN, "c", null, null, Arrays.asList("x", "y")));
        q.anyOf.add(new DbQueryOrGroup(Arrays.asList(crit(DbQueryOp.GT, "d", "7", null, null))));

        String s1 = snapshot(DbQueryToQueryWrapper.convert(q));
        String s2 = snapshot(DbQueryToQueryWrapper.convert(q));
        Assert.assertEquals("同输入同输出（确定性）", s1, s2);
        Assert.assertEquals("两次转换 criteria 数一致", 3, DbQueryToQueryWrapper.convert(q).criteria().size());
        Assert.assertEquals("anyOf → 1 orBranch", 1, DbQueryToQueryWrapper.convert(q).orBranches().size());
        Assert.assertTrue("含 anyOf 条件 → hasPredicate", DbQueryToQueryWrapper.hasPredicate(q));
    }
}
