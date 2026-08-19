package ds;

import com.q3lives.ds.header.FileStoreIdRegistry;
import com.q3lives.ds.header.SharedHeaderDeltaLog;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.BitSet;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class SharedHeaderDeltaLogYdayRecoveryTest {

    private File tierDir;
    private File registryDir;

    @Before
    public void setUp() throws Exception {
        tierDir = new File("target/test-shared-yday-recovery");
        registryDir = new File("target/test-shared-yday-id-registry");
        deleteDir(tierDir);
        deleteDir(registryDir);
        tierDir.mkdirs();
        registryDir.mkdirs();
    }

    @After
    public void tearDown() throws Exception {
        System.gc();
        Thread.sleep(200);
        deleteDir(tierDir);
        deleteDir(registryDir);
    }

    private static void deleteDir(File dir) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteDir(f);
                else f.delete();
            }
        }
        dir.delete();
    }

    @Test
    public void testYesterdayDeltaLogRecoversOnEmptyToday() throws Exception {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        LocalDate today = LocalDate.now();
        String todayKey = today.format(fmt);
        String ydayKey = today.minusDays(1).format(fmt);
        final String STORE_NAME = "shared_store_alpha";

        final int BLOCK = 64 * 1024;
        byte[] gold = new byte[BLOCK];
        BitSet goldDirty = new BitSet(BLOCK);
        for (int i = 0; i < 512; i++) {
            gold[i] = (byte) ((i / 11) & 0xFF);
            goldDirty.set(i);
        }

        FileStoreIdRegistry registry1 = new FileStoreIdRegistry(registryDir);
        SharedHeaderDeltaLog log1 = SharedHeaderDeltaLog.createWithYesterdayRecovery(tierDir, todayKey, registry1);
        try {
            long id1 = log1.internStoreId(STORE_NAME);
            assertTrue("store id must be positive", id1 > 0);
            ByteBuffer base = ByteBuffer.allocate(BLOCK);
            for (int i = goldDirty.nextSetBit(0); i >= 0; i = goldDirty.nextSetBit(i + 1)) {
                base.put(i, gold[i]);
            }
            log1.markFullAndAppend(id1, base);
            log1.flushAll();
        } finally {
            log1.close();
            registry1.close();
        }

        File todayFile = new File(tierDir, "delta_headers_" + todayKey + ".log");
        assertTrue("today log must exist after close, actual: " + Arrays.toString(tierDir.list()), todayFile.exists());
        assertTrue("today log must be at least header+1 page, actual length=" + todayFile.length(), todayFile.length() >= 64 + 4096);
        File ydayFile = new File(tierDir, "delta_headers_" + ydayKey + ".log");
        System.gc();
        Thread.sleep(300);
        Files.move(todayFile.toPath(), ydayFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        assertFalse(todayFile.exists());
        assertTrue(ydayFile.exists());

        FileStoreIdRegistry registry2 = new FileStoreIdRegistry(registryDir);
        SharedHeaderDeltaLog log2 = SharedHeaderDeltaLog.createWithYesterdayRecovery(tierDir, todayKey, registry2);
        try {
            Long idLookup = registry2.lookupIfRegistered(STORE_NAME);
            assertNotNull("yday registry must have preserved store path", idLookup);
            long id2 = idLookup.longValue();

            assertNotNull("yesterday log must be loaded by createWithYesterdayRecovery", log2.getYesterdayLog());

            byte[] snap = log2.getReadSnapshot(id2);
            assertNotNull("read snapshot via yday must not be null, yesterday states: " + log2.getYesterdayLog().liveStoreCount() +
                " (today states=" + log2.liveStoreCount() + ")", snap);
            assertEquals("snapshot length must equal STORE_HEADER_MAX_BYTES", SharedHeaderDeltaLog.STORE_HEADER_MAX_BYTES, snap.length);

            BitSet bs = log2.getDirtyBitSet(id2);
            assertNotNull("dirty BitSet via yday must not be null", bs);
            assertFalse("dirty BitSet via yday must not be empty", bs.isEmpty());
            assertTrue("dirty BitSet must contain idx 0", bs.get(0));
            assertTrue("dirty BitSet must contain idx 511", bs.get(511));
            assertEquals("dirty BitSet first clear must be at 512 (or higher after padding)", 512, bs.nextClearBit(0));

            for (int i = goldDirty.nextSetBit(0); i >= 0 && i < 512; i = goldDirty.nextSetBit(i + 1)) {
                assertEquals("byte idx " + i + " must restore via yday fallback overlay", gold[i], snap[i]);
            }
        } finally {
            log2.close();
            registry2.close();
        }
    }
}
