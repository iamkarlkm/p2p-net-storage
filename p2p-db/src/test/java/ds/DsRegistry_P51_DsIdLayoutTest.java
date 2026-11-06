package ds;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import com.q3lives.ds.cluster.DsIdLayout;
import com.q3lives.ds.enums.DataType;
import com.q3lives.ds.registry.DsRegistry;
import com.q3lives.ds.registry.RegistryEntry;
import org.junit.Test;

public class DsRegistry_P51_DsIdLayoutTest {

    @Test(expected = IllegalArgumentException.class)
    public void testServerIdOutOfRange() throws Exception {
        File tmp = Files.createTempDirectory("dsregp51-svr-").toFile();
        tmp.deleteOnExit();
        int bad = (int) DsIdLayout.SERVER_ID_MASK + 1;
        try (DsRegistry r = new DsRegistry(new File(tmp, "db").getAbsolutePath(), bad, 0)) {
            fail();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPartitionIdOutOfRange() throws Exception {
        File tmp = Files.createTempDirectory("dsregp51-part-").toFile();
        tmp.deleteOnExit();
        int bad = (int) DsIdLayout.PARTITION_MASK + 1;
        try (DsRegistry r = new DsRegistry(new File(tmp, "db").getAbsolutePath(), 0, bad)) {
            fail();
        }
    }

    @Test
    public void testRoundtripLeafAndDir() throws Exception {
        File tmp = Files.createTempDirectory("dsregp51-rt-").toFile();
        tmp.deleteOnExit();
        final int SERVER = 123;
        final int PART = 42;
        try (DsRegistry r = new DsRegistry(new File(tmp, "db").getAbsolutePath(), SERVER, PART)) {
            assertEquals(SERVER, r.serverId());
            assertEquals(PART, r.partitionId());
            r.mkdirs("/a/b/c");
            r.putLong("/a/b/c/x", DataType.REG_DWORD, 37L);
            List<String> keys = r.listKeys("/a/b", Integer.MAX_VALUE);
            assertFalse(keys.isEmpty());
            RegistryEntry x = r.get("/a/b/c/x");
            assertNotNull(x);
            long leafTyped = x.nodeId();
            long global = r.toGlobalNodeId(leafTyped);
            assertEquals(SERVER, DsIdLayout.serverId(global));
            assertEquals(PART, DsIdLayout.partitionId(global));
            long local = DsIdLayout.localAddress(global);
            assertTrue(local > 0L);
            long back = r.fromGlobalNodeId(global, false);
            assertEquals(leafTyped, back);
            RegistryEntry dir = r.get("/a/b");
            assertNotNull(dir);
            assertTrue(dir.isDirectory());
            long dirGlobal = r.toGlobalNodeId(dir.nodeId());
            assertEquals(SERVER, DsIdLayout.serverId(dirGlobal));
            assertEquals(PART, DsIdLayout.partitionId(dirGlobal));
            long dirBack = r.fromGlobalNodeId(dirGlobal, true);
            assertEquals(dir.nodeId(), dirBack);
        }
    }

    @Test
    public void testTwoInstancesIndependentGlobals() throws Exception {
        File tmp1 = Files.createTempDirectory("dsregp51-a-").toFile();
        File tmp2 = Files.createTempDirectory("dsregp51-b-").toFile();
        tmp1.deleteOnExit();
        tmp2.deleteOnExit();
        try (DsRegistry r1 = new DsRegistry(new File(tmp1, "db").getAbsolutePath(), 1, 2);
             DsRegistry r2 = new DsRegistry(new File(tmp2, "db").getAbsolutePath(), 3, 4)) {
            r1.putLong("/a", DataType.REG_DWORD, 11L);
            r2.putLong("/a", DataType.REG_DWORD, 11L);
            RegistryEntry e1 = r1.get("/a");
            RegistryEntry e2 = r2.get("/a");
            assertNotNull(e1);
            assertNotNull(e2);
            long g1 = r1.toGlobalNodeId(e1.nodeId());
            long g2 = r2.toGlobalNodeId(e2.nodeId());
            assertEquals(1, DsIdLayout.serverId(g1));
            assertEquals(2, DsIdLayout.partitionId(g1));
            assertEquals(3, DsIdLayout.serverId(g2));
            assertEquals(4, DsIdLayout.partitionId(g2));
            assertNotEquals(g1, g2);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFromGlobalPartitionMismatch() throws Exception {
        File tmp = Files.createTempDirectory("dsregp51-mm-").toFile();
        tmp.deleteOnExit();
        try (DsRegistry r = new DsRegistry(new File(tmp, "db").getAbsolutePath(), 10, 20)) {
            r.mkdirs("/p");
            long globalOther = DsIdLayout.compose(10, 21, 1L);
            r.fromGlobalNodeId(globalOther, true);
            fail();
        }
    }
}
