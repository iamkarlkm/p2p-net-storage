package ds;

import static org.junit.Assert.assertEquals;

import com.q3lives.ds.cluster.DsIdLayout;
import org.junit.Test;

public class DsIdLayoutTest {

    @Test
    public void testComposeAndExtract() {
        int serverId = 123;
        int partitionId = 42;
        long localAddress = 9876543L; // within 24-bit localAddress limit
        long rowId = DsIdLayout.compose(serverId, partitionId, localAddress);

        assertEquals(serverId, DsIdLayout.serverId(rowId));
        assertEquals(partitionId, DsIdLayout.partitionId(rowId));
        assertEquals(localAddress, DsIdLayout.localAddress(rowId));
    }

    @Test
    public void testBoundaryValues() {
        int maxServerId = (int) DsIdLayout.SERVER_ID_MASK; // 4194303
        int maxPartitionId = (int) DsIdLayout.PARTITION_MASK; // 255
        long maxLocalAddress = DsIdLayout.LOCAL_ADDRESS_MASK; // 2^24 - 1

        long rowId = DsIdLayout.compose(maxServerId, maxPartitionId, maxLocalAddress);
        assertEquals(maxServerId, DsIdLayout.serverId(rowId));
        assertEquals(maxPartitionId, DsIdLayout.partitionId(rowId));
        assertEquals(maxLocalAddress, DsIdLayout.localAddress(rowId));
    }

    @Test
    public void testZeroValues() {
        long rowId = DsIdLayout.compose(0, 0, 0L);
        assertEquals(0, DsIdLayout.serverId(rowId));
        assertEquals(0, DsIdLayout.partitionId(rowId));
        assertEquals(0L, DsIdLayout.localAddress(rowId));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testServerIdTooLarge() {
        DsIdLayout.compose((int) (DsIdLayout.SERVER_ID_MASK + 1), 0, 0L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPartitionIdTooLarge() {
        DsIdLayout.compose(0, (int) (DsIdLayout.PARTITION_MASK + 1), 0L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testLocalAddressTooLarge() {
        DsIdLayout.compose(0, 0, DsIdLayout.LOCAL_ADDRESS_MASK + 1);
    }
}
