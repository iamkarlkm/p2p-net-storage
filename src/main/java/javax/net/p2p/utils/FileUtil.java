package javax.net.p2p.utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.net.p2p.lock.Locks;
import javax.net.p2p.storage.SharedStorage;
import org.apache.commons.lang3.tuple.Triple;

/**
 * 以独占方式访问某个文件包含自己的一些理解
 *
 * // 方案1：利用RandomAccessFile的文件操作选项s，s即表示同步锁方式写
 *
 * RandomAccessFile file = new RandomAccessFile(file,"rws");
 *
 * // 方案2：利用FileChannel的文件锁
 *
 * File file = new File("test.txt");
 *
 * FileInputStream fis = new FileInputStream(file);
 *
 * FileChannel channel = fis.getChannel();
 *
 * FileLock fileLock = null;
 *
 * while(true) {
 *
 * fileLock = channel.tryLock(0,Long.MAX_VALUE,false); // true表示是共享锁，false则是独享锁定
 *
 * if(fileLock!=null) break;
 *
 * else // 有其他线程占据锁
 *
 * sleep(1000);
 *
 * }
 *
 * @author karl
 */
public class FileUtil {

    /**
     * 外，创建RandomAccessFile对象时还需要指定一个mode参数，该参数指定RandomAccessFile的访问模式，一共有4种模式。
     *
     * r 以只读的方式打开文本，也就意味着不能用write来操作文件 rw 读操作和写操作都是允许的 rws
     * 每当进行写操作，同步的刷新到磁盘，刷新内容和元数据 rwd 每当进行写操作，同步的刷新到磁盘，刷新内容
     *
     * @param file
     * @return
     * @throws FileNotFoundException
     * @throws IOException
     */
    public static byte[] loadFile(File file) throws FileNotFoundException, IOException {
        if (file.exists()) {
            //handle file read
            //            byte[] bytearray = new byte[(int) file.length()];
            //            try (FileInputStream fis = new FileInputStream(file);
            //                    BufferedInputStream bis = new BufferedInputStream(fis);
            //                    DataInputStream dis = new DataInputStream(bis);) {
            //                dis.readFully(bytearray, 0, bytearray.length);
            //                return bytearray;
            //            } catch (Exception e) {
            //                e.printStackTrace();
            //                throw new RuntimeException(e.getMessage());
            //            }
            //zero copy :
            try (
                    RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r"); FileChannel fileChannel = randomAccessFile.getChannel(); //                ByteBuffer buffer = ByteBuffer.allocate((int)file.length());
                    //                byte[] bytearray;
                    //                if(buffer.hasArray()){
                    //                    bytearray = buffer.array();
                    //                }else{
                    //                    bytearray = new byte[(int) file.length()];
                    //                }
                     ByteArrayOutputStream out = new ByteArrayOutputStream((int) file.length()); WritableByteChannel wc = Channels.newChannel(out);) {
                fileChannel.transferTo(0, file.length(), wc);
                out.flush();
                return out.toByteArray();
            }
        } else {
            throw new RuntimeException("文件不存在->" + file.getAbsolutePath());
        }

    }

    public static byte[] loadFile(File file, long start, long count) throws FileNotFoundException, IOException {
        if (file.exists()) {
            //zero copy :
            try (
                    RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r"); FileChannel fileChannel = randomAccessFile.getChannel(); //                ByteBuffer buffer = ByteBuffer.allocate((int)file.length());
                    //                byte[] bytearray;
                    //                if(buffer.hasArray()){
                    //                    bytearray = buffer.array();
                    //                }else{
                    //                    bytearray = new byte[(int) file.length()];
                    //                }
                     ByteArrayOutputStream out = new ByteArrayOutputStream((int) count); WritableByteChannel wc = Channels.newChannel(out);) {
                fileChannel.transferTo(start, count, wc);
                out.flush();
                return out.toByteArray();
            }
        } else {
            throw new RuntimeException("文件不存在->" + file.getAbsolutePath());
        }

    }

    public static void storeFile(File file, long start, long count, byte[] data) throws  IOException {
        if (!file.getParentFile().exists()) {
            System.out.println(".mkdirs:"+file.getParentFile());
            file.getParentFile().mkdirs();
        }
        try (
                RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rw"); FileChannel fileChannel = randomAccessFile.getChannel(); //                ByteBuffer buffer = ByteBuffer.allocate((int)file.length());
                //                byte[] bytearray;
                //                if(buffer.hasArray()){
                //                    bytearray = buffer.array();
                //                }else{
                //                    bytearray = new byte[(int) file.length()];
                //                }
                 ByteArrayInputStream in = new ByteArrayInputStream(data); ReadableByteChannel rc = Channels.newChannel(in);) {
            long newLength = start + count;
            if (newLength > randomAccessFile.length()) {
                randomAccessFile.setLength(newLength);
            }
            fileChannel.transferFrom(rc, start, count);
            fileChannel.force(true);

        }

    }
    
    public static void storeMemory(byte[] buffer, long start, long count, byte[] data) throws  IOException {
        System.arraycopy(data, 0, buffer, (int) start, (int) count);

    }

    /**
     *
     * @param path
     * @return
     */
    public static FileLock getLockedFileChannel(String path) {
        FileLock fileLock = null;
        try {
            RandomAccessFile randomAccessFile = new RandomAccessFile(new File(path), "rw");
            FileChannel fileChannel = randomAccessFile.getChannel();
            int count = 9;
            while (count > 0) {
                count--;
                fileLock = fileChannel.tryLock(0, Long.MAX_VALUE, false); // true表示是共享锁，false则是独享锁定

                if (fileLock != null) {
                    break;
                } else // 有其他线程占据锁
                {
                    Thread.sleep(1000);
                }

            }
        } catch (Exception ex) {
            //Logger.getLogger(FileUtil.class.getName()).log(Level.SEVERE, null, ex);
            throw new RuntimeException(ex);
        }
        return fileLock;
    }

    //public static final String concurentAppend
    public static final void concurentAppend(FileLock file, byte[] data) {
        String key = file.channel().toString();
        try {
            Locks.lock(key);//获取或等待锁可用
            ByteBuffer src = ByteBuffer.wrap(data);
            file.channel().write(src);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            Locks.unLock(key);
        }
    }

    /**
     *
     * @param path
     * @return
     */
    public static RandomAccessFile getLockedFile(String path) {
        try {
            return new RandomAccessFile(new File(path), "rws");
        } catch (Exception ex) {
            //Logger.getLogger(FileUtil.class.getName()).log(Level.SEVERE, null, ex);
            throw new RuntimeException(ex);
        }
    }

    /**
     *
     * @param file
     * @return
     */
    public static RandomAccessFile getLockedFile(File file) {
        try {
            return new RandomAccessFile(file, "rws");
        } catch (Exception ex) {
            //Logger.getLogger(FileUtil.class.getName()).log(Level.SEVERE, null, ex);
            throw new RuntimeException(ex);
        }
    }

    public static final void concurentAppend(RandomAccessFile file, byte[] data) {
        String key = file.toString();
        try {
            //ystem.out.println("lock key="+key);
            Locks.lock(key);//获取或等待锁可用
            file.write(data);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            Locks.unLock(key);
        }
    }

    public static final void append(File file, byte[] data) {
        try {
            Files.write(file.toPath(), data);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    public static Triple<File, File, Set<Integer>> getDownInfo(File file) {
        //File f = path.toFile();
        File idx = new File(file.getAbsolutePath() + ".down.idx");
        Set<Integer> indexes = new HashSet();
        if (idx.exists()) {
            try {
                List<String> lines = Files.readAllLines(idx.toPath());
                if (!lines.isEmpty()) {
                    for (String index : lines) {
                        indexes.add(Integer.valueOf(index));
                    }
                    return Triple.of(file, idx, indexes);
                }

            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }
        return Triple.of(file, idx, indexes);
    }

    public static Triple<File, File, Set<Integer>> getDownInfoTmp(String path) {
//		File file = new File(System.getProperty("java.io.tmpdir"), path + ".down");
        File to = new File(path);
        File file = new File("/dev/shm/down", to.getName() + ".down");//linux
        if (!file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }
        File idx = new File(file.getAbsolutePath() + ".idx");
        Set<Integer> indexes = new HashSet();
        if (idx.exists()) {
            try {
                List<String> lines = Files.readAllLines(idx.toPath());
                if (!lines.isEmpty()) {
                    for (String index : lines) {
                        indexes.add(Integer.valueOf(index));
                    }
                    return Triple.of(file, idx, indexes);
                }

            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }
        return Triple.of(file, idx, indexes);
    }

    public static Triple<File, File, Set<Integer>> getUpInfo(File f) {
        //File f = path.toFile();
        File file = new File(f.getAbsolutePath() + ".up.idx");
        Set<Integer> indexes = new HashSet();
        if (file.exists()) {
            try {
                List<String> lines = Files.readAllLines(file.toPath());
                if (!lines.isEmpty()) {
                    for (String index : lines) {
                        indexes.add(Integer.valueOf(index));
                    }
                    return Triple.of(f, file, indexes);
                }

            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }
        return Triple.of(f, file, indexes);
    }

    public static Triple<File, File, Set<Integer>> getUpInfoTmp(String path) {
        File idx = new File(System.getProperty("java.io.tmpdir"), path + ".up.idx");
        Set<Integer> indexes = new HashSet();
        if (idx.exists()) {
            try {
                List<String> lines = Files.readAllLines(idx.toPath());
                if (!lines.isEmpty()) {
                    for (String index : lines) {
                        indexes.add(Integer.valueOf(index));
                    }
                    return Triple.of(null, idx, indexes);
                }

            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }
        return Triple.of(null, idx, indexes);
    }

    /**
     * 判断path是否在storeId->父目录内部,以免越界访问文件系统
     *
     * @param storeId
     * @param path
     * @return
     */
    public static File getAndCheckExistsSandboxFile(int storeId, String path) {
        File parent = SharedStorage.getStorageLocation(storeId);
        if (parent == null) {
            throw new RuntimeException("共享存储ID对应目录不存在 -> " + storeId);
        }
        if (path.charAt(0) == '.') {
            throw new RuntimeException("非法访问路径 -> " + path);
        }
        File file = new File(parent, path);
        if (!file.exists()) {
            throw new RuntimeException("文件不存在 -> " + path);
        }
        return file;

    }
    
    /**
     * 判断path是否在storeId->父目录内部,以免越界访问文件系统
     *
     * @param storeId
     * @param path
     * @return
     */
    public static File getSandboxFileForWrite(int storeId, String path) {
        File parent = SharedStorage.getStorageLocation(storeId);
        if (parent == null) {
            throw new RuntimeException("共享存储ID对应目录不存在 -> " + storeId);
        }
        if (path.charAt(0) == '.') {
            throw new RuntimeException("非法访问路径 -> " + path);
        }
        File file = new File(parent, path);
        if (!file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }
        return file;

    }
    
    /**
     * 判断path是否在storeId->父目录内部,以免越界访问文件系统
     *
     * @param storeId
     * @param path
     * @return
     */
    public static File getSandboxFile(int storeId, String path) {
        File parent = SharedStorage.getStorageLocation(storeId);
        if (parent == null) {
            throw new RuntimeException("共享存储ID对应目录不存在 -> " + storeId);
        }
        if (path.charAt(0) == '.') {
            throw new RuntimeException("非法访问路径 -> " + path);
        }
        File file = new File(parent, path);
        
        return file;

    }
}
