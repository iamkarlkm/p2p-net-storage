package ds;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import com.q3lives.ds.collections.DsHashMapInline;
import org.junit.Test;

public class DsHashMapInlineTest {

    @Test
    public void testEmptyData() {
        long v = DsHashMapInline.encode(new byte[0]);
        assertEquals(0L, v);
        assertArrayEquals(new byte[0], DsHashMapInline.decode(v, 0));
    }

    @Test
    public void testSingleByte() {
        byte[] data = new byte[] { (byte) 0xAB };
        long v = DsHashMapInline.encode(data);
        assertEquals(0xABL, v);
        assertArrayEquals(data, DsHashMapInline.decode(v, 1));
    }

    @Test
    public void testEightBytes() {
        byte[] data = new byte[] {
                (byte) 0x01, (byte) 0x02, (byte) 0x03, (byte) 0x04,
                (byte) 0x05, (byte) 0x06, (byte) 0x07, (byte) 0x08
        };
        long v = DsHashMapInline.encode(data);
        // 小端序：低字节在前
        long expected = 0x0807060504030201L;
        assertEquals(expected, v);
        assertArrayEquals(data, DsHashMapInline.decode(v, 8));
    }

    @Test
    public void testRoundTripForAllLengths() {
        for (int len = 0; len <= 8; len++) {
            byte[] data = new byte[len];
            for (int i = 0; i < len; i++) {
                data[i] = (byte) ((i + 1) * 0x11);
            }
            long v = DsHashMapInline.encode(data);
            assertArrayEquals("length=" + len, data, DsHashMapInline.decode(v, len));
        }
    }

    @Test
    public void testDecodeWithShorterLength() {
        byte[] data = new byte[] { (byte) 0x11, (byte) 0x22, (byte) 0x33 };
        long v = DsHashMapInline.encode(data);
        assertArrayEquals(new byte[] { (byte) 0x11, (byte) 0x22 },
                DsHashMapInline.decode(v, 2));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testEncodeTooLong() {
        DsHashMapInline.encode(new byte[9]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDecodeNegativeLength() {
        DsHashMapInline.decode(0L, -1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDecodeTooLong() {
        DsHashMapInline.decode(0L, 9);
    }

    @Test(expected = NullPointerException.class)
    public void testEncodeNull() {
        DsHashMapInline.encode(null);
    }
}
