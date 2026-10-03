package ds.cache;

import com.q3lives.ds.cache.RedisGeoCache;
import com.q3lives.ds.cache.RedisGeoCache.GeoMember;
import com.q3lives.ds.cache.RedisGeoCache.GeoPoint;
import java.io.File;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class RedisGeoCacheTest {

    private static final String TEST_DIR = "target/test-redis-geo-cache";

    @Before
    public void setUp() {
        File dir = new File(TEST_DIR);
        if (dir.exists()) {
            deleteRecursively(dir);
        }
        dir.mkdirs();
    }

    @Test
    public void geoaddUpdatesAndGeocard() throws Exception {
        RedisGeoCache cache = new RedisGeoCache(TEST_DIR, 128);
        assertEquals(1, cache.geoadd("g", 121.47, 31.23, "a"));
        assertEquals(0, cache.geoadd("g", 121.48, 31.24, "a"));
        assertEquals(1, cache.geoadd("g", 121.49, 31.25, "b"));
        assertEquals(2L, cache.geocard("g"));
        GeoPoint point = cache.geopos("g", "a");
        assertEquals(121.48, point.lon, 0.0);
        assertEquals(31.24, point.lat, 0.0);
        assertNull(cache.geopos("g", "missing"));
    }

    @Test
    public void georadiusOrdersByDistanceThenMember() throws Exception {
        RedisGeoCache cache = new RedisGeoCache(TEST_DIR, 128);
        cache.geoadd("g", 121.4700, 31.2300, "near");
        cache.geoadd("g", 121.4800, 31.2300, "mid");
        cache.geoadd("g", 116.4074, 39.9042, "far");
        List<GeoMember> hit = cache.georadius("g", 121.4700, 31.2300, 2.0);
        assertEquals(2, hit.size());
        assertEquals("near", hit.get(0).member);
        assertEquals("mid", hit.get(1).member);
        assertTrue(hit.get(0).distanceKm <= hit.get(1).distanceKm);
        assertTrue(hit.get(1).distanceKm <= 2.0);
    }

    @Test
    public void georadiusLimitAndClosedRadius() throws Exception {
        RedisGeoCache cache = new RedisGeoCache(TEST_DIR, 128);
        cache.geoadd("g", 121.4700, 31.2300, "a");
        cache.geoadd("g", 121.4710, 31.2300, "b");
        cache.geoadd("g", 121.4720, 31.2300, "c");
        List<GeoMember> limited = cache.georadius("g", 121.4700, 31.2300, 5.0, 2);
        assertEquals(2, limited.size());
        assertEquals("a", limited.get(0).member);
        List<GeoMember> exact = cache.georadius("g", 121.4700, 31.2300, 0.0);
        assertEquals(1, exact.size());
        assertEquals("a", exact.get(0).member);
        assertEquals(0.0, exact.get(0).distanceKm, 0.000001);
    }

    @Test
    public void georemAndDel() throws Exception {
        RedisGeoCache cache = new RedisGeoCache(TEST_DIR, 128);
        cache.geoadd("g", 121.47, 31.23, "a");
        cache.geoadd("g", 121.48, 31.23, "b");
        assertEquals(1, cache.georem("g", "b"));
        assertEquals(0, cache.georem("g", "b"));
        assertEquals(1L, cache.geocard("g"));
        cache.del("g");
        assertEquals(0L, cache.geocard("g"));
        assertTrue(cache.georadius("g", 121.47, 31.23, 10).isEmpty());
    }

    @Test
    public void geodistAndGeohash() throws Exception {
        RedisGeoCache cache = new RedisGeoCache(TEST_DIR, 128);
        cache.geoadd("g", 121.4737, 31.2304, "sha");
        cache.geoadd("g", 116.4074, 39.9042, "bj");
        Double dist = cache.geodist("g", "sha", "bj");
        assertNotNull(dist);
        assertEquals(1068.0, dist, 20.0);
        assertNull(cache.geodist("g", "sha", "missing"));
        String hash = cache.geohash("g", "sha");
        assertEquals(11, hash.length());
        assertEquals(hash, cache.geohash("g", "sha"));
        assertNull(cache.geohash("g", "missing"));
    }

    @Test
    public void persistenceReloadsMembers() throws Exception {
        RedisGeoCache cache = new RedisGeoCache(TEST_DIR, 128);
        cache.geoadd("g", 121.47, 31.23, "a");
        RedisGeoCache reopened = new RedisGeoCache(TEST_DIR, 128);
        GeoPoint point = reopened.geopos("g", "a");
        assertEquals(121.47, point.lon, 0.0);
        assertEquals(31.23, point.lat, 0.0);
        assertEquals(1L, reopened.geocard("g"));
    }

    @Test
    public void rejectsInvalidCoordinates() throws Exception {
        RedisGeoCache cache = new RedisGeoCache(TEST_DIR, 128);
        try {
            cache.geoadd("g", 200.0, 31.0, "a");
            fail("expected invalid lon");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("invalid"));
        }
        try {
            cache.georadius("g", 121.0, 100.0, 1.0);
            fail("expected invalid lat");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("invalid"));
        }
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
