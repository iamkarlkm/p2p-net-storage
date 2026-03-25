import jdk.internal.access.foreign.UnmapperProxy;

import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;






/**
 *
 * @author karl
 */
public class NewClass {
    //Structure
	public static void main(String[] args) throws Exception {
//        AccessModuleUtil.exportAll();
		System.out.println("中文");
        //Dt dt = new Dt()
		System.out.println(DataStoreType.Bitmap);
        //record range(int start, int end){};
        //range.start = 0;
        //try (RandomAccessFile file = new RandomAccessFile("\\\\.\\USBSTOR\\DISK&VEN_KINGSTON&PROD_DATATRAVELER_3.0&REV_\\E0D55EA5232CF6B19828048D&0", "r");
        
        try (RandomAccessFile file = new RandomAccessFile("e:/test/2.ppsx", "r");
                 FileChannel channel = file.getChannel()) {
                MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, 64*1024);
                //byte[] data = new byte[64*1024];
                //buffer.get(data);
            System.out.println(buffer.getClass());
            //sun.nio.ch.FileChannelImpl d;
           // java.nio.DirectByteBufferR r;
            // 加上这几行代码,手动unmap
            //UnmapperProxy unmapper = UnmapperProxy.;
            Method m = MappedByteBuffer.class.getDeclaredMethod("unmapper");
            m.setAccessible(true);
            UnmapperProxy unmapper = (UnmapperProxy) m.invoke( buffer);
            System.out.println(m);
            unmapper.unmap();
//            Method m = FileChannelImpl.class.getDeclaredMethod("unmap",
//                    MappedByteBuffer.class);
//            m.setAccessible(true);
//            m.invoke(FileChannelImpl.class, buffer);
                //Files.write(new File("e:/test/usb.buffer.bin").toPath(), data);
            }
	}

    public static void main2(String[] args) throws Exception {
        FileChannel inChannel=new RandomAccessFile("f:\\01.wmv", "r").getChannel();
        FileChannel outChannel=new RandomAccessFile("f:\\02.wmv", "rw").getChannel();

        MappedByteBuffer map=inChannel.map(FileChannel.MapMode.READ_ONLY, 0, inChannel.size());

        outChannel.write(map);
        outChannel.close();
        inChannel.close();
        System.out.println("复制完毕");
    }
}
