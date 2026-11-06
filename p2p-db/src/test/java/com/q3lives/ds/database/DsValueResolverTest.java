package com.q3lives.ds.database;

import com.q3lives.ds.collections.DsHashMapInline;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class DsValueResolverTest {

    @Test
    public void testResolveNegativeThrows() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> DsValueResolver.resolve(-1));
    }

    @Test
    public void testResolveVariableForZeroLength() {
        Assertions.assertEquals(DsValueResolver.StorageType.VARIABLE_BUCKET, DsValueResolver.resolve(0));
    }

    @Test
    public void testResolveInlineUpToEightBytes() {
        for (int len = 1; len <= DsHashMapInline.MAX_INLINE_BYTES; len++) {
            Assertions.assertEquals(DsValueResolver.StorageType.INLINE, DsValueResolver.resolve(len),
                    "length " + len + " should be inline");
        }
    }

    @Test
    public void testResolveFixedValueStoreAboveEightBytes() {
        Assertions.assertEquals(DsValueResolver.StorageType.FIXED_VALUE_STORE, DsValueResolver.resolve(9));
        Assertions.assertEquals(DsValueResolver.StorageType.FIXED_VALUE_STORE, DsValueResolver.resolve(64));
        Assertions.assertEquals(DsValueResolver.StorageType.FIXED_VALUE_STORE, DsValueResolver.resolve(1024));
    }

    @Test
    public void testPadToLengthPaddsAndTruncates() {
        byte[] data = {1, 2, 3};

        byte[] padded = DsValueResolver.padToLength(data, 8);
        Assertions.assertEquals(8, padded.length);
        Assertions.assertEquals(1, padded[0]);
        Assertions.assertEquals(2, padded[1]);
        Assertions.assertEquals(3, padded[2]);
        for (int i = 3; i < 8; i++) {
            Assertions.assertEquals(0, padded[i]);
        }

        byte[] truncated = DsValueResolver.padToLength(data, 2);
        Assertions.assertEquals(2, truncated.length);
        Assertions.assertEquals(1, truncated[0]);
        Assertions.assertEquals(2, truncated[1]);

        byte[] same = DsValueResolver.padToLength(data, 3);
        Assertions.assertSame(data, same);
    }

    @Test
    public void testPadNullReturnsZeros() {
        byte[] padded = DsValueResolver.padToLength(null, 5);
        Assertions.assertEquals(5, padded.length);
        for (byte b : padded) {
            Assertions.assertEquals(0, b);
        }
    }
}
