
package com.q3lives.ds.fs;

import java.nio.MappedByteBuffer;

public class InodeStructRW {

    /**
     * 从内存映射文件读取 i_mode (2字节, BIG_ENDIAN)
     */
    public static short readIMode(MappedByteBuffer buffer, long offset) {
        return buffer.getShort((int) offset);
    }

    /**
     * 写入 i_mode 到内存映射文件 (2字节, BIG_ENDIAN)
     */
    public static void writeIMode(MappedByteBuffer buffer, long offset, short iMode) {
        buffer.putShort((int) offset, iMode);
    }

    /**
     * 读取你压缩的 16bit i_flags (2字节, BIG_ENDIAN)
     */
    public static short readIFlags(MappedByteBuffer buffer, long offset) {
        return buffer.getShort((int) offset);
    }

    /**
     * 写入 16bit 压缩 i_flags (2字节, BIG_ENDIAN)
     */
    public static void writeIFlags(MappedByteBuffer buffer, long offset, short flags) {
        buffer.putShort((int) offset, flags);
    }

    // ================== Flag 操作 ==================
    public static boolean hasFlag(short flags, short mask) {
        return (flags & mask) != 0;
    }

    public static short addFlag(short flags, short mask) {
        return (short) (flags | mask);
    }

    public static short removeFlag(short flags, short mask) {
        return (short) (flags & ~mask);
    }

    // ================== i_mode 解析 ==================
    public static String getFileType(short iMode) {
        return switch (iMode & 0xF000) {
            case InodeConsts.S_IFILE   -> "regular";
            case InodeConsts.S_IFDIR   -> "directory";
            case InodeConsts.S_IFLNK   -> "symlink";
            case InodeConsts.S_IFCHR   -> "char-dev";
            case InodeConsts.S_IFBLK   -> "block-dev";
            case InodeConsts.S_IFIFO   -> "fifo";
            case InodeConsts.S_IFSOCK  -> "socket";
            default                        -> "unknown";
        };
    }

    public static String getPermission(short iMode) {
        return new StringBuilder()
            .append((iMode & InodeConsts.S_IRUSR) != 0 ? 'r' : '-')
            .append((iMode & InodeConsts.S_IWUSR) != 0 ? 'w' : '-')
            .append((iMode & InodeConsts.S_IXUSR) != 0 ? 'x' : '-')
            .append((iMode & InodeConsts.S_IRGRP) != 0 ? 'r' : '-')
            .append((iMode & InodeConsts.S_IWGRP) != 0 ? 'w' : '-')
            .append((iMode & InodeConsts.S_IXGRP) != 0 ? 'x' : '-')
            .append((iMode & InodeConsts.S_IROTH) != 0 ? 'r' : '-')
            .append((iMode & InodeConsts.S_IWOTH) != 0 ? 'w' : '-')
            .append((iMode & InodeConsts.S_IXOTH) != 0 ? 'x' : '-')
            .toString();
    }
}