package ds;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.q3lives.ds.cluster.DsIdLayout;
import com.q3lives.ds.collections.DsHashMap;
import com.q3lives.ds.collections.DsHashMapIdAllocator;

import java.io.File;
import java.io.IOException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class DsHashMapIdAllocatorTest {

    private File dataFile;
    private DsHashMap aggregationMap;
    private DsHashMapIdAllocator allocator;

    @Before
    public void setUp() throws Exception {
        dataFile = File.createTempFile("test_id_allocator_", ".dat");
        dataFile.deleteOnExit();
        deleteWithSidecars(dataFile);
        aggregationMap = new DsHashMap(dataFile);
        allocator = new DsHashMapIdAllocator(aggregationMap);
    }

    @After
    public void tearDown() throws Exception {
        if (aggregationMap != null) {
            aggregationMap.close();
        }
        deleteWithSidecars(dataFile);
    }

    private static void deleteWithSidecars(File f) {
        File[] files = new File[] {
                f,
                new File(f.getAbsolutePath() + ".e16"),
                new File(f.getAbsolutePath() + ".e16.next"),
                new File(f.getAbsolutePath() + ".e16.free"),
                new File(f.getAbsolutePath() + ".e16.free.tmp"),
                new File(f.getAbsolutePath() + ".e32"),
                new File(f.getAbsolutePath() + ".e32.next"),
                new File(f.getAbsolutePath() + ".e32.free"),
                new File(f.getAbsolutePath() + ".e32.free.tmp"),
                new File(f.getAbsolutePath() + ".e64"),
                new File(f.getAbsolutePath() + ".e64.next"),
                new File(f.getAbsolutePath() + ".e64.free"),
                new File(f.getAbsolutePath() + ".e64.free.tmp"),
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
    public void testAllocateRowIdEmbedsLocationBits() throws IOException {
        int serverId = 7;
        int partitionId = 13;

        long rowId1 = allocator.allocateRowId(serverId, partitionId);

        assertEquals(serverId, DsIdLayout.serverId(rowId1));
        assertEquals(partitionId, DsIdLayout.partitionId(rowId1));
        assertEquals(0L, DsIdLayout.localAddress(rowId1));
    }

    @Test
    public void testAllocateRowIdIncrementsLocalAddress() throws IOException {
        long rowId1 = allocator.allocateRowId(1, 2);
        long rowId2 = allocator.allocateRowId(1, 2);

        long local1 = DsIdLayout.localAddress(rowId1);
        long local2 = DsIdLayout.localAddress(rowId2);

        assertEquals(local1 + 1, local2);
        // serverId/partitionId 应相同，只有 localAddress 递增
        assertEquals(DsIdLayout.serverId(rowId1), DsIdLayout.serverId(rowId2));
        assertEquals(DsIdLayout.partitionId(rowId1), DsIdLayout.partitionId(rowId2));
    }

    @Test
    public void testRecycleRowIdReusesLocalAddress() throws IOException {
        long rowId1 = allocator.allocateRowId(3, 5);
        long local1 = DsIdLayout.localAddress(rowId1);

        allocator.recycleRowId(rowId1);

        long rowId2 = allocator.allocateRowId(9, 11);
        long local2 = DsIdLayout.localAddress(rowId2);

        assertEquals(local1, local2);
        assertEquals(9, DsIdLayout.serverId(rowId2));
        assertEquals(11, DsIdLayout.partitionId(rowId2));
        assertNotEquals(rowId1, rowId2);
    }

    @Test
    public void testDifferentPartitionsDoNotOverlap() throws IOException {
        long rowIdA = allocator.allocateRowId(0, 0);
        long rowIdB = allocator.allocateRowId(0, 255);

        assertEquals(0, DsIdLayout.serverId(rowIdA));
        assertEquals(0, DsIdLayout.serverId(rowIdB));
        assertEquals(0, DsIdLayout.partitionId(rowIdA));
        assertEquals(255, DsIdLayout.partitionId(rowIdB));
    }
}
