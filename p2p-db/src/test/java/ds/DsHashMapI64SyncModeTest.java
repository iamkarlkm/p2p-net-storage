package ds;

import com.q3lives.ds.collections.DsHashMap;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.Map;

import static org.junit.Assert.*;

public class DsHashMapI64SyncModeTest {

    private DsHashMap map;
    private File dataFile;

    @Before
    public void setUp() {
        dataFile = new File("test_dshashmap_i64_syncmode.dat");
        deleteWithSidecars(dataFile);
        map = new DsHashMap(dataFile);
        map.clear();
    }

    private static void deleteWithSidecars(File f) {
        File[] files = new File[] {
            f,
            new File(f.getAbsolutePath() + ".e16"),
            new File(f.getAbsolutePath() + ".e16.next"),
            new File(f.getAbsolutePath() + ".e16.free"),
            new File(f.getAbsolutePath() + ".e32"),
            new File(f.getAbsolutePath() + ".e32.next"),
            new File(f.getAbsolutePath() + ".e32.free"),
            new File(f.getAbsolutePath() + ".e64"),
            new File(f.getAbsolutePath() + ".e64.next"),
            new File(f.getAbsolutePath() + ".e64.free"),
            new File(f.getAbsolutePath() + ".m32"),
            new File(f.getAbsolutePath() + ".m64")
        };
        for (File x : files) {
            if (x.exists()) {
                x.delete();
            }
        }
    }

    @Test
    public void testSyncModeConfiguration() {
        Map<String, Object> m0 = map.getSyncModeMap();
        assertEquals("MANUAL", m0.get("mode"));
        assertEquals(0, ((Number) m0.get("syncEveryWriteRequests")).intValue());

        map.setSyncModeWriteRequests(2);
        Map<String, Object> m1 = map.getSyncModeMap();
        assertEquals("WRITE_REQUESTS", m1.get("mode"));
        assertEquals(2, ((Number) m1.get("syncEveryWriteRequests")).intValue());
        assertEquals(Boolean.FALSE, m1.get("rootBackgroundFlushEnabled"));

        map.setSyncModeSeconds(1);
        Map<String, Object> m2 = map.getSyncModeMap();
        assertEquals("SECONDS", m2.get("mode"));
        assertEquals(Boolean.TRUE, m2.get("rootBackgroundFlushEnabled"));
        assertEquals(6, ((Number) m2.get("enabledObjectCount")).intValue());

        map.disableSyncMode();
        Map<String, Object> m3 = map.getSyncModeMap();
        assertEquals("MANUAL", m3.get("mode"));
        assertEquals(Boolean.FALSE, m3.get("rootBackgroundFlushEnabled"));

        map.setSyncModeStrong100ms();
        Map<String, Object> m4 = map.getSyncModeMap();
        assertEquals("STRONG_100MS", m4.get("mode"));
        assertEquals(Boolean.TRUE, m4.get("rootBackgroundFlushEnabled"));
        assertEquals(6, ((Number) m4.get("enabledObjectCount")).intValue());

        map.setSyncModeSystemAuto();
        Map<String, Object> m5 = map.getSyncModeMap();
        assertEquals("SYSTEM_AUTO", m5.get("mode"));
        assertEquals(Boolean.TRUE, m5.get("rootBackgroundFlushEnabled"));
        assertEquals(6, ((Number) m5.get("enabledObjectCount")).intValue());
    }
}
