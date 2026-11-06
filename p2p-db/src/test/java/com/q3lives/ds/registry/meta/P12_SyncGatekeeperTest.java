package com.q3lives.ds.registry.meta;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * P12 c10 Sync Gatekeeper：25 assertions 钉死 P12 OQ2 跨节点同步的
 *   - A 修饰符 6（class/final/protected/package-private 合规）
 *   - B 方法数 6（facade 方法数基线不变；Replicator 公开方法数 ≤ 阈值）
 *   - C 命名常量 5（/orm/sync / 0x52 / p2p.orm.sync.mode / OP_UPSERT/REMOVE）
 *   - D 核心签名 4（replicateKey×2 / syncPeerSubtree / applyRegistryFrame）
 *   - E Replicator 内部 4（create 工厂 / dispatchFrame protected / 缺失 peer 0 overwrites）
 *
 * 若任一条 fail：说明 P12 OQ2 约束被打破（NG-2 6 facade 0 签名改 / P10+P11 路径 0 字符改 / 0 Maven / magic 兼容）
 */
public class P12_SyncGatekeeperTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @Before
    public void setUp() {
        try {
            Field f = DsRegistryInstance.class.getDeclaredField("INSTANCES");
            f.setAccessible(true);
            Object m = f.get(null);
            if (m instanceof ConcurrentHashMap) ((ConcurrentHashMap<?, ?>) m).clear();
        } catch (Exception ignored) {}
        System.clearProperty(MetaMode.PROPERTY);
        System.clearProperty(AbstractRegistryReplicator.SYNC_OFF_PROP);
    }

    // ========================== A 修饰符 6 assertions ==========================
    @Test
    public void c10_a_modifiers_6assertions() throws Exception {
        Class<?> replicatorCls = Class.forName("com.q3lives.ds.registry.meta.AbstractRegistryReplicator");
        Class<?> dbLocalCls = Class.forName("com.q3lives.ds.database.DsDatabaseLocal");
        Class<?> colStoreCls = Class.forName("com.q3lives.ds.database.columnar.ColumnarStore");
        Class<?> replImplCls = null;
        for (Class<?> dc : replicatorCls.getDeclaredClasses()) {
            if (dc.getSimpleName().equals("ReplicatorImpl")) replImplCls = dc;
        }
        assertNotNull("A1: ReplicatorImpl 内嵌类存在", replImplCls);

        // A1: AbstractRegistryReplicator public abstract
        int modR = replicatorCls.getModifiers();
        assertTrue("A1-AbstractRegistryReplicator 是 public", Modifier.isPublic(modR));
        assertTrue("A1-AbstractRegistryReplicator 是 abstract", Modifier.isAbstract(modR));

        // A2: ReplicatorImpl public static final
        int modRI = replImplCls.getModifiers();
        assertTrue("A2-ReplicatorImpl public", Modifier.isPublic(modRI));
        assertTrue("A2-ReplicatorImpl static", Modifier.isStatic(modRI));
        assertTrue("A2-ReplicatorImpl final", Modifier.isFinal(modRI));

        // A3: DsDatabaseLocal.registryReplicator package-private (non-public) + final
        Field fDb = dbLocalCls.getDeclaredField("registryReplicator");
        int modDbF = fDb.getModifiers();
        assertFalse("A3-DsDatabaseLocal.registryReplicator 非 public（package-private）",
            Modifier.isPublic(modDbF));
        assertTrue("A3-DsDatabaseLocal.registryReplicator final", Modifier.isFinal(modDbF));

        // A4: ColumnarStore.registryReplicator package-private (non-public) + final
        Field fCs = colStoreCls.getDeclaredField("registryReplicator");
        int modCsF = fCs.getModifiers();
        assertFalse("A4-ColumnarStore.registryReplicator 非 public（package-private）",
            Modifier.isPublic(modCsF));
        assertTrue("A4-ColumnarStore.registryReplicator final", Modifier.isFinal(modCsF));

        // A5: AbstractRegistryReplicator(File, MetaMode) 构造器 protected
        Constructor<?> c1 = replicatorCls.getDeclaredConstructor(File.class, MetaMode.class);
        assertTrue("A5-构造器(File,MetaMode) protected",
            Modifier.isProtected(c1.getModifiers()));

        // A6: AbstractRegistryReplicator(File, MetaMode, Map) 构造器 protected
        Constructor<?> c2 = replicatorCls.getDeclaredConstructor(File.class, MetaMode.class, Map.class);
        assertTrue("A6-构造器(File,MetaMode,Map) protected",
            Modifier.isProtected(c2.getModifiers()));
    }

    // ========================== B 方法数 6 assertions ==========================
    @Test
    public void c10_b_methodcount_6assertions() throws Exception {
        Class<?> replicatorCls = Class.forName("com.q3lives.ds.registry.meta.AbstractRegistryReplicator");
        Class<?> replImplCls = null;
        for (Class<?> dc : replicatorCls.getDeclaredClasses()) {
            if (dc.getSimpleName().equals("ReplicatorImpl")) replImplCls = dc;
        }
        Class<?> dbLocalCls = Class.forName("com.q3lives.ds.database.DsDatabaseLocal");
        Class<?> colStoreCls = Class.forName("com.q3lives.ds.database.columnar.ColumnarStore");
        Class<?> tmsCls = Class.forName("com.q3lives.ds.database.columnar.TableMetaStore");
        Class<?> crCls = Class.forName("com.q3lives.ds.database.columnar.ColumnRegistry");
        Set<String> skipObject = new HashSet<>(Arrays.asList("getClass","hashCode","equals","toString","wait","notify","notifyAll"));

        // B1: AbstractRegistryReplicator public methods（含继承，但不含 Object）≤ 20
        List<Method> repub = new ArrayList<>();
        for (Method m : replicatorCls.getMethods()) {
            if (!Modifier.isPublic(m.getModifiers())) continue;
            if (skipObject.contains(m.getName())) continue;
            repub.add(m);
        }
        // (3 replicate + 3 factory/apply/syncPeer + 3 test helper) = 约 9-13
        assertTrue("B1-AbstractRegistryReplicator public methods ≤ 20（实际=" + repub.size() + "）", repub.size() <= 20);

        // B2: DsDatabaseLocal public methods ≤ 80（P11 基线；只允许 package-private 字段，不允许新 public 方法）
        int dbPub = 0;
        for (Method m : dbLocalCls.getMethods()) {
            if (!Modifier.isPublic(m.getModifiers())) continue;
            if (skipObject.contains(m.getName())) continue;
            dbPub++;
        }
        assertTrue("B2-DsDatabaseLocal 公开方法数 ≤ 80（P11 基线；新增只允许 package-private），实际=" + dbPub, dbPub <= 80);

        // B3: ColumnarStore public methods ≤ 60
        int csPub = 0;
        for (Method m : colStoreCls.getMethods()) {
            if (!Modifier.isPublic(m.getModifiers())) continue;
            if (skipObject.contains(m.getName())) continue;
            csPub++;
        }
        assertTrue("B3-ColumnarStore 公开方法数 ≤ 60（P11 基线），实际=" + csPub, csPub <= 60);

        // B4: ReplicatorImpl 自身 declared methods = 2（仅 2 构造器；不新增多余 public 方法）
        int implOwnDeclaredMethods = 0;
        for (Method m : replImplCls.getDeclaredMethods()) implOwnDeclaredMethods++;
        assertEquals("B4-ReplicatorImpl 自身 declared methods=0（除 2 构造器，无额外 declared 方法），实际=" + implOwnDeclaredMethods, 0, implOwnDeclaredMethods);
        int implOwnConstructors = replImplCls.getDeclaredConstructors().length;
        assertEquals("B4-ReplicatorImpl 自身 declared constructors=2（2 构造器），实际=" + implOwnConstructors, 2, implOwnConstructors);

        // B5: TableMetaStore public methods ≤ 40（P10 c08 已钉死 facade，允许±0）
        int tmsPub = 0;
        for (Method m : tmsCls.getMethods()) {
            if (!Modifier.isPublic(m.getModifiers())) continue;
            if (skipObject.contains(m.getName())) continue;
            tmsPub++;
        }
        assertTrue("B5-TableMetaStore public methods ≤ 40，实际=" + tmsPub, tmsPub <= 40);

        // B6: ColumnRegistry public methods ≤ 20
        int crPub = 0;
        for (Method m : crCls.getMethods()) {
            if (!Modifier.isPublic(m.getModifiers())) continue;
            if (skipObject.contains(m.getName())) continue;
            crPub++;
        }
        assertTrue("B6-ColumnRegistry public methods ≤ 20，实际=" + crPub, crPub <= 20);
    }

    // ========================== C 命名常量 5 assertions ==========================
    @Test
    public void c10_c_naming_5assertions() throws Exception {
        // C1: SYNC_ROOT == "/orm/sync"（RegistryMetaPaths 仅命中 1 处字面量已 T1 验证）
        assertEquals("C1-SYNC_ROOT = /orm/sync",
            "/orm/sync",
            (String) RegistryMetaPaths.class.getField("SYNC_ROOT").get(null));

        // C2: SYNC_OVERWRITES_LEAF == "overwrites"
        assertEquals("C2-SYNC_OVERWRITES_LEAF = overwrites",
            "overwrites",
            (String) RegistryMetaPaths.class.getField("SYNC_OVERWRITES_LEAF").get(null));

        // C3: FRAME_MAGIC == 0x52（向后兼容 magic）
        Class<?> replicatorCls = Class.forName("com.q3lives.ds.registry.meta.AbstractRegistryReplicator");
        Field fMagic = replicatorCls.getDeclaredField("FRAME_MAGIC");
        fMagic.setAccessible(true);
        assertEquals("C3-FRAME_MAGIC = 0x52(82)", (byte) 0x52, (byte) fMagic.getByte(null));

        // C4: SYNC_OFF_PROP == "p2p.orm.sync.mode"
        assertEquals("C4-SYNC_OFF_PROP = p2p.orm.sync.mode",
            "p2p.orm.sync.mode",
            (String) replicatorCls.getField("SYNC_OFF_PROP").get(null));

        // C5: OP_UPSERT=0 / OP_REMOVE=1
        Field fOp0 = replicatorCls.getDeclaredField("OP_UPSERT");
        Field fOp1 = replicatorCls.getDeclaredField("OP_REMOVE");
        fOp0.setAccessible(true); fOp1.setAccessible(true);
        assertEquals("C5-OP_UPSERT = 0", (byte) 0, (byte) fOp0.getByte(null));
        assertEquals("C5-OP_REMOVE = 1", (byte) 1, (byte) fOp1.getByte(null));
    }

    // ========================== D 核心签名 4 assertions ==========================
    @Test
    public void c10_d_coresigs_4assertions() throws Exception {
        Class<?> replicatorCls = Class.forName("com.q3lives.ds.registry.meta.AbstractRegistryReplicator");

        // D1: replicateKey(String, byte[], long, long[]) public final
        Method m1 = replicatorCls.getMethod("replicateKey", String.class, byte[].class, long.class, long[].class);
        int mm1 = m1.getModifiers();
        assertTrue("D1-replicateKey public", Modifier.isPublic(mm1));
        assertTrue("D1-replicateKey final", Modifier.isFinal(mm1));
        assertEquals("D1-replicateKey returns void", void.class, m1.getReturnType());

        // D2: replicateRegistryMetaKey(String, byte[], long, long[]) public final void
        Method m2 = replicatorCls.getMethod("replicateRegistryMetaKey", String.class, byte[].class, long.class, long[].class);
        int mm2 = m2.getModifiers();
        assertTrue("D2-replicateRegistryMetaKey public", Modifier.isPublic(mm2));
        assertTrue("D2-replicateRegistryMetaKey final", Modifier.isFinal(mm2));
        assertEquals("D2-replicateRegistryMetaKey returns void", void.class, m2.getReturnType());

        // D3: syncPeerSubtree(InetSocketAddress, String) returns CompletableFuture<Boolean> public final
        Method m3 = replicatorCls.getMethod("syncPeerSubtree", InetSocketAddress.class, String.class);
        int mm3 = m3.getModifiers();
        assertTrue("D3-syncPeerSubtree public", Modifier.isPublic(mm3));
        assertTrue("D3-syncPeerSubtree final", Modifier.isFinal(mm3));
        assertEquals("D3-syncPeerSubtree returns CompletableFuture", CompletableFuture.class, m3.getReturnType());

        // D4: applyRegistryFrame(byte[], long) throws OldFrameMarkerException, IOException public final void
        Method m4 = replicatorCls.getMethod("applyRegistryFrame", byte[].class, long.class);
        int mm4 = m4.getModifiers();
        assertTrue("D4-applyRegistryFrame public", Modifier.isPublic(mm4));
        assertTrue("D4-applyRegistryFrame final", Modifier.isFinal(mm4));
        assertEquals("D4-applyRegistryFrame returns void", void.class, m4.getReturnType());
        List<Class<?>> ex = Arrays.asList(m4.getExceptionTypes());
        assertTrue("D4-applyRegistryFrame throws OldFrameMarkerException",
            ex.stream().anyMatch(c -> c.getSimpleName().equals("OldFrameMarkerException")));
        assertTrue("D4-applyRegistryFrame throws IOException",
            ex.contains(java.io.IOException.class));
    }

    // ========================== E Replicator 内部 4 assertions ==========================
    @Test
    public void c10_e_replicator_internal_4assertions() throws Exception {
        Class<?> replicatorCls = Class.forName("com.q3lives.ds.registry.meta.AbstractRegistryReplicator");

        // E1: create(File, MetaMode) static 返回 AbstractRegistryReplicator
        Method cr1 = replicatorCls.getMethod("create", File.class, MetaMode.class);
        assertTrue("E1-create(File,MetaMode) static", Modifier.isStatic(cr1.getModifiers()));
        assertEquals("E1-create(File,MetaMode) returns AbstractRegistryReplicator",
            replicatorCls, cr1.getReturnType());

        // E2: create(File, MetaMode, Map) static 返回 AbstractRegistryReplicator
        Method cr2 = replicatorCls.getMethod("create", File.class, MetaMode.class, Map.class);
        assertTrue("E2-create(File,MetaMode,Map) static", Modifier.isStatic(cr2.getModifiers()));
        assertEquals("E2-create(File,MetaMode,Map) returns AbstractRegistryReplicator",
            replicatorCls, cr2.getReturnType());

        // E3: dispatchFrame protected（不允许 public 外泄）
        Method disp = replicatorCls.getDeclaredMethod("dispatchFrame", byte[].class, long[].class);
        assertTrue("E3-dispatchFrame protected", Modifier.isProtected(disp.getModifiers()));
        assertFalse("E3-dispatchFrame 非 public（防止外泄）", Modifier.isPublic(disp.getModifiers()));

        // E4: sync=off → create 返回 null（短路零 new）
        System.setProperty(AbstractRegistryReplicator.SYNC_OFF_PROP, "off");
        File root = tmp.newFolder("p12-c10-e4");
        new DsDatabaseMetaMigrator(root, false).migrateAll();
        AbstractRegistryReplicator rr = AbstractRegistryReplicator.create(root, MetaMode.REGISTRY);
        try {
            assertEquals("E4-sync=off → create() 返回 null（短路零 new）", null, rr);
        } finally {
            System.clearProperty(AbstractRegistryReplicator.SYNC_OFF_PROP);
        }
    }
}
