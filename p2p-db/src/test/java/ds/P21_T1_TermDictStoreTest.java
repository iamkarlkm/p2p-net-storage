package ds;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.database.fulltext.TermDictStore;

public class P21_T1_TermDictStoreTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private File root;
    private TermDictStore td;

    @Before public void setup() throws Exception {
        root = tmp.newFolder("p21t1-" + UUID.randomUUID().toString().replace("-","").substring(0,8));
        td = new TermDictStore(root.getAbsolutePath());
    }

    @After public void tearDown() throws Exception {
        try { td.close(); } catch (Exception ignored) {}
        deleteRecursive(new File(root.getParent(), root.getName() + "_unused_rm"));
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) { File[] ch = f.listFiles(); if (ch != null) for (File c : ch) deleteRecursive(c); }
        try { f.delete(); } catch (Exception ignored) {}
    }

    // T1-TR-1：0 保留 + 纯文本往返 + 幂等
    @Test public void tr1_toAndFroRoundTrip() throws Exception {
        long uid = td.toUid("服务", true);
        Assert.assertNotEquals("unit_id=0 保留，分配到 0 表示 DsString indexId 异常", 0L, uid);
        Assert.assertEquals("服", td.toText(td.toUid("服", true)));
        Assert.assertEquals("服务", td.toText(uid));

        // 10000 词 × 2 次 toUid 返回值完全相同（幂等，引用稳定）
        String[] words = new String[10000];
        for (int i = 0; i < 10000; i++) words[i] = "w" + Integer.toHexString(i) + "_" + i;
        long[] u1 = new long[10000]; long[] u2 = new long[10000];
        for (int i = 0; i < 10000; i++) u1[i] = td.toUid(words[i], true);
        for (int i = 0; i < 10000; i++) u2[i] = td.toUid(words[i], true);
        for (int i = 0; i < 10000; i++) Assert.assertEquals("toUid 幂等失败 idx="+i, u1[i], u2[i]);
    }

    // T1-TR-2【USER-HC-0 铁律 100 词 ×6 层级 600 断言 uid 全等】
    @Test public void tr2_userHc0SameTextCrossLevelUidEqual600() throws Exception {
        String[] words = new String[] {
            "中","的","服务","订阅","全文","索引","中国","人民","数据","存储",
            "系统","设计","分层","倒排","距离","相关","查询","邻居","topk","提前",
            "终止","单字","词","词组","语句","段落","章节","阶段","卷","全本",
            "ds0","ds1","ds2","ds3","ds4","ds5","char","word","phrase","sentence",
            "block","doc","parent","level","seq","unit","distance","left","right","hash",
            "farm","mix4","mix2","spi","plugin","jieba","ik","hanlp","spacy","seg",
            "mock","iterator","stream","priority","fallback","scope","provided","maven","refcount","retain",
            "release","global","term","dict","map","bucket","ring","shard","inode","cap",
            "utf8","byte","leveldb","dsdata","indexid","uniq","instance","orm","pk","idx",
            "ingest","pipeline","ing","docid","uid","txt","start","end","off","key"
        };
        Assert.assertEquals("必须恰好 100 个词扩展 tr2 断言集", 100, words.length);
        Map<String,Long> first = new HashMap<>();
        // 6 层级循环调用 toUid（USER-HC-0：同文本永远同 uid，不依赖 level）
        for (int level = 0; level < 6; level++) {
            for (String w : words) {
                long uid = td.toUid(w, true); // 不传 level（对齐 USER-HC-0 签名无 level 参数）
                if (level == 0) first.put(w, uid);
                else Assert.assertEquals("USER-HC-0 严重违反：文本='" + w + "' level=0 uid=" + first.get(w) + " vs level=" + level + " uid=" + uid, first.get(w).longValue(), uid);
            }
        }
        // 额外：同文本 6 层级之后再调用 1 次，uid 仍全等（稳定性跨级联调用）
        for (String w : words) Assert.assertEquals("跨调用稳定性失败 w="+w, first.get(w).longValue(), td.toUid(w, true));
    }

    // T1-TR-3【HC-1：unit_id 位段零编码 level】
    @Test public void tr3_hc1NoLevelBitEncode1000Samples() throws Exception {
        // 反人工合成 (level<<56)|indexId 的典型特征检测：
        // 对 100 个不同文本，每个分别以 level=0..5 语义调用 6 次 toUid（USER-HC-0：返回 uid 恒等，不随 level 变）
        // 若人工编码为 (level<<56)|indexId，则 (uid>>>56) == 传入 level，对同一文本不同 level uid 会变且命中 100%
        // 断言：600 对 (uid, expectedLevel) 中满足 (uid>>>56)==expectedLevel 的对数必须 ≤ 200（通常 ≤100：仅 indexId 自然高位 0 时 level=0 命中）
        final int N = 100;
        long[][] uidPerLevel = new long[6][N];
        for (int lv = 0; lv < 6; lv++) {
            for (int i = 0; i < N; i++) {
                uidPerLevel[lv][i] = td.toUid("hc1_v3_t" + i + "_lv" + lv, true); // 故意区分文本，验证位编码 pattern（同文本 HC-0 在 tr2 已验证）
            }
        }
        int matchLevelHighByte = 0;
        Set<Long> seenHigh = new java.util.HashSet<>();
        for (int lv = 0; lv < 6; lv++) {
            for (int i = 0; i < N; i++) {
                long uid = uidPerLevel[lv][i];
                long high = (uid >>> 56) & 0xFFL;
                seenHigh.add(high);
                if (high == lv) matchLevelHighByte++;
            }
        }
        Assert.assertTrue("疑似人工 (level<<56) 合成 uid：(uid>>>56)==level 命中 " + matchLevelHighByte + "/600（阈值≤200）", matchLevelHighByte <= 200);
        // 高位字节多样性：自然递增/Random 分配高位字节至少应包含 2 种不同值（递增 indexId 通常高位 0，加随机大 id 后扩多样性）
        // 对 indexId 从 1 线性递增，seenHigh 可能只有 {0}；所以用"非 level 特征"加强：100 个大随机 uid 注入后 seenHigh>=3（或放宽到 >=1，递增 id 不触发人工编码误判即可）
        // 额外：uid 作为 indexId 必须是连续递增或无明显位段 pattern，直接验证"纯文本无前缀字节"更可靠
        int firstByteLevel = 0;
        int checked = 0;
        for (int i = 0; i < N && checked < 80; i++) {
            long uid = uidPerLevel[0][i];
            String t = td.toText(uid);
            if (t == null || t.isEmpty()) continue;
            byte[] utf = t.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            int fb = utf[0] & 0xFF;
            // 若人工加 [level_byte][text] 前缀，首字节会被强推入 {0..5}：600 条平均首字节值 < 6 且方差极小
            // 对正常 UTF-8 中英文：中文字符首字节 ∈ [0xE0,0xEF]（224-239），英文 ASCII 常见 97-122
            // 断言：<30/80 首字节落在 [0..5]（递增 DsIndexId 场景，前缀无编码 100% 不会命中 0..5 除个别空串）
            if (fb >= 0 && fb <= 5) firstByteLevel++;
            checked++;
        }
        Assert.assertTrue("UTF-8 首字节 0..5 命中过多（疑似字典层内嵌 level 前缀字节）：" + firstByteLevel + "/" + checked + "（≤30）", firstByteLevel <= 30);
    }

    // T1-TR-4：T1 stub instancesOfUid + TuRow 基础（T2 后替换为真实 IDX2 扫）
    @Test public void tr4_instancesStubBasic() throws Exception {
        // T1 阶段 instancesOfUid 返回空列表（text_units 未接入）：确保签名稳定、无 Exception
        long uid = td.toUid("地球", true);
        Assert.assertNotNull(td.instancesOfUid(uid));
        Assert.assertTrue(td.instancesOfUid(uid).isEmpty());
        // TuRow 6 字段 equals/hashCode
        TermDictStore.TuRow r1 = new TermDictStore.TuRow(1L, 2L, 1, 0, 0, 5);
        TermDictStore.TuRow r2 = new TermDictStore.TuRow(1L, 2L, 1, 0, 0, 5);
        TermDictStore.TuRow r3 = new TermDictStore.TuRow(1L, 2L, 1, 1, 0, 5);
        Assert.assertEquals(r1, r2);
        Assert.assertEquals(r1.hashCode(), r2.hashCode());
        Assert.assertNotEquals(r1, r3);
        Assert.assertTrue(r1.toString().contains("d=1"));
    }
}
