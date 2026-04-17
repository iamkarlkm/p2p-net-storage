
package ds;

import com.q3lives.ds.constant.InodeConsts;
import com.q3lives.ds.fs.InodeStructRW;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

public class MappedInodeDemo {
    public static void main(String[] args) throws Exception {
        // 打开磁盘镜像/文件系统镜像
        try (RandomAccessFile raf = new RandomAccessFile("/path/fs.img", "rw");
             FileChannel ch = raf.getChannel()) {

            // 映射整个文件
            MappedByteBuffer map = ch.map(
                FileChannel.MapMode.READ_WRITE,
                0,
                ch.size()
            );

            // 假设：
            // i_mode 偏移 = 0
            // i_flags 偏移 = 2
            long modeOff  = 0;
            long flagOff  = 2;

            // 读
            short mode = InodeStructRW.readIMode(map, modeOff);
            short flags = InodeStructRW.readIFlags(map, flagOff);

            System.out.println("type: " + InodeStructRW.getFileType(mode));
            System.out.println("perm: " + InodeStructRW.getPermission(mode));
            System.out.println("hasExtent: " + InodeStructRW.hasFlag(flags, InodeConsts.IFLAG_EXTENTS));

            // 写
            short newMode = (short) (InodeConsts.S_IFILE | 0644);
            InodeStructRW.writeIMode(map, modeOff, newMode);

            short newFlags = InodeStructRW.addFlag((short)0, InodeConsts.IFLAG_EXTENTS);
            newFlags = InodeStructRW.addFlag(newFlags, InodeConsts.IFLAG_NOATIME);
            InodeStructRW.writeIFlags(map, flagOff, newFlags);

            // 强制刷回磁盘
            map.force();
        }
    }
}
