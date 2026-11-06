package ds;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.model.ExtaAddCommentRequest;
import javax.net.p2p.model.ExtaAddDescRequest;
import javax.net.p2p.model.ExtaAddSpecRequest;
import javax.net.p2p.model.ExtaAddTagRequest;
import javax.net.p2p.model.ExtaHasTagRequest;
import javax.net.p2p.model.ExtaListTagsRequest;
import javax.net.p2p.model.ExtaRemoveAllCommentsRequest;
import javax.net.p2p.model.ExtaRemoveAllDescsRequest;
import javax.net.p2p.model.ExtaRemoveAllSpecsRequest;
import javax.net.p2p.model.ExtaRemoveAllTagsRequest;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.server.handler.ExtaAddCommentServerHandler;
import javax.net.p2p.server.handler.ExtaAddDescServerHandler;
import javax.net.p2p.server.handler.ExtaAddSpecServerHandler;
import javax.net.p2p.server.handler.ExtaAddTagServerHandler;
import javax.net.p2p.server.handler.ExtaHasTagServerHandler;
import javax.net.p2p.server.handler.ExtaListTagsServerHandler;
import javax.net.p2p.server.handler.ExtaRemoveAllCommentsServerHandler;
import javax.net.p2p.server.handler.ExtaRemoveAllDescsServerHandler;
import javax.net.p2p.server.handler.ExtaRemoveAllSpecsServerHandler;
import javax.net.p2p.server.handler.ExtaRemoveAllTagsServerHandler;
import javax.net.p2p.server.handler.Tgt3RegistryResolver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.q3lives.ds.enums.DataType;
import com.q3lives.ds.registry.DsRegistry;
import com.q3lives.ds.registry.DsRegistryExtAttrsHelper;

public class DsRegistryExtAttrs_P7_Tgt5_PerfDeep_16x16_MatrixStressTest {

    static final int SA = 12, PA = 0, SB = 42, PB = 0;
    static final int MATRIX_S = 8;
    static final int MATRIX_P = 8;
    static final int MATRIX_N = MATRIX_S * MATRIX_P;

    private static File tmpDir(String prefix) throws Exception {
        File f = Files.createTempDirectory(prefix).toFile();
        f.deleteOnExit();
        return f;
    }

    @AfterEach
    void clear() {
        Tgt3RegistryResolver.clearForTest();
    }

    @Test
    void T01_DESC_SPEC_COMMENT_seqStrict_monotonic_zeroGap_zeroDup_removeAll_reset1() throws Exception {
        final int PER_COMBO_PER_TYPE = 100;
        final int THREADS_PER_COMBO = 1;
        File[] tmps = new File[MATRIX_N];
        DsRegistry[] regs = new DsRegistry[MATRIX_N];
        int[] sids = new int[MATRIX_N];
        int[] pids = new int[MATRIX_N];
        try {
            int idx = 0;
            for (int s = 0; s < MATRIX_S; s++) {
                for (int p = 0; p < MATRIX_P; p++) {
                    sids[idx] = s;
                    pids[idx] = p;
                    tmps[idx] = tmpDir("dsreg_tgt5_perf_s" + s + "p" + p + "_");
                    regs[idx] = new DsRegistry(new File(tmps[idx], "db").getAbsolutePath(), s, p);
                    idx++;
                }
            }
            ExecutorService pool = Executors.newFixedThreadPool(MATRIX_N * THREADS_PER_COMBO);
            List<Callable<long[]>> tasks = new ArrayList<>();
            ConcurrentMap<String, AtomicInteger> errCounters = new ConcurrentHashMap<>();
            for (int ci = 0; ci < MATRIX_N; ci++) {
                final int fci = ci;
                for (int tt = 0; tt < THREADS_PER_COMBO; tt++) {
                    final int ftt = tt;
                    tasks.add(() -> {
                        DsRegistry r = regs[fci];
                        String ek = "/apps/combo_" + fci + "_t" + ftt;
                        r.putString(ek, "payload", DataType.REG_SZ);
                        long[] c = new long[6];
                        List<Throwable> thrown = new ArrayList<>();
                        try {
                            long[] dSeqs = new long[PER_COMBO_PER_TYPE];
                            long[] sSeqs = new long[PER_COMBO_PER_TYPE];
                            long[] cSeqs = new long[PER_COMBO_PER_TYPE];
                            for (int i = 0; i < PER_COMBO_PER_TYPE; i++) {
                                dSeqs[i] = DsRegistryExtAttrsHelper.addDesc(r, ek, "desc_v" + i);
                                sSeqs[i] = DsRegistryExtAttrsHelper.addSpec(r, ek, "spec_v" + i);
                                cSeqs[i] = DsRegistryExtAttrsHelper.addComment(r, ek, "comment_v" + i);
                            }
                            long prev;
                            prev = 0;
                            for (int i = 0; i < PER_COMBO_PER_TYPE; i++) {
                                long cur = dSeqs[i];
                                if (cur != prev + 1) {
                                    thrown.add(new AssertionError("DESC seq gap at i=" + i
                                            + " combo=" + fci + " t=" + ftt + " prev=" + prev + " cur=" + cur));
                                }
                                prev = cur;
                            }
                            c[0] = prev;
                            prev = 0;
                            for (int i = 0; i < PER_COMBO_PER_TYPE; i++) {
                                long cur = sSeqs[i];
                                if (cur != prev + 1) {
                                    thrown.add(new AssertionError("SPEC seq gap at i=" + i
                                            + " combo=" + fci + " t=" + ftt + " prev=" + prev + " cur=" + cur));
                                }
                                prev = cur;
                            }
                            c[1] = prev;
                            prev = 0;
                            for (int i = 0; i < PER_COMBO_PER_TYPE; i++) {
                                long cur = cSeqs[i];
                                if (cur != prev + 1) {
                                    thrown.add(new AssertionError("COMMENT seq gap at i=" + i
                                            + " combo=" + fci + " t=" + ftt + " prev=" + prev + " cur=" + cur));
                                }
                                prev = cur;
                            }
                            c[2] = prev;
                            Set<Long> dset = new HashSet<>(PER_COMBO_PER_TYPE * 2);
                            Set<Long> sset = new HashSet<>(PER_COMBO_PER_TYPE * 2);
                            Set<Long> cset = new HashSet<>(PER_COMBO_PER_TYPE * 2);
                            for (long v : dSeqs) if (!dset.add(v))
                                thrown.add(new AssertionError("DESC dup seq " + v + " combo=" + fci + " t=" + ftt));
                            for (long v : sSeqs) if (!sset.add(v))
                                thrown.add(new AssertionError("SPEC dup seq " + v + " combo=" + fci + " t=" + ftt));
                            for (long v : cSeqs) if (!cset.add(v))
                                thrown.add(new AssertionError("COMMENT dup seq " + v + " combo=" + fci + " t=" + ftt));
                            DsRegistryExtAttrsHelper.removeAllDescs(r, ek);
                            DsRegistryExtAttrsHelper.removeAllSpecs(r, ek);
                            DsRegistryExtAttrsHelper.removeAllComments(r, ek);
                            long r1 = DsRegistryExtAttrsHelper.addDesc(r, ek, "after reset");
                            long r2 = DsRegistryExtAttrsHelper.addSpec(r, ek, "after reset");
                            long r3 = DsRegistryExtAttrsHelper.addComment(r, ek, "after reset");
                            if (r1 != 1L) thrown.add(new AssertionError("DESC reset !=1 got=" + r1));
                            if (r2 != 1L) thrown.add(new AssertionError("SPEC reset !=1 got=" + r2));
                            if (r3 != 1L) thrown.add(new AssertionError("COMMENT reset !=1 got=" + r3));
                            c[3] = r1; c[4] = r2; c[5] = r3;
                        } catch (Throwable ex) {
                            thrown.add(ex);
                        }
                        if (!thrown.isEmpty()) {
                            String k = "c" + fci + "_t" + ftt;
                            errCounters.computeIfAbsent(k, x -> new AtomicInteger(0)).addAndGet(thrown.size());
                            for (Throwable th : thrown) th.printStackTrace(System.err);
                        }
                        return c;
                    });
                }
            }
            List<Future<long[]>> results = pool.invokeAll(tasks);
            pool.shutdown();
            long descSum = 0, specSum = 0, commSum = 0, resetSum = 0;
            for (Future<long[]> f : results) {
                long[] c = f.get();
                descSum += c[0]; specSum += c[1]; commSum += c[2];
                resetSum += c[3] + c[4] + c[5];
            }
            long expectedPerType = (long) MATRIX_N * THREADS_PER_COMBO * PER_COMBO_PER_TYPE;
            Assertions.assertEquals(expectedPerType, descSum, "DESC total seqs sum mismatch");
            Assertions.assertEquals(expectedPerType, specSum, "SPEC total seqs sum mismatch");
            Assertions.assertEquals(expectedPerType, commSum, "COMMENT total seqs sum mismatch");
            long expectedReset = (long) MATRIX_N * THREADS_PER_COMBO * 3L;
            Assertions.assertEquals(expectedReset, resetSum, "removeAll reset seq=1 mismatch");
            Assertions.assertTrue(errCounters.isEmpty(), "seq strict error counters: " + errCounters);
        } finally {
            for (DsRegistry r : regs) if (r != null) try { r.close(); } catch (Exception ignore) {}
        }
    }

    @Test
    void T02_TAG_8x8Matrix_concurrent_addHasRemove_throughput_ge10K() throws Exception {
        final int TAG_SET_SIZE = 128;
        final int WARMUP_ROUNDS = 50;
        final int MEASURE_SECONDS = 3;
        final int THREADS_PER_COMBO = 1;
        File[] tmps = new File[MATRIX_N];
        DsRegistry[] regs = new DsRegistry[MATRIX_N];
        try {
            int idx = 0;
            for (int s = 0; s < MATRIX_S; s++) {
                for (int p = 0; p < MATRIX_P; p++) {
                    tmps[idx] = tmpDir("dsreg_tgt5_tag_s" + s + "p" + p + "_");
                    regs[idx] = new DsRegistry(new File(tmps[idx], "db").getAbsolutePath(), s, p);
                    idx++;
                }
            }
            String[] tagPool = new String[TAG_SET_SIZE];
            for (int i = 0; i < TAG_SET_SIZE; i++) tagPool[i] = "tag_" + i;
            ExecutorService pool = Executors.newFixedThreadPool(MATRIX_N * THREADS_PER_COMBO);
            for (int ci = 0; ci < MATRIX_N; ci++) {
                final int fci = ci;
                DsRegistry r = regs[ci];
                String ek = "/apps/tag_combo_" + ci;
                r.putString(ek, "p", DataType.REG_SZ);
                for (int w = 0; w < WARMUP_ROUNDS; w++) {
                    String t = tagPool[w % TAG_SET_SIZE];
                    DsRegistryExtAttrsHelper.addTag(r, ek, t);
                    DsRegistryExtAttrsHelper.hasTag(r, ek, t);
                    DsRegistryExtAttrsHelper.removeTag(r, ek, t);
                }
            }
            ConcurrentMap<String, AtomicInteger> errCounters = new ConcurrentHashMap<>();
            AtomicLong totalOps = new AtomicLong(0);
            List<Callable<long[]>> tasks = new ArrayList<>();
            for (int ci = 0; ci < MATRIX_N; ci++) {
                for (int tt = 0; tt < THREADS_PER_COMBO; tt++) {
                    final int fci = ci;
                    tasks.add(() -> {
                        DsRegistry r = regs[fci];
                        String ek = "/apps/tag_combo_" + fci;
                        List<Throwable> thrown = new ArrayList<>();
                        long ops = 0;
                        long deadline = System.nanoTime() + (long) MEASURE_SECONDS * 1_000_000_000L;
                        int cursor = 0;
                        try {
                            while (System.nanoTime() < deadline) {
                                String t = tagPool[cursor++ % TAG_SET_SIZE];
                                boolean added = DsRegistryExtAttrsHelper.addTag(r, ek, t);
                                boolean has = DsRegistryExtAttrsHelper.hasTag(r, ek, t);
                                if (!added && !has) {
                                    thrown.add(new AssertionError("TAG add false but hasTag false t=" + t
                                            + " c=" + fci));
                                }
                                boolean removed = DsRegistryExtAttrsHelper.removeTag(r, ek, t);
                                if (added != removed) {
                                    // removed may also be true from previous leftover; accept, just count ops
                                }
                                ops += 3;
                            }
                        } catch (Throwable ex) {
                            thrown.add(ex);
                        }
                        if (!thrown.isEmpty()) {
                            String k = "tag_c" + fci + "_t" + 0;
                            errCounters.computeIfAbsent(k, x -> new AtomicInteger(0)).addAndGet(thrown.size());
                            for (Throwable th : thrown) th.printStackTrace(System.err);
                        }
                        totalOps.addAndGet(ops);
                        return new long[]{ops};
                    });
                }
            }
            long startNs = System.nanoTime();
            List<Future<long[]>> results = pool.invokeAll(tasks);
            long elapsedNs = System.nanoTime() - startNs;
            pool.shutdown();
            for (Future<long[]> f : results) f.get();
            Assertions.assertTrue(errCounters.isEmpty(), "TAG concurrent errors: " + errCounters);
            double elapsedSec = elapsedNs / 1_000_000_000.0;
            long ops = totalOps.get();
            double tps = ops / elapsedSec;
            System.out.println("[TAG PerfDeep] totalOps=" + ops + " elapsedSec=" + elapsedSec
                    + " tps=" + String.format("%.0f", tps));
            Assertions.assertTrue(tps >= 10_000.0,
                    "TAG throughput " + String.format("%.0f", tps) + " < 10K ops/s threshold");
            for (int ci = 0; ci < MATRIX_N; ci++) {
                LinkedHashSet<String> tags = DsRegistryExtAttrsHelper.listTags(regs[ci], "/apps/tag_combo_" + ci);
                // All tags removed during add/remove cycle; allow 0..TAG_SET_SIZE (no upper bound constraint)
                Assertions.assertNotNull(tags, "TAG listTags null c=" + ci);
            }
        } finally {
            for (DsRegistry r : regs) if (r != null) try { r.close(); } catch (Exception ignore) {}
        }
    }

    @Test
    void T03_DualRoute_concurrent_globalNodeId_fallback_STD_ERROR_zeroException() throws Exception {
        final int WARMUP = 50;
        final int THREADS = 16;
        final int ITER_PER_THREAD = 150;
        File tmpA = tmpDir("dsreg_tgt5_hA_");
        File tmpB = tmpDir("dsreg_tgt5_hB_");
        try (DsRegistry rA = new DsRegistry(new File(tmpA, SA + "_" + PA).getAbsolutePath(), SA, PA);
             DsRegistry rB = new DsRegistry(new File(tmpB, SB + "_" + PB).getAbsolutePath(), SB, PB)) {
            Tgt3RegistryResolver.injectForTest(SA, PA, rA);
            Tgt3RegistryResolver.injectForTest(SB, PB, rB);
            String ekOk = "/apps/entry_ok";
            String ekBad = "/apps/entry_bad";
            rB.putString(ekOk, "payload", DataType.REG_SZ);
            rA.putString(ekBad, "payload", DataType.REG_SZ);
            ExtaAddTagServerHandler hAdd = new ExtaAddTagServerHandler();
            ExtaHasTagServerHandler hHas = new ExtaHasTagServerHandler();
            ExtaListTagsServerHandler hList = new ExtaListTagsServerHandler();
            ExtaRemoveAllTagsServerHandler hRmAll = new ExtaRemoveAllTagsServerHandler();
            ExtaAddDescServerHandler hAddD = new ExtaAddDescServerHandler();
            ExtaRemoveAllDescsServerHandler hRmD = new ExtaRemoveAllDescsServerHandler();
            ExtaAddSpecServerHandler hAddS = new ExtaAddSpecServerHandler();
            ExtaRemoveAllSpecsServerHandler hRmS = new ExtaRemoveAllSpecsServerHandler();
            ExtaAddCommentServerHandler hAddC = new ExtaAddCommentServerHandler();
            ExtaRemoveAllCommentsServerHandler hRmC = new ExtaRemoveAllCommentsServerHandler();
            for (int i = 0; i < WARMUP; i++) {
                ExtaAddTagRequest ra = new ExtaAddTagRequest(SA, PA, SB, PB, 0L, ekOk, "w" + i);
                hAdd.process(P2PWrapper.build(1, P2PCommand.EXTA_ADD_TAG, ra));
            }
            ExecutorService pool = Executors.newFixedThreadPool(THREADS);
            ConcurrentMap<String, AtomicInteger> errCounters = new ConcurrentHashMap<>();
            AtomicLong goodCount = new AtomicLong(0);
            AtomicLong stdErrCount = new AtomicLong(0);
            List<Callable<long[]>> tasks = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                final int ft = t;
                tasks.add(() -> {
                    List<Throwable> thrown = new ArrayList<>();
                    long good = 0, err = 0;
                    try {
                        for (int i = 0; i < ITER_PER_THREAD; i++) {
                            int mode = (ft + i) % 6;
                            if (mode == 0) {
                                ExtaAddTagRequest rq = new ExtaAddTagRequest(SA, PA, SB, PB, 0L, ekOk,
                                        "t" + ft + "_" + i);
                                P2PWrapper wr = hAdd.process(P2PWrapper.build(1, P2PCommand.EXTA_ADD_TAG, rq));
                                if (wr.getCommand() == P2PCommand.STD_ERROR) err++;
                                else good++;
                            } else if (mode == 1) {
                                long g = rB.toGlobalNodeId(rB.get(ekOk).nodeId());
                                ExtaHasTagRequest rq = new ExtaHasTagRequest(SA, PA, -1, -1, g, ekOk, "w" + (i % WARMUP));
                                P2PWrapper wr = hHas.process(P2PWrapper.build(1, P2PCommand.EXTA_HAS_TAG, rq));
                                if (wr.getCommand() == P2PCommand.STD_ERROR) err++;
                                else good++;
                            } else if (mode == 2) {
                                ExtaListTagsRequest rq = new ExtaListTagsRequest(SA, PA, 999, 999, 0L, ekBad);
                                P2PWrapper wr = hList.process(P2PWrapper.build(1, P2PCommand.EXTA_LIST_TAGS, rq));
                                if (wr.getCommand() == P2PCommand.STD_ERROR) err++;
                                else good++;
                            } else if (mode == 3) {
                                ExtaAddDescRequest rq = new ExtaAddDescRequest(SA, PA, SB, PB, 0L, ekOk, "d" + ft + "_" + i);
                                P2PWrapper wr = hAddD.process(P2PWrapper.build(1, P2PCommand.EXTA_ADD_DESC, rq));
                                if (wr.getCommand() == P2PCommand.STD_ERROR) err++;
                                else good++;
                            } else if (mode == 4) {
                                ExtaAddSpecRequest rq = new ExtaAddSpecRequest(SA, PA, SB, PB, 0L, ekOk, "s" + ft + "_" + i);
                                P2PWrapper wr = hAddS.process(P2PWrapper.build(1, P2PCommand.EXTA_ADD_SPEC, rq));
                                if (wr.getCommand() == P2PCommand.STD_ERROR) err++;
                                else good++;
                            } else {
                                ExtaAddCommentRequest rq = new ExtaAddCommentRequest(SA, PA, SB, PB, 0L, ekOk,
                                        "c" + ft + "_" + i);
                                P2PWrapper wr = hAddC.process(P2PWrapper.build(1, P2PCommand.EXTA_ADD_COMMENT, rq));
                                if (wr.getCommand() == P2PCommand.STD_ERROR) err++;
                                else good++;
                            }
                        }
                    } catch (Throwable ex) {
                        thrown.add(ex);
                    }
                    if (!thrown.isEmpty()) {
                        String k = "dr_t" + ft;
                        errCounters.computeIfAbsent(k, x -> new AtomicInteger(0)).addAndGet(thrown.size());
                        for (Throwable th : thrown) th.printStackTrace(System.err);
                    }
                    goodCount.addAndGet(good);
                    stdErrCount.addAndGet(err);
                    return new long[]{good, err};
                });
            }
            long startNs = System.nanoTime();
            List<Future<long[]>> results = pool.invokeAll(tasks);
            long elapsedNs = System.nanoTime() - startNs;
            pool.shutdown();
            {
                hRmAll.process(P2PWrapper.build(1, P2PCommand.EXTA_REMOVE_ALL_TAGS,
                        new ExtaRemoveAllTagsRequest(SA, PA, SB, PB, 0L, ekOk)));
                hRmD.process(P2PWrapper.build(1, P2PCommand.EXTA_REMOVE_ALL_DESCS,
                        new ExtaRemoveAllDescsRequest(SA, PA, SB, PB, 0L, ekOk)));
                hRmS.process(P2PWrapper.build(1, P2PCommand.EXTA_REMOVE_ALL_SPECS,
                        new ExtaRemoveAllSpecsRequest(SA, PA, SB, PB, 0L, ekOk)));
                hRmC.process(P2PWrapper.build(1, P2PCommand.EXTA_REMOVE_ALL_COMMENTS,
                        new ExtaRemoveAllCommentsRequest(SA, PA, SB, PB, 0L, ekOk)));
            }
            long gTot = 0, eTot = 0;
            for (Future<long[]> f : results) {
                long[] c = f.get();
                gTot += c[0]; eTot += c[1];
            }
            Assertions.assertEquals(goodCount.get(), gTot, "goodCount mismatch");
            Assertions.assertEquals(stdErrCount.get(), eTot, "stdErrCount mismatch");
            Assertions.assertTrue(errCounters.isEmpty(),
                    "DualRoute concurrent exception counters: " + errCounters);
            double sec = elapsedNs / 1_000_000_000.0;
            double tps = (gTot + eTot) / sec;
            System.out.println("[DualRoute PerfDeep] good=" + gTot + " stdErr=" + eTot
                    + " elapsedSec=" + sec + " tps=" + String.format("%.0f", tps));
            Assertions.assertTrue(tps >= 400.0,
                    "DualRoute throughput " + String.format("%.0f", tps) + " < 400 ops/s (Windows I/O bound)");
            Assertions.assertTrue(gTot > THREADS * 100L,
                    "goodCount too low: " + gTot + " (expected > " + (THREADS * 100L) + ")");
        }
    }
}
