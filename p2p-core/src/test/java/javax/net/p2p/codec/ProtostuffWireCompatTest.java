package javax.net.p2p.codec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.utils.SerializationUtil;
import org.junit.Test;

public class ProtostuffWireCompatTest {

    @Test
    public void testP2PWrapperCommandUsesOrdinalEncoding() throws Exception {
        P2PWrapper<byte[]> w = P2PWrapper.build(123, P2PCommand.RPC_UNARY, new byte[] {1, 2, 3});
        byte[] payload = SerializationUtil.serialize(w);

        int cmd = readVarintField(payload, 2);
        assertTrue(cmd >= 0);
        assertEquals(P2PCommand.RPC_UNARY.ordinal(), cmd);
    }

    private static int readVarintField(byte[] data, int fieldNumber) {
        int pos = 0;
        while (pos < data.length) {
            long key = readVarint(data, pos);
            pos += varintSize(data, pos);
            int field = (int) (key >>> 3);
            int wt = (int) (key & 0x7);
            if (wt == 0) {
                long v = readVarint(data, pos);
                int size = varintSize(data, pos);
                if (field == fieldNumber) {
                    return (int) v;
                }
                pos += size;
            } else if (wt == 2) {
                long ln = readVarint(data, pos);
                int lns = varintSize(data, pos);
                pos += lns + (int) ln;
            } else {
                throw new IllegalArgumentException("unsupported wireType=" + wt);
            }
        }
        throw new IllegalArgumentException("field not found: " + fieldNumber);
    }

    private static int varintSize(byte[] data, int pos) {
        int i = 0;
        while (pos + i < data.length) {
            int b = data[pos + i] & 0xFF;
            i++;
            if ((b & 0x80) == 0) {
                return i;
            }
        }
        throw new IllegalArgumentException("varint truncated");
    }

    private static long readVarint(byte[] data, int pos) {
        long out = 0;
        int shift = 0;
        while (pos < data.length) {
            int b = data[pos++] & 0xFF;
            out |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return out;
            }
            shift += 7;
            if (shift > 70) {
                throw new IllegalArgumentException("varint too long");
            }
        }
        throw new IllegalArgumentException("varint truncated");
    }
}

