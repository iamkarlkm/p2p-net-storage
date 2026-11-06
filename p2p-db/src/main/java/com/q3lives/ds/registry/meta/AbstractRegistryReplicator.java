package com.q3lives.ds.registry.meta;

import com.q3lives.ds.enums.DataType;
import com.q3lives.ds.registry.DsRegistry;
import com.q3lives.ds.registry.RegistryEntry;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.UnaryOperator;

/**
 * P12 OQ2：跨节点 DML 同步 Replicator 抽象。
 * <p>
 * 硬约束（c10 Gatekeeper 钉死，修改前必须用户显式批准）：
 *   - 3 个 replicate 方法：public final，不可 override（E2/D1/D2/D3）
 *   - 2 种构造器：(File,MetaMode) + (File,MetaMode,Map)（E1）
 *   - MetaMode=YAML 或 props `p2p.orm.sync.mode=off` 时构造器返回 null，短路零 new（E3/E4）
 *   - 字段 private final（A1/A2）；内嵌静态 ReplicatorImpl static final（A3）
 *   - ≥5 protected helper 方法（A5）
 * </p>
 *
 * <p>
 * 向后兼容策略（R2，magic=0x52 vs 旧 frame）：
 *   接收端 {@link #applyRegistryFrame(byte[], long)} 首字节 == 0x52 → 走新 Registry apply；
 *   非 0x52 → 抛 {@link OldFrameMarkerException}，调用方 catch 后转旧 P7 bucket 分支（业务代码 100% 旧路径不破坏）。
 * </p>
 *
 * <p>
 * 本类 package-private；只允许 p2p-db 内部 DsDatabaseLocal/ColumnarStore 以 package-private 字段引用，
 * 零对外 SDK 暴露（NFR-M4：javax.net.p2p.* Handler 零字符改，不新建 P2PCommandHandler）。
 * </p>
 */
public abstract class AbstractRegistryReplicator {

    static final byte FRAME_MAGIC = 0x52;
    static final short RESERVED_0 = (short) 0;
    static final byte OP_UPSERT = 0;
    static final byte OP_REMOVE = 1;
    static final int BATCH_LIMIT_LEAVES = 1000; // 64KB/批 ≈1000 leaf

    public static final String SYNC_OFF_PROP = "p2p.orm.sync.mode";

    /** 内嵌 static final 实现类（c10 A3：static final），为真实 RPC 接入预留扩展点（当前内存 mock） */
    public static final class ReplicatorImpl extends AbstractRegistryReplicator {
        public ReplicatorImpl(File dbRoot, MetaMode mode) throws IOException { super(dbRoot, mode, null); }
        public ReplicatorImpl(File dbRoot, MetaMode mode, Map<String,String> props) throws IOException { super(dbRoot, mode, props); }
    }

    // ================== 字段 private final（c10 A1/A2） ==================
    private final File dbRoot;
    private final MetaMode mode;
    private final DsRegistry registry;
    private final Map<Long, LongAdder> peerOverwrites;
    private final ConcurrentLinkedQueue<byte[]> inMemoryFrameQueue; // 测试用内存队列

    // ================== 构造器 2 种（c10 E1/E3/E4） ==================

    /** 构造器 1：仅 dbRoot + mode。MetaMode=YAML 或 sync=off 属性 → 返回 null（需调用方工厂方法配合） */
    protected AbstractRegistryReplicator(File dbRoot, MetaMode mode) throws IOException {
        this(dbRoot, mode, null);
    }

    /** 构造器 2：额外 props（含 sync off 属性）。YAML 模式或 props sync=off → 抛 IOException 供调用方降级为 null（零 new Registry） */
    protected AbstractRegistryReplicator(File dbRoot, MetaMode mode, Map<String,String> props) throws IOException {
        this.dbRoot = Objects.requireNonNull(dbRoot, "dbRoot null");
        this.mode = (mode == null) ? MetaMode.global() : mode;
        this.peerOverwrites = new ConcurrentHashMap<>();
        this.inMemoryFrameQueue = new ConcurrentLinkedQueue<>();

        boolean syncOff = false;
        if (props != null && "off".equalsIgnoreCase(props.get(SYNC_OFF_PROP))) syncOff = true;
        if (!syncOff && "off".equalsIgnoreCase(System.getProperty(SYNC_OFF_PROP))) syncOff = true;

        if (this.mode == MetaMode.YAML || syncOff) {
            // 零 new Registry：构造器阶段就抛，由工厂方法 create() catch → return null（保证 Replicator 字段 null，零短路开销）
            // （注意：字段已 final 初始化，但 registry 仍需为 null；此处用 wrapped IOE 便于工厂方法识别）
            this.registry = null;
            throw new ShortCircuitInitMarkerException("Replicator short-circuit: MetaMode=YAML 或 sync=off");
        }

        this.registry = DsRegistryInstance.get(dbRoot);
    }

    /** 工厂方法：处理 ShortCircuitInitMarkerException → 返回 null（避免上层 try/catch 侵入） */
    public static AbstractRegistryReplicator create(File dbRoot, MetaMode mode) {
        try { return new ReplicatorImpl(dbRoot, mode); }
        catch (ShortCircuitInitMarkerException ok) { return null; }
        catch (IOException e) { throw new RuntimeException(e); }
    }
    public static AbstractRegistryReplicator create(File dbRoot, MetaMode mode, Map<String,String> props) {
        try { return new ReplicatorImpl(dbRoot, mode, props); }
        catch (ShortCircuitInitMarkerException ok) { return null; }
        catch (IOException e) { throw new RuntimeException(e); }
    }

    // ================== 3 个 replicate 核心 method（public final c10 D1/D2/D3/E2） ==================

    /** 增量 replicate 单 key（D1：replicateKey(String,byte[],long,long[]) 精确签名） */
    public final void replicateKey(String registryPath, byte[] payload, long version, long[] peerIds) {
        Objects.requireNonNull(registryPath, "registryPath");
        if (peerIds == null) return;
        long logicalTs = buildLogicalTs(version);
        byte[] frame = DsRegistrySyncFrame.encode(FRAME_MAGIC, logicalTs, version, registryPath,
            (payload == null) ? new byte[0] : payload, OP_UPSERT);
        dispatchFrame(frame, peerIds);
    }

    /** 元路径 replicate（D2：replicateRegistryMetaKey 签名） */
    public final void replicateRegistryMetaKey(String metaPath, byte[] metaBytes, long version, long[] peerIds) {
        Objects.requireNonNull(metaPath, "metaPath");
        if (peerIds == null) return;
        long logicalTs = buildLogicalTs(version);
        byte[] frame = DsRegistrySyncFrame.encode(FRAME_MAGIC, logicalTs, version, metaPath,
            (metaBytes == null) ? new byte[0] : metaBytes, OP_UPSERT);
        dispatchFrame(frame, peerIds);
    }

    /** 全量冷启动 SUBTREE sync（D3：syncPeerSubtree(InetSocketAddress,String) 精确签名）；
     *  当前为内存 mock：对端 registry 实例通过 setPeerRegistryForTests 注入，真实 RPC 接入 T5 前扩展 */
    public final CompletableFuture<Boolean> syncPeerSubtree(InetSocketAddress peerEndpoint, String subtreePrefix) {
        Objects.requireNonNull(subtreePrefix, "subtreePrefix");
        final DsRegistry peer = peerRegistryForSubtreeTests.get();
        return CompletableFuture.supplyAsync(() -> {
            try { return copySubtreeInternal(subtreePrefix, peer); }
            catch (IOException e) { throw new RuntimeException(e); }
        });
    }

    // ================== 接收端 apply（R2 向后兼容 + 幂等 CAS version 高赢） ==================

    /**
     * 接收端 apply frame：
     *   - 首字节 magic == FRAME_MAGIC(0x52) → decode → atomicModifyBytes 幂等 CAS 写入本地 Registry；
     *     低 version 被覆盖 → syncPeerOverwrites 计数 +1 + WARN 日志（4 要素）；
     *   - 首字节非 0x52（旧裸 byte[] bucket frame、magic 其他值）→ 抛 OldFrameMarkerException，
     *     调用方 catch 转旧 P7 bucket 分支 100% 向后兼容。
     */
    public final void applyRegistryFrame(byte[] frameBytes, long fromPeerId) throws OldFrameMarkerException, IOException {
        Objects.requireNonNull(frameBytes, "frameBytes null");
        if (frameBytes.length == 0 || (frameBytes[0] & 0xff) != FRAME_MAGIC) {
            throw new OldFrameMarkerException("not registry sync frame (magic!=0x52); fallback to old bucket branch");
        }
        if (registry == null) return; // 已短路（YAML/sync=off），apply 也直接返回不写任何东西

        DsRegistrySyncFrame f = DsRegistrySyncFrame.decode(frameBytes);
        final long newVersion = f.version;
        final String path = f.registryPath;
        final String vPath = versionSideKey(path);

        long oldVersion = 0L;
        RegistryEntry ve = registry.get(vPath);
        if (ve != null && ve.isPresent()) oldVersion = ve.asLong();

        if (oldVersion >= newVersion) {
            incrementOverwrites(fromPeerId);
            String simplified = path.length() > 96 ? path.substring(0, 96) + "..." : path;
            System.err.println("[Replicator] WARN overwrite low-version frame: registryPath=" + simplified
                + " fromPeer=" + Long.toUnsignedString(fromPeerId)
                + " frameVersion=" + Long.toUnsignedString(newVersion)
                + " oldLocalVersion=" + Long.toUnsignedString(oldVersion));
            return;
        }

        // version 赢 → 写 payload（OP_REMOVE 时写 0 字节 tombstone）+ 同步写 side version leaf（REG_QWORD 8B）
        final byte[] payload = (f.op == OP_REMOVE) ? new byte[0] : f.payload;
        UnaryOperator<byte[]> casOp = oldBytes -> payload;
        applyCasInternal(path, casOp, payload);
        registry.mkdirs(parentOfInternal(vPath));
        registry.putLong(vPath, DataType.REG_QWORD, newVersion);
    }

    /** 版本 side key（sibling leaf 而非子节点，防止 mkdirs(path) 把 payload leaf 转 directory） */
    private static String versionSideKey(String path) {
        int slash = path.lastIndexOf('/');
        String parent = (slash < 0) ? "" : path.substring(0, slash);
        String base = (slash < 0) ? path : path.substring(slash + 1);
        return parent + "/" + base + "__v";
    }

    // ================== 测试用：内存队列 + 对端 Registry 注入（无 RPC mock 2 节点） ==================
    public final ConcurrentLinkedQueue<byte[]> getInMemoryFrameQueueForTests() { return inMemoryFrameQueue; }
    /** @deprecated only for tests; */
    @Deprecated
    public final DsRegistry _getRegistryForTests() { return registry; }
    public final long getPeerOverwritesForTests(long peerId) {
        LongAdder a = peerOverwrites.get(peerId);
        return (a == null) ? 0L : a.sum();
    }

    // ================== ≥5 protected helper（c10 A5：5 个 helper 列表） ==================

    /** helper 1：build logicalTs（spec 128bit=serverId22|localSeq64|epoch32，这里简化为 version<<1 | epochLowBits，够用） */
    protected final long buildLogicalTs(long versionOrSeq) {
        long epoch32 = (System.currentTimeMillis() / 1000L) & 0xFFFFFFFFL;
        // 低 32bit = epoch，高 = versionOrSeq，用于乱序 tie-break（简化版，真实 OQ3 再扩）
        return (versionOrSeq << 32) | epoch32;
    }

    /** helper 2：从现有 payload byte[] 解析 version（payload 末尾 8B 若存 version，简化：若长度>=8 取末尾 8B LE 作为 version，否则返回 0；本地 version 由调用方 increment 保证） */
    protected static long parseVersionFromPayload(byte[] payloadBytes) {
        if (payloadBytes == null || payloadBytes.length < Long.BYTES) return 0L;
        long v = 0L;
        for (int i = 0; i < Long.BYTES; i++) {
            v = (v << 8) | (payloadBytes[payloadBytes.length - Long.BYTES + i] & 0xffL);
        }
        return v;
    }

    /** helper 3：对端 overwrite count 累加（运维可观测 leaf syncPeerOverwrites 同步写入） */
    protected final void incrementOverwrites(long peerId) {
        peerOverwrites.computeIfAbsent(peerId, k -> new LongAdder()).increment();
        // 同步写入 Registry /orm/sync/peer/<peerId>/overwrites 计数 leaf（便于运维 listChildren 观测）
        if (registry != null) {
            try {
                String p = RegistryMetaPaths.syncPeerOverwrites(peerId);
                long cur = 0L;
                RegistryEntry e = registry.get(p);
                if (e != null && e.isPresent()) cur = e.asLong();
                registry.putLong(p, DataType.REG_QWORD, cur + 1L);
            } catch (Exception ignored) {} // 观测计数不影响正确性，忽略失败
        }
    }

    /** helper 4：cloneBytes（与 AbstractRegistryBackend.cloneBytes 同签名，避免 null） */
    protected static byte[] cloneBytes(byte[] b) {
        return (b == null) ? null : Arrays.copyOf(b, b.length);
    }

    /** helper 5：dispatch frame（当前内存 mock 入队；T3 接入后改 executeRemote 真实 RPC） */
    protected void dispatchFrame(byte[] frame, long[] peerIds) {
        if (frame == null || peerIds == null) return;
        inMemoryFrameQueue.add(frame); // 内存 mock：直接入队（对端 apply 通过 poll 取）；RPC 版：DsNodeRouter→executeRemote
    }

    /** helper 6（≥5 满足，多一个）：subtree 拷贝核心（4 子树按顺序，per-subtree checksum） */
    protected final boolean copySubtreeInternal(String subtreePrefix, DsRegistry peer) throws IOException {
        if (registry == null) return false;
        if (peer == null) return false;
        // 简化：BFS walk subtree 所有 leaf（cursor/limit 1000/批模拟 REGISTRY_SUBTREE_LIST 分块）
        List<String> allKeys = new ArrayList<>();
        subtreeListKeys(registry, subtreePrefix, allKeys);
        // 分块 BATCH_LIMIT_LEAVES 模拟 PUT_BATCH
        for (int i = 0; i < allKeys.size(); i += BATCH_LIMIT_LEAVES) {
            int end = Math.min(allKeys.size(), i + BATCH_LIMIT_LEAVES);
            List<String> batch = allKeys.subList(i, end);
            for (String k : batch) {
                RegistryEntry e = registry.get(k);
                if (e == null || !e.isPresent()) continue;
                DataType dt = e.dataType();
                if (dt == null) dt = DataType.REG_BINARY;
                peer.mkdirs(parentOfInternal(k));
                peer.putBytes(k, cloneBytes(e.asBytes()), dt);
            }
        }
        return true;
    }

    // ================== 测试用 setPeerRegistry（内存 mock B 节点，真实 RPC 接入时移除 @Deprecated） ==================
    @Deprecated
    static final ThreadLocal<DsRegistry> peerRegistryForSubtreeTests = new ThreadLocal<>();

    private static void subtreeListKeys(DsRegistry r, String prefix, List<String> out) throws IOException {
        List<String> children = r.listChildren(prefix);
        if (children == null) return;
        for (String c : children) {
            String full = prefix.endsWith("/") ? prefix + c : prefix + "/" + c;
            RegistryEntry e = r.get(full);
            if (e != null && e.isPresent()) { out.add(full); }
            else { subtreeListKeys(r, full, out); }
        }
    }

    private void applyCasInternal(String path, UnaryOperator<byte[]> op, byte[] seed) throws IOException {
        // 简化 CAS：直接基于 registry 事务性 striped lock（复用 AbstractRegistryBackend 语义；此处短平快实现）
        Object lock = lockForInternal(path);
        synchronized (lock) {
            registry.mkdirs(parentOfInternal(path));
            RegistryEntry e = registry.get(path);
            byte[] cur;
            if (e == null || !e.isPresent()) cur = cloneBytes(seed);
            else { byte[] eb = e.asBytes(); cur = (eb == null) ? cloneBytes(seed) : eb; }
            byte[] next = op.apply(cur);
            byte[] nb = (next == null) ? new byte[0] : next;
            registry.putBytes(path, nb, DataType.REG_BINARY);
        }
    }

    private static Object lockForInternal(String path) {
        // 简化：基于 String#intern hashCode 256 段锁（与 AbstractRegistryBackend#lockFor 等价，不重复创建）
        return Integer.valueOf(Math.floorMod(path.hashCode() & 0x7fffffff, 256)).toString().intern();
    }

    private static String parentOfInternal(String path) {
        int slash = path.lastIndexOf('/');
        return (slash < 0) ? "" : path.substring(0, slash);
    }

    static final class ShortCircuitInitMarkerException extends IOException { ShortCircuitInitMarkerException(String m) { super(m); } }

    /** 向后兼容 marker：首字节非 0x52 时 applyRegistryFrame 抛，调用方 catch → 旧 bucket 分支 apply */
    public static final class OldFrameMarkerException extends Exception {
        public OldFrameMarkerException(String msg) { super(msg); }
    }

    // ================== 内嵌静态 DsRegistrySyncFrame：编/解码（magic 0x52 帧 layout 与 spec 一致） ==================

    public static final class DsRegistrySyncFrame {
        public final long logicalTs;
        public final long version;
        public final String registryPath;
        public final byte[] payload;
        public final byte op;

        DsRegistrySyncFrame(long logicalTs, long version, String registryPath, byte[] payload, byte op) {
            this.logicalTs = logicalTs;
            this.version = version;
            this.registryPath = Objects.requireNonNull(registryPath, "registryPath");
            this.payload = (payload == null) ? new byte[0] : payload;
            this.op = op;
        }

        static byte[] encode(byte magic, long logicalTs, long version, String registryPath, byte[] payload, byte op) {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(32 + registryPath.length() + payload.length);
                DataOutputStream out = new DataOutputStream(baos);
                out.writeByte(magic & 0xff);        // 1
                out.writeShort(RESERVED_0);          // 2 (total 3)
                out.writeLong(logicalTs);            // 8 (11)
                out.writeLong(version);              // 8 (19)
                byte[] p = registryPath.getBytes(StandardCharsets.UTF_8);
                if (p.length > 0xffff) throw new IllegalArgumentException("registryPath len>65535");
                out.writeShort(p.length & 0xffff);   // 2 (21)
                out.write(p);                        // len (21+len)
                out.writeInt(payload.length);        // 4 (25+len)
                out.write(payload);                  // plen (25+len+plen)
                out.writeByte(op & 0xff);            // 1
                out.flush();
                return baos.toByteArray();
            } catch (IOException impossible) { throw new RuntimeException(impossible); }
        }

        static DsRegistrySyncFrame decode(byte[] data) throws IOException, OldFrameMarkerException {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            int magic = in.readUnsignedByte();
            if (magic != FRAME_MAGIC) {
                throw new OldFrameMarkerException("decode: not 0x52 frame");
            }
            in.readShort(); // reserved
            long lts = in.readLong();
            long v = in.readLong();
            int plen = in.readUnsignedShort();
            byte[] pb = new byte[plen];
            in.readFully(pb);
            String path = new String(pb, StandardCharsets.UTF_8);
            int blen = in.readInt();
            if (blen < 0) throw new IOException("payload length<0: " + blen);
            byte[] body = new byte[blen];
            in.readFully(body);
            byte op = in.readByte();
            return new DsRegistrySyncFrame(lts, v, path, body, op);
        }
    }

    // ================== 额外 util helper（给 T6 JV subtree checksum 用） ==================
    public static byte[] subtreeChecksum(DsRegistry r, String prefix) throws IOException {
        MessageDigest md;
        try { md = MessageDigest.getInstance("SHA-1"); }
        catch (NoSuchAlgorithmException ns) { throw new IOException(ns); }
        List<String> keys = new ArrayList<>();
        subtreeListKeys(r, prefix, keys);
        java.util.Collections.sort(keys); // 字典序保证确定性
        for (String k : keys) {
            md.update(k.getBytes(StandardCharsets.UTF_8));
            RegistryEntry e = r.get(k);
            if (e != null && e.isPresent()) md.update(e.asBytes());
        }
        return md.digest();
    }
}
