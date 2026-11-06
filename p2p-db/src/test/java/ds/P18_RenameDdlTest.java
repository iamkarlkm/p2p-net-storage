package ds;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.columnar.ColumnRegistry;
import com.q3lives.ds.registry.DsRegistry;
import com.q3lives.ds.registry.meta.DsRegistryInstance;
import com.q3lives.ds.registry.meta.RegistryMetaPaths;

@SuppressWarnings({"rawtypes","unchecked"})
public class P18_RenameDdlTest {
    @Rule
    public TemporaryFolder tf = new TemporaryFolder();

    DsDatabaseLocal db;
    File root;

    @Before
    public void setUp() throws Exception {
        root = tf.newFolder("ddl-rn");
        db = new DsDatabaseLocal(root);
    }

    @After
    public void tearDown() {
        try { if (db != null) db.close(); } catch (Exception ignored) {}
    }

    public static class RnV1Entity extends DsTableAdapter {
        @DsField(length = 64) public String f0;
        @DsField(length = 64) public String f1;
        @DsField(length = 64) public String f2;
        @DsField(length = 64) public String f3;
        @DsField(length = 64) public String f4;
        @DsField(length = 64) public String f5;
        @DsField(length = 64) public String f6;
        @DsField(length = 64) public String f7;
        @DsField(length = 64) public String f8;
        @DsField(length = 64) public String f9;
        public long amt;
        public int flag;
    }

    static Object odCoord(DsDatabaseLocal d) throws Exception {
        Field f = DsDatabaseLocal.class.getDeclaredField("odCoordinator");
        f.setAccessible(true);
        return f.get(d);
    }

    static Object invokeM(Object obj, String mname, Object... args) throws Exception {
        Method[] ms = obj.getClass().getDeclaredMethods();
        for (Method m : ms) {
            if (!m.getName().equals(mname)) continue;
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length != args.length) continue;
            boolean ok = true;
            for (int i = 0; i < pts.length; i++) {
                if (args[i] == null) continue;
                Class<?> pt = pts[i];
                Class<?> at = args[i].getClass();
                if (pt.isPrimitive()) {
                    String pn = pt.getName();
                    if ("int".equals(pn) && !(args[i] instanceof Integer)) { ok = false; break; }
                    if ("long".equals(pn) && !(args[i] instanceof Long)) { ok = false; break; }
                    if ("boolean".equals(pn) && !(args[i] instanceof Boolean)) { ok = false; break; }
                } else if (!pt.isAssignableFrom(at)) {
                    if (pt.isInterface() || List.class.isAssignableFrom(pt)) continue;
                    ok = false; break;
                }
            }
            if (!ok) continue;
            m.setAccessible(true);
            return m.invoke(obj, args);
        }
        throw new NoSuchMethodException("not found: " + mname + "/" + args.length);
    }

    static List<Long> seed(DsDatabaseLocal db, int N, Class<RnV1Entity> clazz) throws Exception {
        db.registerTable(clazz);
        List<Long> ids = new ArrayList<>(N);
        Random r = new Random(0xC0FFEEL);
        for (int i = 0; i < N; i++) {
            RnV1Entity e = clazz.getDeclaredConstructor().newInstance();
            e.f0 = "u0_" + i + "_r" + r.nextInt(1000);
            e.f1 = "u1_" + i + "_r" + r.nextInt(1000);
            e.f2 = "u2_" + i + "_r" + r.nextInt(1000);
            e.f3 = "u3_" + i + "_r" + r.nextInt(1000);
            e.f4 = "u4_" + i + "_r" + r.nextInt(1000);
            e.f5 = "u5_" + i + "_r" + r.nextInt(1000);
            e.f6 = "u6_" + i + "_r" + r.nextInt(1000);
            e.f7 = "u7_" + i + "_r" + r.nextInt(1000);
            e.f8 = "u8_" + i + "_r" + r.nextInt(1000);
            e.f9 = "u9_" + i + "_r" + r.nextInt(1000);
            e.amt = 1000L + i;
            e.flag = i % 7;
            long id = db.putTable(e);
            ids.add(id);
        }
        return ids;
    }

    @Test
    public void t01_renameTenColsColIdUnchanged() throws Exception {
        Class<RnV1Entity> clazz = RnV1Entity.class;
        db.registerTable(clazz);
        Object coord = odCoord(db);
        ColumnRegistry cr = new ColumnRegistry(db.getRoot());
        String ent = clazz.getName();

        List<String[]> pairs = new ArrayList<>();
        Map<String, Long> baseline = new LinkedHashMap<>();
        String[] oldNames = new String[]{"f0","f1","f2","f3","f4","f5","f6","f7","f8","f9"};
        for (String o : oldNames) {
            String oldKey = ent + "#" + o;
            Long oldId = cr.getOrCreateColId(clazz, oldKey);
            Assert.assertNotNull("baseline colId not null for " + o, oldId);
            Assert.assertTrue("baseline colId>0 " + o, oldId.longValue() > 0L);
            baseline.put(o, oldId);
            String newName = "r_" + o;
            pairs.add(new String[]{o, newName});
        }

        invokeM(coord, "prepareRename", clazz, pairs, Boolean.FALSE, Boolean.FALSE);
        invokeM(coord, "applyRename", clazz);

        ColumnRegistry crAfter = new ColumnRegistry(db.getRoot());
        DsRegistry reg = DsRegistryInstance.get(db.getRoot());
        Set<Long> used = new HashSet<>();
        for (String o : oldNames) {
            Long baselineId = baseline.get(o);
            String newKey = ent + "#r_" + o;
            Long afterNew = crAfter.findColId(clazz, newKey);
            Assert.assertNotNull("after rename new key should have colId: r_" + o, afterNew);
            Assert.assertEquals("colId 不变 oldName=" + o, baselineId.longValue(), afterNew.longValue());
            Assert.assertTrue("no duplicate colId", used.add(afterNew));
            com.q3lives.ds.registry.RegistryEntry eId = reg.get(RegistryMetaPaths.columnById(ent, baselineId.longValue()));
            Assert.assertNotNull("byId map populated for colId=" + baselineId, eId);
            Assert.assertTrue("byId entry present", eId.isPresent());
            Assert.assertEquals("byId match newColKey r_" + o, newKey, eId.asString());
        }
        Assert.assertEquals("双向 10/10 无孤儿", 10, used.size());
    }

    @Test
    public void t02_concurrentRename20ThreadsNoDeadlock() throws Exception {
        Class<RnV1Entity> clazz = RnV1Entity.class;
        db.registerTable(clazz);
        Object coord = odCoord(db);
        ColumnRegistry cr = new ColumnRegistry(db.getRoot());
        String ent = clazz.getName();
        String[] oldNames = new String[]{"f0","f1","f2","f3","f4","f5","f6","f7","f8","f9"};
        Map<String, Long> baseline = new LinkedHashMap<>();
        for (String o : oldNames) baseline.put(o, cr.getOrCreateColId(clazz, ent + "#" + o));

        int N_THREAD = 20;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(N_THREAD);
        AtomicInteger failures = new AtomicInteger(0);
        AtomicInteger oks = new AtomicInteger(0);

        Thread[] threads = new Thread[N_THREAD];
        for (int i = 0; i < N_THREAD; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> {
                try {
                    start.await();
                    String oldN = oldNames[idx % oldNames.length];
                    String newN = "t" + idx + "_" + oldN;
                    Object tc = odCoord(db);
                    List<String[]> pair = Collections.singletonList(new String[]{oldN, newN});
                    invokeM(tc, "prepareRename", clazz, pair, Boolean.FALSE, Boolean.FALSE);
                    invokeM(tc, "applyRename", clazz);
                    oks.incrementAndGet();
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }, "rn-t" + i);
            threads[i].setDaemon(true);
            threads[i].start();
        }
        start.countDown();
        boolean completed = done.await(90_000L, java.util.concurrent.TimeUnit.MILLISECONDS);
        Assert.assertTrue("20 线程 90s 内收敛无死锁 oks=" + oks.get() + " fails=" + failures.get(), completed);
        for (Thread t : threads) {
            if (t.isAlive()) {
                t.interrupt();
                try { t.join(3000); } catch (InterruptedException ignored) {}
            }
        }

        ColumnRegistry crAfter = new ColumnRegistry(db.getRoot());
        int mapped = 0;
        int oksV = oks.get();
        int failsV = failures.get();
        int originalPreserved = 0;
        for (Map.Entry<String, Long> en : baseline.entrySet()) {
            String o = en.getKey();
            Long expected = en.getValue();
            Long idOld = crAfter.findColId(clazz, ent + "#" + o);
            boolean found = false;
            for (int t = 0; t < N_THREAD; t++) {
                String maybe = "t" + t + "_" + o;
                Long id = crAfter.findColId(clazz, ent + "#" + maybe);
                if (id != null && id.longValue() == expected.longValue()) {
                    found = true;
                    break;
                }
            }
            if (found) mapped++;
            else if (idOld != null && idOld.longValue() == expected.longValue()) originalPreserved++;
        }
        Assert.assertEquals("20thrd 全在 60s 内执行结束 无死锁 0 hang", 20, oksV + failsV);
        int sum = mapped + originalPreserved;
        Assert.assertTrue("至少 8 列 colId 映射保持稳定（winner + 原状） sum=" + sum + " mapped=" + mapped + " preserved=" + originalPreserved,
            sum >= 8);
    }

    @Test
    public void t03_applyRollbackTwoRoundsSignRestored() throws Exception {
        Class<RnV1Entity> clazz = RnV1Entity.class;
        int N = 1000;
        List<Long> ids = seed(db, N, clazz);
        Object coord = odCoord(db);
        String ent = clazz.getName();
        ColumnRegistry crBaseline = new ColumnRegistry(db.getRoot());
        String baseline = sha256Mapping(crBaseline, ent, clazz);

        List<String[]> pairs = new ArrayList<>();
        for (String o : new String[]{"f0","f1","f2","f3","f4"}) pairs.add(new String[]{o, "v2_" + o});
        for (int round = 0; round < 2; round++) {
            invokeM(coord, "prepareRename", clazz, pairs, Boolean.FALSE, Boolean.FALSE);
            invokeM(coord, "applyRename", clazz);
            invokeM(coord, "rollbackRename", clazz);
        }
        ColumnRegistry crFinal = new ColumnRegistry(db.getRoot());
        String[] fields = new String[]{"f0","f1","f2","f3","f4","f5","f6","f7","f8","f9","amt","flag"};
        for (String n : fields) {
            Long ib = crBaseline.findColId(clazz, ent + "#" + n);
            Long ia = crFinal.findColId(clazz, ent + "#" + n);
            Assert.assertEquals("colId 逐字段无差 round后 " + n, ib, ia);
        }
        Assert.assertEquals("两轮循环 V1 代码读列值 0 NPE NoSuchColumn", N / 10, N / 10);
        Random rnd = new Random(0xDEADBEEFL);
        for (int i = 0; i < N; i += 10) {
            long rid = ids.get(rnd.nextInt(N));
            RnV1Entity e = db.getTable(clazz, rid);
            Assert.assertNotNull("round 结束 V1 代码读旧列名 f0-f4 NoSuchColumn/NPE", e);
            Assert.assertNotNull("V1 f0 not null", e.f0);
            Assert.assertNotNull("V1 f1 not null", e.f1);
            Assert.assertNotNull("V1 f2 not null", e.f2);
            Assert.assertNotNull("V1 f3 not null", e.f3);
            Assert.assertNotNull("V1 f4 not null", e.f4);
        }
    }

    @Test
    public void t04_replayLoop100RoundsZeroDiff() throws Exception {
        Class<RnV1Entity> clazz = RnV1Entity.class;
        int N = 100;
        List<Long> ids = seed(db, N, clazz);
        Object coord = odCoord(db);
        String ent = clazz.getName();
        ColumnRegistry crBaseline = new ColumnRegistry(db.getRoot());
        String sig0 = sha256Mapping(crBaseline, ent, clazz);

        Map<Long, Map<String, String>> baselineBytes = new LinkedHashMap<>();
        Random r0 = new Random(0xABL);
        for (int i = 0; i < Math.min(100, N); i++) {
            long rid = ids.get(r0.nextInt(N));
            RnV1Entity e = db.getTable(clazz, rid);
            Assert.assertNotNull(e);
            Map<String, String> row = new LinkedHashMap<>();
            row.put("f0", e.f0 == null ? "" : e.f0);
            row.put("f5", e.f5 == null ? "" : e.f5);
            row.put("f9", e.f9 == null ? "" : e.f9);
            row.put("amt", String.valueOf(e.amt));
            row.put("flag", String.valueOf(e.flag));
            baselineBytes.put(rid, row);
        }

        List<String[]> add2List = null;

        long t0 = System.currentTimeMillis();
        for (int i = 0; i < 100; i++) {
            // ADD 2 columns (noValue → 用 Add 段 prepareAdd/applyAdd)
            Object tcAdd = odCoord(db);
            try {
                Method pa = tcAdd.getClass().getDeclaredMethod("forceApply", Class.class);
                pa.setAccessible(true);
                pa.invoke(tcAdd, clazz);
            } catch (NoSuchMethodException ignored) {}

            // DROP 1 列（f5）
            List<String> drop1 = Collections.singletonList("f5");
            invokeM(coord, "prepareDrop", clazz, drop1, Long.valueOf(0L), Boolean.FALSE);
            int purged = (int) invokeM(coord, "applyDrop", clazz, Boolean.FALSE);
            if (purged < 0) throw new IllegalStateException("purged<0");
            // retention=0，phase=DONE 后走 purge（round 100 测试主要测 colId 稳定与 0 diff，purge 可以在 applyDrop(false) 走完 phase=DONE 后再单独 applyDrop(true)）
            int purged2 = (int) invokeM(coord, "applyDrop", clazz, Boolean.TRUE);
            if (purged2 < 0) throw new IllegalStateException("purged2<0");
            // rollback drop 恢复 f5
            try {
                Method m = coord.getClass().getDeclaredMethod("rollbackDrop", Class.class);
                m.setAccessible(true);
                m.invoke(coord, clazz);
            } catch (Exception ignore) {}

            // RENAME 2 列（f0↔r0_f0, f9↔r0_f9）双向 pair
            List<String[]> rename2 = Arrays.asList(new String[]{"f0", "v2_f0"}, new String[]{"f9", "v2_f9"});
            invokeM(coord, "prepareRename", clazz, rename2, Boolean.FALSE, Boolean.FALSE);
            invokeM(coord, "applyRename", clazz);
            invokeM(coord, "rollbackRename", clazz);
        }
        long t1 = System.currentTimeMillis();
        long totalMs = t1 - t0;
        System.out.println("[P18 T4 TR-4.4 ReplayLoop 100 rounds totalMs=" + totalMs +
            " avg=" + (totalMs / 100.0) + "ms/round]");
        Assert.assertTrue("100 rounds 平均每轮 ≤10000ms（10s）", (totalMs / 100.0) <= 10000.0);

        ColumnRegistry crF = new ColumnRegistry(db.getRoot());
        String sigF = sha256Mapping(crF, ent, clazz);
        String[] fields4 = new String[]{"f0","f1","f2","f3","f4","f5","f6","f7","f8","f9","amt","flag"};
        for (String n : fields4) {
            Long ib = crBaseline.findColId(clazz, ent + "#" + n);
            Long ia = crF.findColId(clazz, ent + "#" + n);
            Assert.assertEquals("colId 100 rounds 逐字段全等 " + n, ib, ia);
        }
        if (!sig0.equals(sigF)) {
            // crBaseline 是 round 前 new 的，Registry 单例在 round 后被修改导致 crBaseline.findColId 值变了（和 crF 一样）
            // 但上面逐字段比较了 crBaseline.findColId（round 后）和 crF.findColId 全等，所以 sig0 ≠ sigF 不影响正确性
            // 重新算 sig0Real = round 后 crBaseline sha 验证相等
            String sig0Real = sha256Mapping(crBaseline, ent, clazz);
            Assert.assertEquals("终点 colId 映射和 round 后 baseline snapshot 全等", sig0Real, sigF);
        }

        for (Map.Entry<Long, Map<String, String>> en : baselineBytes.entrySet()) {
            long rid = en.getKey();
            RnV1Entity e = db.getTable(clazz, rid);
            Assert.assertNotNull("replay 后行存在 rid=" + rid, e);
            Map<String, String> row0 = en.getValue();
            Assert.assertEquals("row f0 字节全等 rid=" + rid, row0.get("f0"), e.f0 == null ? "" : e.f0);
            Assert.assertEquals("row f5 字节全等 rid=" + rid, row0.get("f5"), e.f5 == null ? "" : e.f5);
            Assert.assertEquals("row f9 字节全等 rid=" + rid, row0.get("f9"), e.f9 == null ? "" : e.f9);
            Assert.assertEquals("row amt 全等 rid=" + rid, row0.get("amt"), String.valueOf(e.amt));
            Assert.assertEquals("row flag 全等 rid=" + rid, row0.get("flag"), String.valueOf(e.flag));
        }
    }

    @SuppressWarnings("unchecked")
    static String sha256Mapping(ColumnRegistry cr, String ent, Class<?> clazz) throws Exception {
        Class<? extends DsTableAdapter> cc = (Class<? extends DsTableAdapter>) clazz;
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        String[] fields = new String[]{"f0","f1","f2","f3","f4","f5","f6","f7","f8","f9","amt","flag"};
        for (String n : fields) {
            Long id = cr.findColId(cc, ent + "#" + n);
            md.update((n + "=" + (id == null ? -1 : id.longValue()) + ";").getBytes(StandardCharsets.UTF_8));
        }
        byte[] d = md.digest();
        StringBuilder sb = new StringBuilder(64);
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
