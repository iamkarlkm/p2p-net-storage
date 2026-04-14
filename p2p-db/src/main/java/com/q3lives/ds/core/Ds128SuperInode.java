
package com.q3lives.ds.core;

import java.nio.MappedByteBuffer;

import com.q3lives.ds.util.DsConstant;

/**
 * 超级索引节点 (Super Inode) 类。
 * <p>
 * 存储文件系统的全局元数据，类似于 Unix 文件系统中的 Superblock。
 * 包含文件系统版本、根节点、大小、时间戳等关键信息。
 * </p>
 */
public class Ds128SuperInode {
   // 8字节，文件系统标志和版本号，当前为DS64v1.0
    //long magic;
    byte[] magic = DsConstant.DS_VERSION;
    // 根节点
    long i_root_node;
    // Size in bytes
    long mft_size;
    // 块占用总数
    long block_total;
    // 块大小
    int block_size;
    // 系统内部命名标识符字符串位置偏移
    int i_sys_names_offset;
    // 短命名标识符存储
    long[] extra_data = new long[3];
    // 时间戳序列号
    long sn;
    // inode创建时间
    long inode_ctime;
    // 文件模式存储空间扩展
    long i_ext_super_mount_nodes;
    // 块设备模式存储空间扩展
    long i_ext_super_nodes;
    byte[] md5 = new byte[16];
    // 多版本并发控制 -- 文件系统
    long i_next_mvcc_super_node;
    // 可执行代码入口 main entry,boot entry
    long i_exec_entry_node;

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
        buffer.putInt(i_sys_names_offset);
        for (long data : extra_data) {
            buffer.putLong(data);
        }
        buffer.putLong(sn);
        buffer.putLong(inode_ctime);
        buffer.putLong(i_ext_super_mount_nodes);
        buffer.putLong(i_ext_super_nodes);
        buffer.put(md5);
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
        i_sys_names_offset = buffer.getInt();
        for (int i = 0; i < extra_data.length; i++) {
            extra_data[i] = buffer.getLong();
        }
        sn = buffer.getLong();
        inode_ctime = buffer.getLong();
        i_ext_super_mount_nodes = buffer.getLong();
        i_ext_super_nodes = buffer.getLong();
        buffer.get(md5);
        i_next_mvcc_super_node = buffer.getLong();
        i_exec_entry_node = buffer.getLong();
    }
    
    public static void main(String[] args) {
        System.out.println("DS64v1.0".getBytes().length);
    }
}
