package com.q3lives.ds.database.fulltext.segment;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * P37 段仓库（Segment Store）：{@code rootDir/segment/} 下段文件的生命周期管理。
 *
 * <p>
 * 段文件命名固定为 {@code seg-<segId>.didx}，另有一份纯文本 {@code manifest}（一行一个 segId，
 * 升序）作为「已注册段」的清单。段不可变（immutable snapshot / append-only 语义）：
 * {@link #append(long, DsIndexSegment)} 只登记已落盘的段文件，不做任何内容改写；
 * {@link #remove(long)} 删除段文件并同步 manifest。
 *
 * <p>
 * 本类不持有任何 mmap 资源，{@link #close()} 为幂等空实现，仅用于统一 try-with-resources 语义； 真正的 mmap
 * 生命周期由 {@link DsIndexSegmentReader} 承担。
 *
 * <p>
 * 5 条 IAE 守卫：① rootDir 为 null/空白 ② segId &lt; 0 ③ append 时段文件缺失 ④ 重复 append ⑤
 * 段不存在（load/remove）或 manifest 损坏（非数字行）。
 *
 * <p>
 * 红线：ZeroLeak3 —— 本类位于 {@code fulltext.segment} 子包，不新增 fulltext 根包 top-level 类。
 */
public final class DsIndexSegmentStore implements AutoCloseable {

    private static final String SEG_PREFIX = "seg-";
    private static final String SEG_SUFFIX = ".didx";
    private static final String MANIFEST_NAME = "manifest";

    private final File dir;
    private final File manifest;

    /**
     * 在 {@code rootDir/segment/} 下创建（或复用）段仓库。
     *
     * @param rootDir 仓库根目录，非 null 且非空白
     */
    public DsIndexSegmentStore(String rootDir) throws IOException {
        if (rootDir == null || rootDir.trim().isEmpty()) {
            throw new IllegalArgumentException("store rootDir null or empty");
        }
        this.dir = new File(rootDir, "segment");
        if (!dir.isDirectory()) {
            if (!dir.mkdirs() && !dir.isDirectory()) {
                throw new IOException("cannot create segment dir: " + dir.getAbsolutePath());
            }
        }
        this.manifest = new File(dir, MANIFEST_NAME);
    }

    /**
     * 段文件所在目录（{@code rootDir/segment/}）。
     */
    public File dir() {
        return dir;
    }

    /**
     * 计算某 segId 对应的段文件路径（不检查存在）。
     *
     * @param segId 段 id，≥ 0
     */
    public File segmentFile(long segId) {
        if (segId < 0) {
            throw new IllegalArgumentException("segId < 0: " + segId);
        }
        return new File(dir, SEG_PREFIX + segId + SEG_SUFFIX);
    }

    /**
     * 登记一个已落盘的段（写 manifest，不改段内容）。
     *
     * @param segId 段 id，≥ 0；对应文件必须已存在
     * @param seg 段描述对象，其头信息必须与磁盘段文件一致
     * @return 从磁盘段头解析出的段描述对象
     */
    public DsIndexSegment append(long segId, DsIndexSegment seg) throws IOException {
        if (seg == null) {
            throw new IllegalArgumentException("append null segment");
        }
        File f = segmentFile(segId);
        if (!f.isFile()) {
            throw new IllegalArgumentException("segment file missing: " + f.getName());
        }
        long[] ids = list();
        if (contains(ids, segId)) {
            throw new IllegalArgumentException("duplicate append segId=" + segId);
        }
        DsIndexSegment onDisk = readHeader(f);
        if (!onDisk.fingerprint().equals(seg.fingerprint())) {
            throw new IllegalArgumentException("segment header mismatch for segId=" + segId);
        }
        StringBuilder sb = manifestText(ids);
        sb.append(segId).append('\n');
        writeManifest(sb.toString());
        return onDisk;
    }

    /**
     * 读取某已注册段的描述对象。
     *
     * @param segId 段 id，≥ 0 且已注册
     */
    public DsIndexSegment load(long segId) throws IOException {
        requireRegistered(segId);
        return readHeader(segmentFile(segId));
    }

    /**
     * 打开某已注册段的只读读取端（mmap 零重建）。
     *
     * @param segId 段 id，≥ 0 且已注册
     */
    public DsIndexSegmentReader openReader(long segId) throws IOException {
        requireRegistered(segId);
        return DsIndexSegmentReader.open(segmentFile(segId));
    }

    /**
     * 列出全部已注册段的 segId（manifest 中的原始顺序）。
     */
    public long[] list() throws IOException {
        if (!manifest.isFile()) {
            return new long[0];
        }
        List<Long> ids = new ArrayList<>();
        for (String line : Files.readAllLines(manifest.toPath(), StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            long v;
            try {
                v = Long.parseLong(t);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("manifest corrupt entry: " + t);
            }
            if (v < 0) {
                throw new IllegalArgumentException("manifest corrupt segId: " + v);
            }
            ids.add(v);
        }
        long[] out = new long[ids.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = ids.get(i);
        }
        return out;
    }

    /**
     * 删除一个已注册段：删段文件 + 从 manifest 摘除。
     *
     * @param segId 段 id，≥ 0 且已注册
     */
    public void remove(long segId) throws IOException {
        long[] ids = list();
        requireRegistered(segId, ids);
        File f = segmentFile(segId);
        if (f.isFile() && !f.delete() && f.isFile()) {
            throw new IOException("cannot delete segment: " + f.getAbsolutePath());
        }
        StringBuilder sb = new StringBuilder();
        for (long id : ids) {
            if (id != segId) {
                sb.append(id).append('\n');
            }
        }
        writeManifest(sb.toString());
    }

    @Override
    public void close() {
        // 本类不持有 mmap 资源，幂等空实现
    }

    private void requireRegistered(long segId) throws IOException {
        requireRegistered(segId, list());
    }

    private void requireRegistered(long segId, long[] ids) {
        if (segId < 0) {
            throw new IllegalArgumentException("segId < 0: " + segId);
        }
        if (!contains(ids, segId)) {
            throw new IllegalArgumentException("segment not found segId=" + segId);
        }
    }

    private static StringBuilder manifestText(long[] ids) {
        StringBuilder sb = new StringBuilder(ids.length * 4 + 4);
        for (long id : ids) {
            sb.append(id).append('\n');
        }
        return sb;
    }

    private void writeManifest(String content) throws IOException {
        Files.write(manifest.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean contains(long[] arr, long v) {
        for (long a : arr) {
            if (a == v) {
                return true;
            }
        }
        return false;
    }

    /**
     * 读取段文件头（仅 {@link DsIndexSegment#HEADER_SIZE} 字节）并解析校验。
     */
    private static DsIndexSegment readHeader(File f) throws IOException {
        if (!f.isFile()) {
            throw new IllegalArgumentException("segment file missing: " + f.getName());
        }
        if (f.length() < DsIndexSegment.HEADER_SIZE) {
            throw new IllegalArgumentException("segment file too short: " + f.length());
        }
        byte[] head = new byte[DsIndexSegment.HEADER_SIZE];
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            raf.readFully(head);
        }
        return SegmentColumnCodec.readHeader(ByteBuffer.wrap(head));
    }
}
