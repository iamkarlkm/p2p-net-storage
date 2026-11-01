package ds;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.collections.DsHashSet;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.columnar.ColumnarStore;
import com.q3lives.ds.database.columnar.TableMetaStore;
import com.q3lives.ds.database.columnar.pushdown.ColumnScanPushdown;
import com.q3lives.ds.database.columnar.pushdown.DbQueryToQueryWrapper;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.schema.DynamicIndexUtil;

import javax.net.p2p.model.DbQuery;
import javax.net.p2p.model.DbQueryCriterion;
import javax.net.p2p.model.DbQueryOp;
import javax.net.p2p.model.DbQueryOrGroup;

/**
 * P52 T2：{@link ColumnScanPushdown} 逐 op 语义全等单测 6 TR 0F0E。
 *
 * <p>
 * TR2.1 比较算子（EQ/NE/GT/GE/LT/LE/BETWEEN）；TR2.2 IN/NOT_IN；TR2.3 LIKE 系列（含 Q4 对齐
 * ORM 的 语义修正断言）；TR2.4 IS_NULL/IS_NOT_NULL + null/空 where；TR2.5 anyOf OR 分支（全局
 * where ∩ 组）；TR2.6 边界值（BETWEEN a==b / 单元素 IN / 多条件 AND）与纯函数确定性。
 *
 * <p>
 * 全部 TR 以 ORM
 * 基准（{@link DbQueryToQueryWrapper#filterIdsByPredicate} → {@code GenericManager.listIds}）
 * 为 R12 真值逐条比对。夹具采用「同根双写对齐」：ORM 行（binlog/id 索引）与列存列值（{@code ColumnarStore} +
 * {@link TableMetaStore}）落在同一 {@code p2p.db.home} 根目录，行 id 一一对应。
 */
public class P52_T2_ColumnScanSemanticsTest {

    /**
     * 测试实体：tag 定长字符串（LIKE 系列）、num 整型（比较系列）、note 可空字符串（IS_NULL 系列）。
     */
    public static class Row extends DsTableAdapter {

        @DsField(name = "tag", length = 16)
        public String tag;
        @DsField(name = "num", length = 4)
        public int num;
        @DsField(name = "note", length = 16)
        public String note;

        public Row() {
        }
    }

    private File tmpRoot;
    private String oldHome;
    private GenericManager<Row> gm;
    private ColumnarStore cs;
    private long[] ids;
    private List<Long> idList;
    private final Map<String, Long> byTag = new HashMap<>();

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p52_t2_").toFile();
        DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());

        gm = new GenericManager<>(Row.class);
        new TableMetaStore(tmpRoot).ensureMeta(Row.class);
        cs = new ColumnarStore(tmpRoot);

        long a = addRow("alpha", 10, "n1");
        long b = addRow("beta", 20, null);
        long c = addRow("alphabet", 30, "n3");
        long d = addRow("gamma", 20, "n4");

        ids = new long[]{a, b, c, d};
        Arrays.sort(ids);
        cs.close();
        registerIds(ids);
        idList = box(ids);
    }

    @After
    public void after() {
        try {
            if (cs != null) {
                cs.close();
            }
        } catch (Throwable ignore) {
        }
        try {
            DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        } catch (Throwable ignore) {
        }
        try {
            DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        } catch (Throwable ignore) {
        }
        deleteRecursive(tmpRoot);
        if (oldHome == null) {
            System.clearProperty("p2p.db.home");
        } else {
            System.setProperty("p2p.db.home", oldHome);
        }
    }

    private long addRow(String tag, int num, String note) throws IOException {
        Row e = new Row();
        e.tag = tag;
        e.num = num;
        e.note = note;
        gm.saveOrUpdate(e);
        long id = e.getId();
        String ent = Row.class.getName();
        cs.putValue(ent, "tag", id, pad(tag, 16));
        cs.putValue(ent, "num", id, ByteBuffer.allocate(4).putInt(num).array());
        if (note != null) {
            cs.putValue(ent, "note", id, pad(note, 16));
        }
        byTag.put(tag, id);
        return id;
    }

    private static byte[] pad(String s, int len) {
        byte[] raw = s.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[len];
        System.arraycopy(raw, 0, out, 0, Math.min(raw.length, len));
        return out;
    }

    private void registerIds(long[] rowIds) throws IOException {
        DsHashSet set = new DsHashSet(DynamicIndexUtil.idsFile(tmpRoot, Row.class.getName()));
        for (long id : rowIds) {
            set.add(id);
        }
    }

    private static List<Long> box(long[] v) {
        List<Long> out = new ArrayList<>(v.length);
        for (long x : v) {
            out.add(x);
        }
        return out;
    }

    private List<Long> oracle(DbQuery q) {
        List<Long> out = new ArrayList<>(DbQueryToQueryWrapper.filterIdsByPredicate(
                Row.class.getName(), q, idList));
        Collections.sort(out);
        return out;
    }

    private List<Long> pushdown(DbQuery q) {
        return ColumnScanPushdown.filter(tmpRoot, Row.class.getName(), q);
    }

    /**
     * 断言：列扫描结果 ≡ ORM 基准（R12 逐条全等）+ 升序（R11）。
     */
    private void assertSameAsOracle(DbQuery q, String label) {
        List<Long> push = pushdown(q);
        List<Long> orc = oracle(q);
        Assert.assertEquals("R12 等价: " + label, orc, push);
        List<Long> sorted = new ArrayList<>(push);
        Collections.sort(sorted);
        Assert.assertEquals("R11 输出升序: " + label, sorted, push);
    }

    private List<Long> expect(String... tags) {
        List<Long> out = new ArrayList<>();
        for (String t : tags) {
            out.add(byTag.get(t));
        }
        Collections.sort(out);
        return out;
    }

    private static DbQuery q(DbQueryCriterion... cs) {
        DbQuery x = new DbQuery();
        x.where.addAll(Arrays.asList(cs));
        return x;
    }

    private static DbQueryCriterion c(DbQueryOp op, String name, String a, String b, List<String> list) {
        return new DbQueryCriterion(op, name, a, b, list);
    }

    // ============== TR2.1 比较算子 ==============
    @Test(timeout = 60000)
    public void tr21_comparisonOps() {
        assertSameAsOracle(q(c(DbQueryOp.EQ, "tag", "alpha", null, null)), "EQ tag=alpha");
        Assert.assertEquals("EQ tag=alpha 命中", expect("alpha"),
                pushdown(q(c(DbQueryOp.EQ, "tag", "alpha", null, null))));

        assertSameAsOracle(q(c(DbQueryOp.NE, "tag", "alpha", null, null)), "NE tag!=alpha");
        assertSameAsOracle(q(c(DbQueryOp.GT, "num", "10", null, null)), "GT num>10");
        assertSameAsOracle(q(c(DbQueryOp.GE, "num", "20", null, null)), "GE num>=20");
        assertSameAsOracle(q(c(DbQueryOp.LT, "num", "20", null, null)), "LT num<20");
        assertSameAsOracle(q(c(DbQueryOp.LE, "num", "20", null, null)), "LE num<=20");
        assertSameAsOracle(q(c(DbQueryOp.BETWEEN, "num", "10", "20", null)), "BETWEEN num 10..20");

        Assert.assertEquals("GT num>10 命中", expect("beta", "alphabet", "gamma"),
                pushdown(q(c(DbQueryOp.GT, "num", "10", null, null))));
    }

    // ============== TR2.2 IN / NOT_IN ==============
    @Test(timeout = 60000)
    public void tr22_inAndNotIn() {
        assertSameAsOracle(q(c(DbQueryOp.IN, "tag", null, null, Arrays.asList("alpha", "gamma"))), "IN");
        Assert.assertEquals("IN 命中", expect("alpha", "gamma"),
                pushdown(q(c(DbQueryOp.IN, "tag", null, null, Arrays.asList("alpha", "gamma")))));

        assertSameAsOracle(q(c(DbQueryOp.NOT_IN, "tag", null, null, Arrays.asList("alpha"))), "NOT_IN");
        Assert.assertEquals("NOT_IN 命中", expect("beta", "alphabet", "gamma"),
                pushdown(q(c(DbQueryOp.NOT_IN, "tag", null, null, Arrays.asList("alpha")))));

        assertSameAsOracle(q(c(DbQueryOp.IN, "num", null, null, Arrays.asList("20"))), "IN num");
        Assert.assertEquals("IN num=20 命中", expect("beta", "gamma"),
                pushdown(q(c(DbQueryOp.IN, "num", null, null, Arrays.asList("20")))));
    }

    // ============== TR2.3 LIKE 系列 + Q4 语义修正 ==============
    @Test(timeout = 60000)
    public void tr23_likeFamilyAlignedToOrm() {
        assertSameAsOracle(q(c(DbQueryOp.LIKE, "tag", "lph", null, null)), "LIKE contains lph");
        Assert.assertEquals("LIKE contains 'lph' 命中", expect("alpha", "alphabet"),
                pushdown(q(c(DbQueryOp.LIKE, "tag", "lph", null, null))));

        // Q4：LIKE_LEFT = startsWith（对齐 ORM），而非旧 handler 的 endsWith
        assertSameAsOracle(q(c(DbQueryOp.LIKE_LEFT, "tag", "a", null, null)), "LIKE_LEFT startsWith a");
        Assert.assertEquals("LIKE_LEFT 'a' → startsWith 命中", expect("alpha", "alphabet"),
                pushdown(q(c(DbQueryOp.LIKE_LEFT, "tag", "a", null, null))));

        // Q4：LIKE_RIGHT = endsWith（对齐 ORM）
        assertSameAsOracle(q(c(DbQueryOp.LIKE_RIGHT, "tag", "a", null, null)), "LIKE_RIGHT endsWith a");
        Assert.assertEquals("LIKE_RIGHT 'a' → endsWith 命中", expect("alpha", "beta", "gamma"),
                pushdown(q(c(DbQueryOp.LIKE_RIGHT, "tag", "a", null, null))));

        Assert.assertNotEquals("LIKE_LEFT 与 LIKE_RIGHT 语义已区分（Q4 修正生效）",
                pushdown(q(c(DbQueryOp.LIKE_LEFT, "tag", "a", null, null))),
                pushdown(q(c(DbQueryOp.LIKE_RIGHT, "tag", "a", null, null))));
    }

    // ============== TR2.4 IS_NULL / IS_NOT_NULL + null/空 where ==============
    @Test(timeout = 60000)
    public void tr24_nullOpsAndEmptyWhere() {
        assertSameAsOracle(q(c(DbQueryOp.IS_NULL, "note", null, null, null)), "IS_NULL note");
        Assert.assertEquals("IS_NULL note 命中", expect("beta"),
                pushdown(q(c(DbQueryOp.IS_NULL, "note", null, null, null))));

        assertSameAsOracle(q(c(DbQueryOp.IS_NOT_NULL, "note", null, null, null)), "IS_NOT_NULL note");
        Assert.assertEquals("IS_NOT_NULL note 命中", expect("alpha", "alphabet", "gamma"),
                pushdown(q(c(DbQueryOp.IS_NOT_NULL, "note", null, null, null))));

        // query == null / 空 where → 全量透传（兼容旧行为）
        Assert.assertEquals("query=null → 全量", idList,
                ColumnScanPushdown.filter(tmpRoot, Row.class.getName(), null));
        Assert.assertEquals("空 where → 全量", idList, pushdown(new DbQuery()));
    }

    // ============== TR2.5 anyOf OR 分支 ==============
    @Test(timeout = 60000)
    public void tr25_anyOfOrGroups() {
        DbQuery q = q(c(DbQueryOp.NE, "tag", "zzz", null, null));
        q.anyOf.add(new DbQueryOrGroup(Arrays.asList(c(DbQueryOp.EQ, "tag", "alpha", null, null))));
        q.anyOf.add(new DbQueryOrGroup(Arrays.asList(c(DbQueryOp.EQ, "tag", "gamma", null, null))));
        assertSameAsOracle(q, "anyOf 2 组 ∩ 全局");
        Assert.assertEquals("anyOf 命中", expect("alpha", "gamma"), pushdown(q));

        // 空组 → 全局条件
        DbQuery q2 = q(c(DbQueryOp.EQ, "num", "20", null, null));
        q2.anyOf.add(new DbQueryOrGroup());
        assertSameAsOracle(q2, "anyOf 空组 → 全局");
        Assert.assertEquals("anyOf 空组命中", expect("beta", "gamma"), pushdown(q2));

        // 仅有 anyOf 无全局
        DbQuery q3 = new DbQuery();
        q3.anyOf.add(new DbQueryOrGroup(Arrays.asList(c(DbQueryOp.EQ, "tag", "beta", null, null))));
        assertSameAsOracle(q3, "anyOf 无全局");
        Assert.assertEquals("anyOf 无全局命中", expect("beta"), pushdown(q3));
    }

    // ============== TR2.6 边界值 + 纯函数确定性 ==============
    @Test(timeout = 60000)
    public void tr26_boundaryAndDeterminism() {
        // BETWEEN a==b
        assertSameAsOracle(q(c(DbQueryOp.BETWEEN, "num", "20", "20", null)), "BETWEEN a==b");
        Assert.assertEquals("BETWEEN 20..20 命中", expect("beta", "gamma"),
                pushdown(q(c(DbQueryOp.BETWEEN, "num", "20", "20", null))));

        // 单元素 IN
        assertSameAsOracle(q(c(DbQueryOp.IN, "tag", null, null, Arrays.asList("alpha"))), "IN 单元素");
        Assert.assertEquals("IN 单元素命中", expect("alpha"),
                pushdown(q(c(DbQueryOp.IN, "tag", null, null, Arrays.asList("alpha")))));

        // 多条件 AND
        assertSameAsOracle(q(
                c(DbQueryOp.GE, "num", "20", null, null),
                c(DbQueryOp.NE, "tag", "beta", null, null)), "AND 多条件");
        Assert.assertEquals("AND 命中", expect("alphabet", "gamma"),
                pushdown(q(c(DbQueryOp.GE, "num", "20", null, null),
                        c(DbQueryOp.NE, "tag", "beta", null, null))));

        // 确定性：同输入同输出
        DbQuery stable = q(c(DbQueryOp.GE, "num", "10", null, null));
        Assert.assertEquals("确定性 1", pushdown(stable), pushdown(stable));
        Assert.assertEquals("确定性 2", pushdown(stable), pushdown(stable));
    }

    private static void deleteRecursive(File f) {
        if (f == null) {
            return;
        }
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) {
                for (File c : ch) {
                    deleteRecursive(c);
                }
            }
        }
        f.delete();
    }
}
