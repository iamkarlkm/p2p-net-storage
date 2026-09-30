package ds.cache;

import com.q3lives.ds.cache.RedisSortedSetCache;
import com.q3lives.ds.cache.RedisSortedSetCache.ScoredMember;
import java.io.File;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class RedisSortedSetCacheTest {

    private static final String TEST_DIR = "target/test-redis-sorted-set-cache";

    @Before
    public void setUp() {
        File dir = new File(TEST_DIR);
        if (dir.exists()) {
            deleteRecursively(dir);
        }
        dir.mkdirs();
    }

    @Test
    public void zaddUpdatesScoreAndZcard() throws Exception {
        RedisSortedSetCache cache = new RedisSortedSetCache(TEST_DIR, 128);
        assertEquals(1, cache.zadd("z", "a", 1.5));
        assertEquals(0, cache.zadd("z", "a", 2.0));
        assertEquals(1, cache.zadd("z", "b", 0.5));
        assertEquals(2L, cache.zcard("z"));
        assertEquals(2.0, cache.zscore("z", "a"), 0.0);
        assertNull(cache.zscore("z", "missing"));
    }

    @Test
    public void zrangeOrdersByScoreThenMember() throws Exception {
        RedisSortedSetCache cache = new RedisSortedSetCache(TEST_DIR, 128);
        cache.zadd("z", "c", 1.0);
        cache.zadd("z", "a", 1.0);
        cache.zadd("z", "b", 2.0);
        assertEquals(List.of("a", "c", "b"), cache.zrange("z", 0, -1));
        assertEquals(List.of("b", "c", "a"), cache.zrevrange("z", 0, -1));
        assertEquals(List.of("c"), cache.zrange("z", 1, 1));
    }

    @Test
    public void zrangeByScoreIsClosedInterval() throws Exception {
        RedisSortedSetCache cache = new RedisSortedSetCache(TEST_DIR, 128);
        cache.zadd("z", "a", 1.0);
        cache.zadd("z", "b", 2.0);
        cache.zadd("z", "c", 3.0);
        List<ScoredMember> hit = cache.zrangeByScore("z", 2.0, 3.0);
        assertEquals(2, hit.size());
        assertEquals("b", hit.get(0).member);
        assertEquals("c", hit.get(1).member);
    }

    @Test
    public void zremAndZremrangeByScore() throws Exception {
        RedisSortedSetCache cache = new RedisSortedSetCache(TEST_DIR, 128);
        cache.zadd("z", "a", 1.0);
        cache.zadd("z", "b", 2.0);
        cache.zadd("z", "c", 3.0);
        assertEquals(1, cache.zrem("z", "b"));
        assertEquals(0, cache.zrem("z", "b"));
        assertEquals(1, cache.zremrangeByScore("z", 3.0, 3.0));
        assertEquals(List.of("a"), cache.zrange("z", 0, -1));
        cache.zrem("z", "a");
        assertEquals(0L, cache.zcard("z"));
        assertTrue(cache.zrange("z", 0, -1).isEmpty());
    }

    @Test
    public void zincrbyCreatesAndIncrements() throws Exception {
        RedisSortedSetCache cache = new RedisSortedSetCache(TEST_DIR, 128);
        assertEquals(1.0, cache.zincrby("z", "m", 1.0), 0.0);
        assertEquals(3.5, cache.zincrby("z", "m", 2.5), 0.0);
        assertEquals(3.5, cache.zscore("z", "m"), 0.0);
    }

    @Test
    public void persistenceReloadsMembers() throws Exception {
        RedisSortedSetCache cache = new RedisSortedSetCache(TEST_DIR, 128);
        cache.zadd("z", "a", 9.0);
        RedisSortedSetCache reopened = new RedisSortedSetCache(TEST_DIR, 128);
        assertEquals(List.of("a"), reopened.zrange("z", 0, -1));
        assertEquals(9.0, reopened.zscore("z", "a"), 0.0);
    }

    @Test
    public void zrangeWithScoresAndDel() throws Exception {
        RedisSortedSetCache cache = new RedisSortedSetCache(TEST_DIR, 128);
        cache.zadd("z", "a", 1.0);
        List<ScoredMember> scored = cache.zrangeWithScores("z", 0, -1);
        assertEquals(1, scored.size());
        assertEquals("a", scored.get(0).member);
        assertEquals(1.0, scored.get(0).score, 0.0);
        cache.del("z");
        assertEquals(0L, cache.zcard("z"));
    }

    private void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File f : files) {
                    deleteRecursively(f);
                }
            }
        }
        file.delete();
    }
}
