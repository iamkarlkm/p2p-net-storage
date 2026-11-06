package com.q3lives.ds.util;

import java.nio.ByteBuffer;

public final class BloomFilter64 {

    private static final double LN2 = Math.log(2.0);
    private static final double LN2_SQUARED = LN2 * LN2;
    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;

    private final long numBits;
    private final int numHashFunctions;
    private final long[] bits;

    public BloomFilter64(long expectedInsertions, double falsePositiveRate) {
        if (expectedInsertions <= 0) {
            throw new IllegalArgumentException("expectedInsertions must be > 0");
        }
        if (falsePositiveRate <= 0.0 || falsePositiveRate >= 1.0) {
            throw new IllegalArgumentException("falsePositiveRate must be in (0,1)");
        }
        long m = optimalNumBits(expectedInsertions, falsePositiveRate);
        if (m < 64) m = 64;
        this.numBits = m;
        this.numHashFunctions = optimalNumHashFunctions(expectedInsertions, m);
        int longCount = (int) ((m + 63L) / 64L);
        this.bits = new long[longCount];
    }

    private BloomFilter64(long numBits, int numHashFunctions, long[] bits) {
        this.numBits = numBits;
        this.numHashFunctions = numHashFunctions;
        this.bits = bits;
    }

    private static long optimalNumBits(long n, double p) {
        double m = -((double) n) * Math.log(p) / LN2_SQUARED;
        return (long) Math.ceil(m);
    }

    private static int optimalNumHashFunctions(long n, long m) {
        double k = ((double) m / (double) n) * LN2;
        int ki = (int) Math.round(k);
        if (ki < 1) ki = 1;
        if (ki > 32) ki = 32;
        return ki;
    }

    public void add(byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("value cannot be null");
        }
        long h1 = DsDataUtil.hash64(value);
        long h2 = Long.rotateLeft(h1, 21) ^ GOLDEN_GAMMA;
        long combined = h1;
        final long bitsMask = this.numBits - 1L;
        final int k = this.numHashFunctions;
        final long[] b = this.bits;
        for (int i = 0; i < k; i++) {
            long pos = combined & Long.MAX_VALUE;
            if ((numBits & (numBits - 1)) == 0L) {
                pos = pos & bitsMask;
            } else {
                pos = pos % numBits;
            }
            int longIdx = (int) (pos >>> 6);
            int bitIdx = (int) (pos & 63L);
            b[longIdx] |= (1L << bitIdx);
            combined += h2;
        }
    }

    public boolean mightContain(byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("value cannot be null");
        }
        long h1 = DsDataUtil.hash64(value);
        long h2 = Long.rotateLeft(h1, 21) ^ GOLDEN_GAMMA;
        long combined = h1;
        final long bitsMask = this.numBits - 1L;
        final int k = this.numHashFunctions;
        final long[] b = this.bits;
        for (int i = 0; i < k; i++) {
            long pos = combined & Long.MAX_VALUE;
            if ((numBits & (numBits - 1)) == 0L) {
                pos = pos & bitsMask;
            } else {
                pos = pos % numBits;
            }
            int longIdx = (int) (pos >>> 6);
            int bitIdx = (int) (pos & 63L);
            if ((b[longIdx] & (1L << bitIdx)) == 0L) {
                return false;
            }
            combined += h2;
        }
        return true;
    }

    public long getNumBits() {
        return numBits;
    }

    public int getNumHashFunctions() {
        return numHashFunctions;
    }

    public byte[] toBytes() {
        ByteBuffer buf = ByteBuffer.allocate(8 + 4 + 4 + bits.length * 8);
        buf.putLong(numBits);
        buf.putInt(numHashFunctions);
        buf.putInt(bits.length);
        for (long l : bits) {
            buf.putLong(l);
        }
        return buf.array();
    }

    public static BloomFilter64 fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length < 16) {
            throw new IllegalArgumentException("invalid bloom bytes");
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        long nBits = buf.getLong();
        int nHash = buf.getInt();
        int longCount = buf.getInt();
        if (longCount < 0 || longCount > (bytes.length - 16) / 8) {
            throw new IllegalArgumentException("invalid bloom bytes (longCount out of range)");
        }
        long[] bArr = new long[longCount];
        for (int i = 0; i < longCount; i++) {
            bArr[i] = buf.getLong();
        }
        return new BloomFilter64(nBits, nHash, bArr);
    }
}
