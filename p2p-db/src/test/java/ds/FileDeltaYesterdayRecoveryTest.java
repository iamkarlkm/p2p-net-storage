package ds;

import com.q3lives.ds.header.FileDeltaHeaderTierStore;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.BitSet;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class FileDeltaYesterdayRecoveryTest {

    private File tierDir;

    @Before
    public void setUp() throws Exception {
        tierDir = new File("target/test-delta-yday-recovery");
        deleteDir(tierDir);
        tierDir.mkdirs();
    }

    @After
    public void tearDown() throws Exception {
        deleteDir(tierDir);
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
    public void testYesterdayHdrTierFallbackOnEmptyToday() throws Exception {
        final int BLOCK = 64 * 1024;
        final String STORE_NAME = "my_delta_test_store";
        byte[] gold = new byte[BLOCK];
        for (int i = 0; i < BLOCK; i += 1024) {
            gold[i] = (byte) ((i / 1024) & 0xFF);
            gold[i + 512] = (byte) ((i / 7 + 31) & 0xFF);
        }
        BitSet goldDirty = new BitSet(BLOCK);
        for (int i = 0; i < BLOCK; i += 1024) {
            goldDirty.set(i);
            goldDirty.set(i + 512);
        }

        FileDeltaHeaderTierStore s1 = new FileDeltaHeaderTierStore(STORE_NAME, BLOCK, tierDir.getAbsolutePath());
        try {
            ByteBuffer base = ByteBuffer.allocate(BLOCK);
            s1.attachBase(base);
            base.put(gold);
            base.clear();
            s1.markFullDirty();
            s1.flush();
        } finally {
            s1.close();
        }

        File deltaFile = new File(tierDir, STORE_NAME + ".hdr_tier");
        assertTrue("today hdr_tier must exist directly under tierDir: " + deltaFile.getAbsolutePath() + " actual files: " + Arrays.toString(tierDir.list()), deltaFile.exists());
        File ydayCopy = new File(tierDir, STORE_NAME + ".hdr_tier.yday_20260815_bak");
        Files.move(deltaFile.toPath(), ydayCopy.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        assertFalse(deltaFile.exists());
        assertTrue(ydayCopy.exists());

        FileDeltaHeaderTierStore s2 = new FileDeltaHeaderTierStore(STORE_NAME, BLOCK, tierDir.getAbsolutePath());
        byte[] restored = new byte[BLOCK];
        try {
            ByteBuffer base2 = ByteBuffer.allocate(BLOCK);
            s2.attachBase(base2);
            base2.clear();
            base2.get(restored);
            for (int i = goldDirty.nextSetBit(0); i >= 0; i = goldDirty.nextSetBit(i + 1)) {
                assertEquals("byte idx " + i + " must equal via yday overlay", gold[i], restored[i]);
            }
        } finally {
            s2.close();
        }
    }
}

