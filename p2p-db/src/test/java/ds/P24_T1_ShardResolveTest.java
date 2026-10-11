package ds;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHit;
import com.q3lives.ds.database.fulltext.FulltextStore.ShardMode;
import com.q3lives.ds.database.fulltext.TermDictStore;
import com.q3lives.ds.database.fulltext.TermDictStore.FarmHash64;

/**
 * P24 T1: Shard 解析 + 路由公式手算全等（4 TR）
 * Scope: ShardMode enum, resolveShardForUnitIdForTest / resolveShardForDocIdForTest HC probes
 */
public class P24_T1_ShardResolveTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private FulltextStore store;
    private File root;

    @Before
    public void setup() throws IOException {
        root = tmp.newFolder("p24t1");
        store = new FulltextStore(root.getAbsolutePath());
    }

    @After
    public void teardown() throws IOException {
        if (store != null) try { store.close(); } catch (Exception ignore) {}
    }

    // TR1.1: resolveShardForUnitIdForTest 手算全等（uid 高 16 位 mod 4）
    @Test
    public void tr11_resolveUnitShardHand() {
        // uid = 0x1234 0000 0000 0000 → 高 16 位 = 0x1234 → mod 4 = 0x1234 & 3 = 0
        long u1 = 0x1234000000000000L;
        int s1 = FulltextStore.resolveShardForUnitIdForTest(u1, 4);
        Assert.assertEquals(0x1234 % 4, s1);
        Assert.assertEquals(0, s1);
        // 0x5678... % 4 = 0
        long u2 = 0x5678000000000000L;
        int s2 = FulltextStore.resolveShardForUnitIdForTest(u2, 4);
        Assert.assertEquals(0x5678 % 4, s2);
        Assert.assertEquals(0, s2);
        // 0x0001... shard=1 mod 4=1
        long u3 = 0x0001000000000000L;
        int s3 = FulltextStore.resolveShardForUnitIdForTest(u3, 4);
        Assert.assertEquals(1, s3);
        long u4 = 0x0002000000000000L;
        int s4 = FulltextStore.resolveShardForUnitIdForTest(u4, 4);
        Assert.assertEquals(2, s4);
        long u5 = 0x0003000000000000L;
        int s5 = FulltextStore.resolveShardForUnitIdForTest(u5, 4);
        Assert.assertEquals(3, s5);
        // mod 16 真实
        long u6 = 0x0005000000000000L;
        int s6 = FulltextStore.resolveShardForUnitIdForTest(u6, 16);
        Assert.assertEquals(5, s6);
    }

    // TR1.2: resolveShardForDocIdForTest 手算全等（FarmHash.mix4 反射取返回值）
    @Test
    public void tr12_resolveDocShardHandViaMix4Reflect() throws Exception {
        Method mix4 = FarmHash64.class.getDeclaredMethod("mix4", long.class, long.class, long.class, long.class);
        mix4.setAccessible(true);
        long SEED = 0xC0FFEEL;
        long[] docIds = new long[] { 0L, 123456789L, -1L };
        for (long docId : docIds) {
            long V = (Long) mix4.invoke(null, docId, SEED, 0L, 0L);
            long shifted = (V >>> 16) & 0x7FFFFFFFL; // 正整数
            int expected4 = (int) (shifted % 4L);
            if (expected4 < 0) expected4 += 4; // 双重保险（shifted 已是正数，但防万一）
            int actual = FulltextStore.resolveShardForDocIdForTest(docId, 4);
            Assert.assertEquals("docId=" + docId + " V=" + Long.toHexString(V) + " shifted=" + shifted,
                                expected4, actual);
        }
    }

    // TR1.3: 边界（shardCount=1→0；shardCount<=0→IAE；uid=0→0；docId 边界）
    @Test
    public void tr13_resolveBoundary() {
        // shardCount=1 → 任何 uid/doc shard 0
        for (long uid = -100L; uid <= 100L; uid += 17) {
            Assert.assertEquals(0, FulltextStore.resolveShardForUnitIdForTest(uid, 1));
        }
        for (long docId = Long.MIN_VALUE; docId <= Long.MIN_VALUE + 1000; docId += 129) {
            Assert.assertEquals(0, FulltextStore.resolveShardForDocIdForTest(docId, 1));
        }
        Assert.assertEquals(0, FulltextStore.resolveShardForUnitIdForTest(0L, 7));
        // shardCount <= 0 → IAE
        try {
            FulltextStore.resolveShardForUnitIdForTest(1L, 0);
            Assert.fail("expect IAE");
        } catch (IllegalArgumentException ok) {
            Assert.assertTrue(ok.getMessage().contains("shardCount"));
        }
        try {
            FulltextStore.resolveShardForUnitIdForTest(1L, -1);
            Assert.fail("expect IAE");
        } catch (IllegalArgumentException ok) {}
        try {
            FulltextStore.resolveShardForDocIdForTest(1L, 0);
            Assert.fail("expect IAE");
        } catch (IllegalArgumentException ok) {
            Assert.assertTrue(ok.getMessage().contains("shardCount"));
        }
        try {
            FulltextStore.resolveShardForDocIdForTest(1L, -5);
            Assert.fail("expect IAE");
        } catch (IllegalArgumentException ok) {}
    }

    // TR1.4: ShardMode 枚举值顺序（AUTO/UNIT/DOC，3 元素）
    @Test
    public void tr14_shardModeEnumOrder3() {
        ShardMode[] vals = ShardMode.values();
        Assert.assertEquals(3, vals.length);
        Assert.assertEquals(ShardMode.AUTO, vals[0]);
        Assert.assertEquals(ShardMode.UNIT, vals[1]);
        Assert.assertEquals(ShardMode.DOC,  vals[2]);
        Assert.assertEquals("AUTO", ShardMode.AUTO.name());
    }

    // ScoredDocHit 第 12 字段 shardId 默认 -1（单 shard 场景）：P24 TR 前置验证（避免 T3 重复）
    @Test
    public void tr14b_scoredDocHit12thShardIdDefaultMinus1() {
        // 10 参（P22 兼容）
        ScoredDocHit h10 = new ScoredDocHit(1L, 2L, 0, 1, 3L, 0.5, 0.4, 0.3, 0.2, 2);
        Assert.assertEquals(-1, h10.shardId);
        // 11 参（P23 兼容）
        ScoredDocHit h11 = new ScoredDocHit(1L, 2L, 0, 1, 3L, 0.5, 0.4, 0.3, 0.2, 2, 0.123);
        Assert.assertEquals(-1, h11.shardId);
        // 12 参（P24 新增）
        ScoredDocHit h12 = new ScoredDocHit(1L, 2L, 0, 1, 3L, 0.5, 0.4, 0.3, 0.2, 2, 0.123, 7);
        Assert.assertEquals(7, h12.shardId);
        // SearchOptions shardMode 默认 AUTO / localTopKFactor=3 / concurrentScatter=false
        SearchOptions o = SearchOptions.defaults();
        Assert.assertEquals(ShardMode.AUTO, o.shardMode);
        Assert.assertEquals(3, o.localTopKFactor);
        Assert.assertFalse(o.concurrentScatter);
    }
}
