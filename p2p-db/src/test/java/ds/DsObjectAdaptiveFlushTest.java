package ds;

import com.q3lives.ds.core.DsObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;

public class DsObjectAdaptiveFlushTest {

    private DsObject obj;
    private File dataFile;

    @Before
    public void setUp() throws Exception {
        dataFile = new File("test_dsobject_adaptive_flush.dat");
        if (dataFile.exists()) {
            dataFile.delete();
        }
        obj = new DsObject(dataFile, 8);
        obj.setMaxMappedBytes(2L * 64L * 1024L);
        obj.setEvictPreferClean(true);
    }

    @After
    public void tearDown() throws Exception {
        obj.disableBackgroundFlush();
        obj.sync();
    }

    @Test
    public void testEnableAdaptiveFlushAndStats() throws Exception {
        assertFalse(obj.isBackgroundFlushEnabled());
        obj.enableAdaptiveBackgroundFlush(50, 500, 8);
        assertTrue(obj.isBackgroundFlushEnabled());

        for (int i = 0; i < 100; i++) {
            long id = (long) i * 20_000L;
            obj.writeLong(id, 0, i);
        }

        int dirty0 = obj.getDirtyBufferCount() + obj.getDirtyFrameCount();
        assertTrue(dirty0 >= 0);
        assertTrue(obj.getMappedBytes() <= obj.getMaxMappedBytes());

        DsObject.BufferStats s = obj.getBufferStats();
        assertNotNull(s);
        assertTrue(s.getEvictionAttempts() >= 0);
        assertTrue(s.getEvictionSuccess() >= 0);
        assertTrue(s.getFlushCycles() >= 0);

        DsObject.BufferStats d1 = obj.getAndResetBufferStats();
        assertNotNull(d1);
        DsObject.BufferStats d2 = obj.getAndResetBufferStats();
        assertEquals(0, d2.getEvictionAttempts());
        assertEquals(0, d2.getEvictionSuccess());
        assertEquals(0, d2.getFlushCycles());
    }
}
