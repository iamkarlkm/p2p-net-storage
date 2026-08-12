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
import org.junit.Before;
import org.junit.Test;

public class DsHashMapRangeDebug2Test {
    private File tempDir;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("dshm-dbg2-").toFile();
    }

    @After
    public void tearDown() {
        if (tempDir != null && tempDir.exists()) {
            File[] fs = tempDir.listFiles();
            if (fs != null) for (File f : fs) f.delete();
            tempDir.delete();
        }
    }

    @Test
    public void debugAllSeg16Counts() throws Exception {
        long nonNegSplit = 0x1_0000_0000L;
        long[] keys = new long[]{
            Long.MIN_VALUE, Long.MIN_VALUE + 1,
            -999999999999L, -10000L, -1L,
            0L, 100L, 65534L, 65535L,
            65536L, 0xFFFF_FFFFL - 10L, 0xFFFF_FFFFL,
            0x1_0000_0000L, 0x1_0000_0001L, Long.MAX_VALUE - 1, Long.MAX_VALUE
        };
        NavigableMap<Long, Long> tm = new TreeMap<>();
        DsHashMap map = new DsHashMap(new File(tempDir, "allseg"));
        try {
            for (long k : keys) {
                long v = k ^ 0x9E3779B97F4A7C15L;
                map.put(k, v);
                tm.put(k, v);
            }
            System.out.println("sizeLong=" + map.sizeLong() + " tm.size=" + tm.size());
            java.lang.reflect.Field nextHashMapF = DsHashMap.class.getDeclaredField("nextHashMap");
            java.lang.reflect.Field nextHashMap64F = DsHashMap.class.getDeclaredField("nextHashMap64");
            java.lang.reflect.Field hashOffsetF = DsHashMap.class.getDeclaredField("hashOffset");
            java.lang.reflect.Field hashLenF = DsHashMap.class.getDeclaredField("hashLen");
            java.lang.reflect.Field sizeF = DsHashMap.class.getDeclaredField("size");
            nextHashMapF.setAccessible(true);
            nextHashMap64F.setAccessible(true);
            hashOffsetF.setAccessible(true);
            hashLenF.setAccessible(true);
            sizeF.setAccessible(true);
            DsHashMap nextMap = (DsHashMap) nextHashMapF.get(map);
            DsHashMap nextMap64 = (DsHashMap) nextHashMap64F.get(map);
            System.out.println("map.this.size(16级) = " + sizeF.get(map)
                + "   nextHashMap(32级).size=" + sizeF.get(nextMap)
                + "   nextHashMap64.size=" + sizeF.get(nextMap64));
            for (int i = 0; i < 3; i++) {
                DsHashMap m = i == 0 ? map : (i == 1 ? nextMap : nextMap64);
                String tag = i == 0 ? "this(16)" : i == 1 ? "nextMap(32)" : "nextMap64(64)";
                System.out.println("[" + tag + "] sizeF=" + sizeF.get(m)
                    + "  hashOffset=" + hashOffsetF.get(m)
                    + " hashLen=" + hashLenF.get(m)
                    + "  .sizeLong()=" + m.sizeLong());
            }
            long seg16Raw = callInternalCountSubtree(map);
            long seg32Raw = callInternalCountSubtree(nextMap);
            long seg64Raw = callInternalCountSubtree(nextMap64);
            System.out.println("internalCountSubtree -> this(16) = " + seg16Raw + "  next32 = " + seg32Raw + " next64 = " + seg64Raw);
            Object ctx = newTraversalContext();
            long negSize = (Long) invokeInternalIndexOfCeiling(nextMap64, 0L, ctx);
            System.out.println("nextHashMap64.internalIndexOfCeiling(0) = " + negSize + "   (pos64 half = " + (seg64Raw - negSize) + ")");
            System.out.println("---- collectKeysWithNextLevel diagnostics for this(16) + nextMap(32) ----");
            {
                Object ctxLocal = newTraversalContext();
                long cnt = (Long) invokeInternalCountSubtree(map, ctxLocal);
                System.out.println("this(16).internalCountSubtree(基础 trie 计数，不含 STATE_NEXT_LEVEL 升级的 nextHashMap 条目) = " + cnt);
            }
            {
                System.out.println("[deep debug nextHashMapCollectKeys with startLevel and prefix]");
                Object nhm32 = nextMap;
                if (nhm32 != null) {
                    java.lang.reflect.Method mth = nhm32.getClass().getDeclaredMethod("countSubtreeIncludingNextLevel", long.class, int.class, getTraversalContextClass());
                    mth.setAccessible(true);
                    Object ctx32 = newTraversalContext();
                    int ho = (Integer) hashOffsetF.get(nhm32);
                    Number num = (Number) mth.invoke(nhm32, 0L, ho, ctx32);
                    long result = num.longValue();
                    System.out.println("  nextMap(32).countSubtreeIncludingNextLevel(root,hashOffset=" + ho + ") = " + result);
                }
                Object nhm = map;
                if (nhm != null) {
                    java.lang.reflect.Method mth = nhm.getClass().getDeclaredMethod("countSubtreeIncludingNextLevel", long.class, int.class, getTraversalContextClass());
                    mth.setAccessible(true);
                    Object ctxThis = newTraversalContext();
                    int ho = (Integer) hashOffsetF.get(nhm);
                    Number num = (Number) mth.invoke(nhm, 0L, ho, ctxThis);
                    long result = num.longValue();
                    System.out.println("  this(16).countSubtreeIncludingNextLevel(root,hashOffset=" + ho + ") = " + result);
                    java.lang.reflect.Field nextF = DsHashMap.class.getDeclaredField("nextHashMap");
                    nextF.setAccessible(true);
                    boolean printedNext64 = false;
                    Object cur = nhm;
                    java.lang.reflect.Method ckw = nhm.getClass().getDeclaredMethod("collectKeysWithNextLevel", Class.forName("com.q3lives.ds.collections.DsHashMap$Longs"), long.class, int.class);
                    ckw.setAccessible(true);
                    Object longsCls = Class.forName("com.q3lives.ds.collections.DsHashMap$Longs");
                    java.lang.reflect.Constructor<?> ctor = ((Class<?>) longsCls).getDeclaredConstructor(int.class);
                    ctor.setAccessible(true);
                    Object o0 = ctor.newInstance(16);
                    ckw.invoke(cur, o0, 0L, ho);
                    java.lang.reflect.Method toArr = ((Class<?>) longsCls).getDeclaredMethod("toArray");
                    toArr.setAccessible(true);
                    long[] rkeys = (long[]) toArr.invoke(o0);
                    System.out.println("  this(16).collectKeysWithNextLevel = " + Arrays.toString(rkeys));
                    java.lang.reflect.Method nhku = nhm.getClass().getDeclaredMethod("nextHashMapKeysUnder", int.class, int.class);
                    nhku.setAccessible(true);
                    for (int ss = 0; ss < 256; ss++) {
                        boolean printed = false;
                        Object res = nhku.invoke(cur, ss, 7);
                        long[] arr = (long[]) res;
                        if (arr != null && arr.length > 0) {
                            System.out.println("  this(16).nextHashMapKeysUnder(startSlot=" + ss + ", startLevel=7).len=" + arr.length + " arr=" + Arrays.toString(arr));
                            printed = true;
                        }
                        Object res2 = nhku.invoke(cur, ss, 2);
                        long[] arr2 = (long[]) res2;
                        if (arr2 != null && arr2.length > 0) {
                            System.out.println("  this(16).nextHashMapKeysUnder(startSlot=" + ss + ", startLevel=2).len=" + arr2.length + " arr=" + Arrays.toString(arr2));
                            printed = true;
                        }
                        if (printed && ss > 2) {
                            Object res3 = nhku.invoke(cur, ss, 6);
                            long[] arr3 = (long[]) res3;
                            if (arr3 != null && arr3.length > 0) {
                                System.out.println("    this(16).nextHashMapKeysUnder(startSlot=" + ss + ", startLevel=6).len=" + arr3.length + " arr=" + Arrays.toString(arr3));
                            }
                        }
                    }
                    while (true) {
                        Object nx = nextF.get(cur);
                        if (nx == null) break;
                        int nho = (Integer) hashOffsetF.get(nx);
                        Object o1 = ctor.newInstance(16);
                        ckw.invoke(nx, o1, 0L, nho);
                        long[] r1 = (long[]) toArr.invoke(o1);
                        if (!printedNext64) {
                            System.out.println("  nextMap(32).collectKeysWithNextLevel = " + Arrays.toString(r1));
                            printedNext64 = true;
                        } else {
                            System.out.println("  nextMap64.collectKeysWithNextLevel = " + Arrays.toString(r1));
                        }
                        cur = nx;
                    }
                }
            }
            {
                Object ctx16 = newTraversalContext();
                Object ctx32 = newTraversalContext();
                Object ctx64 = newTraversalContext();
                System.out.println("  this(16).internalCountSubtree              = " + invokeInternalCountSubtree(map, ctx16));
                System.out.println("  nextMap(32).internalCountSubtree           = " + invokeInternalCountSubtree(nextMap, ctx32));
                System.out.println("  nextMap64.internalCountSubtree            = " + invokeInternalCountSubtree(nextMap64, ctx64));
                System.out.println("  iter16 产生 " + 3 + " 条, 但 MergedIterator 期望 iter16=0..65534（真实全局顺序 0~65534） ");
                System.out.println("  iter32 产生 " + 3 + " 条，应为 65535, 65536, 4294967285, 然后还缺 4294967295 ");
            }
            System.out.println("---- 跑 forEachRange 流程并内部打印：forEachRange(0,size) ----");
            java.util.List<Long> frKeys = new java.util.ArrayList<>();
            map.forEachRange(0, (int) map.sizeLong(), (k, v) -> {
                frKeys.add(k);
                System.out.println("fr emit k=" + k);
            });
            System.out.println("fr emitted = " + frKeys.size());
            System.out.println("---- per iterator output segment diagnostics ----");
            java.lang.reflect.Field it16F = Class.forName("com.q3lives.ds.collections.DsHashMap$MergedIterator").getDeclaredField("iter16");
            it16F.setAccessible(true);
            Object mergedIt = map.iterator();
            if (!(mergedIt.getClass().getName().contains("MergedIterator"))) {
                java.lang.reflect.Method mth = DsHashMap.class.getDeclaredMethod("mergedIterator");
                mth.setAccessible(true);
                try { mergedIt = mth.invoke(map); } catch (Exception ignore) {}
            }
            Object iter16Val = it16F.get(mergedIt);
            printAllEntriesOfIterator("iter16 (OrderedIterator this)", (Iterator<?>) iter16Val);
            System.out.println();
            DsHashMap m0 = map;
            Object ctx0 = newTraversalContext();
            long localCount0 = (Long) invokeInternalCountSubtree(m0, ctx0);
            System.out.println("m=this(16).internalCountSubtree = " + localCount0 + "  (forEachRange nonNeg half accept range 0 <= k < 0x100000000)");
            java.util.concurrent.atomic.AtomicInteger c0 = new java.util.concurrent.atomic.AtomicInteger(0);
            long[] s0 = new long[]{0L};
            java.util.concurrent.atomic.AtomicInteger em0 = new java.util.concurrent.atomic.AtomicInteger(0);
            Object ctx0B = newTraversalContext();
            Object v0 = java.lang.reflect.Proxy.newProxyInstance(
                DsHashMapRangeDebug2Test.class.getClassLoader(),
                new Class<?>[]{getEntryVisitorClass()},
                (proxy, method, args) -> {
                    long k = (Long) args[0];
                    long v = (Long) args[1];
                    int ci = c0.getAndIncrement();
                    System.out.println("  seg.this#" + ci + "  accept[k=" + k + "]? " + (k >= 0 && k < nonNegSplit) + "  (nonNegSplit=" + nonNegSplit + ")");
                    em0.incrementAndGet();
                    return Boolean.FALSE;
                }
            );
            invokeInternalCollectRangeOrdered(m0, s0, Integer.MAX_VALUE, v0, new int[]{0}, ctx0B);
            System.out.println("  => emitted nonNeg-filtered = " + em0.get());
            System.out.println();
            DsHashMap m1 = nextMap;
            Object ctx1 = newTraversalContext();
            long localCount1 = (Long) invokeInternalCountSubtree(m1, ctx1);
            System.out.println("m=nextMap(32).internalCountSubtree = " + localCount1 + "  (hashOffset=" + hashOffsetF.get(m1) + " hashLen=" + hashLenF.get(m1) + ")  k range accept 0<=k<0x100000000?");
            java.util.concurrent.atomic.AtomicInteger c1 = new java.util.concurrent.atomic.AtomicInteger(0);
            long[] s1 = new long[]{0L};
            java.util.concurrent.atomic.AtomicInteger em1 = new java.util.concurrent.atomic.AtomicInteger(0);
            Object ctx1B = newTraversalContext();
            Object v1 = java.lang.reflect.Proxy.newProxyInstance(
                DsHashMapRangeDebug2Test.class.getClassLoader(),
                new Class<?>[]{getEntryVisitorClass()},
                (proxy, method, args) -> {
                    long k = (Long) args[0];
                    long v = (Long) args[1];
                    int ci = c1.getAndIncrement();
                    System.out.println("  seg.nextMap(32)#" + ci + "  k=" + k + " accept? " + (k >= 0 && k < nonNegSplit));
                    em1.incrementAndGet();
                    return Boolean.FALSE;
                }
            );
            invokeInternalCollectRangeOrdered(m1, s1, Integer.MAX_VALUE, v1, new int[]{0}, ctx1B);
            System.out.println("  => emitted nonNeg-filtered = " + em1.get());
            System.out.println("---- 64pos half ----");
            Object ctx64B = newTraversalContext();
            long[] s64 = new long[]{negSize};
            java.util.concurrent.atomic.AtomicInteger c64 = new java.util.concurrent.atomic.AtomicInteger(0);
            java.util.concurrent.atomic.AtomicInteger em64 = new java.util.concurrent.atomic.AtomicInteger(0);
            Object v64 = java.lang.reflect.Proxy.newProxyInstance(
                DsHashMapRangeDebug2Test.class.getClassLoader(),
                new Class<?>[]{getEntryVisitorClass()},
                (proxy, method, args) -> {
                    long k = (Long) args[0];
                    long v = (Long) args[1];
                    int ci = c64.getAndIncrement();
                    System.out.println("  seg.64pos#" + ci + "  k=" + k + " accept? " + (k >= nonNegSplit));
                    em64.incrementAndGet();
                    return Boolean.FALSE;
                }
            );
            invokeInternalCollectRangeOrdered(nextMap64, s64, Integer.MAX_VALUE, v64, new int[]{0}, ctx64B);
            System.out.println("  => emitted pos64-filtered = " + em64.get());

            List<Map.Entry<Long, Long>> itAll = new ArrayList<>();
            Iterator<Map.Entry<Long, Long>> it = map.iterator();
            while (it.hasNext()) itAll.add(it.next());
            System.out.println("iterator emit " + itAll.size() + " keys:");
            for (Map.Entry<Long, Long> e : itAll) System.out.println("  k=" + e.getKey());
            System.out.println("range(0,size) emit " + map.range(0, (int) map.sizeLong()).size() + " keys:");
            for (Map.Entry<Long, Long> e : map.range(0, (int) map.sizeLong())) System.out.println("  rk=" + e.getKey());
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            map.syncAll();
            map.close();
        }
    }

    private static long callInternalCountSubtree(DsHashMap m) throws Exception {
        java.lang.reflect.Method mth = DsHashMap.class.getDeclaredMethod("internalCountSubtree", getTraversalContextClass());
        mth.setAccessible(true);
        Object ctx = newTraversalContext();
        return (Long) mth.invoke(m, ctx);
    }

    private static Object invokeInternalIndexOfCeiling(DsHashMap m, long key, Object ctx) throws Exception {
        java.lang.reflect.Method mth = DsHashMap.class.getDeclaredMethod("internalIndexOfCeiling", long.class, getTraversalContextClass());
        mth.setAccessible(true);
        return mth.invoke(m, key, ctx);
    }

    private static Object invokeInternalCountSubtree(DsHashMap m, Object ctx) throws Exception {
        java.lang.reflect.Method mth = DsHashMap.class.getDeclaredMethod("internalCountSubtree", getTraversalContextClass());
        mth.setAccessible(true);
        return mth.invoke(m, ctx);
    }

    private static Object invokeInternalCollectRangeOrdered(DsHashMap m, long[] skip, int limit, Object visitor, int[] emitted, Object ctx) throws Exception {
        Class<?> visitorType = Class.forName("com.q3lives.ds.collections.DsHashMap$EntryVisitor");
        java.lang.reflect.Method mth = DsHashMap.class.getDeclaredMethod("internalCollectRangeOrdered", long[].class, int.class, visitorType, int[].class, getTraversalContextClass());
        mth.setAccessible(true);
        return mth.invoke(m, skip, limit, visitor, emitted, ctx);
    }

    private static void printAllEntriesOfIterator(String name, Iterator<?> iter) {
        int n = 0;
        System.out.println(name + ":");
        while (iter.hasNext()) {
            Object e = iter.next();
            String s = (e instanceof Map.Entry<?, ?>) ? "k=" + ((Map.Entry<?, ?>) e).getKey() : String.valueOf(e);
            System.out.println("    #" + n + ": " + s);
            n++;
        }
        System.out.println("  total=" + n);
    }

    private static Class<?> getTraversalContextClass() throws ClassNotFoundException {
        return Class.forName("com.q3lives.ds.collections.DsHashMap$TraversalContext");
    }

    private static Class<?> getEntryVisitorClass() throws ClassNotFoundException {
        return Class.forName("com.q3lives.ds.collections.DsHashMap$EntryVisitor");
    }

    private static Object newTraversalContext() throws Exception {
        Class<?> clz = getTraversalContextClass();
        java.lang.reflect.Constructor<?> ctr = clz.getDeclaredConstructor(int.class);
        ctr.setAccessible(true);
        return ctr.newInstance(8);
    }
}
