package ds;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.TermDictStore;
import com.q3lives.ds.database.fulltext.TermDictStore.TuRow;

public class P21_T2_TextUnitsOrmTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private File root;
    private FulltextStore store;
    private TermDictStore td;

    @Before public void setup() throws Exception {
        root = tmp.newFolder("p21t2-" + UUID.randomUUID().toString().replace("-","").substring(0,8));
        store = new FulltextStore(root.getAbsolutePath());
        td = store.getTermDict();
    }

    @After public void tearDown() throws Exception {
        try { store.close(); } catch (Exception ignored) {}
    }

    // T2-TR-1：复合 PK dup 抛 IAE("uk dup")；同 uid 跨 level 2 行合法；3 IDX 查询一致性
    @Test public void tr1_pkDupUkAnd3IndexConsistent() throws Exception {
        final long DOC = 7L, PARENT = 8L;
        // A. 同 PK 第 2 次 insert 必须抛 IAE("uk dup")
        long uidA = td.toUid("订阅", true);
        TuRow r1 = new TuRow(uidA, DOC, PARENT, 1, 0, 0, 2, "订阅");
        store.insert(r1);
        try {
            store.insert(new TuRow(uidA, DOC, PARENT, 1, 0, 0, 2, "订阅"));
            Assert.fail("复合 PK 完全重复未抛 IAE(\"uk dup\")（HC-6 约束）");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue("异常信息不含 uk dup：" + expected.getMessage(), expected.getMessage().contains("uk dup"));
        }
        // B. 同 uid 跨 level = 不同合法行（USER-HC-0：uid 纯文本，不依赖 level；同 uid 实例跨 level 多行）
        long uidDing = td.toUid("订", true);
        store.insert(new TuRow(uidDing, DOC, PARENT+1, 0, 0, 0, 1, "订"));
        store.insert(new TuRow(uidA, DOC, PARENT+2, 2, 1, 0, 4, "订阅")); // level=2 phrase, 文本="订阅"=uidA
        Assert.assertNotNull("ds2 行未插入（跨 level 实例应合法）", store.selectByPk(uidA, DOC, PARENT+2, 2));
        Assert.assertNotEquals("USER-HC-0 语义检查：'订'!= '订阅' uid 不应相同：" + uidDing + " vs " + uidA, uidDing, uidA);

        // C. 3 IDX 一致性：IDX1/IDX2/IDX3 扫行数 = 实际 insert 数
        // seq 从 1..200，避开 r1 seq=0，确保严格升序无 dup
        int inserts = 0;
        for (int i = 0; i < 200; i++) {
            long uid = td.toUid("w" + i, true);
            int seq = i + 1;
            try {
                store.insert(new TuRow(uid, DOC, PARENT, 1, seq, seq*3, seq*3+3, "w"+i));
                inserts++;
            } catch (IllegalArgumentException ignore) { }
        }
        Assert.assertEquals("insert 200 行数异常", 200, inserts);
        // IDX1：(DOC,PARENT,ds1=1) → 200 行 + 1 行 r1(seq=0) = 201
        List<TuRow> rows1 = store.selectByParentLevel(DOC, PARENT, 1);
        Assert.assertEquals("IDX1(DOC,PARENT,ds1) 行数不一致（r1(seq=0) + 200 w(seq=1..200)）", 201, rows1.size());
        // 按 seq 严格递增（LL-7 兄弟序列 O(N log N) 排序稳定）：r1 seq=0 排第一个，w1..w200 跟随
        Assert.assertEquals("第 0 行应为 r1(seq=0)", 0, rows1.get(0).seq);
        for (int i = 1; i < rows1.size(); i++) {
            int expectedSeq = i; // 0 + i（第 0 个 0，第 1 个 1，…，第 200 个 200）
            Assert.assertTrue("IDX1 seq 未严格升序 i="+i+": prev="+rows1.get(i-1).seq+" cur="+rows1.get(i).seq,
                rows1.get(i-1).seq < rows1.get(i).seq);
            Assert.assertEquals("IDX1 seq 值应为连续 i="+i+": "+rows1.get(i).seq, expectedSeq, rows1.get(i).seq);
        }
        // IDX2：uidA="订阅" 的 instances = 跨 2 条（r1 ds1 word=订阅 + ds2 phrase=订阅）
        Assert.assertEquals("IDX2(uidA) 跨 level 聚合行数不一致", 2, store.selectByUnit(uidA).size());
        // IDX2 加强：uidDing="订" 只出现 1 次（ds0 char）
        Assert.assertEquals("IDX2(uidDing) 行应为 1", 1, store.selectByUnit(uidDing).size());
        // IDX3：(DOC,ds1) 行数 = 201
        Assert.assertEquals("IDX3(DOC,ds1) 行数不一致", 201, store.selectByDocLevel(DOC, 1).size());
    }

    // T2-TR-3【USER-HC-0 铁律扩展 T1 到 text_units 实例行】：2 句跨 6 level 写入，同文本 uid 恒等；同 uid 跨 level 2 行合法
    @Test public void tr3_userHc0InstancesCrossLevel() throws Exception {
        final long DOC = 11L;
        final long PARENT_S1 = 100L, PARENT_S2 = 101L;
        String S1 = "全文索引系统";
        String S2 = "订阅和服务的距离";
        long uidS1 = td.toUid(S1, true);
        long uidS2 = td.toUid(S2, true);
        // 跨 6 level 插入实例（6×2=12 行），同一文本 uid 恒等；每行 PK= 唯一 (unitId, doc, parent, level)
        String[] crossLevelTexts = new String[] {"全","文","索","引","系","统","订","阅","和","服","务","的","距","离"};
        // 60 断言：同一 crossLevelTexts[i] 跨 level 0..5 插入 → 调用 toUid(t, true) 恒等
        for (int i = 0; i < crossLevelTexts.length; i++) {
            String t = crossLevelTexts[i];
            long firstUid = -1L;
            for (int lv = 0; lv < 6; lv++) {
                long uid = td.toUid(t, true);
                if (lv == 0) firstUid = uid;
                else Assert.assertEquals("USER-HC-0 实例跨 level 写入：文本=\""+t+"\" lv=0 uid="+firstUid+" vs lv="+lv+" uid="+uid, firstUid, uid);
                // PK 唯一：parent = PARENT_S1+i×6+lv
                store.insert(new TuRow(uid, DOC, PARENT_S1*1000 + i*6 + lv, lv, i, i, i+1, t));
            }
        }
        // 验证 2 句 ds3(sentence) 插入并返回 1 条
        store.insert(new TuRow(uidS1, DOC, PARENT_S1, 3, 0, 0, S1.length(), S1));
        store.insert(new TuRow(uidS2, DOC, PARENT_S2, 3, 1, 0, S2.length(), S2));
        TuRow s1 = store.selectByPk(uidS1, DOC, PARENT_S1, 3);
        TuRow s2 = store.selectByPk(uidS2, DOC, PARENT_S2, 3);
        Assert.assertEquals(S1, s1.text); Assert.assertEquals(S2, s2.text);
        Assert.assertEquals("ds3=3 行 level 不对", 3, s1.level);
        // 跨 level 总行数（14×6 单字 ×6level + 2 句 = 86）
        List<TuRow> allByDoc3 = new ArrayList<>();
        for (int lv = 0; lv < 6; lv++) allByDoc3.addAll(store.selectByDocLevel(DOC, lv));
        Assert.assertTrue("总插入行过少："+allByDoc3.size()+"（期望≥84）", allByDoc3.size() >= 84);
    }

    // T2-TR-4：200 行批量 upsert 幂等（2×200 插入，200 IAE，IDX2 size=200 稳定）
    @Test public void tr4_bulk200UpsertIdempotent() throws Exception {
        final long DOC = 33L, PARENT = 44L;
        int N = 200;
        int iae = 0;
        // round1 200 insert
        for (int i = 0; i < N; i++) {
            String t = "bulk" + i;
            long uid = td.toUid(t, true);
            store.insert(new TuRow(uid, DOC, PARENT, 1, i, i*5, i*5+5, t));
        }
        // round2 200 insert 同样 PK → 全 IAE("uk dup")
        for (int i = 0; i < N; i++) {
            String t = "bulk" + i;
            long uid = td.toUid(t, true);
            try { store.insert(new TuRow(uid, DOC, PARENT, 1, i, i*5, i*5+5, t)); }
            catch (IllegalArgumentException e) { if (e.getMessage().contains("uk dup")) iae++; }
        }
        Assert.assertEquals("round2 应为 200 次 UK IAE（幂等性）", N, iae);
        // IDX1 行数仍 = 200（无新增）
        List<TuRow> rows = store.selectByParentLevel(DOC, PARENT, 1);
        Assert.assertEquals("幂等后 IDX1 行数增长（漏检查 PK dup）", N, rows.size());
        // 每 seq 唯一升序
        boolean[] seen = new boolean[N];
        for (TuRow r : rows) {
            Assert.assertFalse("seq dup: " + r.seq, seen[r.seq]);
            seen[r.seq] = true;
        }
    }
}
