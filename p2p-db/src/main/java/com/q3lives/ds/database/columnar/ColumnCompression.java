package com.q3lives.ds.database.columnar;

import java.nio.ByteBuffer;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;

/**
 * LZ4 列压缩辅助类。
 *
 * <p>存储格式（大端序）：
 * <pre>
 * [2B magic = 0x4C 0x5A] [1B version = 1] [4B originalLen] [4B compressedLen] [compressed bytes]
 * </pre>
 *
 * <p>未压缩数据或不符合上述魔数的数据会被原样返回，保证对旧数据的最大兼容。</p>
 */
public final class ColumnCompression {

    public static final int HEADER_SIZE = 11;
    public static final byte[] MAGIC = {(byte) 0x4C, (byte) 0x5A};
    private static final byte VERSION = 1;

    private static final LZ4Factory FACTORY = LZ4Factory.fastestInstance();
    private static final LZ4Compressor COMPRESSOR = FACTORY.fastCompressor();
    private static final LZ4FastDecompressor DECOMPRESSOR = FACTORY.fastDecompressor();

    private ColumnCompression() {
    }

    /**
     * 尝试压缩输入数据。若压缩后未节省空间或数据小于阈值，则返回 null。
     */
    public static byte[] compress(byte[] input, int threshold) {
        if (input == null || input.length < threshold || input.length == 0) {
            return null;
        }
        int maxCompressed = COMPRESSOR.maxCompressedLength(input.length);
        byte[] compressed = new byte[maxCompressed];
        int compressedLen = COMPRESSOR.compress(input, 0, input.length, compressed, 0, maxCompressed);
        // 只有当压缩后字节数（含头）严格小于原始长度时才启用压缩
        if (compressedLen + HEADER_SIZE >= input.length) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + compressedLen);
        buf.put(MAGIC[0]);
        buf.put(MAGIC[1]);
        buf.put(VERSION);
        buf.putInt(input.length);
        buf.putInt(compressedLen);
        buf.put(compressed, 0, compressedLen);
        return buf.array();
    }

    /**
     * 若输入数据是压缩格式则解压，否则返回 null（调用方应原样使用输入）。
     */
    public static byte[] decompress(byte[] input) {
        if (input == null || input.length < HEADER_SIZE) {
            return null;
        }
        if (input[0] != MAGIC[0] || input[1] != MAGIC[1]) {
            return null;
        }
        if (input[2] != VERSION) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(input);
        buf.position(3);
        int originalLen = buf.getInt();
        int compressedLen = buf.getInt();
        if (originalLen < 0 || compressedLen < 0 || HEADER_SIZE + compressedLen > input.length) {
            return null;
        }
        byte[] out = new byte[originalLen];
        DECOMPRESSOR.decompress(input, HEADER_SIZE, out, 0, originalLen);
        return out;
    }

    /**
     * 判断输入数据是否为当前压缩格式（含魔数与版本）。
     */
    public static boolean isCompressed(byte[] input) {
        if (input == null || input.length < HEADER_SIZE) {
            return false;
        }
        return input[0] == MAGIC[0] && input[1] == MAGIC[1] && input[2] == VERSION;
    }
}
