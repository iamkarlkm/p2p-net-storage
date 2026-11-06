package ds;

import static org.junit.Assert.*;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import com.q3lives.ds.cluster.DsIdLayout;
import com.q3lives.ds.enums.DataType;
import com.q3lives.ds.registry.DsRegistry;
import com.q3lives.ds.registry.RegistryEntry;
import org.junit.Test;

public class DsRegistry_P6b_DsIdLayoutStressTest {

    private static final int TYPE_REGISTRY_DIR_META = 205;
    private static final int POWER_NODE_BLOCK_DIR = 7;
    private static final int TYPE_REGISTRY_NODE_META = 202;
    private static final int POWER_NODE_BLOCK = 6;

    private static long encodeMT(int type, int power, long idx) {
        return ((long) (type & 0xFFFF) << 48) | ((long) (power & 0xFF) << 40) | (idx & 0xFFFFFFFFFFL);
    }

    private static long rootDirTypedId() {
        return encodeMT(TYPE_REGISTRY_DIR_META, POWER_NODE_BLOCK_DIR, 0L);
    }

    private static long getTypedIdViaGet(DsRegistry r, String key) throws Exception {
        RegistryEntry e = r.get(key);
        assertTrue("entry present for: " + key, e.isPresent());
        return e.nodeId();
    }

    @Test
    public void testCrossInstanceGlobalsIndependent() throws Exception {
        File tmp1 = Files.createTempDirectory("dsregp6b-inst1-").toFile();
        File tmp2 = Files.createTempDirectory("dsregp6b-inst2-").toFile();
        tmp1.deleteOnExit();
        tmp2.deleteOnExit();
        try (DsRegistry r1 = new DsRegistry(new File(tmp1, "db").getAbsolutePath(), 1, 0);
             DsRegistry r2 = new DsRegistry(new File(tmp2, "db").getAbsolutePath(), 2, 0)) {
            r1.putLong("/shared/item", DataType.REG_DWORD, 42L);
            r2.putLong("/shared/item", DataType.REG_DWORD, 42L);
            long typed1 = getTypedIdViaGet(r1, "/shared/item");
            long typed2 = getTypedIdViaGet(r2, "/shared/item");
            long g1 = r1.toGlobalNodeId(typed1);
            long g2 = r2.toGlobalNodeId(typed2);
            assertNotEquals("Different serverId must produce different global rowIds", g1, g2);
            assertEquals(1, DsIdLayout.serverId(g1));
            assertEquals(2, DsIdLayout.serverId(g2));
            assertEquals(0, DsIdLayout.partitionId(g1));
            assertEquals(0, DsIdLayout.partitionId(g2));
            long back1 = r1.fromGlobalNodeId(g1, false);
            long back2 = r2.fromGlobalNodeId(g2, false);
            assertEquals(typed1, back1);
            assertEquals(typed2, back2);
            try {
                r1.fromGlobalNodeId(g2, false);
                fail("Expected IAE on mismatched serverId");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("serverId") || expected.getMessage().contains("mismatch")
                        || expected.getMessage().contains("server"));
            }
        }
    }

    @Test
    public void testMismatchPartitionThrowsIAE() throws Exception {
        File tmp = Files.createTempDirectory("dsregp6b-mismatch-").toFile();
        tmp.deleteOnExit();
        try (DsRegistry r = new DsRegistry(new File(tmp, "db").getAbsolutePath(), 7, 128)) {
            r.mkdirs("/dir/x");
            long typedId = getTypedIdViaGet(r, "/dir/x");
            long correct = r.toGlobalNodeId(typedId);
            long fake = DsIdLayout.compose(7, 200, DsIdLayout.localAddress(correct));
            try {
                r.fromGlobalNodeId(fake, true);
                fail("Expected IAE on partition mismatch");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().length() > 0);
            }
            r.fromGlobalNodeId(correct, true);
        }
    }

    @Test
    public void testDirLeafRoundtripMultiDepth() throws Exception {
        final int DEPTH = 10;
        final int N = 200;
        StringBuilder sb = new StringBuilder(256);
        String[] layers = new String[DEPTH];
        for (int i = 0; i < DEPTH; i++) layers[i] = "/l" + i;
        File tmp = Files.createTempDirectory("dsregp6b-roundtrip-").toFile();
        tmp.deleteOnExit();
        try (DsRegistry r = new DsRegistry(new File(tmp, "db").getAbsolutePath(), 4194303, 255)) {
            for (int k = 0; k < N; k++) {
                sb.setLength(0);
                for (int i = 0; i < DEPTH; i++) sb.append(layers[i]);
                sb.append("/item-").append(k);
                r.putLong(sb.toString(), DataType.REG_DWORD, (long) (k * 7));
            }
            for (int i = 1; i <= DEPTH; i++) {
                sb.setLength(0);
                for (int j = 0; j < i; j++) sb.append(layers[j]);
                String d = sb.toString();
                long dirTyped = getTypedIdViaGet(r, d);
                long gDir = r.toGlobalNodeId(dirTyped);
                assertEquals(4194303, DsIdLayout.serverId(gDir));
                assertEquals(255, DsIdLayout.partitionId(gDir));
                long backDir = r.fromGlobalNodeId(gDir, true);
                assertEquals("DIR roundtrip equal for " + d, dirTyped, backDir);
            }
            for (int k = 0; k < N; k++) {
                sb.setLength(0);
                for (int i = 0; i < DEPTH; i++) sb.append(layers[i]);
                sb.append("/item-").append(k);
                String key = sb.toString();
                long leafTyped = getTypedIdViaGet(r, key);
                long gLeaf = r.toGlobalNodeId(leafTyped);
                assertEquals(4194303, DsIdLayout.serverId(gLeaf));
                assertEquals(255, DsIdLayout.partitionId(gLeaf));
                long backLeaf = r.fromGlobalNodeId(gLeaf, false);
                assertEquals("LEAF roundtrip equal for " + key, leafTyped, backLeaf);
                assertEquals((long) (k * 7), r.get(key).asLong());
            }
        }
    }

    @Test
    public void testRootLocalZeroMapsToRootTypedId() throws Exception {
        int[] serverIds = new int[]{0, 1, 100, 4194303};
        int[] parts = new int[]{0, 128, 255, 17};
        for (int s = 0; s < serverIds.length; s++) {
            int sid = serverIds[s];
            int pid = parts[s];
            File tmp = Files.createTempDirectory("dsregp6b-root-" + sid + "-" + pid + "-").toFile();
            tmp.deleteOnExit();
            try (DsRegistry r = new DsRegistry(new File(tmp, "db").getAbsolutePath(), sid, pid)) {
                assertTrue(r.exists("/"));
                long rootG = DsIdLayout.compose(sid, pid, 0L);
                long back = r.fromGlobalNodeId(rootG, true);
                assertEquals("ROOT local=0 -> rootDirTypedId for s=" + sid + " p=" + pid,
                        rootDirTypedId(), back);
                long toG = r.toGlobalNodeId(rootDirTypedId());
                assertEquals("ROOT typedId toGlobal must compose with local=0", 0L, DsIdLayout.localAddress(toG));
                assertEquals(sid, DsIdLayout.serverId(toG));
                assertEquals(pid, DsIdLayout.partitionId(toG));
            }
        }
    }

    @Test
    public void testConcurrentMixedWorkloadStress() throws Exception {
        final int INSTANCES = 4;
        final int THREADS_PER_INSTANCE = 4;
        final int N_PER_THREAD = 250;
        final int DEPTH = 8;
        String[] layers = new String[DEPTH];
        for (int i = 0; i < DEPTH; i++) layers[i] = "/d" + i;
        File[] tmps = new File[INSTANCES];
        DsRegistry[] regs = new DsRegistry[INSTANCES];
        int[][] sp = new int[][]{{1, 0}, {5, 255}, {4194303, 128}, {0, 17}};
        try {
            for (int i = 0; i < INSTANCES; i++) {
                tmps[i] = Files.createTempDirectory("dsregp6b-stress-" + i + "-").toFile();
                tmps[i].deleteOnExit();
                regs[i] = new DsRegistry(new File(tmps[i], "db").getAbsolutePath(), sp[i][0], sp[i][1]);
            }
            ExecutorService pool = Executors.newFixedThreadPool(INSTANCES * THREADS_PER_INSTANCE);
            List<Callable<int[]>> tasks = new ArrayList<>();
            ConcurrentMap<String, AtomicInteger> errCounters = new ConcurrentHashMap<>();
            for (int inst = 0; inst < INSTANCES; inst++) {
                for (int t = 0; t < THREADS_PER_INSTANCE; t++) {
                    final int finst = inst;
                    final int ftid = t;
                    tasks.add(() -> {
                        DsRegistry rr = regs[finst];
                        StringBuilder sb2 = new StringBuilder(256);
                        int[] counts = new int[]{0, 0, 0};
                        List<Throwable> thrown = new ArrayList<>();
                        try {
                            for (int k = 0; k < N_PER_THREAD; k++) {
                                sb2.setLength(0);
                                for (int i = 0; i < DEPTH; i++) sb2.append(layers[i]);
                                sb2.append("/t").append(ftid).append("/item-").append(k);
                                String key = sb2.toString();
                                rr.putLong(key, DataType.REG_DWORD, (long) (finst * 1000000 + ftid * 1000 + k));
                                counts[0]++;
                                RegistryEntry e = rr.get(key);
                                if (!e.isPresent()) {
                                    thrown.add(new AssertionError("missing key " + key));
                                    continue;
                                }
                                long typed = e.nodeId();
                                long g = rr.toGlobalNodeId(typed);
                                counts[1]++;
                                boolean isDir = e.isDirectory();
                                long back = rr.fromGlobalNodeId(g, isDir);
                                if (typed != back) {
                                    thrown.add(new AssertionError("roundtrip mismatch for " + key));
                                }
                                long val = e.asLong();
                                long expected = (long) (finst * 1_000_000 + ftid * 1000 + k);
                                if (val != expected) {
                                    thrown.add(new AssertionError("value mismatch for " + key + ": got=" + val + " exp=" + expected));
                                }
                                counts[2]++;
                            }
                        } catch (Throwable ex) {
                            thrown.add(ex);
                        }
                        if (!thrown.isEmpty()) {
                            String name = "inst" + finst + "-t" + ftid;
                            AtomicInteger cnt = errCounters.computeIfAbsent(name, k -> new AtomicInteger(0));
                            cnt.addAndGet(thrown.size());
                            for (Throwable th : thrown) {
                                th.printStackTrace(System.err);
                            }
                        }
                        return counts;
                    });
                }
            }
            List<Future<int[]>> results = pool.invokeAll(tasks);
            pool.shutdown();
            int putCount = 0, toGlobalCount = 0, backCount = 0;
            for (Future<int[]> f : results) {
                int[] c = f.get();
                putCount += c[0];
                toGlobalCount += c[1];
                backCount += c[2];
            }
            int expected = INSTANCES * THREADS_PER_INSTANCE * N_PER_THREAD;
            assertEquals(expected, putCount);
            assertEquals(expected, toGlobalCount);
            assertEquals(expected, backCount);
            for (int i = 0; i < INSTANCES; i++) {
                String key = "inst" + i;
                assertFalse("No thread errors for instance " + i + ": " + errCounters,
                        errCounters.keySet().stream().anyMatch(k -> k.startsWith(key)));
            }
        } finally {
            for (DsRegistry r : regs) if (r != null) try { r.close(); } catch (Exception ignore) {}
        }
    }
}
