package ds;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.FulltextStore.DocHit;
import com.q3lives.ds.database.fulltext.TermDictStore;
import com.q3lives.ds.database.fulltext.TermDictStore.TuRow;

/**
 * T7-3（REV8）：scope 倒排（HC-11）。
 * `(scopeKey,scopeValue) → {unitId}` + `unitId → scopeSet` 双向倒排；
 * 复用既有物理原语（DsHashMap/DsFixedBucketStore），**0 第 4 store**；检索按 scope 过滤。
 */
public class P21_T7_ScopeTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File root;
    private FulltextStore store;
    private TermDictStore td;

    @Before
    public void before() throws IOException {
        root = tmp.newFolder("p21-t7-sc-" + UUID.randomUUID().toString().replace("-", ""));
        System.setProperty("p2p.db.home", root.getAbsolutePath());
        store = new FulltextStore(root.getAbsolutePath());
        td = store.getTermDict();
    }

    @After
    public void after() {
        try { store.close(); } catch (Exception ignore) {}
    }

    // ---------------- TR1【AC-FR10-2】：scope 过滤——dept:HR 只召回 HR block，FIN 被过滤 ----------------
    @Test
    public void tr1_scopeFilterHrOnly() throws IOException {
        long dHr = 501L, dFin = 502L;
        store.ingestDoc(dHr, "热爱人民服务");
        store.ingestDoc(dFin, "人民币汇率波动");
        long hrBlock = blockUnit(dHr);
        long finBlock = blockUnit(dFin);
        store.putScope(hrBlock, "dept", "HR");
        store.putScope(finBlock, "dept", "FIN");

        // 无 scope：两文档均因「人民」char 层择优召回
        Set<Long> all = docs(store.recallDocsByChars("人民", 10));
        Assert.assertTrue("无 scope 时 HR 召回", all.contains(dHr));
        Assert.assertTrue("无 scope 时 FIN 召回", all.contains(dFin));

        // scope=dept:HR：只召回 HR，FIN 被过滤
        Set<Long> hr = docs(store.recallDocsByCharsScoped("人民", 10, "dept", "HR"));
        Assert.assertTrue("dept:HR 召回 HR block", hr.contains(dHr));
        Assert.assertFalse("dept:HR 过滤掉 FIN block", hr.contains(dFin));

        Set<Long> fin = docs(store.recallDocsByCharsScoped("人民", 10, "dept", "FIN"));
        Assert.assertTrue("dept:FIN 召回 FIN block", fin.contains(dFin));
        Assert.assertFalse("dept:FIN 过滤掉 HR block", fin.contains(dHr));

        // 正向倒排：unitIdsWithScope((dept,HR)) 命中 HR block unit，不含 FIN block unit
        long[] fwd = store.unitIdsWithScope("dept", "HR");
        Assert.assertTrue("正向命中 HR block unit", contains(fwd, hrBlock));
        Assert.assertFalse("正向不含 FIN block unit", contains(fwd, finBlock));
    }

    // ---------------- TR2【AC-FR10-3】：0 第 4 store——scope 复用既有物理原语 ----------------
    @Test
    public void tr2_noFourthStore() throws IOException {
        store.ingestDoc(601L, "热爱人民服务");
        store.putScope(blockUnit(601L), "cls", "SECRET");

        // scope 落盘在既有 maps/ 目录下的 DsHashMap 文件（复用原语，非新增 store 类型）
        Assert.assertTrue("scope 正向倒排复用 DsHashMap（maps/）", new File(root, "maps/scope_fwd.dshash").exists());
        Assert.assertTrue("scope 反向倒排复用 DsHashMap（maps/）", new File(root, "maps/scope_rev.dshash").exists());

        // 不引入第 4 种 store：root 下无 scope 命名的额外 store 目录
        File[] subs = root.listFiles(File::isDirectory);
        if (subs != null) for (File d : subs) {
            Assert.assertFalse("不得新增 scope store 目录: " + d.getName(), d.getName().toLowerCase().contains("scope"));
        }
    }

    // ---------------- TR3【HC-11 双向】：unitId → scopeSet 反向可读，且 (key,value) 成对 ----------------
    @Test
    public void tr3_reverseBidirectional() throws IOException {
        long block = blockUnit(store.ingestDoc(701L, "热爱人民服务").docId);
        store.putScope(block, "dept", "HR");
        store.putScope(block, "cls", "SECRET");
        store.putScope(block, "dept", "HR"); // 幂等：重复写入不翻倍

        long[] rev = store.scopesOf(block);
        Assert.assertEquals("两组 scope = 4 个 long（扁平 pair 且去重）", 4, rev.length);
        Assert.assertTrue("含 dept", contains(rev, td.toUid("dept", false)));
        Assert.assertTrue("含 HR", contains(rev, td.toUid("HR", false)));
        Assert.assertTrue("含 cls", contains(rev, td.toUid("cls", false)));
        Assert.assertTrue("含 SECRET", contains(rev, td.toUid("SECRET", false)));
    }

    /** HC-9 实体树：DS5 章节 → 其 block 子节点。 */
    private long blockUnit(long docId) throws IOException {
        for (TuRow ch : store.selectByDocLevel(docId, FulltextStore.DS5_DOC_STRUCT)) {
            List<TuRow> blocks = store.selectByParentLevel(docId, ch.unit_id(), FulltextStore.DS4_BLOCK);
            if (!blocks.isEmpty()) return blocks.get(0).unit_id();
        }
        Assert.fail("存在 block 实例");
        return -1L;
    }

    private static Set<Long> docs(List<DocHit> hits) {
        Set<Long> s = new HashSet<>();
        for (DocHit h : hits) s.add(h.docId);
        return s;
    }

    private static boolean contains(long[] arr, long v) {
        for (long x : arr) if (x == v) return true;
        return false;
    }
}
