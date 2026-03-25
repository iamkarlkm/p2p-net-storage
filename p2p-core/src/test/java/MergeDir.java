
import com.google.common.io.Files;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

/**
 *
 * @author karl iamkarl@163.com
 * @date 2020年7月8日 上午10:10:24
 * @version V1.0
 * @desc 
 */
public class MergeDir {
    public static void main(String[] args) {
        String to = args[0];
        String from = args[1];
        mergeDir(new File(to), new File(from));
    }
    
    public static void main3(String[] args) {
        System.out.println("sd.gg.gq".split("[.]")[1]);
    }
    
    public static void mergeDir(File to,File from){
        if(!to.isDirectory()){
            return;
        }
        if(!to.exists()){
            to.mkdirs();
        }
        File[] froms = from.listFiles();
        for(File src:froms){
            File dest = new File(to,src.getName());
            System.out.println(src+"->\n"+dest);
            if(!dest.exists()){
                //src.renameTo(dest);
                if (src.isFile()) {
                    fileCopyNIO(src, dest);
                    src.delete();
                }else{
                    dest.mkdirs();
                }
            }else if(src.isFile()){
                src.delete();
            }
            if(src.isDirectory()){
                mergeDir(dest, src);
            }
        }
        if(from.exists()){
            from.delete();
        }
    }
    
        public static void fileCopyNIO(File src, File dest) {
        if(!dest.getParentFile().exists()){
            dest.getParentFile().mkdirs();
        }
        try (RandomAccessFile randomAccessFile = new RandomAccessFile(src, "r");
                FileOutputStream fos = new FileOutputStream(dest);
                FileChannel fileChannel = randomAccessFile.getChannel();
                WritableByteChannel wc = Channels.newChannel(fos);) {
            //NIO 实现
            fileChannel.transferTo(0, src.length(), wc);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public static void fileCopyNIO(InputStream is, File dest,long count) {
        if(!dest.getParentFile().exists()){
            dest.getParentFile().mkdirs();
        }
        try (RandomAccessFile randomAccessFile = new RandomAccessFile(dest, "rw");
                FileChannel fileChannel = randomAccessFile.getChannel();
                ReadableByteChannel rc = Channels.newChannel(is);) {
            //NIO 实现
            fileChannel.transferFrom(rc, 0,count);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public void fileCopyNIO(File src, OutputStream os) {
        try (RandomAccessFile randomAccessFile = new RandomAccessFile(src, "r");
                FileChannel fileChannel = randomAccessFile.getChannel();
                WritableByteChannel wc = Channels.newChannel(os);) {
            //NIO 实现
            fileChannel.transferTo(0, src.length(), wc);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
