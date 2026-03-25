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

    /**
     * ============================================================================
     * 使用示例 - 完整演示 FileUtil 类的各种功能
     * ============================================================================
     * 
     * <pre>
     * <code>
     * // 示例1: 基本文件读写操作
     * public void basicFileOperationsExample() throws Exception {
     *     // 创建测试文件
     *     File testFile = new File("test.txt");
     *     
     *     // 写入文件内容
     *     String content = "这是一个测试文件内容\n第二行内容";
     *     byte[] data = content.getBytes("UTF-8");
     *     
     *     // 使用FileUtil存储文件
     *     FileUtil.storeFile(testFile, 0, data.length, data);
     *     System.out.println("文件写入完成，大小: " + testFile.length() + " bytes");
     *     
     *     // 读取整个文件
     *     byte[] readData = FileUtil.loadFile(testFile);
     *     String readContent = new String(readData, "UTF-8");
     *     System.out.println("读取的文件内容:\n" + readContent);
     *     
     *     // 清理测试文件
     *     testFile.delete();
     * }
     * 
     * // 示例2: 文件分块读写（支持大文件处理）
     * public void chunkedFileOperationsExample() throws Exception {
     *     File largeFile = new File("large_file.dat");
     *     
     *     // 模拟写入大文件（分块）
     *     int blockSize = 1024 * 1024; // 1MB 每块
     *     int totalBlocks = 10;
     *     
     *     System.out.println("开始分块写入文件...");
     *     for (int i = 0; i < totalBlocks; i++) {
     *         byte[] blockData = generateTestData(blockSize, i);
     *         long startPos = i * blockSize;
     *         
     *         FileUtil.storeFile(largeFile, startPos, blockData.length, blockData);
     *         System.out.println("写入第 " + (i + 1) + " 块，位置: " + startPos);
     *     }
     *     
     *     // 分块读取验证
     *     System.out.println("\n开始分块读取验证...");
     *     for (int i = 0; i < totalBlocks; i++) {
     *         long startPos = i * blockSize;
     *         byte[] readBlock = FileUtil.loadFile(largeFile, startPos, blockSize);
     *         
     *         System.out.println("验证第 " + (i + 1) + " 块，大小: " + readBlock.length + " bytes");
     *         // 这里可以添加具体的数据验证逻辑
     *     }
     *     
     *     // 清理文件
     *     largeFile.delete();
     * }
     * 
     * // 示例3: 文件锁定和并发访问控制
     * public void fileLockingExample() throws Exception {
     *     String filePath = "shared_config.json";
     *     
     *     // 获取文件锁（独占锁）
     *     FileLock fileLock = FileUtil.getLockedFileChannel(filePath);
     *     
     *     if (fileLock != null) {
     *         try {
     *             System.out.println("成功获取文件锁，开始写入配置...");
     *             
     *             // 安全地写入文件（使用锁保护）
     *             String config = "{ \"server\": \"localhost\", \"port\": 8080 }";
     *             byte[] configData = config.getBytes("UTF-8");
     *             
     *             FileUtil.concurentAppend(fileLock, configData);
     *             System.out.println("配置写入完成");
     *             
     *         } finally {
     *             // 释放文件锁
     *             fileLock.release();
     *             System.out.println("文件锁已释放");
     *         }
     *     } else {
     *         System.err.println("获取文件锁失败，可能被其他进程占用");
     *     }
     * }
     * 
     * // 示例4: 断点续传功能实现
     * public void resumeTransferExample() throws Exception {
     *     // 假设正在下载一个大文件
     *     File targetFile = new File("downloads/video.mp4");
     *     
     *     // 获取下载进度信息
     *     Triple<File, File, Set<Integer>> downInfo = FileUtil.getDownInfo(targetFile);
     *     
     *     File actualFile = downInfo.getLeft();        // 实际文件
     *     File indexFile = downInfo.getMiddle();       // 索引文件
     *     Set<Integer> completedBlocks = downInfo.getRight(); // 已完成的块
     *     
     *     System.out.println("断点续传检查:");
     *     System.out.println("  目标文件: " + actualFile.getAbsolutePath());
     *     System.out.println("  索引文件: " + indexFile.getAbsolutePath());
     *     System.out.println("  已完成块数: " + completedBlocks.size());
     *     
     *     // 模拟文件总块数
     *     int totalBlocks = 100;
     *     
     *     // 找出需要下载的块
     *     List<Integer> blocksToDownload = new ArrayList<>();
     *     for (int i = 0; i < totalBlocks; i++) {
     *         if (!completedBlocks.contains(i)) {
     *             blocksToDownload.add(i);
     *         }
     *     }
     *     
     *     System.out.println("  需要下载的块: " + blocksToDownload.size() + " 个");
     *     
     *     // 模拟下载过程
     *     for (int blockIndex : blocksToDownload) {
     *         System.out.println("下载块 " + blockIndex + "...");
     *         
     *         // 模拟下载数据
     *         byte[] blockData = downloadBlock(blockIndex);
     *         
     *         // 写入文件块
     *         int blockSize = 1024 * 1024; // 1MB per block
     *         long startPos = blockIndex * blockSize;
     *         FileUtil.storeFile(targetFile, startPos, blockData.length, blockData);
     *         
     *         // 更新索引文件，标记该块已完成
     *         updateIndexFile(indexFile, blockIndex);
     *     }
     *     
     *     System.out.println("下载完成");
     *     
     *     // 清理索引文件
     *     indexFile.delete();
     * }
     * 
     * // 示例5: 安全沙箱文件访问
     * public void sandboxFileAccessExample() {
     *     // 在沙箱环境中安全访问文件
     *     int storeId = 1; // 存储ID
     *     String relativePath = "user_docs/report.pdf";
     *     
     *     try {
     *         // 获取沙箱文件（只读访问，文件必须存在）
     *         File sandboxFile = FileUtil.getAndCheckExistsSandboxFile(storeId, relativePath);
     *         
     *         System.out.println("沙箱文件访问:");
     *         System.out.println("  存储ID: " + storeId);
     *         System.out.println("  相对路径: " + relativePath);
     *         System.out.println("  绝对路径: " + sandboxFile.getAbsolutePath());
     *         System.out.println("  文件大小: " + sandboxFile.length() + " bytes");
     *         
     *         // 读取文件内容
     *         byte[] fileData = FileUtil.loadFile(sandboxFile);
     *         System.out.println("  读取数据大小: " + fileData.length + " bytes");
     *         
     *     } catch (RuntimeException e) {
     *         System.err.println("沙箱文件访问失败: " + e.getMessage());
     *     }
     * }
     * 
     * // 示例6: 内存缓冲区操作
     * public void memoryBufferExample() throws Exception {
     *     // 创建内存缓冲区
     *     int bufferSize = 1024 * 1024; // 1MB
     *     byte[] buffer = new byte[bufferSize];
     *     
     *     // 向缓冲区写入数据
     *     String textData = "内存缓冲区测试数据";
     *     byte[] data = textData.getBytes("UTF-8");
     *     
     *     FileUtil.storeMemory(buffer, 0, data.length, data);
     *     System.out.println("已向缓冲区写入 " + data.length + " bytes 数据");
     *     
     *     // 从缓冲区读取数据（这里是演示，实际从buffer读取）
     *     byte[] readBuffer = new byte[data.length];
     *     System.arraycopy(buffer, 0, readBuffer, 0, data.length);
     *     
     *     String readText = new String(readBuffer, "UTF-8");
     *     System.out.println("从缓冲区读取: " + readText);
     * }
     * 
     * // 示例7: 多线程并发追加写入
     * public void concurrentWriteExample() throws Exception {
     *     File logFile = new File("application.log");
     *     
     *     // 创建多个线程同时写入日志
     *     int threadCount = 5;
     *     ExecutorService executor = Executors.newFixedThreadPool(threadCount);
     *     
     *     List<Future<?>> futures = new ArrayList<>();
     *     
     *     for (int i = 0; i < threadCount; i++) {
     *         final int threadId = i;
     *         futures.add(executor.submit(() -> {
     *             try {
     *                 // 每个线程写入10条日志
     *                 for (int j = 0; j < 10; j++) {
     *                     String logMessage = String.format(
     *                         "[Thread-%d] Log entry %d at %s\n",
     *                         threadId, j, new Date()
     *                     );
     *                     byte[] logData = logMessage.getBytes("UTF-8");
     *                     
     *                     // 使用文件锁安全追加
     *                     FileLock lock = FileUtil.getLockedFileChannel(logFile.getAbsolutePath());
     *                     if (lock != null) {
     *                         try {
     *                             FileUtil.concurentAppend(lock, logData);
     *                         } finally {
     *                             lock.release();
     *                         }
     *                     }
     *                     
     *                     Thread.sleep(10); // 模拟处理间隔
     *                 }
     *             } catch (Exception e) {
     *                 e.printStackTrace();
     *             }
     *         }));
     *     }
     *     
     *     // 等待所有线程完成
     *     for (Future<?> future : futures) {
     *         future.get();
     *     }
     *     
     *     executor.shutdown();
     *     
     *     // 验证日志文件
     *     byte[] logContent = FileUtil.loadFile(logFile);
     *     System.out.println("日志文件大小: " + logContent.length + " bytes");
     *     System.out.println("日志内容前500字节:\n" + new String(logContent, 0, Math.min(500, logContent.length), "UTF-8"));
     *     
     *     // 清理文件
     *     logFile.delete();
     * }
     * 
     * // 示例8: 文件上传管理
     * public void fileUploadManagementExample() throws Exception {
     *     // 获取上传进度信息
     *     String uploadFilePath = "uploads/temp_file.bin";
     *     Triple<File, File, Set<Integer>> upInfo = FileUtil.getUpInfo(new File(uploadFilePath));
     *     
     *     File actualFile = upInfo.getLeft();          // 实际文件
     *     File indexFile = upInfo.getMiddle();         // 上传索引文件
     *     Set<Integer> uploadedBlocks = upInfo.getRight(); // 已上传的块
     *     
     *     System.out.println("文件上传管理:");
     *     System.out.println("  文件: " + (actualFile != null ? actualFile.getAbsolutePath() : "null"));
     *     System.out.println("  索引文件: " + indexFile.getAbsolutePath());
     *     System.out.println("  已上传块数: " + uploadedBlocks.size());
     *     
     *     // 计算上传进度
     *     int totalBlocks = 50;
     *     double progress = (uploadedBlocks.size() * 100.0) / totalBlocks;
     *     System.out.println("  上传进度: " + String.format("%.2f", progress) + "%");
     *     
     *     // 模拟继续上传
     *     for (int blockIndex = 0; blockIndex < totalBlocks; blockIndex++) {
     *         if (!uploadedBlocks.contains(blockIndex)) {
     *             System.out.println("  上传块 " + blockIndex + "...");
     *             
     *             // 模拟上传数据
     *             byte[] blockData = generateUploadData(blockIndex);
     *             
     *             // 写入文件
     *             int blockSize = 512 * 1024; // 512KB per block
     *             long startPos = blockIndex * blockSize;
     *             FileUtil.storeFile(actualFile, startPos, blockData.length, blockData);
     *             
     *             // 更新上传索引
     *             updateUploadIndex(indexFile, blockIndex);
     *         }
     *     }
     *     
     *     System.out.println("上传完成");
     * }
     * 
     * // 示例9: 临时文件操作（使用共享内存）
     * public void tempFileOperationsExample() throws Exception {
     *     // 在临时目录或共享内存中操作文件
     *     String filePath = "/tmp/test_data.bin";
     *     Triple<File, File, Set<Integer>> tmpInfo = FileUtil.getDownInfoTmp(filePath);
     *     
     *     File tmpFile = tmpInfo.getLeft();
     *     File tmpIndex = tmpInfo.getMiddle();
     *     Set<Integer> tmpBlocks = tmpInfo.getRight();
     *     
     *     System.out.println("临时文件操作:");
     *     System.out.println("  临时文件: " + tmpFile.getAbsolutePath());
     *     System.out.println("  临时索引: " + tmpIndex.getAbsolutePath());
     *     System.out.println("  已处理块: " + tmpBlocks.size());
     *     
     *     // 在临时文件中写入数据（使用共享内存提高性能）
     *     byte[] testData = "临时文件测试数据".getBytes("UTF-8");
     *     FileUtil.storeFile(tmpFile, 0, testData.length, testData);
     *     
     *     // 从临时文件读取数据
     *     byte[] readData = FileUtil.loadFile(tmpFile);
     *     System.out.println("  读取数据: " + new String(readData, "UTF-8"));
     *     
     *     // 清理临时文件
     *     tmpFile.delete();
     *     tmpIndex.delete();
     * }
     * 
     * // 示例10: 主方法演示全部功能
     * public static void main(String[] args) {
     *     try {
     *         FileUtilExample demo = new FileUtilExample();
     *         
     *         System.out.println("=== FileUtil 使用示例演示 ===");
     *         System.out.println();
     *         
     *         System.out.println("1. 基本文件操作演示:");
     *         demo.basicFileOperationsExample();
     *         System.out.println();
     *         
     *         System.out.println("2. 文件锁定演示:");
     *         demo.fileLockingExample();
     *         System.out.println();
     *         
     *         System.out.println("3. 内存缓冲区演示:");
     *         demo.memoryBufferExample();
     *         System.out.println();
     *         
     *         System.out.println("4. 沙箱文件访问演示:");
     *         demo.sandboxFileAccessExample();
     *         
     *     } catch (Exception e) {
     *         e.printStackTrace();
     *     }
     * }
     * 
     * // 辅助方法
     * private static byte[] generateTestData(int size, int blockIndex) {
     *     byte[] data = new byte[size];
     *     Arrays.fill(data, (byte) (blockIndex % 256));
     *     return data;
     * }
     * 
     * private static byte[] downloadBlock(int blockIndex) {
     *     // 模拟下载数据
     *     return ("Block " + blockIndex + " data").getBytes();
     * }
     * 
     * private static void updateIndexFile(File indexFile, int blockIndex) throws IOException {
     *     // 向索引文件追加块索引
     *     Files.write(indexFile.toPath(), 
     *         (blockIndex + "\n").getBytes("UTF-8"), 
     *         StandardOpenOption.APPEND, StandardOpenOption.CREATE);
     * }
     * 
     * private static byte[] generateUploadData(int blockIndex) {
     *     // 模拟上传数据
     *     return ("Upload block " + blockIndex).getBytes();
     * }
     * 
     * private static void updateUploadIndex(File indexFile, int blockIndex) throws IOException {
     *     // 更新上传索引
     *     updateIndexFile(indexFile, blockIndex);
     * }
     * </code>
     * </pre>
     * 
     * 注意事项:
     * 1. 所有文件操作都应该使用try-with-resources或确保资源正确释放
     * 2. 文件锁定可以防止并发访问导致的数据不一致问题
     * 3. 对于大文件操作，建议使用分块读写以提高性能和可靠性
     * 4. 断点续传功能依赖索引文件来记录下载/上传进度
     * 5. 沙箱文件访问确保文件操作不会越界访问系统文件
     * 6. 内存缓冲区操作适合需要高性能的内存数据处理场景
     * 7. 临时文件操作可以使用共享内存（如/dev/shm）提高IO性能
     */
}
