package ds;

import com.q3lives.ds.fs.DsFile;
import com.q3lives.ds.fs.FileMetadata;
import org.junit.Before;
import org.junit.Test;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.Assert.*;

public class DsFileDirTest {
    private static final String TEST_DIR = "target/test-ds-file-dir";
    private DsFile fs;

    @Before
    public void setUp() throws IOException {
        File dir = new File(TEST_DIR);
        if (dir.exists()) {
            deleteRecursively(dir);
        }
        dir.mkdirs();
        fs = new DsFile(TEST_DIR);
    }

    private void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File f : children) {
                    deleteRecursively(f);
                }
            }
        }
        file.delete();
    }

    @Test
    public void testSaveByPathAndList() throws Exception {
        byte[] content = "abc".getBytes(StandardCharsets.UTF_8);
        FileMetadata meta = new FileMetadata();
        meta.tags.tags.put("k", "v");
        fs.saveFile("/docs/a.txt", content, meta);

        byte[] out = fs.getFileContentByPath("/docs/a.txt");
        assertArrayEquals(content, out);

        List<Long> docsEntries = fs.listDir("/docs", 0, 10);
        assertFalse(docsEntries.isEmpty());
        assertTrue(docsEntries.get(0) > 0);

        List<FileMetadata.BasicMeta> byTag = fs.getFilesByTag("k", "v");
        assertEquals(1, byTag.size());
        assertEquals("a.txt", byTag.get(0).fileName);
    }

    @Test
    public void testSystemPath() throws Exception {
        byte[] content = "sys".getBytes(StandardCharsets.UTF_8);
        fs.saveFile("$/cfg/sys.txt", content, new FileMetadata());
        assertArrayEquals(content, fs.getFileContentByPath("$/cfg/sys.txt"));

        List<Long> cfgEntries = fs.listDir("$/cfg", 0, 10);
        assertFalse(cfgEntries.isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRelativeRequiresParent() throws Exception {
        fs.mkdirs("a/b");
    }
}
