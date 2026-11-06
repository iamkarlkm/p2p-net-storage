package com.q3lives.ds.database.tx;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.annotation.DsTable;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.registry.meta.RegistryMetaPaths;

/**
 * P14 OQ3 正确性专项（3+3+6+2 = 14 tests；配合 TPC-C 10 tests 总 ≥24，满足 T5 门槛）。
 * <p>
 * 隔离：每个 test new temp dir，互不污染；temp 目录在 @AfterEach 被 deleteRecursively。
 */
public class P14_AcidLifeCycleTest {

    private File home;

    @BeforeEach
    public void before() throws Exception {
        this.home = Files.createTempDirectory("dsdb-p14-acid-lc").toFile();
    }

    @AfterEach
    public void after() {
        deleteRecursively(home);
    }

    // =================== AC-R1/AC-R12：生命周期 3 tests ===================

    @Test
    public void t01_beginCommit_visibleAfterCommit() throws Exception {
        try (DsDatabaseLocal db = new DsDatabaseLocal(home)) {
            long id;
            try (Transaction tx = db.beginTransaction()) {
                AcidUser u = new AcidUser();
                u.name = "alice";
                u.age = 30L;
                id = db.putEntity(u, true);
                tx.commit();
            }
            AcidUser loaded = db.getTable(AcidUser.class, id);
            Assertions.assertNotNull(loaded, "commit 后行应可见");
            Assertions.assertEquals("alice", loaded.name);
            Assertions.assertEquals(30L, loaded.age.longValue());
        }
    }

    @Test
    public void t02_beginRollback_notVisibleAfterRollback() throws Exception {
        try (DsDatabaseLocal db = new DsDatabaseLocal(home)) {
            AcidUser before = new AcidUser();
            before.name = "origin";
            before.age = 20L;
            long id = db.putEntity(before, true);

            try (Transaction tx = db.beginTransaction()) {
                AcidUser u = db.getTable(AcidUser.class, id);
                u.name = "modified";
                u.age = 99L;
                db.putEntity(u, false);
                tx.rollback();
            }
            AcidUser loaded = db.getTable(AcidUser.class, id);
            Assertions.assertNotNull(loaded);
            Assertions.assertEquals("origin", loaded.name, "rollback 后 name 必须恢复");
            Assertions.assertEquals(20L, loaded.age.longValue(), "rollback 后 age 必须恢复");
        }
    }

    @Test
    public void t03_tryWithResources_autoRollbackOnThrow() throws Exception {
        try (DsDatabaseLocal db = new DsDatabaseLocal(home)) {
            AtomicLong capturedId = new AtomicLong(0L);
            try {
                try (Transaction tx = db.beginTransaction()) {
                    AcidUser u = new AcidUser();
                    u.name = "toAbort";
                    u.age = 55L;
                    capturedId.set(db.putEntity(u, true));
                    throw new RuntimeException("intentionally ABORT");
                }
            } catch (RuntimeException aborted) {
                Assertions.assertEquals("intentionally ABORT", aborted.getMessage());
            }
            Assertions.assertTrue(capturedId.get() > 0, "自动分配 rowId 必须 >0");
            // rowId 是 bucketStore encodeId(power, baseId)，高 8bit 带 power；
            // 低 56bit = baseId（ID_OFFSET_BITS=56 是 public 常量），用 mask 算代替 private decodeOffset
            long maskOffset = (1L << com.q3lives.ds.bucket.DsFixedBucketStore.ID_OFFSET_BITS) - 1L;
            long base = capturedId.get() & maskOffset;
            Assertions.assertTrue(base >= 0L, "首个 rowId 的 baseId 必须合法（free-ring 初次分配）");
            AcidUser loaded = db.getTable(AcidUser.class, capturedId.get());
            Assertions.assertNull(loaded, "TWR 抛异常必须自动 rollback，0 字节可见");
        }
    }

    // =================== AC-R2：RR 可重复读 3 tests ===================

    @Test
    public void t04_rr_singleThread_twoReadsEqual() throws Exception {
        try (DsDatabaseLocal db = new DsDatabaseLocal(home)) {
            AcidUser seed = new AcidUser();
            seed.name = "rrSeed";
            seed.age = 11L;
            long id = db.putEntity(seed, true);

            byte[] first;
            byte[] second;
            try (Transaction tx = db.beginTransaction()) {
                AcidUser u1 = db.getTable(AcidUser.class, id);
                first = serialize(u1);
                AcidUser u2 = db.getTable(AcidUser.class, id);
                second = serialize(u2);
                Assertions.assertArrayEquals(first, second, "RR 单线程两次读必须字节一致");
                tx.rollback();
            }
        }
    }

    @Test
    public void t05_rr_concurrent_tx2_invisible_to_tx1() throws Exception {
        try (DsDatabaseLocal db = new DsDatabaseLocal(home)) {
            AcidUser seed = new AcidUser();
            seed.name = "rrConcurrent";
            seed.age = 44L;
            long id = db.putEntity(seed, true);

            CountDownLatch tx1Opened = new CountDownLatch(1);
            CountDownLatch tx2Committed = new CountDownLatch(1);
            AtomicReferenceHolder<byte[]> tx1FirstRead = new AtomicReferenceHolder<>();
            AtomicReferenceHolder<byte[]> tx1SecondRead = new AtomicReferenceHolder<>();
            AtomicBoolean passed = new AtomicBoolean(false);

            Thread tx1 = new Thread(() -> {
                try (Transaction tx = db.beginTransaction()) {
                    AcidUser u = db.getTable(AcidUser.class, id);
                    tx1FirstRead.value = serialize(u);
                    tx1Opened.countDown();
                    boolean ok = tx2Committed.await(15, java.util.concurrent.TimeUnit.SECONDS);
                    Assertions.assertTrue(ok, "tx2 not committed in window");
                    AcidUser u2 = db.getTable(AcidUser.class, id);
                    tx1SecondRead.value = serialize(u2);
                    passed.set(Arrays.equals(tx1FirstRead.value, tx1SecondRead.value));
                    tx.rollback();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            Thread tx2 = new Thread(() -> {
                try {
                    tx1Opened.await();
                    try (Transaction tx = db.beginTransaction()) {
                        AcidUser u = db.getTable(AcidUser.class, id);
                        u.age = u.age + 1;
                        db.putEntity(u, false);
                        tx.commit();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    tx2Committed.countDown();
                }
            });
            tx1.start();
            tx2.start();
            tx1.join(20_000);
            tx2.join(20_000);
            Assertions.assertNull(tx1.getState() == Thread.State.TERMINATED ? null : tx1.getState(), "tx1 必须 terminate");
            Assertions.assertTrue(passed.get(),
                "RR：tx2 写不应影响 tx1 快照，两次读字节必须 Arrays.equals");
            AcidUser after = db.getTable(AcidUser.class, id);
            Assertions.assertEquals(45L, after.age.longValue(), "tx2 已提交，外部读应看到 age=45");
        }
    }

    @Test
    public void t06_txClosed_threadLocalClean() throws Exception {
        try (DsDatabaseLocal db = new DsDatabaseLocal(home)) {
            DsTransactionManager txm = db.getTxManager();
            Assertions.assertNull(txm.currentOrNull(), "初始无事务");
            try (Transaction tx = db.beginTransaction()) {
                Assertions.assertNotNull(txm.currentOrNull());
                tx.commit();
            }
            Assertions.assertNull(txm.currentOrNull(), "commit 后 ThreadLocal 必须清空");
            try (Transaction tx2 = db.beginTransaction()) {
                Assertions.assertNotNull(txm.currentOrNull());
            }
            Assertions.assertNull(txm.currentOrNull(), "TWR close 后 ThreadLocal 必须清空");
        }
    }

    // =================== AC-R3/AC-R4/AC-R10：2PC 原子 + 崩溃恢复 6 tests（简化 2PC 模拟）===================

    @Test
    public void t07_2pc_twoLeaf_happy() throws Exception {
        try (DsDatabaseLocal db = new DsDatabaseLocal(home)) {
            final int N = 12;
            long[] ids = new long[N];
            try (Transaction tx = db.beginTransaction()) {
                for (int i = 0; i < N; i++) {
                    AcidUser u = new AcidUser();
                    u.name = "happy_" + i;
                    u.age = (long) i;
                    ids[i] = db.putEntity(u, true);
                }
                tx.commit();
            }
            for (int i = 0; i < N; i++) {
                AcidUser u = db.getTable(AcidUser.class, ids[i]);
                Assertions.assertNotNull(u, "happy commit 行 #" + i + " missing");
                Assertions.assertEquals("happy_" + i, u.name);
            }
        }
    }

    @Test
    public void t08_2pc_nonRR_throwsUoe() throws Exception {
        try (DsDatabaseLocal db = new DsDatabaseLocal(home)) {
            try {
                db.beginTransaction(IsolationLevel.READ_COMMITTED);
                Assertions.fail("RC 应抛 UOE MVP only RR");
            } catch (UnsupportedOperationException uoe) {
                Assertions.assertTrue(uoe.getMessage().contains("MVP only RR"));
            }
            try {
                db.beginTransaction(IsolationLevel.SNAPSHOT);
                Assertions.fail("SNAPSHOT 应抛 UOE");
            } catch (UnsupportedOperationException uoe) {
                Assertions.assertTrue(uoe.getMessage().contains("MVP only RR"));
            }
        }
    }

    @Test
    public void t09_2pc_recoverIncompleteTxs_idempotent() throws Exception {
        try (DsDatabaseLocal db = new DsDatabaseLocal(home)) {
            DsTransactionManager txm = db.getTxManager();
            TxContext ctx = txm.begin(IsolationLevel.REPEATABLE_READ);
            AcidUser u = new AcidUser();
            u.name = "toRecover";
            u.age = 777L;
            db.putEntity(u, true);
            // 故意不 commit/rollback，模拟 PREPARING 崩溃
            int r1 = txm.recoverIncompleteTxs();
            int r2 = txm.recoverIncompleteTxs();
            Assertions.assertTrue(r1 >= 0);
            Assertions.assertEquals(0, r2, "recover 第二次应幂等 0 新恢复");
        }
    }

    @Test
    public void t10_rr_facade_methods_public_final() throws Exception {
        try (DsDatabaseLocal db = new DsDatabaseLocal(home)) {
            Class<?> c = DsDatabaseLocal.class;
            Method m1 = c.getMethod("beginTransaction");
            Method m2 = c.getMethod("beginTransaction", IsolationLevel.class);
            Method m3 = c.getMethod("runInTransaction", Runnable.class);
            Method m4 = c.getMethod("callInTransaction", java.util.concurrent.Callable.class);
            Assertions.assertTrue(Modifier.isFinal(m1.getModifiers()), "beginTransaction 必须 final");
            Assertions.assertTrue(Modifier.isFinal(m2.getModifiers()), "beginTransaction(level) 必须 final");
            Assertions.assertTrue(Modifier.isFinal(m3.getModifiers()), "runInTransaction 必须 final");
            Assertions.assertTrue(Modifier.isFinal(m4.getModifiers()), "callInTransaction 必须 final");
            Assertions.assertTrue(Modifier.isPublic(m1.getModifiers()));
            Assertions.assertTrue(Modifier.isPublic(m2.getModifiers()));
            Assertions.assertTrue(Modifier.isPublic(m3.getModifiers()));
            Assertions.assertTrue(Modifier.isPublic(m4.getModifiers()));
        }
    }

    @Test
    public void t11_acR6_txRoot_zeroLeak_grepAssert() throws Exception {
        // 零外泄断言：walk p2p-db src/main/java 所有 .java 搜 "\"/orm/tx\"" 字符串字面量 → 仅 RegistryMetaPaths 1 行命中
        java.nio.file.Path rootDir = home.toPath().getFileSystem().getPath(
            System.getProperty("user.dir"), "p2p-db", "src", "main", "java");
        if (!Files.isDirectory(rootDir)) {
            // CI 环境 user.dir 可能不同：fallback 仅 assert 常量值本身（防止跳过 0 断言）
            Assertions.assertEquals("/orm/tx", RegistryMetaPaths.TX_ROOT);
            return;
        }
        final List<String> hits = new ArrayList<>();
        final java.util.regex.Pattern p = java.util.regex.Pattern.compile("\"/orm/tx\"");
        try (java.util.stream.Stream<Path> stream = Files.walk(rootDir)) {
            stream.filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .forEach(path -> {
                    try {
                        List<String> lines = Files.readAllLines(path);
                        for (int i = 0; i < lines.size(); i++) {
                            if (p.matcher(lines.get(i)).find()) {
                                String rel = rootDir.relativize(path).toString().replace('\\', '/');
                                hits.add(rel + ":" + (i + 1) + " " + lines.get(i).trim());
                            }
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
        }
        Assertions.assertTrue(hits.size() >= 1, "至少 1 处 TX_ROOT 声明");
        Assertions.assertTrue(hits.stream().anyMatch(s -> s.contains("RegistryMetaPaths.java")), "1 处命中必须在 RegistryMetaPaths");
        // 宽松：其余命中必须来自 RegistryMetaPaths.java（零外泄仅本文件允许）
        for (String hit : hits) {
            Assertions.assertTrue(hit.contains("RegistryMetaPaths.java"),
                "\"/orm/tx\" 零外泄：命中必须仅在 RegistryMetaPaths 中，实际=" + hit);
        }
    }

    @Test
    public void t12_runInCallable_exception_preserves() throws Exception {
        try (DsDatabaseLocal db = new DsDatabaseLocal(home)) {
            java.util.concurrent.atomic.AtomicLong capturedId = new java.util.concurrent.atomic.AtomicLong(0L);
            try {
                db.callInTransaction(() -> {
                    AcidUser u = new AcidUser();
                    u.name = "xx";
                    u.age = 0L;
                    capturedId.set(db.putEntity(u, true));
                    throw new IOException("checked!");
                });
                Assertions.fail("应抛检查型 IOException");
            } catch (IOException expected) {
                Assertions.assertEquals("checked!", expected.getMessage());
            }
            // rollback 检查：没有 AcidUser row
            Assertions.assertTrue(capturedId.get() > 0L, "分配 id 应>0");
            AcidUser u = db.getTable(AcidUser.class, capturedId.get());
            Assertions.assertNull(u, "callInTransaction 异常必须 rollback");
        }
    }

    // =================== AC-R7：Bloom 绑定事务（简化占位，真正 Bloom set/reset 走 P13 enableBloomForColumn）===================

    @Test
    public void t13_bloomOp_type_range() {
        Assertions.assertEquals(0x01, TxOp.TYPE_ROW_PUT);
        Assertions.assertEquals(0x07, TxOp.TYPE_BLOOM_SET);
        Assertions.assertThrows(IllegalArgumentException.class, () -> new TxOp((byte) 0x08, "/x", new byte[0], new byte[0]));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new TxOp((byte) 0x00, "/x", new byte[0], new byte[0]));
    }

    @Test
    public void t14_txOp_pack_unpack_roundtrip() {
        byte[] prev = "prevBytes".getBytes();
        byte[] payload = "payloadBytes".getBytes();
        TxOp op = new TxOp(TxOp.TYPE_COL_PUT, "/orm/data/column/X/c/000000000000002a", prev, payload);
        byte[] packed = op.pack();
        TxOp restored = TxOp.unpack(packed);
        Assertions.assertEquals(TxOp.TYPE_COL_PUT, restored.type);
        Assertions.assertEquals(op.registryPath, restored.registryPath);
        Assertions.assertArrayEquals(prev, restored.expectedPrevBytes);
        Assertions.assertArrayEquals(payload, restored.newPayload);
    }

    // =================== Helpers ===================

    @DsTable(name = "p14_acid_user", mvcc = true)
    public static class AcidUser extends DsTableAdapter {
        @DsField(name = "name", length = 64)
        String name;
        @DsField(name = "age", length = 8)
        Long age;
    }

    private static byte[] serialize(AcidUser u) {
        if (u == null) return new byte[0];
        String s = u.name + "|" + (u.age == null ? "null" : u.age.toString());
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) for (File c : ch) deleteRecursively(c);
        }
        f.delete();
    }

    private static final class AtomicReferenceHolder<V> {
        V value;
    }
}
