
package com.q3lives.ds.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * 超级索引节点内存映射 IO 辅助类。
 * <p>
 * 提供通过 FileChannel 和 MappedByteBuffer 读写 {@link Ds128SuperInode} 的方法。
 * </p>
 */
public class Ds128SuperInodeMemoryMappedIO {
    private static final int SIZE = 8 * 10 + 4 * 2 + 16; // 计算 Ds128SuperInode 的字节大小

    public static void writeToFile(Ds128SuperInode inode, String filePath) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(new File(filePath), "rw");
             FileChannel channel = file.getChannel()) {
            MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, SIZE);
            inode.writeToMappedByteBuffer(buffer);
        }
    }

    public static Ds128SuperInode readFromFile(String filePath) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(new File(filePath), "r");
             FileChannel channel = file.getChannel()) {
            MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, SIZE);
            Ds128SuperInode inode = new Ds128SuperInode();
            inode.readFromMappedByteBuffer(buffer);
            return inode;
        }
    }

    public static void main(String[] args) {
        Ds128SuperInode inode = new Ds128SuperInode();
        //inode.magic = 0x123456789ABCDEFL;
        inode.i_root_node = 1L;
       

        String filePath = "super_inode.dat";
        try {
            writeToFile(inode, filePath);
            Ds128SuperInode readInode = readFromFile(filePath);
            System.out.println("Magic: " + readInode.magic);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
