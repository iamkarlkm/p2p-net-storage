package com.q3lives.ds.collections;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class DsHashMapRangeTest {

    private File tempDir;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("dshm-range-").toFile();
    }

    @After
    public void tearDown() {
        if (tempDir != null && tempDir.exists()) {
            for (File f : tempDir.listFiles()) {
                f.delete();
            }
            tempDir.delete();
        }
    }

    private DsHashMap open(String name) throws IOException {
        return new DsHashMap(new File(tempDir, name));
    }

    private static long[] toKeyArray(List<Map.Entry<Long, Long>> list) {
        long[] out = new long[list.size()];
        for (int i = 0; i < list.size(); i++) {
            out[i] = list.get(i).getKey().longValue();
        }
        return out;
    }

    private static long[] sortedLongKeys(NavigableMap<Long, Long> tm) {
        long[] out = new long[tm.size()];
        Iterator<Map.Entry<Long, Long>> it = tm.entrySet().iterator();
        int i = 0;
        while (it.hasNext()) {
            out[i++] = it.next().getKey();
        }
        return out;
    }

    private static long[] tailKeys(NavigableMap<Long, Long> tm, long fromKey, boolean inclusive, int limit) {
        List<Long> out = new ArrayList<>();
        Iterator<Map.Entry<Long, Long>> it = tm.tailMap(fromKey, inclusive).entrySet().iterator();
        while (it.hasNext() && out.size() < limit) {
            out.add(it.next().getKey());
        }
        long[] arr = new long[out.size()];
        for (int i = 0; i < out.size(); i++) arr[i] = out.get(i).longValue();
        return arr;
    }

    private static long[] headLastKeys(NavigableMap<Long, Long> tm, long toKey, boolean inclusive, int limit) {
        NavigableMap<Long, Long> head = tm.headMap(toKey, inclusive);
        int skip = Math.max(0, head.size() - limit);
        List<Long> out = new ArrayList<>();
        Iterator<Map.Entry<Long, Long>> it = head.entrySet().iterator();
        int i = 0;
        while (it.hasNext()) {
            Map.Entry<Long, Long> e = it.next();
            if (i >= skip) {
                out.add(e.getKey());
            }
            i++;
        }
        long[] arr = new long[out.size()];
        for (int k = 0; k < out.size(); k++) arr[k] = out.get(k).longValue();
        return arr;
    }

    @Test
    public void shouldRangeAndFirstLastMatchTreeMap16Dense() throws IOException {
        long[] keys = new long[220];
        for (int i = 0; i < keys.length; i++) keys[i] = 219 - i;
        NavigableMap<Long, Long> tm = new TreeMap<>();
        DsHashMap map = open("dense16");
        try {
            for (long k : keys) {
                long v = k * 11L;
                map.put(k, v);
                tm.put(k, v);
            }
            long[] gold = sortedLongKeys(tm);
            long[] actual = toKeyArray(map.range(0, tm.size()));
            Assert.assertArrayEquals("full range dense16", gold, actual);
            long[] mid = toKeyArray(map.range(50, 100));
            long[] midGold = Arrays.copyOfRange(gold, 50, 150);
            Assert.assertArrayEquals("mid range 50+100 dense16", midGold, mid);
            Assert.assertEquals("firstKey dense16", gold[0], map.firstKey().longValue());
            Assert.assertEquals("lastKey dense16", gold[gold.length - 1], map.lastKey().longValue());
        } finally {
            map.syncAll();
            map.close();
        }
    }

    @Test
    public void shouldGtGteLtLteMatchTreeMapCross16And32() throws IOException {
        long[] base = new long[40];
        int p = 0;
        for (int i = 0; i < 10; i++) base[p++] = 0xFFF0L + i;
        for (int i = 0; i < 20; i++) base[p++] = 0xFFFFL - 10L + i;
        for (int i = 0; i < 10; i++) base[p++] = 0x1_0000L + i * 7L;
        NavigableMap<Long, Long> tm = new TreeMap<>();
        DsHashMap map = open("cross1632");
        try {
            for (long k : base) {
                long v = ~k;
                map.put(k, v);
                tm.put(k, v);
            }
            long pivotIn16 = 0xFFF8L;
            Assert.assertArrayEquals("gte pivotIn16 limit=50",
                tailKeys(tm, pivotIn16, true, 50),
                toKeyArray(map.gte(pivotIn16, 50)));
            Assert.assertArrayEquals("gt pivotIn16 limit=50",
                tailKeys(tm, pivotIn16, false, 50),
                toKeyArray(map.gt(pivotIn16, 50)));
            long pivotCross = 0xFFFFL;
            Assert.assertArrayEquals("gte cross 0xFFFF limit=50",
                tailKeys(tm, pivotCross, true, 50),
                toKeyArray(map.gte(pivotCross, 50)));
            Assert.assertArrayEquals("gt cross 0xFFFF limit=50",
                tailKeys(tm, pivotCross, false, 50),
                toKeyArray(map.gt(pivotCross, 50)));
            Assert.assertArrayEquals("lt cross pivot limit=8",
                headLastKeys(tm, pivotCross, false, 8),
                toKeyArray(map.lt(pivotCross, 8)));
            Assert.assertArrayEquals("lte cross pivot limit=20",
                headLastKeys(tm, pivotCross, true, 20),
                toKeyArray(map.lte(pivotCross, 20)));
        } finally {
            map.syncAll();
            map.close();
        }
    }

    @Test
    public void shouldNavigableKeysMatchTreeMapAcrossAllSegments() throws IOException {
        long[] keys = new long[]{
            Long.MIN_VALUE, Long.MIN_VALUE + 1,
            -999999999999L, -10000L, -1L,
            0L, 100L, 65534L, 65535L,
            65536L, 0xFFFF_FFFFL - 10L, 0xFFFF_FFFFL,
            0x1_0000_0000L, 0x1_0000_0001L, Long.MAX_VALUE - 1, Long.MAX_VALUE
        };
        NavigableMap<Long, Long> tm = new TreeMap<>();
        DsHashMap map = open("allseg");
        try {
            for (long k : keys) {
                long v = k ^ 0x9E3779B97F4A7C15L;
                map.put(k, v);
                tm.put(k, v);
            }
            long[] gold = sortedLongKeys(tm);
            Assert.assertArrayEquals("full ordered allseg", gold, toKeyArray(map.range(0, tm.size())));
            for (long k : keys) {
                Long tCeil = tm.ceilingKey(k);
                Long mCeil = map.ceilingKey(k);
                Assert.assertEquals("ceiling " + k, tCeil, mCeil);
                Long tHigh = tm.higherKey(k);
                Long mHigh = map.higherKey(k);
                Assert.assertEquals("higher " + k, tHigh, mHigh);
                Long tFlr = tm.floorKey(k);
                Long mFlr = map.floorKey(k);
                Assert.assertEquals("floor " + k, tFlr, mFlr);
                Long tLow = tm.lowerKey(k);
                Long mLow = map.lowerKey(k);
                Assert.assertEquals("lower " + k, tLow, mLow);
            }
            long[] midKeys = new long[]{Long.MIN_VALUE + 2, -5000L, -2L, 50L, 65533L, 70000L, 0xFFFF_FFFEL, 0x1_0000_0002L};
            for (long k : midKeys) {
                Long tCeil = tm.ceilingKey(k);
                Long mCeil = map.ceilingKey(k);
                Assert.assertEquals("ceiling missing " + k, tCeil, mCeil);
                Long tHigh = tm.higherKey(k);
                Long mHigh = map.higherKey(k);
                Assert.assertEquals("higher missing " + k, tHigh, mHigh);
                Long tFlr = tm.floorKey(k);
                Long mFlr = map.floorKey(k);
                Assert.assertEquals("floor missing " + k, tFlr, mFlr);
                Long tLow = tm.lowerKey(k);
                Long mLow = map.lowerKey(k);
                Assert.assertEquals("lower missing " + k, tLow, mLow);
            }
            long from = -10000L;
            long to = 0x1_0000L;
            long[] subGold = new long[tm.subMap(from, true, to, false).size()];
            Iterator<Map.Entry<Long, Long>> it = tm.subMap(from, true, to, false).entrySet().iterator();
            int i = 0;
            while (it.hasNext()) subGold[i++] = it.next().getKey();
            long[] subActual = toKeyArray(map.subMap(from, true, to, false, Integer.MAX_VALUE));
            Assert.assertArrayEquals("subMap[-10000, 0x10000)", subGold, subActual);
        } finally {
            map.syncAll();
            map.close();
        }
    }

    @Test
    public void shouldForEachKeyRangeAndForEachRangeBeConsistentWithIndexRange() throws IOException {
        int n = 180;
        long[] keys = new long[n];
        NavigableMap<Long, Long> tm = new TreeMap<>();
        DsHashMap map = open("consistent");
        try {
            for (int i = 0; i < n; i++) {
                long k = (((long) i * 2654435761L) & 0x7FFF_FFFF_0000_0000L) >>> 16;
                if (k < 0) k = ~k;
                keys[i] = k;
                long v = k * 3L;
                map.put(k, v);
                tm.put(k, v);
            }
            long[] gold = sortedLongKeys(tm);
            long[] byIndex = toKeyArray(map.range(0, tm.size()));
            Assert.assertArrayEquals("index range full", gold, byIndex);
            List<Long> byKeyRange = new ArrayList<>();
            map.forEachKeyRange(Long.MIN_VALUE, true, Long.MAX_VALUE, true, Integer.MAX_VALUE, (k, v) -> byKeyRange.add(k));
            long[] byKeyArr = new long[byKeyRange.size()];
            for (int j = 0; j < byKeyRange.size(); j++) byKeyArr[j] = byKeyRange.get(j).longValue();
            Assert.assertArrayEquals("key range full", gold, byKeyArr);
            List<Long> byForEachIdx = new ArrayList<>();
            map.forEachRange(20, 40, (k, v) -> byForEachIdx.add(k));
            long[] forEachIdxArr = new long[byForEachIdx.size()];
            for (int j = 0; j < byForEachIdx.size(); j++) forEachIdxArr[j] = byForEachIdx.get(j).longValue();
            long[] idxGold = Arrays.copyOfRange(gold, 20, 60);
            Assert.assertArrayEquals("forEachRange(20,40)", idxGold, forEachIdxArr);
        } finally {
            map.syncAll();
            map.close();
        }
    }

    @Test
    public void shouldNavigableOnEmptyAndSingletonMapsReturnCorrectNulls() throws IOException {
        DsHashMap empty = open("empty");
        try {
            Assert.assertNull("empty ceiling", empty.ceilingKey(0L));
            Assert.assertNull("empty higher", empty.higherKey(0L));
            Assert.assertNull("empty floor", empty.floorKey(0L));
            Assert.assertNull("empty lower", empty.lowerKey(0L));
            Assert.assertNull("empty first", empty.firstKey());
            Assert.assertNull("empty last", empty.lastKey());
            Assert.assertTrue("empty gt", empty.gt(0L, 5).isEmpty());
            Assert.assertTrue("empty lt", empty.lt(0L, 5).isEmpty());
        } finally {
            empty.syncAll();
            empty.close();
        }
        DsHashMap single = open("single");
        try {
            single.put(42L, 100L);
            Assert.assertEquals("single ceiling(42)", Long.valueOf(42L), single.ceilingKey(42L));
            Assert.assertNull("single higher(42)", single.higherKey(42L));
            Assert.assertEquals("single floor(42)", Long.valueOf(42L), single.floorKey(42L));
            Assert.assertNull("single lower(42)", single.lowerKey(42L));
            Assert.assertEquals("single ceiling(10)", Long.valueOf(42L), single.ceilingKey(10L));
            Assert.assertEquals("single floor(100)", Long.valueOf(42L), single.floorKey(100L));
        } finally {
            single.syncAll();
            single.close();
        }
    }
}
