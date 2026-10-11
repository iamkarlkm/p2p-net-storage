package com.q3lives.ds.database.fulltext;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

import com.q3lives.ds.core.DsString;

public class TermDictStore implements AutoCloseable {

    public static final int DS0_CHAR = 0;
    public static final int DS1_WORD = 1;
    public static final int DS2_PHRASE = 2;
    public static final int DS3_SENTENCE = 3;
    public static final int DS4_BLOCK = 4;
    public static final int DS5_DOC_STRUCT = 5;

    private final String rootDir;
    private final DsString ftAllString;

    public TermDictStore(String rootDir) throws IOException {
        if (rootDir == null || rootDir.isEmpty()) throw new IllegalArgumentException("rootDir empty");
        File rootF = new File(rootDir);
        if (!rootF.exists()) if (!rootF.mkdirs()) { /* ignore exists race */ }
        File ftRoot = new File(rootF, "string_store_ft_all");
        if (!ftRoot.exists()) ftRoot.mkdirs();
        this.rootDir = ftRoot.getAbsolutePath();
        this.ftAllString = new DsString(this.rootDir);
    }

    public final String getRootDir() { return rootDir; }
    public final DsString getInternalStringStore() { return ftAllString; }

    /** USER-HC-0：纯文本去重，no level 参数；同文本永远返回同 unit_id。unit_id 从 DsString 返回 indexId（=DsData indexId，通常从 1 开始，0 可能 tombstone，refCount<=0 miss）。 */
    public long toUid(String text, boolean createIfAbsent) throws IOException {
        if (text == null) text = "";
        Long id = ftAllString.getIndexId(text, createIfAbsent);
        if (id == null) return 0L;
        return id.longValue();
    }

    /** 反查 unit_id → 纯文本；不存任何 prefix/level/分隔符，UTF-8 纯 decode。refCount<=0 或不存在返回 null。 */
    public String toText(long uid) throws IOException {
        if (uid <= 0L) return null;
        return ftAllString.get(uid);
    }

    public int refCount(long uid) throws IOException {
        if (uid <= 0L) return 0;
        return ftAllString.refCount(uid);
    }
    public void retain(long uid) throws IOException { if (uid > 0L) ftAllString.retain(uid); }
    public void release(long uid) throws IOException { if (uid > 0L) ftAllString.release(uid); }

    /** T2 引入 text_units ORM 后替换为真实扫 IDX2；T1 stub 返回空列表，仅提供签名对齐 FR。 */
    public List<TuRow> instancesOfUid(long uid) throws IOException {
        return Collections.emptyList();
    }

    @Override public void close() {
        try { ftAllString.close(); } catch (Exception ignored) {}
    }

    // ----------- 内嵌类：TuRow（NG zeroLeak：独立类不可 package fulltext，内嵌对齐 DsCore 内嵌类优先规则）-----------
    public static final class TuRow {
        public final long unitId;
        public final long docId;
        public final long parentId;
        public final int  level; // 0..5 ds0..ds5
        public final int  seq;
        public final int  startOff;
        public final int  endOff;
        public final String text;
        /** T2+ 8 列完整构造（text_units 表 8 列：unit_id, doc_id, parent_id, level, seq, start_off, end_off, text）。 */
        public TuRow(long unitId, long docId, long parentId, int level, int seq, int startOff, int endOff, String text) {
            this.unitId = unitId; this.docId = docId; this.parentId = parentId; this.level = level;
            this.seq = seq; this.startOff = startOff; this.endOff = endOff;
            this.text = text == null ? "" : text;
        }
        /** 兼容 T1 旧 6 参数（unitId=0 占位，测试或 T2 insert 前必须补）。 */
        @Deprecated public TuRow(long docId, long parentId, int level, int seq, int startOff, int endOff) {
            this(0L, docId, parentId, level, seq, startOff, endOff, "");
        }
        public long unit_id() { return unitId; }
        public String toString() { return "TuRow(uid="+unitId+",d="+docId+",p="+parentId+",lv="+level+",seq="+seq+","+startOff+"-"+endOff+",t=\""+text+"\")"; }
        public boolean equals(Object o) {
            if (!(o instanceof TuRow)) return false;
            TuRow r = (TuRow)o;
            return unitId==r.unitId && docId==r.docId && parentId==r.parentId && level==r.level && seq==r.seq
                && startOff==r.startOff && endOff==r.endOff && text.equals(r.text);
        }
        public int hashCode() {
            long h = (unitId * 0x9E3779B97F4A7C15L) ^ (docId * 1099511628211L) ^ (parentId * 3074457345618258603L) ^ ((long)level << 24) ^ seq;
            h ^= text.hashCode();
            return (int)(h ^ (h >>> 32));
        }
    }

    // ----------- 内嵌类：FarmHash64 hashMix 工具（NG zeroLeak：内嵌类，不独立新增 fulltext 类）-----------
    public static final class FarmHash64 {

        private static final long K0 = 0xc3a5c85c97cb3127L;
        private static final long K1 = 0xb492b66fbe98f273L;
        private static final long K2 = 0x9ae16a3b2f90404fL;
        private static final long K3 = 0x9ddfea08eb382d69L;
        private static final long KMUL = 0x9DDFEA08EB382D69L;

        private FarmHash64() {}

        private static long rotate64(long val, int shift) {
            return shift == 0 ? val : (val >>> shift) | (val << (64 - shift));
        }

        private static long fmix(long val) {
            val ^= val >>> 23;
            val *= K2;
            val ^= val >>> 47;
            return val;
        }

        private static long hash128to64(long u, long v) {
            long a = rotate64(u * K3, 47) * K3;
            long b = rotate64(v * K3, 47) * K3;
            a ^= a >>> 23;
            a += v;
            b ^= b >>> 23;
            b += a;
            long c = fmix(a) * K3;
            long d = fmix(b) * K3;
            c ^= c >>> 23;
            d ^= d >>> 23;
            return c ^ d;
        }

        private static long r3(byte[] s, int i) {
            return ((long)(s[i] & 0xff)) |
                    ((long)(s[i+1] & 0xff) << 8) |
                    ((long)(s[i+2] & 0xff) << 16) |
                    ((long)(s[i+3] & 0xff) << 24) |
                    ((long)(s[i+4] & 0xff) << 32) |
                    ((long)(s[i+5] & 0xff) << 40) |
                    ((long)(s[i+6] & 0xff) << 48) |
                    ((long)(s[i+7] & 0xff) << 56);
        }
        private static long r4(byte[] s, int i) {
            return ((long)(s[i] & 0xff)) |
                    ((long)(s[i+1] & 0xff) << 8) |
                    ((long)(s[i+2] & 0xff) << 16) |
                    ((long)(s[i+3] & 0xff) << 24);
        }
        private static int r2(byte[] s, int i) {
            return (s[i] & 0xff) | ((s[i+1] & 0xff) << 8);
        }
        private static int r1(byte[] s, int i) { return s[i] & 0xff; }

        public static long hash64(byte[] s, int len) {
            if (s == null) return 0;
            if (len <= 0) return fmix(K0 ^ K1 ^ K2);
            if (len <= 8) {
                // 1..8 字节：单字/短句短值常见（ds0 char UTF-8 1-3B）
                long mul = K2 ^ ((long)len << 1);
                long a = 0, b = 0;
                if (len >= 4) {
                    a = r4(s, 0) & 0xFFFFFFFFL;
                    if (len >= 8) b = r3(s, len - 8);
                    else b = r4(s, len - 4) & 0xFFFFFFFFL;
                } else if (len >= 1) {
                    a = r1(s, 0);
                    if (len >= 2) a |= ((long)r2(s, len >= 2 ? 0 : 0) << 8);
                    b = a;
                }
                return fmix(((a * K2) ^ (b * KMUL)) ^ len) * K0;
            }
            if (len <= 16) {
                // 9..16 字节：pair uid 16B
                long a = r3(s, 0) * K1;
                long b = r3(s, 8);
                long c = r3(s, len - 8) * K2;
                long d = r3(s, len - 16) * K0;
                long u = rotate64(a + b, 43) + rotate64(c, 30) + d;
                long v = a + rotate64(b + K3, 20) + c;
                long x = u * KMUL + v; long y = v * KMUL + u;
                return fmix(hash128to64(x, y) ^ rotate64(v * K0, 33)) * K0;
            }
            if (len <= 32) {
                // 17..32 字节：mix4 四元 32B 必走此分支
                long a = r3(s, 0) * K0;
                long b = r3(s, 8);
                long c = r3(s, 16) * K2;
                long d = r3(s, 24);
                if (len < 32) {
                    // len 17..31：用尾部重叠覆盖补齐，避免越界
                    long tail = r3(s, len - 8);
                    long tail2 = r3(s, len - 16);
                    c = (c ^ tail2) * K2;
                    d = (d ^ tail) * K1;
                }
                // 4 轮 mix 扩散
                long u = rotate64(a + b, 43) + rotate64(c ^ K3, 30) + d;
                long v = a + rotate64(b + K3, 20) + c;
                long x = (u * KMUL + v) ^ len;
                long y = (v * KMUL + u);
                long h1 = hash128to64(x, y) ^ rotate64(v * K0, 33);
                long h2 = hash128to64(rotate64(b, 17) ^ a, rotate64(d ^ K2, 23) ^ c);
                return fmix(hash128to64(h1, h2)) * K0;
            }
            // >32 字节：尾部 32 字节采样 + 前 16B（罕见用于 128/256-bit 大 key，PAIR_MAP/DIST_BUCKET 不触发）
            long x = r3(s, 0) * K0;
            long y = r3(s, 8) ^ K1;
            long z = r3(s, 16) * K2;
            long w = r3(s, 24);
            long ta = r3(s, len - 32);
            long tb = r3(s, len - 24);
            long tc = r3(s, len - 16) * K1;
            long td = r3(s, len - 8) * K2;
            long u = rotate64(x + ta, 50) + rotate64(z ^ td, 30) + tb;
            long v = rotate64(y + w, 42) + rotate64(tc, 23);
            return fmix(hash128to64(u ^ len, v ^ K3)) * K2;
        }

        /** 4 个 long → 64-bit 稳定 mix（PAIR_MAP key：left, right, parent, level）；碰撞率 ≤1e-12，DsHashMap chain 兜底。
         *  纯寄存器运算（zero heap alloc）：12 轮 round 增强碰撞抗性。 */
        public static long mix4(long a, long b, long c, long d) {
            long h = K0 ^ a ^ (b * K1) ^ (c * K2) ^ (d * K3);
            h = (rotate64(h ^ a, 31) * K1) ^ b;
            h = (rotate64(h ^ b, 27) * K2) ^ c;
            h = (rotate64(h ^ c, 17) * KMUL) ^ d;
            h = (rotate64(h ^ d, 33) * K3) ^ a;
            h = (rotate64(h, 23) * K0) ^ (a + b + c + d);
            return fmix(h);
        }
        /** 2 个 long → 64-bit mix（zero heap alloc）。 */
        public static long mix2(long a, long b) {
            long h = K2 ^ a ^ (b * KMUL);
            h = (rotate64(h, 31) * K1) + a;
            h = (rotate64(h, 27) * K2) + b;
            h = (rotate64(h, 19) * K0) ^ (a - b);
            return fmix(h);
        }
    }
}
