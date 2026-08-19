package ds;

import com.q3lives.ds.header.FileStoreIdRegistry;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class FileStoreIdRegistryCrashHalfWriteTest {

    private File baseDir;

    @Before
    public void setUp() throws Exception {
        baseDir = new File("target/test-registry-crash-v2");
        deleteDir(baseDir);
        baseDir.mkdirs();
    }

    @After
    public void tearDown() throws Exception {
        deleteDir(baseDir);
    }

    private static void deleteDir(File dir) throws Exception {
        if (!dir.exists()) return;
        Files.walk(dir.toPath())
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
    }

    @Test
    public void testCorruptTailCrcDoesNotRewindNextStoreId() throws Exception {
        FileStoreIdRegistry r1 = new FileStoreIdRegistry(baseDir);
        long idA = r1.intern("stores/hashmap_a.dat");
        long idB = r1.intern("stores/hashmap_b.dat");
        long idC = r1.intern("stores/hashmap_c.dat");
        assertTrue(idA < idB);
        assertTrue(idB < idC);
        long oldNext = r1.nextAssignedStoreId();
        assertEquals(idC + 1, oldNext);
        r1.close();

        File idxFile = new File(baseDir, "store_id.idx");
        assertTrue(idxFile.exists());
        long len = idxFile.length();
        byte[] junk = new byte[48];
        java.util.concurrent.ThreadLocalRandom.current().nextBytes(junk);
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(idxFile, "rw")) {
            raf.seek(len);
            raf.write(junk);
            raf.setLength(len + junk.length);
            raf.seek(len + junk.length - 4);
            raf.writeInt(0xDEADBEEF);
        }

        FileStoreIdRegistry r2 = new FileStoreIdRegistry(baseDir);
        assertEquals(Long.valueOf(idA), r2.lookupIfRegistered("stores/hashmap_a.dat"));
        assertEquals(Long.valueOf(idB), r2.lookupIfRegistered("stores/hashmap_b.dat"));
        assertEquals(Long.valueOf(idC), r2.lookupIfRegistered("stores/hashmap_c.dat"));
        assertEquals("stores/hashmap_c.dat", r2.resolvePath(idC));
        long newNext = r2.nextAssignedStoreId();
        assertTrue("nextStoreId must not rewind, oldNext=" + oldNext + " newNext=" + newNext, newNext >= oldNext);
        long idD = r2.intern("stores/hashmap_d.dat");
        assertTrue(idD >= newNext);
        assertTrue(idD > idC);
        r2.close();
    }
}
