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
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHit;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.TermDictStore.TuRow;

/**
 * T7-1（REV8 / HC-9）：实体包含树 + 向上聚合。
 * ingestDoc 由「同文扁平 6 路（parentId=docId*10+lv 假父）」升级为**父包含子实体树**：
 * sentence(3).parent → block(4)；block(4).parent → 章节 DS5(5)；章节 DS5.parent → doc 根锚；
 * 且**不越层**（章节及以上只倒排其直接下级，block 只含 sentence 级、章节不冗余 sentence）。
 */
public class P21_T7_EntityTreeTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private FulltextStore store;

    /** 两章文本：标题行后紧跟正文（无空行），故 block 文本 ≠ 章节标题 ≠ 任一句 → 规避 USER-HC-0 同文本 uid 混叠。 */
    private static final String TREE_TEXT =
            "# 第一章\n"
            + "第一句内容测试。第二句内容测试。\n"
            + "\n"
            + "# 第二章\n"
            + "第三句内容测试。第四句内容测试。";

    @Before
    public void before() throws IOException {
        File root = tmp.newFolder("p21-t7-et-" + UUID.randomUUID().toString().replace("-", ""));
        System.setProperty("p2p.db.home", root.getAbsolutePath());
        store = new FulltextStore(root.getAbsolutePath());
    }

    @After
    public void after() {
        try { store.close(); } catch (Exception ignore) {}
    }

    // ---------------- TR1【AC-FR9-1】：实体树自底向上回溯 ≥3 级（sentence→block→章节→doc） ----------------
    @Test
    public void tr1_entityTreeUpwardThreeLevels() throws IOException {
        long docId = 9001L;
        store.ingestDoc(docId, TREE_TEXT);
        String sentence = "第二句内容测试。";

        // ① sentence 实例（level 3）
        TuRow sent = findByText(store.selectByDocLevel(docId, FulltextStore.DS3_SENTENCE), sentence);
        Assert.assertNotNull("存在句子实例: " + sentence, sent);

        // hop1：sentence.parentId → 同 doc 的 block(4) 实例（真实父实体，非假父）
        long blockUid = sent.parentId;
        TuRow block = findRow(docId, blockUid, FulltextStore.DS4_BLOCK);
        Assert.assertNotNull("hop1 sentence.parentId 命中同 doc 的 block 实例: " + blockUid, block);
        Assert.assertNotEquals("句行 parentId 非假父 docId*10+lv",
                docId * 10L + FulltextStore.DS4_BLOCK, sent.parentId);

        // hop2：block.parentId → 章节 DS5(5) 节点
        long chapterUid = block.parentId;
        TuRow chapter = findRow(docId, chapterUid, FulltextStore.DS5_DOC_STRUCT);
        Assert.assertNotNull("hop2 block.parentId 命中章节 DS5 节点: " + chapterUid, chapter);
        Assert.assertNotEquals("块行 parentId 非假父 docId*10+lv",
                docId * 10L + FulltextStore.DS5_DOC_STRUCT, block.parentId);

        // hop3：章节.parentId → doc 根锚
        Assert.assertEquals("hop3 章节.parentId = doc 根锚（≥3 级回溯链闭合）", docId, chapter.parentId);
        Assert.assertTrue("章节节点文本为标题: " + chapter.text, chapter.text.startsWith("#"));
    }

    // ---------------- TR2【AC-FR9-2】：含句检索 → 命中 block/segment → 向上聚合章节（不越层） ----------------
    @Test
    public void tr2_sentenceHitAggregatesToBlockAndChapter() throws IOException {
        long docId = 9002L;
        store.ingestDoc(docId, TREE_TEXT);
        String sentence = "第四句内容测试。";

        // sentence 倒排命中（含句检索，level=3）
        DocHit hit = null;
        for (DocHit h : store.byTerm(sentence)) {
            if (h.docId == docId && h.level == FulltextStore.DS3_SENTENCE) hit = h;
        }
        Assert.assertNotNull("sentence 倒排命中（含句检索）", hit);

        // 定位该句所属 block/segment
        TuRow block = findRow(docId, hit.parentId, FulltextStore.DS4_BLOCK);
        Assert.assertNotNull("命中句 → 其 block/segment", block);

        // 沿 parent_id 向上聚合到章节
        TuRow chapter = findRow(docId, block.parentId, FulltextStore.DS5_DOC_STRUCT);
        Assert.assertNotNull("block → 向上聚合命中章节 DS5", chapter);
        Assert.assertEquals("聚合到第二章标题", "# 第二章", chapter.text);

        // 章节的直接下级确实包含该 block（聚合链自洽）
        Assert.assertTrue("章节含该 block 为其直接下级",
                containsUid(store.selectByParentLevel(docId, chapter.unit_id(), FulltextStore.DS4_BLOCK), block.unit_id()));

        // 端到端：search(整句) 命中目标 doc
        boolean hitDoc = false;
        for (ScoredDocHit h : store.search(sentence, 10, SearchOptions.defaults())) {
            if (h.docId == docId) hitDoc = true;
        }
        Assert.assertTrue("search(整句) 命中目标 doc", hitDoc);
    }

    // ---------------- TR3【AC-FR9-3】：不越层——章节只倒排直接下级 block，不冗余 sentence ----------------
    @Test
    public void tr3_noCrossLevelRedundancy() throws IOException {
        long docId = 9003L;
        store.ingestDoc(docId, TREE_TEXT);

        List<TuRow> chapters = store.selectByDocLevel(docId, FulltextStore.DS5_DOC_STRUCT);
        Assert.assertEquals("两标题 → 两章节 DS5 节点", 2, chapters.size());
        Set<Long> chapterUids = new HashSet<>();
        for (TuRow ch : chapters) {
            Assert.assertTrue("章节节点文本为标题（非句子冗余）: " + ch.text, ch.text.startsWith("#"));
            chapterUids.add(ch.unit_id());
        }

        // 章节及以上只倒排其直接下级（block）；不冗余 sentence 级
        for (TuRow ch : chapters) {
            Assert.assertFalse("章节含其直接下级 block: " + ch.text,
                    store.selectByParentLevel(docId, ch.unit_id(), FulltextStore.DS4_BLOCK).isEmpty());
            Assert.assertTrue("章节不冗余倒排 sentence 级（不越层）: " + ch.text,
                    store.selectByParentLevel(docId, ch.unit_id(), FulltextStore.DS3_SENTENCE).isEmpty());
        }

        // 每个 sentence 的父实体都是 block（level 4），即未扁平化到章节/doc
        List<TuRow> sents = store.selectByDocLevel(docId, FulltextStore.DS3_SENTENCE);
        Assert.assertFalse("存在句子实例", sents.isEmpty());
        for (TuRow s : sents) {
            Assert.assertNotNull("sentence.parentId 命中 block 实例: " + s.text,
                    findRow(docId, s.parentId, FulltextStore.DS4_BLOCK));
        }

        // 每个 block 的父实体都是章节 DS5（非 doc 根），即未扁平化
        for (TuRow b : store.selectByDocLevel(docId, FulltextStore.DS4_BLOCK)) {
            Assert.assertTrue("block.parentId 为章节 DS5 节点: " + b.text, chapterUids.contains(b.parentId));
        }
    }

    /** 在 (docId, level) 下按 uid 取实例行（USER-HC-0：同 uid 跨 level 合法，故须 level 过滤）。 */
    private TuRow findRow(long docId, long uid, int level) throws IOException {
        for (TuRow r : store.selectByUnit(uid)) {
            if (r.docId == docId && r.level == level) return r;
        }
        return null;
    }

    private static TuRow findByText(List<TuRow> rows, String text) {
        for (TuRow r : rows) if (text.equals(r.text)) return r;
        return null;
    }

    private static boolean containsUid(List<TuRow> rows, long uid) {
        for (TuRow r : rows) if (r.unit_id() == uid) return true;
        return false;
    }
}
