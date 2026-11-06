package com.q3lives.ds.cluster;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class DsPartitionRouterTest {

    @Test
    public void testPartitionIdExtractsLow8Bits() {
        for (int i = 0; i < 256; i++) {
            long rowId = DsIdLayout.compose(0, i, 12345L + i);
            Assertions.assertEquals(i, DsPartitionRouter.partitionId(rowId));
        }
    }

    @Test
    public void testPartitionIdIgnoresServerIdAndLocalAddress() {
        long rowId = DsIdLayout.compose(4095, 42, DsIdLayout.LOCAL_ADDRESS_MASK);
        Assertions.assertEquals(42, DsPartitionRouter.partitionId(rowId));
    }

    @Test
    public void testMaxPartitionCount() {
        Assertions.assertEquals(256, DsPartitionRouter.maxPartitionCount());
    }
}
