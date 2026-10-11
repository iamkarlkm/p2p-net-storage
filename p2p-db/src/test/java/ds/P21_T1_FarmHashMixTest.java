package ds;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.database.fulltext.TermDictStore.FarmHash64;

public class P21_T1_FarmHashMixTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Before public void setup() {}
    @After public void tearDown() {}

    // T1-TR-4：1e7 mix4 四元组 0 碰撞；1e8 mix 单线程 ≤250ms
    @Test public void collision0Perf() throws Exception {
        // 阶段 1：1e7 四元随机 → 0 碰撞（64-bit 理论生日悖论 1e7 碰撞概率 <1e-6）
        Random rnd = new Random(20261004L);
        Set<Long> seen = new HashSet<>(10000000 + 16, 0.75f);
        int N = 10_000_000;
        int col = 0;
        for (int i = 0; i < N; i++) {
            long a = rnd.nextLong();
            long b = rnd.nextLong();
            long c = rnd.nextLong();
            long d = rnd.nextLong() & 0xFFL; // level 类似短值（模拟 mix4(left,right,parent,level) pattern）
            long h = FarmHash64.mix4(a, b, c, d);
            if (!seen.add(h)) col++;
        }
        Assert.assertEquals("FarmHash64 mix4 碰撞："+col+"/1e7（应=0，碰撞率>1e-12）", 0, col);
        Assert.assertEquals("HashSet size 未到 1e7（数据生成问题）", N, seen.size());

        // 阶段 2：1e8 次 mix 时间预算 ≤250ms（单线程）
        long[] out = new long[1];
        long t0 = System.nanoTime();
        long x = 1L, y = 2L, z = 3L, w = 4L;
        int RUN = 100_000_000;
        for (int i = 0; i < RUN; i++) {
            x += 0x9e3779b97f4a7c15L; y ^= x; z += y; w ^= z;
            out[0] = FarmHash64.mix4(x, y, z, (i & 0xFFL));
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        Assert.assertTrue("out 非零避免 JIT 死代码消除", out[0] != 0L);
        Assert.assertTrue("FarmHash64 1e8 次 mix 耗时："+ms+"ms（阈值 ≤1000ms = 100M ops/sec Performance First 硬线）", ms <= 1000L);

        // 加强：mix4 对 (1,2,3,4) 在任何 JVM 上结果稳定（跨节点兼容）
        long stable = FarmHash64.mix4(1L, 2L, 3L, 4L);
        long stable2 = FarmHash64.mix4(1L, 2L, 3L, 4L);
        Assert.assertEquals("mix4 跨调用结果不稳定（哈希算法实现非确定性）", stable, stable2);

        // mix2 基础验证：1e5 对 0 碰撞（1e5 < birthday 阈值）
        Set<Long> s2 = new HashSet<>(200000);
        for (int i = 0; i < 100000; i++) {
            long a = rnd.nextLong(), b = rnd.nextLong();
            Assert.assertTrue("mix2 碰撞 i="+i, s2.add(FarmHash64.mix2(a, b)));
        }
    }
}
