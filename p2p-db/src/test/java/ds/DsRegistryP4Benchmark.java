package ds;

import java.io.File;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import com.q3lives.ds.collections.DsHashMap;
import com.q3lives.ds.enums.DataType;
import com.q3lives.ds.registry.DsRegistry;
import com.q3lives.ds.registry.DsRegistryNodeSerializer;

public class DsRegistryP4Benchmark {

    private static final long ROOT_DIR_NODE_ID = 1L;
    private static final int TYPE_REGISTRY_DIR_META = 205;
    private static final int POWER_NODE_BLOCK_DIR = 7;
    private static final int TYPE_REGISTRY_NODE_META = 202;
    private static final int POWER_NODE_BLOCK = 6;

    private static long encodeMT(int type, int power, long idx) {
        return ((long) (type & 0xFFFF) << 48) | ((long) (power & 0xFF) << 40) | (idx & 0xFFFFFFFFFFL);
    }

    private static long decodeIndexId(long typedId) {
        return typedId & 0xFFFFFFFFFFL;
    }

    private static long rootDirTypedId() {
        return encodeMT(TYPE_REGISTRY_DIR_META, POWER_NODE_BLOCK_DIR, 0L);
    }

    private static long makeDirTypedId(long keyId) {
        long idx = decodeIndexId(keyId);
        return encodeMT(TYPE_REGISTRY_DIR_META, POWER_NODE_BLOCK_DIR, idx + 1L);
    }

    private static long sumGcCount() {
        long c = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            long n = b.getCollectionCount();
            if (n > 0) c += n;
        }
        return c;
    }

    private static long walkDir(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long t = 0;
        File[] ch = f.listFiles();
        if (ch != null) for (File c : ch) t += walkDir(c);
        return t;
    }

    private static long sumBucketBytes(File rootCanonical) {
        long total = 0;
        File registryDir = new File(rootCanonical, "registry");
        if (registryDir.exists()) total += walkDir(registryDir);
        File[] subs = rootCanonical.listFiles();
        if (subs != null) for (File s : subs) {
            String n = s.getName();
            if (n.startsWith("type_")) total += walkDir(s);
        }
        return total;
    }

    private static long countRingFiles(File rootCanonical) {
        File ringDir = new File(rootCanonical, "registry" + File.separator + "rings");
        if (!ringDir.isDirectory()) return 0;
        File[] list = ringDir.listFiles();
        return list == null ? 0 : list.length;
    }

    private static long readNextSeq(DsRegistry r) throws Exception {
        Field f = DsRegistry.class.getDeclaredField("nextSequence");
        f.setAccessible(true);
        DsHashMap m = (DsHashMap) f.get(r);
        Method gm = DsHashMap.class.getMethod("get", long.class);
        Object v = gm.invoke(m, 1L);
        if (v == null) return 0L;
        return ((Number) v).longValue();
    }

    private static long readKeyIdToDirIdSize(DsRegistry r) throws Exception {
        Field f = DsRegistry.class.getDeclaredField("keyIdToDirId");
        f.setAccessible(true);
        DsHashMap m = (DsHashMap) f.get(r);
        Method sz = DsHashMap.class.getMethod("size");
        return ((Number) sz.invoke(m)).longValue();
    }

    private static void injectP3LegacyState(File tmp, String[] layers, int DEPTH) throws Exception {
        try (DsRegistry r = new DsRegistry(new File(tmp, "db").getAbsolutePath())) {
            Field keyIdToDirIdF = DsRegistry.class.getDeclaredField("keyIdToDirId");
            Field dirIdToRingIdF = DsRegistry.class.getDeclaredField("dirIdToRingId");
            keyIdToDirIdF.setAccessible(true);
            dirIdToRingIdF.setAccessible(true);
            DsHashMap keyIdToDirId = (DsHashMap) keyIdToDirIdF.get(r);
            DsHashMap dirIdToRingId = (DsHashMap) dirIdToRingIdF.get(r);
            Method keyStringsMethod = DsRegistry.class.getMethod("keyStrings");
            Object keyStrings = keyStringsMethod.invoke(r);
            Method getIndexIdMethod = keyStrings.getClass().getMethod("getIndexId", String.class, boolean.class);
            Method putLong = DsHashMap.class.getMethod("put", long.class, long.class);
            Method remove = DsHashMap.class.getMethod("remove", long.class);
            Method get = DsHashMap.class.getMethod("get", long.class);
            long plainBase = 200_000L;
            StringBuilder sb = new StringBuilder(256);
            int nDirs = 0;
            for (int i = 1; i <= DEPTH; i++) {
                sb.setLength(0);
                for (int j = 0; j < i; j++) sb.append(layers[j]);
                String d = sb.toString();
                Long keyId = (Long) getIndexIdMethod.invoke(keyStrings, d, false);
                if (keyId == null) continue;
                long typed = makeDirTypedId(keyId);
                Object ringObj = get.invoke(dirIdToRingId, typed);
                if (ringObj == null) continue;
                long ring = ((Number) ringObj).longValue();
                long fake = plainBase + nDirs;
                putLong.invoke(keyIdToDirId, keyId.longValue(), fake);
                putLong.invoke(dirIdToRingId, fake, ring);
                nDirs++;
            }
            Object rootRing = get.invoke(dirIdToRingId, rootDirTypedId());
            if (rootRing != null) {
                long rootRingV = ((Number) rootRing).longValue();
                putLong.invoke(dirIdToRingId, ROOT_DIR_NODE_ID, rootRingV);
                remove.invoke(dirIdToRingId, rootDirTypedId());
            }
        }
    }

    public static long[] runBenchmark(String mode, int DEPTH, int N, int serverId, int partitionId) throws Exception {
        File tmp = Files.createTempDirectory("dsregp6c-" + mode + "-s" + serverId + "p" + partitionId + "-").toFile();
        tmp.deleteOnExit();
        File rootCanonical = new File(tmp, "db").getCanonicalFile();
        String[] layers = new String[DEPTH];
        for (int i = 0; i < DEPTH; i++) layers[i] = "/l" + i;
        StringBuilder sb = new StringBuilder(256);
        long seqBefore = 0;
        long seqAfter = 0;
        long bucketBefore, bucketAfter;
        long ringBefore, ringAfter;
        long gcBefore = sumGcCount();
        long t0, t1;
        if ("P3".equalsIgnoreCase(mode)) {
            try (DsRegistry r = new DsRegistry(rootCanonical.getAbsolutePath(), serverId, partitionId)) {
                for (int k = 0; k < N; k++) {
                    sb.setLength(0);
                    for (int i = 0; i < DEPTH; i++) sb.append(layers[i]);
                    sb.append("/item-").append(k);
                    r.putLong(sb.toString(), DataType.REG_DWORD, (long) k);
                }
            }
            injectP3LegacyState(tmp, layers, DEPTH);
            bucketBefore = sumBucketBytes(rootCanonical);
            ringBefore = countRingFiles(rootCanonical);
            try (DsRegistry r2 = new DsRegistry(rootCanonical.getAbsolutePath(), serverId, partitionId)) {
                seqBefore = readNextSeq(r2);
            }
            try (DsRegistry r = new DsRegistry(rootCanonical.getAbsolutePath(), serverId, partitionId)) {
                t0 = System.nanoTime();
                for (int k = 0; k < N; k++) {
                    sb.setLength(0);
                    for (int i = 0; i < DEPTH; i++) sb.append(layers[i]);
                    sb.append("/item-").append(k + N);
                    r.putLong(sb.toString(), DataType.REG_DWORD, (long) k);
                }
                t1 = System.nanoTime();
                seqAfter = readNextSeq(r);
            }
            bucketAfter = sumBucketBytes(rootCanonical);
            ringAfter = countRingFiles(rootCanonical);
        } else {
            bucketBefore = sumBucketBytes(rootCanonical);
            ringBefore = countRingFiles(rootCanonical);
            try (DsRegistry r = new DsRegistry(rootCanonical.getAbsolutePath(), serverId, partitionId)) {
                seqBefore = readNextSeq(r);
                t0 = System.nanoTime();
                for (int k = 0; k < N; k++) {
                    sb.setLength(0);
                    for (int i = 0; i < DEPTH; i++) sb.append(layers[i]);
                    sb.append("/item-").append(k);
                    r.putLong(sb.toString(), DataType.REG_DWORD, (long) k);
                }
                t1 = System.nanoTime();
                seqAfter = readNextSeq(r);
            }
            bucketAfter = sumBucketBytes(rootCanonical);
            ringAfter = countRingFiles(rootCanonical);
        }
        long gcAfter = sumGcCount();
        long totalNs = t1 - t0;
        double ms = totalNs / 1_000_000.0;
        double ops = ms <= 0 ? 0.0 : (N * 1000.0 / ms);
        long leafBlock = DsRegistryNodeSerializer.NODE_BLOCK_BYTES;
        long dirBlock = 128L;
        long readBytesEst = (long) N * (leafBlock + (long) DEPTH * dirBlock);
        long keyId2DirSize;
        try (DsRegistry r2 = new DsRegistry(rootCanonical.getAbsolutePath(), serverId, partitionId)) {
            keyId2DirSize = readKeyIdToDirIdSize(r2);
        }
        return new long[]{
                Math.max(0L, seqAfter - seqBefore),
                keyId2DirSize,
                Math.max(0L, bucketAfter - bucketBefore),
                Math.max(0L, ringAfter - ringBefore),
                readBytesEst,
                Math.max(0L, gcAfter - gcBefore),
                totalNs,
                (long) ops
        };
    }

    public static void main(String[] args) throws Exception {
        String mode = "BOTH";
        int DEPTH = 10;
        int N = 2000;
        int serverId = 0;
        int partitionId = 0;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--mode": mode = args[++i]; break;
                case "--depth": DEPTH = Integer.parseInt(args[++i]); break;
                case "-n": case "--n": N = Integer.parseInt(args[++i]); break;
                case "--serverId": case "--sid": serverId = Integer.parseInt(args[++i]); break;
                case "--partitionId": case "--pid": partitionId = Integer.parseInt(args[++i]); break;
                default:
                    System.err.println("Unknown arg: " + a);
                    System.err.println("Usage: [--mode P3|P4|BOTH] [--depth 10] [--n 2000] [--sid 0] [--pid 0]");
                    System.exit(2);
            }
        }
        System.out.println("run,mode,DEPTH,N,serverId,partitionId,nextSeq_diff,keyIdToDirId_size,bucket_bytes_delta,ring_count_delta,read_bytes_est,yg_gc_count,total_ns,ops_per_sec");
        int runIdx = 1;
        List<String> modes = "BOTH".equalsIgnoreCase(mode) ? Arrays.asList("P3", "P4") : Arrays.asList(mode);
        for (String m : modes) {
            long[] r = runBenchmark(m, DEPTH, N, serverId, partitionId);
            System.out.printf("%d,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
                    runIdx++, m, DEPTH, N, serverId, partitionId,
                    r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7]);
        }
    }
}
