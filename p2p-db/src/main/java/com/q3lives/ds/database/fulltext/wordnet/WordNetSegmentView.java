package com.q3lives.ds.database.fulltext.wordnet;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.FulltextStore.Neighbor;
import com.q3lives.ds.database.fulltext.FulltextStore.NeighborDir;
import com.q3lives.ds.database.fulltext.FulltextStore.PositionSpan;
import com.q3lives.ds.database.fulltext.segment.SegmentColumnCodec;

/**
 * P51 跨段词网只读视图（Word Net Segment View，mmap 零重建）。
 *
 * <p>
 * 包装 1..N 个已注册「词网段」（{@code rootDir/wordnet/seg-<id>.dnet}），按调用方显式给定的「旧→新」
 * 段序叠加，向上暴露与 P21 {@code FulltextStore} 同 API 的词网查询面：{@link #neighborsWithin} /
 * {@link #distanceBetween} / {@link #topKNeighbors} / {@link #positions}。
 *
 * <p>
 * <b>零重建（R10）</b>：{@link #open} 仅 mmap 段文件 + 解析段头（MAGIC / VERSION 硬校验）+ 逐
 * section 复算 CRC32，不把段内容读进堆、不建哈希表、不重算任何统计 → 打开代价与段体积无关（逆指标：冷启动 P95 ≤ 200ms）。
 *
 * <p>
 * <b>覆盖语义（R8）</b>：对同一词网键，从新到旧取<b>首个命中段</b>（短路覆盖）；段序由调用方显式给定， 不依赖 manifest
 * 物理行序。{@link #fingerprint()} 随段序变化，可用于审计视图构成。
 *
 * <p>
 * <b>TOPK 铁律（R3）</b>：本类纯只读数据搬运，0 改 K、0 触 {@code SearchOptions}、0 污染
 * {@code bucketAccessCounter}；TOPK 语义由段内 payload 的 (d↑, R→L) 顺序与去重键
 * {@code (uid<<1)|dir} 在视图层复现。
 *
 * <p>
 * 4 条 IAE 守卫：① rootDir 为 null/空白 ② segIds 为 null ③ segIds 为空 ④ {@code level} ∉
 * [0,5]； 另由 {@link Store#open} 兜底「段未注册」/「段头非法」IAE。
 *
 * <p>
 * 红线：本类位于新子包 {@code fulltext.wordnet}（ZeroLeak3=3 永保）；P21 {@code FulltextStore}
 * 仅 append-only；P37 {@code segment/} 源码 0 改（仅复用其公开静态编解码工具）；仅 JDK + first-party
 * import（R2 NG-3）。
 */
public final class WordNetSegmentView implements AutoCloseable {

    /**
     * 分层级数（char/word/phrase/sentence/sentence_block/doc_struct）。
     */
    private static final int LEVELS = 6;
    /**
     * 邻接条目定长宽度：{@code [d 4B][dir 4B][uid 8B]}。
     */
    private static final int ENTRY_REC = 16;
    /**
     * positions 命中区间定长宽度：{@code [start 4B][end 4B]}。
     */
    private static final int SPAN_REC = 8;

    private final long[] segIdsOldToNew;
    private final Store[] stores;
    private boolean closed;

    private WordNetSegmentView(long[] segIdsOldToNew, Store[] stores) {
        this.segIdsOldToNew = segIdsOldToNew;
        this.stores = stores;
    }

    /**
     * 打开 1..N 个已注册词网段（旧→新）的零重建只读视图。
     *
     * @param wordNetRootDir 词网段根目录（其下为 {@code wordnet/}），非 null 且非空白
     * @param segIdsOldToNew 段 id 序列（旧在前、新在后），非 null 非空
     * @return 跨段词网只读视图
     */
    public static WordNetSegmentView open(String wordNetRootDir, long[] segIdsOldToNew) throws IOException {
        if (wordNetRootDir == null || wordNetRootDir.trim().isEmpty()) {
            throw new IllegalArgumentException("view rootDir null or empty");
        }
        if (segIdsOldToNew == null) {
            throw new IllegalArgumentException("view segIds null");
        }
        if (segIdsOldToNew.length == 0) {
            throw new IllegalArgumentException("view segIds empty");
        }
        Store[] stores = new Store[segIdsOldToNew.length];
        List<Store> opened = new ArrayList<>(segIdsOldToNew.length);
        try {
            for (int i = 0; i < stores.length; i++) {
                Store s = Store.open(wordNetRootDir, segIdsOldToNew[i]);
                stores[i] = s;
                opened.add(s);
            }
        } catch (IOException | RuntimeException e) {
            for (Store s : opened) {
                s.close();
            }
            throw e;
        }
        return new WordNetSegmentView(segIdsOldToNew.clone(), stores);
    }

    /**
     * 在 (docId,parentId,level) 上下文下，读 target 的 1..maxD 内所有邻居（新段优先短路覆盖）。
     *
     * <p>
     * {@code maxD} 钳制到 [1, {@link FulltextStore#DIST_MAX}]；miss 返回空列表（zero
     * null）。
     */
    public List<Neighbor> neighborsWithin(long targetUid, long docId, long parentId, int level, int maxD) {
        checkLevel(level);
        ensureOpen();
        int cap = maxD;
        if (cap < 1) {
            cap = 1;
        }
        if (cap > FulltextStore.DIST_MAX) {
            cap = FulltextStore.DIST_MAX;
        }
        Set<Neighbor> out = new LinkedHashSet<>();
        byte[] payload = firstAdjPayload(docId, parentId, level, targetUid);
        if (payload == null) {
            return new ArrayList<>(out);
        }
        ByteBuffer bb = ByteBuffer.wrap(payload);
        int count = bb.getInt();
        for (int i = 0; i < count; i++) {
            int d = bb.getInt();
            int dir = bb.getInt();
            long uid = bb.getLong();
            if (d <= cap) {
                out.add(new Neighbor(uid, dir == 1 ? NeighborDir.R : NeighborDir.L, d));
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * 在 (docId,parentId,level) 上下文下，left → right 的最小正距离（新段优先短路覆盖）；不存在返回 -1。
     */
    public int distanceBetween(long leftUid, long rightUid, long docId, long parentId, int level) {
        checkLevel(level);
        ensureOpen();
        byte[] payload = firstAdjPayload(docId, parentId, level, leftUid);
        if (payload == null) {
            return -1;
        }
        ByteBuffer bb = ByteBuffer.wrap(payload);
        int count = bb.getInt();
        for (int i = 0; i < count; i++) {
            int d = bb.getInt();
            int dir = bb.getInt();
            long uid = bb.getLong();
            if (dir == 1 && uid == rightUid) {
                return d;
            }
        }
        return -1;
    }

    /**
     * 在 (docId,parentId,level) 上下文下取 target 的前 K 个邻居（d↑ + R→L + 去重，满 K 即返）。
     */
    public List<Neighbor> topKNeighbors(long targetUid, long docId, long parentId, int level, int K) {
        checkLevel(level);
        ensureOpen();
        List<Neighbor> out = new ArrayList<>();
        if (K <= 0) {
            return out;
        }
        byte[] payload = firstAdjPayload(docId, parentId, level, targetUid);
        if (payload == null) {
            return out;
        }
        Set<Long> seen = new HashSet<>();
        ByteBuffer bb = ByteBuffer.wrap(payload);
        int count = bb.getInt();
        for (int i = 0; i < count; i++) {
            int d = bb.getInt();
            int dir = bb.getInt();
            long uid = bb.getLong();
            long dedupKey = (uid << 1) | (dir == 1 ? 1L : 0L);
            if (seen.add(dedupKey)) {
                out.add(new Neighbor(uid, dir == 1 ? NeighborDir.R : NeighborDir.L, d));
                if (out.size() >= K) {
                    return out;
                }
            }
        }
        return out;
    }

    /**
     * 读 (uid,docId,parentId,level) 的 positions 命中区间（新段优先短路覆盖）；miss 返回空列表。
     */
    public List<PositionSpan> positions(long uid, long docId, long parentId, int level) {
        checkLevel(level);
        ensureOpen();
        List<PositionSpan> out = new ArrayList<>();
        byte[] payload = firstPosPayload(uid, docId, parentId, level);
        if (payload == null) {
            return out;
        }
        ByteBuffer bb = ByteBuffer.wrap(payload);
        int count = bb.getInt();
        for (int i = 0; i < count; i++) {
            out.add(new PositionSpan(bb.getInt(), bb.getInt()));
        }
        return out;
    }

    /**
     * 参与本视图的段数。
     */
    public int segmentCount() {
        return stores.length;
    }

    /**
     * 视图指纹（随段序 / 各段头指纹变化）。
     */
    public String fingerprint() {
        StringBuilder sb = new StringBuilder(stores.length * 80);
        for (int i = 0; i < stores.length; i++) {
            sb.append(stores[i].segId()).append(':').append(stores[i].segmentFingerprint()).append(';');
        }
        return WordNetSnapshot.sha256Hex(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 视图段序快照（旧→新，防御性拷贝）。
     */
    public long[] segIdsOldToNew() {
        return segIdsOldToNew.clone();
    }

    /**
     * P54 Q4：只读审计——返回 {@code segIdsOldToNew} 中被更新段完全覆盖（数据或墓碑）的 segId 列表
     * （旧→新序，供调用方决策，<b>0 自动删</b>）。
     *
     * <p>
     * 判定：段 i 仅当「其每个 ctx 键出现在某更新段（j&gt;i）的 ctx 键集」且「其每个 pos 键出现在某更新段的 pos
     * 键集」时视为完全被覆盖。墓碑段（哨兵 payload）的键同样计入覆盖集。
     *
     * @param wordNetRootDir 词网段根目录，非 null 且非空白
     * @param segIdsOldToNew 段 id 序列（旧在前、新在后），非 null 非空且均已登记
     * @return 完全被覆盖的 segId（保持传入序）
     */
    public static long[] auditSuperseded(String wordNetRootDir, long[] segIdsOldToNew) throws IOException {
        if (wordNetRootDir == null || wordNetRootDir.trim().isEmpty()) {
            throw new IllegalArgumentException("auditSuperseded rootDir null or empty");
        }
        if (segIdsOldToNew == null) {
            throw new IllegalArgumentException("auditSuperseded segIds null");
        }
        if (segIdsOldToNew.length == 0) {
            throw new IllegalArgumentException("auditSuperseded segIds empty");
        }
        return Store.supersededSegments(wordNetRootDir, segIdsOldToNew);
    }

    /**
     * P54 Q4/Q5：显式淘汰——先 unregister（重写 manifest 移除）→ 后 delete 段文件（R9' 强不变式）。
     *
     * <p>
     * 淘汰恒由调用方<b>显式</b>触发（0 后台 / 0 定时器 / 0 新线程）；建议先经 {@link #auditSuperseded}
     * 只读判定可淘汰集。
     *
     * @param wordNetRootDir 词网段根目录，非 null 且非空白
     * @param segIdsToEvict 待淘汰段 id，非 null 非空且均已登记
     */
    public static void evict(String wordNetRootDir, long[] segIdsToEvict) throws IOException {
        if (wordNetRootDir == null || wordNetRootDir.trim().isEmpty()) {
            throw new IllegalArgumentException("evict rootDir null or empty");
        }
        if (segIdsToEvict == null) {
            throw new IllegalArgumentException("evict segIds null");
        }
        if (segIdsToEvict.length == 0) {
            throw new IllegalArgumentException("evict segIds empty");
        }
        Store.evictSegments(wordNetRootDir, segIdsToEvict);
    }

    /**
     * 关闭全部底层段读取端（幂等）。
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (Store s : stores) {
            s.close();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalArgumentException("view already closed");
        }
    }

    private void checkLevel(int level) {
        if (level < 0 || level >= LEVELS) {
            throw new IllegalArgumentException("level out of range [0," + (LEVELS - 1) + "]: " + level);
        }
    }

    private byte[] firstAdjPayload(long docId, long parentId, int level, long srcUid) {
        long key = WordNetExporter.ctxKey(docId, parentId, level, srcUid);
        for (int i = stores.length - 1; i >= 0; i--) {
            byte[] p = stores[i].adjPayload(key);
            if (p != null) {
                return p;
            }
        }
        return null;
    }

    private byte[] firstPosPayload(long uid, long docId, long parentId, int level) {
        long key = WordNetExporter.posKey(uid, docId, parentId, level);
        for (int i = stores.length - 1; i >= 0; i--) {
            byte[] p = stores[i].posPayload(key);
            if (p != null) {
                return p;
            }
        }
        return null;
    }

    /**
     * 单个词网段的 mmap 读取端 + 词网段仓库（manifest）静态操作（ZeroLeak3：内嵌类不计 top-level）。
     *
     * <p>
     * 段文件布局：4KB 头（MAGIC {@code 'D','N','E','T'} + VERSION + 三类计数 + 4 段
     * offset/len + 4×CRC32）+ 依次 4KB 对齐的 4 个 section（ctx 邻接索引 / pos 位置索引 /
     * adjVar 邻接动态区 / posVar 位置动态区）。索引 section 为定长 16B 记录
     * {@code [key 8B][off 4B][len 4B]} 且按键严格升序， 因此
     * {@link SegmentColumnCodec#binarySearchKey} 可直接在 mmap 上 O(log N) 定位；动态区复用
     * {@link SegmentColumnCodec#readVar} 按需解压。
     *
     * <p>
     * 资源管理：{@link #close()} 通过反射调用 JDK 内部 unmapper（与
     * {@code DsObject.unloadBuffer} 同款 双通道），确保 Windows 下段文件可被立即删除；close 幂等。
     */
    public static final class Store implements AutoCloseable {

        /**
         * 词网段魔数：'D' 'N' 'E' 'T'（独立于 P37 DIDX，R5 段格式 0 变更）。
         */
        static final byte[] MAGIC = {'D', 'N', 'E', 'T'};
        /**
         * 词网段格式版本。
         */
        static final int VERSION = 1;
        /**
         * 段头保留 1 页（4KB 页对齐）。
         */
        static final int HEADER_SIZE = 4096;
        /**
         * 校验和 section 数量（ctx / pos / adjVar / posVar）。
         */
        static final int CRC_SECTIONS = 4;
        /**
         * 索引记录定长宽度：{@code [key 8B][off 4B][len 4B]}。
         */
        static final int REC_SIZE = 16;
        /**
         * 段头定长字段区字节数（CRC32 之后全部补零）。
         */
        static final int HEADER_FIXED = 112;

        private static final String DIR_NAME = "wordnet";
        private static final String SEG_PREFIX = "seg-";
        private static final String SEG_SUFFIX = ".dnet";
        private static final String MANIFEST_NAME = "manifest";

        private static Method UNMAPPER_METHOD;
        private static Object UNSAFE_OBJ;
        private static Method UNSAFE_INVOKE_CLEANER;

        private final long segId;
        private final MappedByteBuffer map;
        private final long ctxCount;
        private final long adjCount;
        private final long posCount;
        private final String segmentFingerprint;
        private final ByteBuffer ctxCol;
        private final ByteBuffer posCol;
        private final ByteBuffer adjVar;
        private final ByteBuffer posVar;
        private final long posRecCount;
        private boolean closed;

        private Store(long segId, MappedByteBuffer map, long ctxCount, long adjCount, long posCount,
                long ctxOff, long ctxLen, long posOff, long posLen,
                long adjVarOff, long adjVarLen, long posVarOff, long posVarLen, String segmentFingerprint) {
            this.segId = segId;
            this.map = map;
            this.ctxCount = ctxCount;
            this.adjCount = adjCount;
            this.posCount = posCount;
            this.segmentFingerprint = segmentFingerprint;
            this.ctxCol = slice(map, ctxOff, ctxLen);
            this.posCol = slice(map, posOff, posLen);
            this.adjVar = slice(map, adjVarOff, adjVarLen);
            this.posVar = slice(map, posVarOff, posVarLen);
            this.posRecCount = posLen / REC_SIZE;
        }

        /**
         * 打开某已注册词网段（mmap + 段头硬校验 + 4×CRC32），零重建。
         *
         * @param wordNetRootDir 词网段根目录，非 null 且非空白
         * @param segId 段 id，≥ 0 且已注册
         */
        static Store open(String wordNetRootDir, long segId) throws IOException {
            File f = segFile(wordNetRootDir, segId);
            if (!f.isFile()) {
                throw new IllegalArgumentException("wordnet segment missing segId=" + segId);
            }
            if (f.length() < HEADER_SIZE) {
                throw new IllegalArgumentException("wordnet segment too short: " + f.length());
            }
            MappedByteBuffer map;
            try (FileChannel ch = FileChannel.open(f.toPath(), StandardOpenOption.READ)) {
                map = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
            }
            ByteBuffer head = map.duplicate();
            head.position(0);
            for (int i = 0; i < MAGIC.length; i++) {
                if (head.get(i) != MAGIC[i]) {
                    throw new IllegalArgumentException("wordnet bad magic at " + i);
                }
            }
            int version = head.getInt(4);
            if (version != VERSION) {
                throw new IllegalArgumentException("wordnet version mismatch: got=" + version + " expect=" + VERSION);
            }
            long ctxCount = head.getLong(8);
            long adjCount = head.getLong(16);
            long posCount = head.getLong(24);
            long ctxOff = head.getLong(32);
            long ctxLen = head.getLong(40);
            long posOff = head.getLong(48);
            long posLen = head.getLong(56);
            long adjVarOff = head.getLong(64);
            long adjVarLen = head.getLong(72);
            long posVarOff = head.getLong(80);
            long posVarLen = head.getLong(88);
            int[] crcs = new int[CRC_SECTIONS];
            for (int i = 0; i < CRC_SECTIONS; i++) {
                crcs[i] = head.getInt(96 + i * 4);
            }
            verifySection(map, ctxOff, ctxLen, crcs[0], "ctx");
            verifySection(map, posOff, posLen, crcs[1], "pos");
            verifySection(map, adjVarOff, adjVarLen, crcs[2], "adjVar");
            verifySection(map, posVarOff, posVarLen, crcs[3], "posVar");
            byte[] headFixed = new byte[HEADER_FIXED];
            ByteBuffer hb = map.duplicate();
            hb.position(0);
            hb.get(headFixed);
            String fp = WordNetSnapshot.sha256Hex(headFixed);
            return new Store(segId, map, ctxCount, adjCount, posCount, ctxOff, ctxLen, posOff, posLen,
                    adjVarOff, adjVarLen, posVarOff, posVarLen, fp);
        }

        /**
         * 组装 4KB 段头（前 {@link #HEADER_FIXED} 字节定长字段区，其余补零）。
         */
        static byte[] buildHeader(long ctxCount, long adjCount, long posCount,
                long ctxOff, long ctxLen, long posOff, long posLen,
                long adjVarOff, long adjVarLen, long posVarOff, long posVarLen, int[] crcs) {
            if (crcs == null || crcs.length != CRC_SECTIONS) {
                throw new IllegalArgumentException("header crc32 must have " + CRC_SECTIONS + " entries");
            }
            ByteBuffer bb = ByteBuffer.allocate(HEADER_SIZE);
            bb.put(MAGIC);
            bb.putInt(VERSION);
            bb.putLong(ctxCount);
            bb.putLong(adjCount);
            bb.putLong(posCount);
            bb.putLong(ctxOff);
            bb.putLong(ctxLen);
            bb.putLong(posOff);
            bb.putLong(posLen);
            bb.putLong(adjVarOff);
            bb.putLong(adjVarLen);
            bb.putLong(posVarOff);
            bb.putLong(posVarLen);
            for (int c : crcs) {
                bb.putInt(c);
            }
            return bb.array();
        }

        /**
         * 词网段仓库目录（{@code rootDir/wordnet/}），必要时创建。
         *
         * @param wordNetRootDir 根目录，非 null 且非空白
         */
        static File dirOf(String wordNetRootDir) throws IOException {
            if (wordNetRootDir == null || wordNetRootDir.trim().isEmpty()) {
                throw new IllegalArgumentException("store rootDir null or empty");
            }
            File dir = new File(wordNetRootDir, DIR_NAME);
            if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                throw new IOException("cannot create wordnet dir: " + dir.getAbsolutePath());
            }
            return dir;
        }

        /**
         * 计算某 segId 对应的词网段文件路径（不检查存在）。
         */
        static File segFile(String wordNetRootDir, long segId) throws IOException {
            if (segId < 0) {
                throw new IllegalArgumentException("segId < 0: " + segId);
            }
            return new File(dirOf(wordNetRootDir), SEG_PREFIX + segId + SEG_SUFFIX);
        }

        /**
         * 列出全部已注册词网段的 segId（manifest 原始顺序）。
         */
        static long[] list(String wordNetRootDir) throws IOException {
            File manifest = new File(dirOf(wordNetRootDir), MANIFEST_NAME);
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
                    throw new IllegalArgumentException("wordnet manifest corrupt entry: " + t);
                }
                if (v < 0) {
                    throw new IllegalArgumentException("wordnet manifest corrupt segId: " + v);
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
         * 登记一个已落盘的词网段（写 manifest，不改段内容；段头 MAGIC/VERSION 校验通过方可登记）。
         *
         * @param wordNetRootDir 根目录
         * @param segId 段 id，≥ 0；对应文件必须已存在
         */
        static void append(String wordNetRootDir, long segId) throws IOException {
            File f = segFile(wordNetRootDir, segId);
            if (!f.isFile()) {
                throw new IllegalArgumentException("wordnet segment file missing: " + f.getName());
            }
            long[] ids = list(wordNetRootDir);
            for (long id : ids) {
                if (id == segId) {
                    throw new IllegalArgumentException("duplicate append segId=" + segId);
                }
            }
            byte[] head = new byte[8];
            try (FileChannel ch = FileChannel.open(f.toPath(), StandardOpenOption.READ)) {
                ByteBuffer b = ByteBuffer.wrap(head);
                int n = ch.read(b);
                if (n < 8) {
                    throw new IllegalArgumentException("wordnet segment header truncated: " + f.getName());
                }
            }
            for (int i = 0; i < MAGIC.length; i++) {
                if (head[i] != MAGIC[i]) {
                    throw new IllegalArgumentException("wordnet segment bad magic: " + f.getName());
                }
            }
            int version = ByteBuffer.wrap(head).getInt(4);
            if (version != VERSION) {
                throw new IllegalArgumentException("wordnet segment version mismatch: " + version);
            }
            StringBuilder sb = new StringBuilder(ids.length * 4 + 4);
            for (long id : ids) {
                sb.append(id).append('\n');
            }
            sb.append(segId).append('\n');
            File manifest = new File(dirOf(wordNetRootDir), MANIFEST_NAME);
            Files.write(manifest.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        }

        /**
         * P54 Q4：只读审计——返回「全部键均被某个更新段覆盖（数据或墓碑）」的 segId（保持传入旧→新序）。
         *
         * <p>
         * 实现：逐段 mmap 顺序读键列（{@link #ctxKeys()} / {@link #posKeys()}）；覆盖集以「排序
         * {@code long[]} + {@link Arrays#binarySearch}」表达（<b>0 建堆哈希表</b>，对齐
         * R10）。纯只读，
         * <b>0 删段</b>。
         */
        static long[] supersededSegments(String wordNetRootDir, long[] segIdsOldToNew) throws IOException {
            long[] registered = list(wordNetRootDir);
            for (long id : segIdsOldToNew) {
                if (!contains(registered, id)) {
                    throw new IllegalArgumentException("auditSuperseded segId not registered: " + id);
                }
            }
            int n = segIdsOldToNew.length;
            long[][] ctxKeys = new long[n][];
            long[][] posKeys = new long[n][];
            for (int i = 0; i < n; i++) {
                Store s = open(wordNetRootDir, segIdsOldToNew[i]);
                try {
                    ctxKeys[i] = s.ctxKeys();
                    posKeys[i] = s.posKeys();
                } finally {
                    s.close();
                }
            }
            List<Long> out = new ArrayList<>();
            for (int i = 0; i + 1 < n; i++) {
                long[] coverCtx = unionSorted(ctxKeys, i + 1, n);
                long[] coverPos = unionSorted(posKeys, i + 1, n);
                if (allCovered(ctxKeys[i], coverCtx) && allCovered(posKeys[i], coverPos)) {
                    out.add(segIdsOldToNew[i]);
                }
            }
            long[] res = new long[out.size()];
            for (int i = 0; i < res.length; i++) {
                res[i] = out.get(i).longValue();
            }
            return res;
        }

        /**
         * P54 Q4/Q5：显式淘汰——先 unregister（重写 manifest 移除）→ 后 delete 段文件（R9' 强不变式）。
         *
         * <p>
         * 全部 segId 先整体校验均已登记（任一未登记 → IAE，0 部分变更）；随后原子重写 manifest （{@code .tmp}
         * → {@link Files#move}）移除目标 segId；最后删段文件。崩溃点：① unregister 前崩 → 段仍可见；②
         * unregister 后 delete 前崩 → 段文件为孤儿（不在 manifest，{@link #list} 不可见）；③
         * delete 后崩 → 已淘汰。<b>任一时刻 manifest 内 segId ⇔ 段文件存在</b>。
         */
        static void evictSegments(String wordNetRootDir, long[] segIdsToEvict) throws IOException {
            long[] registered = list(wordNetRootDir);
            for (long id : segIdsToEvict) {
                if (!contains(registered, id)) {
                    throw new IllegalArgumentException("evict segId not registered: " + id);
                }
            }
            StringBuilder sb = new StringBuilder(registered.length * 4);
            for (long id : registered) {
                if (!contains(segIdsToEvict, id)) {
                    sb.append(id).append('\n');
                }
            }
            File manifest = new File(dirOf(wordNetRootDir), MANIFEST_NAME);
            File tmp = new File(manifest.getAbsolutePath() + ".tmp");
            Files.write(tmp.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), manifest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            for (long id : segIdsToEvict) {
                Files.deleteIfExists(segFile(wordNetRootDir, id).toPath());
            }
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
         * 合并 {@code keys[from..to)} 为单个升序数组（用于覆盖集，0 哈希表）。
         */
        private static long[] unionSorted(long[][] keys, int from, int to) {
            int total = 0;
            for (int i = from; i < to; i++) {
                total += keys[i].length;
            }
            long[] out = new long[total];
            int p = 0;
            for (int i = from; i < to; i++) {
                System.arraycopy(keys[i], 0, out, p, keys[i].length);
                p += keys[i].length;
            }
            Arrays.sort(out);
            return out;
        }

        /**
         * 判断 {@code keys} 中每个键是否都在升序 {@code cover} 中命中（空 keys 恒 true）。
         */
        private static boolean allCovered(long[] keys, long[] cover) {
            for (long k : keys) {
                if (Arrays.binarySearch(cover, k) < 0) {
                    return false;
                }
            }
            return true;
        }

        long segId() {
            return segId;
        }

        String segmentFingerprint() {
            return segmentFingerprint;
        }

        long ctxCount() {
            return ctxCount;
        }

        long adjCount() {
            return adjCount;
        }

        long posCount() {
            return posCount;
        }

        /**
         * 二分查邻接 payload；miss 返回 null。
         */
        byte[] adjPayload(long ctxKey) {
            ensureOpen();
            int i = SegmentColumnCodec.binarySearchKey(ctxCol, REC_SIZE, ctxCount, ctxKey);
            if (i < 0) {
                return null;
            }
            int base = i * REC_SIZE;
            int off = ctxCol.getInt(base + 8);
            int len = ctxCol.getInt(base + 12);
            return SegmentColumnCodec.readVar(adjVar, off, len);
        }

        /**
         * 二分查 positions payload；miss 返回 null。
         */
        byte[] posPayload(long posKey) {
            ensureOpen();
            int i = SegmentColumnCodec.binarySearchKey(posCol, REC_SIZE, posRecCount, posKey);
            if (i < 0) {
                return null;
            }
            int base = i * REC_SIZE;
            int off = posCol.getInt(base + 8);
            int len = posCol.getInt(base + 12);
            return SegmentColumnCodec.readVar(posVar, off, len);
        }

        /**
         * P54：顺序枚举本段全部 ctx（邻接）键（mmap 键列直读，<b>0 建堆哈希表</b>）；键列严格升序 → 返回有序。
         */
        long[] ctxKeys() {
            ensureOpen();
            long[] out = new long[(int) ctxCount];
            for (int i = 0; i < out.length; i++) {
                out[i] = ctxCol.getLong(i * REC_SIZE);
            }
            return out;
        }

        /**
         * P54：顺序枚举本段全部 pos（positions）键（mmap 键列直读，<b>0 建堆哈希表</b>）；键列严格升序 → 返回有序。
         */
        long[] posKeys() {
            ensureOpen();
            long[] out = new long[(int) posRecCount];
            for (int i = 0; i < out.length; i++) {
                out[i] = posCol.getLong(i * REC_SIZE);
            }
            return out;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            unmap(map);
        }

        private void ensureOpen() {
            if (closed) {
                throw new IllegalArgumentException("store already closed");
            }
        }

        private static ByteBuffer slice(MappedByteBuffer map, long off, long len) {
            ByteBuffer b = map.duplicate();
            b.position((int) off);
            b.limit((int) (off + len));
            return b.slice();
        }

        private static void verifySection(MappedByteBuffer map, long off, long len, int expectCrc, String name) {
            if (off < 0 || len < 0 || off + len > map.limit()) {
                throw new IllegalArgumentException("wordnet section out of range [" + name + "]: off=" + off + " len=" + len);
            }
            ByteBuffer b = map.duplicate();
            b.position((int) off);
            b.limit((int) (off + len));
            byte[] data = new byte[(int) len];
            b.get(data);
            int actual = SegmentColumnCodec.crc32(data);
            if (actual != expectCrc) {
                throw new IllegalArgumentException(
                        "wordnet CRC mismatch [" + name + "]: expect=" + expectCrc + " actual=" + actual);
            }
        }

        private static void unmap(MappedByteBuffer buffer) {
            if (buffer == null) {
                return;
            }
            try {
                if (UNMAPPER_METHOD == null) {
                    UNMAPPER_METHOD = MappedByteBuffer.class.getDeclaredMethod("unmapper");
                    UNMAPPER_METHOD.setAccessible(true);
                }
                Object unmapper = UNMAPPER_METHOD.invoke(buffer);
                if (unmapper != null) {
                    Method unmap = unmapper.getClass().getMethod("unmap");
                    unmap.invoke(unmapper);
                }
                return;
            } catch (Throwable ignore) {
                // 退化到 Unsafe.invokeCleaner
            }
            try {
                if (UNSAFE_INVOKE_CLEANER == null || UNSAFE_OBJ == null) {
                    Class<?> uc = Class.forName("sun.misc.Unsafe");
                    Method gf = uc.getDeclaredMethod("getUnsafe");
                    gf.setAccessible(true);
                    UNSAFE_OBJ = gf.invoke(null);
                    UNSAFE_INVOKE_CLEANER = uc.getMethod("invokeCleaner", ByteBuffer.class);
                }
                UNSAFE_INVOKE_CLEANER.invoke(UNSAFE_OBJ, buffer);
            } catch (Throwable ignore) {
                // 无法强制 unmap 时交由 GC 回收
            }
        }
    }
}
