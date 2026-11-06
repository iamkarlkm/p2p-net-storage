package ds;

import com.q3lives.ds.core.DsFreeRing;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class DsFreeRingAsInodeSetTest {

    private static DsFreeRing openTemp(int initialCap) throws Exception {
        File base = File.createTempFile("test_dsfreering_inodeset_", ".dat");
        base.deleteOnExit();
        File freeFile = new File(base.getAbsolutePath() + ".free");
        if (freeFile.exists()) freeFile.delete();
        return new DsFreeRing(freeFile, initialCap);
    }

    @Test
    public void testContainsAndOfferUniqueLinear() throws Exception {
        try (DsFreeRing ring = openTemp(8)) {
            assertTrue(ring.offerUnique(1001L));
            assertTrue(ring.offerUnique(2002L));
            assertTrue(ring.offerUnique(3003L));
            assertFalse(ring.offerUnique(1001L));
            assertFalse(ring.offerUnique(2002L));
            assertEquals(3L, ring.count());
            assertEquals(3L, ring.size());

            assertTrue(ring.contains(1001L));
            assertTrue(ring.contains(2002L));
            assertTrue(ring.contains(3003L));
            assertFalse(ring.contains(4004L));
            assertFalse(ring.contains(-1L));
            assertFalse(ring.contains(0L));
        }
    }

    @Test
    public void testOfferUniqueWrapAroundAfterPolls() throws Exception {
        try (DsFreeRing ring = openTemp(4)) {
            assertTrue(ring.offerUnique(10L));
            assertTrue(ring.offerUnique(20L));
            assertTrue(ring.offerUnique(30L));
            assertTrue(ring.offerUnique(40L));
            assertEquals(10L, ring.poll());
            assertEquals(20L, ring.poll());
            // 环形空间已被使用 [0,4) 然后 poll 两次，head=2,tail=0,count=2,cap=4 (head>tail 段)
            assertTrue(ring.offerUnique(50L));
            assertTrue(ring.offerUnique(60L));
            // 现在 count=4, head=2, tail=2, cap=4 ，环形两段满 (head==tail 但 count=cap)
            assertFalse(ring.offerUnique(30L));
            assertFalse(ring.offerUnique(40L));
            assertFalse(ring.offerUnique(50L));
            assertFalse(ring.offerUnique(60L));
            assertTrue(ring.contains(30L));
            assertTrue(ring.contains(40L));
            assertTrue(ring.contains(50L));
            assertTrue(ring.contains(60L));
            assertEquals(4L, ring.count());
        }
    }

    @Test
    public void testSnapshotVsHashSetOracleThroughout() throws Exception {
        try (DsFreeRing ring = openTemp(4)) {
            Set<Long> oracle = new HashSet<>();
            long base = 1_000_000_000_000L;
            for (int i = 0; i < 80; i++) {
                long inode = base + (i * 7L % 100);
                boolean a = ring.offerUnique(inode);
                boolean b = oracle.add(inode);
                assertEquals("step=" + i + " inode=" + inode, b, a);
            }
            for (long v : new long[]{base, base + 7, base + 13, base + 23, base + 29, base + 57}) {
                assertEquals("contains " + v, oracle.contains(v), ring.contains(v));
            }
            assertEquals(oracle.size(), ring.count());
            long[] snap = ring.snapshot();
            assertEquals(oracle.size(), snap.length);
            Set<Long> snapSet = new HashSet<>();
            for (long v : snap) snapSet.add(v);
            assertEquals(oracle, snapSet);
        }
    }

    @Test
    public void testRemoveHeadMiddleTailAndThenSnapshot() throws Exception {
        try (DsFreeRing ring = openTemp(4)) {
            for (long v : new long[]{10, 20, 30, 40, 50, 60}) ring.offerUnique(v);
            assertEquals(6, ring.count());
            // 线性阶段 head=0,tail=6,cap=8 after expand
            assertTrue(ring.remove(20L));
            assertTrue(ring.remove(40L));
            assertTrue(ring.remove(60L));
            assertFalse(ring.remove(20L));
            assertFalse(ring.remove(0L));
            assertEquals(3, ring.count());
            long[] snap = ring.snapshot();
            Arrays.sort(snap);
            assertArrayEquals(new long[]{10L, 30L, 50L}, snap);
        }
    }

    @Test
    public void testRemoveInWrapAroundPhase() throws Exception {
        try (DsFreeRing ring = openTemp(4)) {
            // 驱动进入 head>tail wrap: offer 4, poll 2, offer 2
            ring.offerUnique(1L);
            ring.offerUnique(2L);
            ring.offerUnique(3L);
            ring.offerUnique(4L);
            ring.poll(); // 1
            ring.poll(); // 2
            ring.offerUnique(5L); // tail=1
            ring.offerUnique(6L); // tail=2, head=2, count=4, cap=4 full, head==tail wrap
            // contents: 3(@2),4(@3),5(@0),6(@1)
            assertTrue(ring.contains(3L));
            assertTrue(ring.contains(4L));
            assertTrue(ring.contains(5L));
            assertTrue(ring.contains(6L));
            assertFalse(ring.contains(1L));
            assertFalse(ring.contains(2L));

            assertTrue(ring.remove(4L)); // slot@3, replaced with last slot@1 (6)
            assertFalse(ring.contains(4L));
            assertTrue(ring.contains(6L)); // still there
            assertEquals(3, ring.count());

            assertTrue(ring.remove(5L)); // slot@0, replaced with last slot@3 (now was 6? verify)
            assertFalse(ring.contains(5L));
            assertEquals(2, ring.count());
            long[] s = ring.snapshot();
            Arrays.sort(s);
            assertArrayEquals(new long[]{3L, 6L}, s);
        }
    }

    @Test
    public void testPersistenceReloadSetSemanticsAcrossClose() throws Exception {
        File base = File.createTempFile("test_dsfreering_reload_", ".dat");
        base.deleteOnExit();
        File freeFile = new File(base.getAbsolutePath() + ".free");
        if (freeFile.exists()) freeFile.delete();

        try (DsFreeRing ring = new DsFreeRing(freeFile, 2)) {
            for (long v : new long[]{100L, 200L, 300L, 400L}) ring.offerUnique(v);
            ring.remove(200L);
            assertEquals(3, ring.count());
        }
        try (DsFreeRing ring2 = new DsFreeRing(freeFile, 2)) {
            assertEquals(3L, ring2.count());
            assertTrue(ring2.contains(100L));
            assertFalse(ring2.contains(200L));
            assertTrue(ring2.contains(300L));
            assertTrue(ring2.contains(400L));
            assertFalse(ring2.offerUnique(100L));
            assertTrue(ring2.offerUnique(500L));
            assertEquals(4L, ring2.count());
        }
    }

    @Test
    public void testExistingQueueSemanticsStillHoldsRegression() throws Exception {
        // 保证旧的 offer/poll FIFO + expand + 持久化 不被 Set 改动影响
        File base = File.createTempFile("test_dsfreering_queue_reg_", ".dat");
        base.deleteOnExit();
        File freeFile = new File(base.getAbsolutePath() + ".free");
        if (freeFile.exists()) freeFile.delete();

        try (DsFreeRing ring = new DsFreeRing(freeFile, 2)) {
            assertEquals(0L, ring.count());
            assertTrue(ring.offer(10L));
            assertTrue(ring.offer(20L));
            assertTrue(ring.offer(30L)); // cap 2 -> 4
            assertEquals(3L, ring.count());
            assertEquals(10L, ring.poll());
            assertEquals(20L, ring.poll());
            assertEquals(30L, ring.poll());
            assertEquals(-1L, ring.poll());
            assertEquals(0L, ring.count());

            assertTrue(ring.offer(1L));
            assertTrue(ring.offer(2L));
        }
        try (DsFreeRing ring2 = new DsFreeRing(freeFile, 2)) {
            assertEquals(2L, ring2.count());
            assertEquals(1L, ring2.poll());
            assertEquals(2L, ring2.poll());
            assertEquals(-1L, ring2.poll());
            assertEquals(0L, ring2.count());
        }
    }

    @Test
    public void testExpandWhileInWrapPhasePreservesAllValues() throws Exception {
        try (DsFreeRing ring = openTemp(4)) {
            // cap=4: poll 后 wrap 满再 expand
            for (long v = 1; v <= 4; v++) ring.offerUnique(v);
            ring.poll(); // 1
            ring.poll(); // 2
            ring.offerUnique(5L);
            ring.offerUnique(6L);
            // count=4, head=2, tail=2, cap=4 (环形满) — 元素是 3,4,5,6
            assertTrue(ring.offerUnique(7L)); // 触发 expand cap=8, 拉平 head=0,tail=5
            assertEquals(5, ring.count());
            Set<Long> expected = new HashSet<>(Arrays.asList(3L, 4L, 5L, 6L, 7L));
            Set<Long> actual = new HashSet<>();
            for (long v : ring.snapshot()) actual.add(v);
            assertEquals(expected, actual);
            // FIFO 顺序再保证
            assertEquals(3L, ring.poll());
            assertEquals(4L, ring.poll());
            assertEquals(5L, ring.poll());
            assertEquals(6L, ring.poll());
            assertEquals(7L, ring.poll());
            assertEquals(-1L, ring.poll());
        }
    }
}
