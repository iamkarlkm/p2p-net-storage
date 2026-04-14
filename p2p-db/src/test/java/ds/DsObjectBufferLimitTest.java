package ds;

import com.q3lives.ds.core.DsObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;

public class DsObjectBufferLimitTest {

    private DsObject obj;
    private File dataFile;

    @Before
    public void setUp() throws Exception {
        dataFile = new File("test_dsobject_buffer_limit.dat");
        if (dataFile.exists()) {
            dataFile.delete();
        }
        obj = new DsObject(dataFile, 8);
        obj.setMaxMappedBytes(2L * 64L * 1024L);
    }

    @After
    public void tearDown() throws Exception {
        obj.sync();
    }

    @Test
    public void testMappedBytesUnderLimit() throws Exception {
        for (int i = 0; i < 50; i++) {
            long id = (long) i * 10_000L;
            obj.writeLong(id, 0, i);
        }
        obj.trimMappedBuffers();
        assertTrue("mappedBytes=" + obj.getMappedBytes() + " max=" + obj.getMaxMappedBytes(), obj.getMappedBytes() <= obj.getMaxMappedBytes());
    }
}
