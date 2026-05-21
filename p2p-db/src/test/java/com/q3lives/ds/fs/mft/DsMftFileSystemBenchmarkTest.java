package com.q3lives.ds.fs.mft;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DsMftFileSystem vs 传统文件系统（java.nio.file）性能对比测试。
 *
 * <p>测试场景：</p>
 * <ul>
 *   <li>创建 1000 个小文件（每文件约 100 字节）</li>
 *   <li>覆盖写入 1000 个已有小文件</li>
 *   <li>创建 1000 个目录</li>
 *   <li>读取 1000 个小文件</li>
 *   <li>删除 1000 个小文件</li>
 *   <li>删除 1000 个目录</li>
 * </ul>
 */
class DsMftFileSystemBenchmarkTest {

    private static final int FILE_COUNT = 1000;
    private static final int DIR_COUNT = 1000;
    private static final int FILE_SIZE = 100; // 字节
    private static final byte[] FILE_CONTENT;

    static {
        StringBuilder sb = new StringBuilder(FILE_SIZE);
        for (int i = 0; i < FILE_SIZE; i++) {
            sb.append((char) ('a' + (i % 26)));
        }
        FILE_CONTENT = sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private Path nativeTempDir;
    private Path mftTempDir;
    private DsMftFileSystem mftFs;

    @BeforeEach
    void setUp() throws IOException {
        nativeTempDir = Files.createTempDirectory("native_fs_bench_");
        mftTempDir = Files.createTempDirectory("mft_fs_bench_");

        DsMftFileSystemConfig cfg = new DsMftFileSystemConfig();
        cfg.setNamespaceDir(mftTempDir.toString());
        cfg.setAtimeEnabled(false);   // 关闭 atime 避免额外开销
        cfg.setAuditLogEnabled(false); // 关闭审计日志避免额外开销
        mftFs = DsMftFileSystem.loadOrInit(cfg);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (mftFs != null) {
            mftFs.close();
            mftFs = null;
        }
        // 给 Windows 一点时间释放文件句柄
        try {
            Thread.sleep(200);
        } catch (InterruptedException ignored) {
        }
        deleteDirRecursively(nativeTempDir);
        deleteDirRecursively(mftTempDir);
    }

    private static void deleteDirRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        // 重试删除（Windows MappedByteBuffer 释放有延迟）
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        tryDelete(file);
                        return FileVisitResult.CONTINUE;
                    }
                    @Override
                    public FileVisitResult postVisitDirectory(Path d, IOException exc) {
                        tryDelete(d);
                        return FileVisitResult.CONTINUE;
                    }
                });
                if (!Files.exists(dir)) {
                    return;
                }
            } catch (IOException ignored) {
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private static void tryDelete(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }

    // ================== 基准测试 ==================

    @Test
    void benchmarkCreateSmallFiles() throws IOException {
        System.out.println("\n========== 创建 " + FILE_COUNT + " 个小文件（" + FILE_SIZE + " 字节） ==========");

        // 传统文件系统
        long nativeTime = benchmarkNativeCreateFiles();
        System.out.println("传统文件系统: " + nativeTime + " ms");

        // DsMftFileSystem
        long mftTime = benchmarkMftCreateFiles();
        System.out.println("DsMftFileSystem: " + mftTime + " ms");

        double ratio = (double) mftTime / nativeTime;
        System.out.printf("DsMftFileSystem / 传统文件系统 = %.2fx\n", ratio);

        // 验证数据正确性
        verifyMftFiles();
    }

    @Test
    void benchmarkWriteExistingFiles() throws IOException {
        System.out.println("\n========== 覆盖写入 " + FILE_COUNT + " 个已有小文件 ==========");

        // 预先创建文件
        prepareNativeFiles();
        prepareMftFiles();

        byte[] newContent = new byte[FILE_SIZE];
        for (int i = 0; i < FILE_SIZE; i++) {
            newContent[i] = (byte) ('z' - (i % 26));
        }

        // 传统文件系统
        long nativeTime = benchmarkNativeWriteExistingFiles(newContent);
        System.out.println("传统文件系统: " + nativeTime + " ms");

        // DsMftFileSystem
        long mftTime = benchmarkMftWriteExistingFiles(newContent);
        System.out.println("DsMftFileSystem: " + mftTime + " ms");

        double ratio = (double) mftTime / nativeTime;
        System.out.printf("DsMftFileSystem / 传统文件系统 = %.2fx\n", ratio);

        // 验证数据
        verifyMftFilesWritten(newContent);
    }

    @Test
    void benchmarkCreateDirectories() throws IOException {
        System.out.println("\n========== 创建 " + DIR_COUNT + " 个目录 ==========");

        // 传统文件系统
        long nativeTime = benchmarkNativeCreateDirs();
        System.out.println("传统文件系统: " + nativeTime + " ms");

        // DsMftFileSystem
        long mftTime = benchmarkMftCreateDirs();
        System.out.println("DsMftFileSystem: " + mftTime + " ms");

        double ratio = (double) mftTime / nativeTime;
        System.out.printf("DsMftFileSystem / 传统文件系统 = %.2fx\n", ratio);

        verifyMftDirs();
    }

    @Test
    void benchmarkReadSmallFiles() throws IOException {
        System.out.println("\n========== 读取 " + FILE_COUNT + " 个小文件 ==========");

        // 预先创建文件
        prepareNativeFiles();
        prepareMftFiles();

        // 传统文件系统
        long nativeTime = benchmarkNativeReadFiles();
        System.out.println("传统文件系统: " + nativeTime + " ms");

        // DsMftFileSystem
        long mftTime = benchmarkMftReadFiles();
        System.out.println("DsMftFileSystem: " + mftTime + " ms");

        double ratio = (double) mftTime / nativeTime;
        System.out.printf("DsMftFileSystem / 传统文件系统 = %.2fx\n", ratio);
    }

    @Test
    void benchmarkDeleteSmallFiles() throws IOException {
        System.out.println("\n========== 删除 " + FILE_COUNT + " 个小文件 ==========");

        // 传统文件系统
        prepareNativeFiles();
        long nativeTime = benchmarkNativeDeleteFiles();
        System.out.println("传统文件系统: " + nativeTime + " ms");

        // DsMftFileSystem
        prepareMftFiles();
        long mftTime = benchmarkMftDeleteFiles();
        System.out.println("DsMftFileSystem: " + mftTime + " ms");

        double ratio = (double) mftTime / nativeTime;
        System.out.printf("DsMftFileSystem / 传统文件系统 = %.2fx\n", ratio);
    }

    @Test
    void benchmarkDeleteDirectories() throws IOException {
        System.out.println("\n========== 删除 " + DIR_COUNT + " 个目录 ==========");

        // 传统文件系统
        prepareNativeDirs();
        long nativeTime = benchmarkNativeDeleteDirs();
        System.out.println("传统文件系统: " + nativeTime + " ms");

        // DsMftFileSystem
        prepareMftDirs();
        long mftTime = benchmarkMftDeleteDirs();
        System.out.println("DsMftFileSystem: " + mftTime + " ms");

        double ratio = (double) mftTime / nativeTime;
        System.out.printf("DsMftFileSystem / 传统文件系统 = %.2fx\n", ratio);
    }

    @Test
    void benchmarkFullWorkflow() throws IOException {
        System.out.println("\n========== 完整工作流对比（创建 + 读取 + 删除） ==========");
        System.out.println("文件数: " + FILE_COUNT + ", 目录数: " + DIR_COUNT);

        // 传统文件系统完整流程
        long nativeTotal = runNativeFullWorkflow();
        System.out.println("\n传统文件系统总耗时: " + nativeTotal + " ms");

        // DsMftFileSystem 完整流程
        long mftTotal = runMftFullWorkflow();
        System.out.println("DsMftFileSystem 总耗时: " + mftTotal + " ms");

        double ratio = (double) mftTotal / nativeTotal;
        System.out.printf("\n>>> DsMftFileSystem / 传统文件系统 = %.2fx\n", ratio);

        if (ratio < 1.0) {
            System.out.println(">>> DsMftFileSystem 更快");
        } else {
            System.out.println(">>> 传统文件系统更快");
        }
    }

    // ================== 传统文件系统操作 ==================

    private long benchmarkNativeCreateFiles() throws IOException {
        long start = System.currentTimeMillis();
        for (int i = 0; i < FILE_COUNT; i++) {
            Path file = nativeTempDir.resolve("file_" + i + ".txt");
            Files.write(file, FILE_CONTENT);
        }
        return System.currentTimeMillis() - start;
    }

    private long benchmarkNativeCreateDirs() throws IOException {
        long start = System.currentTimeMillis();
        for (int i = 0; i < DIR_COUNT; i++) {
            Path dir = nativeTempDir.resolve("dir_" + i);
            Files.createDirectories(dir);
        }
        return System.currentTimeMillis() - start;
    }

    private long benchmarkNativeReadFiles() throws IOException {
        long start = System.currentTimeMillis();
        for (int i = 0; i < FILE_COUNT; i++) {
            Path file = nativeTempDir.resolve("file_" + i + ".txt");
            byte[] data = Files.readAllBytes(file);
            assertEquals(FILE_SIZE, data.length);
        }
        return System.currentTimeMillis() - start;
    }

    private long benchmarkNativeDeleteFiles() throws IOException {
        long start = System.currentTimeMillis();
        for (int i = 0; i < FILE_COUNT; i++) {
            Path file = nativeTempDir.resolve("file_" + i + ".txt");
            Files.deleteIfExists(file);
        }
        return System.currentTimeMillis() - start;
    }

    private long benchmarkNativeDeleteDirs() throws IOException {
        long start = System.currentTimeMillis();
        for (int i = 0; i < DIR_COUNT; i++) {
            Path dir = nativeTempDir.resolve("dir_" + i);
            Files.deleteIfExists(dir);
        }
        return System.currentTimeMillis() - start;
    }

    private long benchmarkNativeWriteExistingFiles(byte[] data) throws IOException {
        long start = System.currentTimeMillis();
        for (int i = 0; i < FILE_COUNT; i++) {
            Path file = nativeTempDir.resolve("file_" + i + ".txt");
            Files.write(file, data);
        }
        return System.currentTimeMillis() - start;
    }

    private long benchmarkMftWriteExistingFiles(byte[] data) throws IOException {
        long start = System.currentTimeMillis();
        for (int i = 0; i < FILE_COUNT; i++) {
            mftFs.writeFile("/bench_files/file_" + i + ".txt", data);
        }
        return System.currentTimeMillis() - start;
    }

    private long runNativeFullWorkflow() throws IOException {
        long t1 = benchmarkNativeCreateDirs();
        System.out.println("  [Native] 创建目录: " + t1 + " ms");

        long t2 = benchmarkNativeCreateFiles();
        System.out.println("  [Native] 创建文件: " + t2 + " ms");

        long t3 = benchmarkNativeReadFiles();
        System.out.println("  [Native] 读取文件: " + t3 + " ms");

        long t4 = benchmarkNativeDeleteFiles();
        System.out.println("  [Native] 删除文件: " + t4 + " ms");

        long t5 = benchmarkNativeDeleteDirs();
        System.out.println("  [Native] 删除目录: " + t5 + " ms");

        return t1 + t2 + t3 + t4 + t5;
    }

    private void prepareNativeFiles() throws IOException {
        for (int i = 0; i < FILE_COUNT; i++) {
            Path file = nativeTempDir.resolve("file_" + i + ".txt");
            if (!Files.exists(file)) {
                Files.write(file, FILE_CONTENT);
            }
        }
    }

    private void prepareNativeDirs() throws IOException {
        for (int i = 0; i < DIR_COUNT; i++) {
            Path dir = nativeTempDir.resolve("dir_" + i);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
        }
    }

    // ================== DsMftFileSystem 操作 ==================

    private long benchmarkMftCreateFiles() throws IOException {
        // 预先创建父目录（/bench_files）
        mftFs.mkdir("/bench_files");
        long start = System.currentTimeMillis();
        for (int i = 0; i < FILE_COUNT; i++) {
            mftFs.writeFile("/bench_files/file_" + i + ".txt", FILE_CONTENT);
        }
        return System.currentTimeMillis() - start;
    }

    private long benchmarkMftCreateDirs() throws IOException {
        // 预先创建父目录（/bench_dirs）
        mftFs.mkdir("/bench_dirs");
        long start = System.currentTimeMillis();
        for (int i = 0; i < DIR_COUNT; i++) {
            mftFs.mkdir("/bench_dirs/dir_" + i);
        }
        return System.currentTimeMillis() - start;
    }

    private long benchmarkMftReadFiles() throws IOException {
        long start = System.currentTimeMillis();
        for (int i = 0; i < FILE_COUNT; i++) {
            byte[] data = mftFs.readFile("/bench_files/file_" + i + ".txt");
            assertNotNull(data);
            assertEquals(FILE_SIZE, data.length);
        }
        return System.currentTimeMillis() - start;
    }

    private long benchmarkMftDeleteFiles() throws IOException {
        long start = System.currentTimeMillis();
        for (int i = 0; i < FILE_COUNT; i++) {
            mftFs.deleteFile("/bench_files/file_" + i + ".txt");
        }
        return System.currentTimeMillis() - start;
    }

    private long benchmarkMftDeleteDirs() throws IOException {
        long start = System.currentTimeMillis();
        for (int i = 0; i < DIR_COUNT; i++) {
            mftFs.deleteDir("/bench_dirs/dir_" + i);
        }
        return System.currentTimeMillis() - start;
    }

    private long runMftFullWorkflow() throws IOException {
        long t1 = benchmarkMftCreateDirs();
        System.out.println("  [MFT] 创建目录: " + t1 + " ms");

        long t2 = benchmarkMftCreateFiles();
        System.out.println("  [MFT] 创建文件: " + t2 + " ms");

        long t3 = benchmarkMftReadFiles();
        System.out.println("  [MFT] 读取文件: " + t3 + " ms");

        long t4 = benchmarkMftDeleteFiles();
        System.out.println("  [MFT] 删除文件: " + t4 + " ms");

        long t5 = benchmarkMftDeleteDirs();
        System.out.println("  [MFT] 删除目录: " + t5 + " ms");

        return t1 + t2 + t3 + t4 + t5;
    }

    private void prepareMftFiles() throws IOException {
        if (!mftFs.exists("/bench_files")) {
            mftFs.mkdir("/bench_files");
        }
        for (int i = 0; i < FILE_COUNT; i++) {
            String path = "/bench_files/file_" + i + ".txt";
            if (!mftFs.exists(path)) {
                mftFs.writeFile(path, FILE_CONTENT);
            }
        }
    }

    private void prepareMftDirs() throws IOException {
        if (!mftFs.exists("/bench_dirs")) {
            mftFs.mkdir("/bench_dirs");
        }
        for (int i = 0; i < DIR_COUNT; i++) {
            String path = "/bench_dirs/dir_" + i;
            if (!mftFs.exists(path)) {
                mftFs.mkdir(path);
            }
        }
    }

    // ================== 验证 ==================

    private void verifyMftFiles() throws IOException {
        for (int i = 0; i < FILE_COUNT; i++) {
            byte[] data = mftFs.readFile("/bench_files/file_" + i + ".txt");
            assertArrayEquals(FILE_CONTENT, data, "file_" + i + ".txt content mismatch");
        }
    }

    private void verifyMftFilesWritten(byte[] expected) throws IOException {
        for (int i = 0; i < FILE_COUNT; i++) {
            byte[] data = mftFs.readFile("/bench_files/file_" + i + ".txt");
            assertArrayEquals(expected, data, "file_" + i + ".txt overwritten content mismatch");
        }
    }

    private void verifyMftDirs() throws IOException {
        for (int i = 0; i < DIR_COUNT; i++) {
            assertTrue(mftFs.exists("/bench_dirs/dir_" + i), "dir_" + i + " should exist");
        }
    }
}
