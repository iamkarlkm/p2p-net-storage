
package com.q3lives.ds.fs;

import com.q3lives.ds.constant.DsConstant;
import com.q3lives.ds.constant.InodeConsts;
import java.nio.MappedByteBuffer;


/**
 * 超级索引节点 (Super Inode) 类。
 * <p>
 * 存储文件系统的全局元数据，类似于 Unix 文件系统中的 Superblock。
 * 包含文件系统版本、根节点、大小、时间戳等关键信息。
 * </p>
 */
public class Ds128SuperInode {
   // 8字节，文件系统标志和版本号，当前为DS64v1.0
    // 8-byte  magic;
    public byte[] magic = DsConstant.DS_VERSION; 
    // 8-byte 根节点
    public long i_root_node;
    // 8-byte mft Size in bytes 
    public long mft_size;
    // 8-byte 块占用总数
    public long block_total;
    // 4-byte 块大小
    public int block_size;
    // 2-byte linux兼容属性位
    public short i_mode;
    //2-byte 标志位
    public short i_flags;//
    // 32-byte 短命名标识符存储 byte[0]-> value>0:短命名标识符有效,且存储标识符长度(不大于31);value==0:标识符长度>31,存储Big-endian DsString id,忽略byte[0],可存储最大56位id值
    public byte[] name = new byte[32];
    // 8-byte 时间戳序列号
    public long sn;
    // 8-byte inode创建时间
    public long inode_ctime;
    // 8-byte inode创建时间
    public long inode_mtime;
    // 8-byte 存储空间扩展-文件模式
    public long i_ext_super_mount_nodes;
    // 8-byte 存储空间扩展-块设备模式
    public long i_ext_super_block_nodes;
    //  8-byte 多版本并发控制 -- 文件系统
    public long i_next_mvcc_super_node;
    //  8-byte 可执行代码入口 main entry,boot entry
    public long i_exec_entry_node;

    
    /**
     * 将超级节点数据写入到内存映射缓冲区。
     * @param buffer 目标缓冲区
     */
    public void writeToMappedByteBuffer(MappedByteBuffer buffer) {
        buffer.put(0,magic,0,8);
        buffer.putLong(i_root_node);
        buffer.putLong(mft_size);
        buffer.putLong(block_total);
        buffer.putInt(block_size);
        buffer.putShort(i_mode);
        buffer.putShort(i_flags);
        buffer.put(name);
        buffer.putLong(sn);
        buffer.putLong(inode_ctime);
        buffer.putLong(i_ext_super_mount_nodes);
        buffer.putLong(i_ext_super_block_nodes);
        buffer.putLong(i_next_mvcc_super_node);
        buffer.putLong(i_exec_entry_node);
    }

    /**
     * 从内存映射缓冲区读取超级节点数据。
     * @param buffer 源缓冲区
     */
    public void readFromMappedByteBuffer(MappedByteBuffer buffer) {
        buffer.get(0, magic, 0, 8);
        i_root_node = buffer.getLong();
        mft_size = buffer.getLong();
        block_total = buffer.getLong();
        block_size = buffer.getInt();
        i_mode = buffer.getShort();
        i_flags = buffer.getShort();
        buffer.get(name);
        sn = buffer.getLong();
        inode_ctime = buffer.getLong();
        i_ext_super_mount_nodes = buffer.getLong();
        i_ext_super_block_nodes = buffer.getLong();
        i_next_mvcc_super_node = buffer.getLong();
        i_exec_entry_node = buffer.getLong();
    }
    
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
            case InodeConsts.S_IFILE   -> "file";
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
    
    public static void main(String[] args) {
        System.out.println("DS64v1.0".getBytes().length);
    }
}
