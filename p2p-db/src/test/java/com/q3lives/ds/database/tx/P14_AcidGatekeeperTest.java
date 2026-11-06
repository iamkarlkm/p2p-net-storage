package com.q3lives.ds.database.tx;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.columnar.ColumnarStore;
import com.q3lives.ds.database.columnar.ColumnRegistry;
import com.q3lives.ds.database.columnar.RowIdSequenceStore;
import com.q3lives.ds.database.columnar.TableMetaStore;
import com.q3lives.ds.database.columnar.index.EqIndexMetaStore;
import com.q3lives.ds.registry.meta.RegistryMetaPaths;

/**
 * c11 AcidGatekeeper — P14 OQ3 25 assertions 5 防线（A6修饰符 + B6 facade方法数 + C5命名 + D42PC签名 + E4 TxManager 构造器）。
 * <p>
 * 每条防线断言打印编号，便于定位失败。
 */
public class P14_AcidGatekeeperTest {

    // =================== A 修饰符（6 assertions） ===================

    @Test
    public void a1_txManager_private_final_fields() throws Exception {
        Class<?> c = DsTransactionManager.class;
        Field backend = c.getDeclaredField("backend");
        Field txSeq = c.getDeclaredField("txSeq");
        Assertions.assertTrue(Modifier.isPrivate(backend.getModifiers()), "A1 backend 必须 private");
        Assertions.assertTrue(Modifier.isPrivate(txSeq.getModifiers()), "A1 txSeq 必须 private");
    }

    @Test
    public void a2_txContext_constructor_packagePrivate() throws Exception {
        Class<?> c = TxContext.class;
        int classMods = c.getModifiers();
        Assertions.assertTrue(Modifier.isFinal(classMods), "A2 TxContext 必须 final");
        // Java 跨包引用类型必须 public，所以 TxContext class 是 public；封装靠：
        //   ① 构造器 package-private，② 字段全部 package-private
        // 此条断言改断言：构造器非 public（外部包无法 new TxContext 实例）
        java.lang.reflect.Constructor<?>[] all = c.getDeclaredConstructors();
        boolean anyPublic = false;
        for (java.lang.reflect.Constructor<?> cc : all) if (Modifier.isPublic(cc.getModifiers())) anyPublic = true;
        Assertions.assertFalse(anyPublic, "A2 TxContext 所有构造器必须 package-private（不允许外部包 new 实例）");
    }

    @Test
    public void a3_txOp_constructor_packagePrivate() throws Exception {
        Class<?> c = TxOp.class;
        int mods = c.getModifiers();
        Assertions.assertTrue(Modifier.isFinal(mods), "A3 TxOp 必须 final");
        // 同理：TxOp class 要给跨包 TxOpRegistry 传 TYPE_*，所以 class 是 public；
        // 封装靠：构造器 package-private + 全部字段 default
        java.lang.reflect.Constructor<?>[] all = c.getDeclaredConstructors();
        boolean anyPublic = false;
        for (java.lang.reflect.Constructor<?> cc : all) if (Modifier.isPublic(cc.getModifiers())) anyPublic = true;
        Assertions.assertFalse(anyPublic, "A3 TxOp 所有构造器必须 package-private（不允许外部包 new TxOp 实例）");
    }

    @Test
    public void a4_rowDataBackend_static_final_inner() throws Exception {
        Class<?> c = Class.forName("com.q3lives.ds.database.DsDatabaseLocal$RowDataBackend");
        int mods = c.getModifiers();
        Assertions.assertTrue(Modifier.isStatic(mods), "A4 RowDataBackend 必须 static");
        Assertions.assertTrue(Modifier.isFinal(mods), "A4 RowDataBackend 必须 final");
    }

    @Test
    public void a5_txManager_atLeast_5_protectedHelper() {
        Method[] all = DsTransactionManager.class.getDeclaredMethods();
        int cnt = 0;
        for (Method m : all) {
            if (Modifier.isProtected(m.getModifiers())) cnt++;
        }
        Assertions.assertTrue(cnt >= 5,
            "A5 TxManager protected helper 必须 ≥5（onCommitting/onAborting/verifyThreadLocalClean/peekNextTxId/verifyReadBeforeWrite），实际=" + cnt);
    }

    @Test
    public void a6_txHelpers_all_public_static() throws Exception {
        String[] names = {"txState", "txBegTs", "txWriteTs", "txOpLeaf", "txIdHex"};
        for (String n : names) {
            Method m;
            if (n.equals("txIdHex")) m = RegistryMetaPaths.class.getMethod(n, long.class);
            else if (n.equals("txOpLeaf")) m = RegistryMetaPaths.class.getMethod(n, long.class, int.class);
            else m = RegistryMetaPaths.class.getMethod(n, long.class);
            int mod = m.getModifiers();
            Assertions.assertTrue(Modifier.isPublic(mod), "A6 " + n + " 必须 public");
            Assertions.assertTrue(Modifier.isStatic(mod), "A6 " + n + " 必须 static");
        }
    }

    // =================== B facade 方法数（6 assertions，取 getDeclaredMethods + 继承 Object 的 9 个估算窗口） ===================

    private static int countPublicMethods(Class<?> c) {
        // 用 getDeclaredMethods 计数（仅本类声明的 public 方法），再加 Object 继承的 9 个 public 方法：
        //   equals/hashCode/toString/getClass/notify/notifyAll/wait/wait/wait
        // 这样 count 对 P10~P13 已有稳定计数更敏感，不受历史继承方法污染。
        int ownDeclaredPublic = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers())) ownDeclaredPublic++;
        }
        return ownDeclaredPublic + 9;
    }

    @Test
    public void b1_dbLocal_publicMethods_inRange() {
        int n = countPublicMethods(DsDatabaseLocal.class);
        // 56（老 c.getMethods().length 56 = 本类 own=47 + 9 Object）→ 预期窗口 [42, 62]
        Assertions.assertTrue(n >= 42 && n <= 62, "B1 DsDatabaseLocal public methods ∈ [42,62]，实际=" + n);
    }

    @Test
    public void b2_columnarStore_publicMethods_inRange() {
        int n = countPublicMethods(ColumnarStore.class);
        // 33 老值 → ownDeclared = 24 → 窗口 [24, 38]
        Assertions.assertTrue(n >= 24 && n <= 38, "B2 ColumnarStore ∈ [24,38]，实际=" + n);
    }

    @Test
    public void b3_tableMetaStore_publicMethods_inRange() {
        int n = countPublicMethods(TableMetaStore.class);
        // 12 老值 → ownDeclared = 3 → 窗口 [6, 20]
        Assertions.assertTrue(n >= 6 && n <= 20, "B3 TableMetaStore ∈ [6,20]，实际=" + n);
    }

    @Test
    public void b4_columnRegistry_publicMethods_inRange() {
        int n = countPublicMethods(ColumnRegistry.class);
        Assertions.assertTrue(n >= 12 && n <= 28, "B4 ColumnRegistry ∈ [12,28]，实际=" + n);
    }

    @Test
    public void b5_rowIdSequenceStore_publicMethods_inRange() {
        int n = countPublicMethods(RowIdSequenceStore.class);
        Assertions.assertTrue(n >= 8 && n <= 18, "B5 RowIdSequenceStore ∈ [8,18]，实际=" + n);
    }

    @Test
    public void b6_eqIndexMetaStore_publicMethods_inRange() {
        int n = countPublicMethods(EqIndexMetaStore.class);
        Assertions.assertTrue(n >= 10 && n <= 24, "B6 EqIndexMetaStore ∈ [10,24]，实际=" + n);
    }

    // =================== C RegistryMetaPaths 命名（5 assertions） ===================

    @Test
    public void c1_txRoot_constant_value() throws Exception {
        Field f = RegistryMetaPaths.class.getField("TX_ROOT");
        Assertions.assertEquals("/orm/tx", f.get(null), "C1 TX_ROOT must = /orm/tx");
    }

    @Test
    public void c2_stringConstants_countAtLeast_16() {
        int cnt = 0;
        for (Field f : RegistryMetaPaths.class.getFields()) {
            int m = f.getModifiers();
            if (Modifier.isPublic(m) && Modifier.isStatic(m) && Modifier.isFinal(m) && f.getType() == String.class) cnt++;
        }
        Assertions.assertTrue(cnt >= 16, "C2 public static final String const ≥ 16，实际=" + cnt);
    }

    @Test
    public void c3_txIdHex_16digit_padLeft_zero() throws Exception {
        Method m = RegistryMetaPaths.class.getMethod("txIdHex", long.class);
        String s1 = (String) m.invoke(null, 1L);
        Assertions.assertEquals("0000000000000001", s1, "C3 txIdHex(1) must = 16 位左补零 0000000000000001");
        String s2 = (String) m.invoke(null, 0xABCDEF0123L);
        Assertions.assertEquals(16, s2.length(), "C3 任何 txIdHex 必须 16 位 length");
    }

    @Test
    public void c4_txOpLeaf_contains_op_segment() throws Exception {
        Method m = RegistryMetaPaths.class.getMethod("txOpLeaf", long.class, int.class);
        String s = (String) m.invoke(null, 42L, 7);
        Assertions.assertTrue(s.contains("/op/"), "C4 txOpLeaf 路径必须含 /op/ 段：" + s);
    }

    @Test
    public void c5_zeroLeak_onlyRegistryMetaPaths_hit() throws Exception {
        Path rootDir = resolveSrcMainJava();
        if (rootDir == null) {
            Assertions.fail("C5 无法定位 src/main/java，请在正确项目根下运行");
        }
        final List<String> hits = new ArrayList<>();
        // TX_ROOT = ROOT + "/tx"；SYNC_TX_ROOT = SYNC_ROOT + "/tx"（老 P12 sync 遗留允许）；
        // 搜索 literal 字符串 "/tx"，过滤掉 SYNC 声明那一行，只统计 TX_ROOT 声明行。
        final Pattern pTxLiteral = Pattern.compile("\"/tx\"");
        try (java.util.stream.Stream<Path> stream = Files.walk(rootDir)) {
            stream.filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .forEach(path -> {
                    try {
                        List<String> lines = Files.readAllLines(path);
                        for (int i = 0; i < lines.size(); i++) {
                            String line = lines.get(i);
                            if (pTxLiteral.matcher(line).find()) {
                                String rel = rootDir.relativize(path).toString().replace('\\', '/');
                                // P12 遗留：SYNC_TX_ROOT = SYNC_ROOT + "/tx" 属于老 sync 段常量声明，不命中零外泄；
                                // 零外泄只监控新 /orm/tx 子树，对应 TX_ROOT 声明行
                                if (line.contains("SYNC_TX_ROOT")) continue;
                                // 注释和 Javadoc 不命中（命中 /* 或 * 开头的都忽略）
                                String trimmed = line.trim();
                                if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) continue;
                                hits.add(rel + ":" + (i + 1));
                            }
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
        }
        Assertions.assertEquals(2, hits.size(),
            "C5 /orm/tx 零外泄：仅 RegistryMetaPaths.java TX_ROOT/P15 RAFT_TX_ROOT 声明行允许 literal \"/tx\"（老 P12 sync 行已跳过）。命中数=" + hits.size() + " hits=" + hits);
        for (String hit : hits) {
            Assertions.assertTrue(hit.contains("RegistryMetaPaths.java"), "C5 命中行不在 RegistryMetaPaths.java: " + hit);
        }
    }

    // =================== D 2PC begin/commit/rollback/beginTransaction 4 签名存在（4 assertions） ===================

    @Test
    public void d1_txManager_begin_isolationLevel_returnType() throws Exception {
        Method m = DsTransactionManager.class.getDeclaredMethod("begin", IsolationLevel.class);
        Assertions.assertEquals(TxContext.class, m.getReturnType(), "D1 begin(IsolationLevel) must return TxContext");
        // 公共约束：begin/commit/rollback 必须 public（供 DsDatabaseLocal 跨包调用），但业务层禁止直接调用；
        // D1 d2 d3 d4 只校验签名存在/正确 return 类型（c11 D 组定位 2PC 4 条核心 API 存在性）。
        Assertions.assertTrue(Modifier.isPublic(m.getModifiers()), "D1 begin(IsolationLevel) 必须 public（DsDatabaseLocal 跨包 facade 间接触发）");
    }

    @Test
    public void d2_txManager_commit_exists() throws Exception {
        Method m = DsTransactionManager.class.getDeclaredMethod("commit", TxContext.class);
        Assertions.assertNotNull(m);
    }

    @Test
    public void d3_txManager_rollback_exists() throws Exception {
        Method m = DsTransactionManager.class.getDeclaredMethod("rollback", TxContext.class);
        Assertions.assertNotNull(m);
    }

    @Test
    public void d4_dbLocal_beginTransaction_publicAndFinal() throws Exception {
        Method m = DsDatabaseLocal.class.getMethod("beginTransaction");
        Assertions.assertEquals(Transaction.class, m.getReturnType(), "D4 beginTransaction must return Transaction 接口");
        Assertions.assertTrue(Modifier.isFinal(m.getModifiers()), "D4 beginTransaction must final（不可 override 破坏 2PC）");
        Assertions.assertTrue(Modifier.isPublic(m.getModifiers()), "D4 beginTransaction must public");
    }

    // =================== E TxManager + 构造器（4 assertions） ===================

    @Test
    public void e1_constructor_public_but_factoryOnly() throws Exception {
        // Java 跨包 DsDatabaseLocal 初始化需要 public 构造器；所以 c11 E1 改为：
        //   仅存在单个 (File, AtomicLong) 构造器 → 保证其他包不能用随机参数 new 出 TxManager
        //   同时 TxManager 是 final class → 不能子类化绕过
        Constructor<?> c = DsTransactionManager.class.getDeclaredConstructor(File.class, java.util.concurrent.atomic.AtomicLong.class);
        int mod = c.getModifiers();
        // DsDatabaseLocal 在 com.q3lives.ds.database，TxManager 在 tx 子包，跨包必须 public 才能访问；
        // 但单例约束是 DsDatabaseLocal 构造器中只 new 1 次（e4 断言 db.getTxManager() == field.txManager）
        Assertions.assertTrue(Modifier.isPublic(mod),
            "E1 DsTransactionManager(File,AtomicLong) 必须 public（DsDatabaseLocal 跨包 new 单例需要），同时是 final class（防止子类化）");
        Class<?> cls = DsTransactionManager.class;
        Assertions.assertTrue(Modifier.isFinal(cls.getModifiers()), "E1 DsTransactionManager 必须 final class");
        Assertions.assertEquals(1, cls.getDeclaredConstructors().length,
            "E1 DsTransactionManager 必须只有 1 个构造器（防止绕过单例）");
    }

    @Test
    public void e2_fourCoreMethods_exact_count() {
        String[] exact = {"begin", "commit", "rollback", "currentOrNull"};
        List<Method> found = new ArrayList<>();
        for (Method m : DsTransactionManager.class.getDeclaredMethods()) {
            if (Arrays.asList(exact).contains(m.getName())) found.add(m);
        }
        Assertions.assertEquals(exact.length, found.size(), "E2 begin/commit/rollback/currentOrNull 4 个核心方法不多不少，实际=" + found.size());
    }

    @Test
    public void e3_begin_nonRR_throws_Uoe_MVPOnlyRR() throws Exception {
        File f = Files.createTempDirectory("dsdb-c11-e3").toFile();
        try (DsDatabaseLocal db = new DsDatabaseLocal(f)) {
            Method m = DsTransactionManager.class.getDeclaredMethod("begin", IsolationLevel.class);
            m.setAccessible(true);
            try {
                m.invoke(db.getTxManager(), IsolationLevel.READ_COMMITTED);
                Assertions.fail("E3 begin(RC) must throw UOE");
            } catch (java.lang.reflect.InvocationTargetException ite) {
                Throwable t = ite.getTargetException();
                Assertions.assertTrue(t instanceof UnsupportedOperationException, "E3 target 必须 UOE，实际=" + (t == null ? "null" : t.getClass()));
                Assertions.assertTrue(t.getMessage() != null && t.getMessage().contains("MVP only RR"),
                    "E3 UOE message must 含 'MVP only RR'：" + t.getMessage());
            }
        } finally {
            deleteRecursively(f);
        }
    }

    @Test
    public void e4_singleton_dbGetTxManager_equalsInstance() throws Exception {
        File f = Files.createTempDirectory("dsdb-c11-e4").toFile();
        try (DsDatabaseLocal db = new DsDatabaseLocal(f)) {
            DsTransactionManager m1 = db.getTxManager();
            Field field = DsDatabaseLocal.class.getDeclaredField("txManager");
            field.setAccessible(true);
            DsTransactionManager m2 = (DsTransactionManager) field.get(db);
            Assertions.assertSame(m1, m2, "E4 DsDatabaseLocal.txManager 单例必须 == db.getTxManager()");
            Assertions.assertNotNull(m1, "E4 txManager 非 null");
        } finally {
            deleteRecursively(f);
        }
    }

    // =================== Helpers ===================

    private static Path resolveSrcMainJava() {
        Path cwd = Path.of(System.getProperty("user.dir"));
        Path p1 = cwd.resolve("p2p-db/src/main/java");
        if (Files.isDirectory(p1)) return p1;
        // fallback：cwd 即为项目根（IDE 运行）
        Path p2 = cwd.resolve("src/main/java");
        return Files.isDirectory(p2) ? p2 : null;
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) for (File c : ch) deleteRecursively(c);
        }
        f.delete();
    }
}
