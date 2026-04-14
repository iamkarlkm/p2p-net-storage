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

public class DsFileTest {
    private static final String TEST_DIR = "target/test-ds-file";
    private DsFile dsFile;

    @Before
    public void setUp() throws IOException {
        File dir = new File(TEST_DIR);
        if (dir.exists()) {
            deleteRecursively(dir);
        }
        dir.mkdirs();
        dsFile = new DsFile(TEST_DIR);
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
    public void testSaveAndGetFile() throws Exception {
        String contentStr = "Hello, Distributed File System!";
        byte[] content = contentStr.getBytes(StandardCharsets.UTF_8);

        FileMetadata meta = new FileMetadata();
        meta.basic.fileName = "test.txt";
        meta.basic.displayName = "Test File";
        meta.basic.fileType = FileMetadata.FileType.NORMAL;
        meta.security.ownerUid = "user1";
        meta.ext.source = "微信";
        meta.tags.tags.put("project", "DsFileTest");

        FileMetadata savedMeta = dsFile.saveFile(content, meta);
        assertNotNull(savedMeta.globalFileId);
        assertNotNull(savedMeta.basic.contentHash);
        assertEquals(content.length, savedMeta.basic.fileSize);

        FileMetadata retrievedMeta = dsFile.getMetadata(savedMeta.globalFileId);
        assertNotNull(retrievedMeta);
        assertEquals(savedMeta.globalFileId, retrievedMeta.globalFileId);
        assertEquals(savedMeta.basic.fileName, retrievedMeta.basic.fileName);
        assertEquals("Test File", retrievedMeta.basic.displayName);
        assertEquals("user1", retrievedMeta.security.ownerUid);
        assertEquals("微信", retrievedMeta.ext.source);
        assertEquals("DsFileTest", retrievedMeta.tags.tags.get("project"));

        FileMetadata.BasicMeta basicMeta = dsFile.getBasicMetadata(savedMeta.globalFileId);
        assertNotNull(basicMeta);
        assertEquals(savedMeta.basic.fileName, basicMeta.fileName);
        assertEquals("Test File", basicMeta.displayName);
        assertEquals(savedMeta.basic.contentHash, basicMeta.contentHash);

        byte[] retrievedContent = dsFile.getFileContentById(savedMeta.globalFileId);
        assertArrayEquals(content, retrievedContent);
    }

    @Test
    public void testGetFilesByTag() throws Exception {
        String contentStr = "Tagged File";
        byte[] content = contentStr.getBytes(StandardCharsets.UTF_8);

        FileMetadata meta = new FileMetadata();
        meta.basic.fileName = "tagged_file.txt";
        meta.tags.tags.put("category", "important");
        meta.tags.tags.put("status", "processed");

        dsFile.saveFile(content, meta);

        List<FileMetadata.BasicMeta> results = dsFile.getFilesByTag("category", "important");
        assertNotNull(results);
        assertEquals(1, results.size());
        assertEquals("tagged_file.txt", results.get(0).fileName);

        List<FileMetadata.BasicMeta> emptyResults = dsFile.getFilesByTag("category", "none");
        assertNotNull(emptyResults);
        assertTrue(emptyResults.isEmpty());

        FileMetadata meta2 = new FileMetadata();
        meta2.basic.fileName = "tagged_file_2.txt";
        meta2.tags.tags.put("category", "important");
        dsFile.saveFile(content, meta2);

        List<FileMetadata.BasicMeta> multipleResults = dsFile.getFilesByTag("category", "important");
        assertEquals(2, multipleResults.size());
    }

    @Test
    public void testDeduplication() throws Exception {
        String contentStr = "Duplicate Content";
        byte[] content = contentStr.getBytes(StandardCharsets.UTF_8);

        FileMetadata meta1 = new FileMetadata();
        meta1.basic.fileName = "file1.txt";
        FileMetadata savedMeta1 = dsFile.saveFile(content, meta1);

        FileMetadata meta2 = new FileMetadata();
        meta2.basic.fileName = "file2.txt";
        FileMetadata savedMeta2 = dsFile.saveFile(content, meta2);

        assertEquals(savedMeta1.basic.contentHash, savedMeta2.basic.contentHash);
        assertNotEquals(savedMeta1.globalFileId, savedMeta2.globalFileId);
        assertArrayEquals(content, dsFile.getFileContentById(savedMeta1.globalFileId));
        assertArrayEquals(content, dsFile.getFileContentById(savedMeta2.globalFileId));
    }
}
