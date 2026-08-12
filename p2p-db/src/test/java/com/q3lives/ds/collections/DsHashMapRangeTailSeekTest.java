package com.q3lives.ds.collections;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class DsHashMapRangeTailSeekTest {
    private File tempDir;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("dshm-tailseek-").toFile();
    }

    @After
    public void tearDown() {
        if (tempDir != null && tempDir.exists()) {
            File[] fs = tempDir.listFiles();
            if (fs != null) for (File f : fs) f.delete();
            tempDir.delete();
        }
    }

    private DsHashMap open(String name) throws IOException {
        return new DsHashMap(new File(tempDir, name));
    }

    @Test
    public void shouldRangeWithLargeStartNotDegenerateToFullScan() throws IOException {
        int N = 200_000;
        TreeMap<Long, Long> gold = new TreeMap<>();
        long seed = 42L;
        for (int i = 0; i < N; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            long k = seed & 0x7FFFFFFFFFFFFFFFL;
            gold.put(k, k * 3L + 7L);
        }
        List<Map.Entry<Long, Long>> goldEntries = new ArrayList<>(gold.entrySet());
        DsHashMap map = open("tailseek-big");
        for (Map.Entry<Long, Long> e : goldEntries) {
            map.put(e.getKey(), e.getValue());
        }
        assertEquals(N, map.sizeLong());

        int start = N - 1000;
        int count = 1000;
        long t0 = System.nanoTime();
        List<Map.Entry<Long, Long>> r = map.range(start, count);
        long dtSeek = System.nanoTime() - t0;
        assertEquals(count, r.size());
        for (int i = 0; i < count; i++) {
            Map.Entry<Long, Long> ge = goldEntries.get(start + i);
            assertEquals(ge.getKey(), r.get(i).getKey());
            assertEquals(ge.getValue(), r.get(i).getValue());
        }

        t0 = System.nanoTime();
        List<Map.Entry<Long, Long>> rHead = map.range(0, count);
        long dtHead = System.nanoTime() - t0;
        assertEquals(count, rHead.size());
        for (int i = 0; i < count; i++) {
            assertEquals(goldEntries.get(i).getKey(), rHead.get(i).getKey());
        }

        long ratio = (long) ((double) dtSeek / Math.max(1L, dtHead));
        System.out.println("head(0,1000) " + dtHead / 1000 + " us | range(" + start + "," + count + ") " + dtSeek / 1000 + " us | ratio(seek/head) " + ratio);
        assertTrue("range(" + start + "," + count + ") expected to be <= 6x of head range, got " + ratio + "x (degenerate O(N) scan would be ~180x)", ratio <= 6);
    }

    @Test
    public void shouldForEachRangeStart90pctProduceLast10pct() throws IOException {
        int N = 50_000;
        DsHashMap map = open("tailseek-90pct");
        TreeMap<Long, Long> gold = new TreeMap<>();
        for (long k = 0; k < N; k++) {
            long v = k * 11L + 1L;
            gold.put(k, v);
            map.put(k, v);
        }
        assertEquals(N, map.sizeLong());
        int start = (int) (N * 0.9);
        int count = N - start;
        long t0 = System.nanoTime();
        List<Long> got = new ArrayList<>(count);
        int emitted = map.forEachRange(start, count, (k, v) -> got.add(k));
        long dt = System.nanoTime() - t0;
        assertEquals(count, emitted);
        assertEquals(count, got.size());
        long k = start;
        for (long gk : got) {
            assertEquals(Long.valueOf(k++), Long.valueOf(gk));
        }
        System.out.println("forEachRange(90%=" + start + "," + count + ") " + dt / 1000 + " us for N=" + N);
    }

    @Test
    public void shouldForEachRangeConsistentWithRangeStartVariousPositions() throws IOException {
        int N = 2000;
        DsHashMap map = open("tailseek-various");
        TreeMap<Long, Long> gold = new TreeMap<>();
        long seed = 7L;
        for (int i = 0; i < N; i++) {
            seed = seed * 2862933555777941757L + 3037000493L;
            long k = seed ^ (seed >>> 33);
            gold.put(k, k | 1L);
        }
        List<Map.Entry<Long, Long>> entries = new ArrayList<>(gold.entrySet());
        for (Map.Entry<Long, Long> e : entries) {
            map.put(e.getKey(), e.getValue());
        }
        assertEquals(entries.size(), map.sizeLong());
        int[] starts = new int[]{0, 1, N / 4, N / 2, 3 * N / 4, N - 10, N - 1};
        for (int s : starts) {
            int c = Math.min(200, N - s);
            List<Map.Entry<Long, Long>> r1 = map.range(s, c);
            int emitted = map.forEachRange(s, c, (k, v) -> { });
            assertEquals("forEachRange returned wrong emitted for start=" + s, Math.min(c, N - s), emitted);
            long[] k2 = new long[emitted];
            int[] idx = {0};
            map.forEachRange(s, c, (k, v) -> { k2[idx[0]++] = k; });
            assertEquals(r1.size(), emitted);
            for (int j = 0; j < r1.size(); j++) {
                assertEquals("mismatch at start=" + s + " idx=" + j, r1.get(j).getKey().longValue(), k2[j]);
            }
        }
    }
}
