package p2pws.sdk.core_compat;

import java.io.ByteArrayOutputStream;

final class Varint {
    private Varint() {
    }

    static void writeU64(ByteArrayOutputStream out, long value) {
        long v = value;
        while ((v & ~0x7FL) != 0) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
    }

    static void writeI32(ByteArrayOutputStream out, int value) {
        long u = value & 0xFFFF_FFFFL;
        if (value < 0) {
            u |= 0xFFFF_FFFF_0000_0000L;
        }
        writeU64(out, u);
    }

    static long readU64(byte[] data, int[] posRef) {
        int pos = posRef[0];
        long out = 0;
        int shift = 0;
        while (pos < data.length && shift < 64) {
            int b = data[pos++] & 0xFF;
            out |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                posRef[0] = pos;
                return out;
            }
            shift += 7;
        }
        throw new IllegalArgumentException("truncated varint");
    }
}

