package ds;

import com.q3lives.ds.fs.FileMetadata;
import com.q3lives.ds.legacy.db.DbFile;
import org.junit.Before;
import org.junit.Test;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.*;

public class DbFileTest {
    private static final String TEST_DIR = "target/test-db-file";
    private DbFile dbFile;

    @Before
    public void setUp() throws IOException {
        File dir = new File(TEST_DIR);
        if (dir.exists()) {
            deleteRecursively(dir);
        }
        dir.mkdirs();
        dbFile = new DbFile(TEST_DIR);
    }

    private void deleteRecursively(File file) {
        if (file.isDirectory()) {
            for (File f : file.listFiles()) {
                deleteRecursively(f);
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
        meta.basic.displayName = "Test File"; // 增加显示文件名
        meta.basic.fileType = FileMetadata.FileType.NORMAL;
        meta.security.ownerUid = "user1";
        meta.ext.source = "微信";
        meta.tags.tags.put("project", "DbFileTest"); // 使用独立的 TagsMeta
        
        // 1. 保存文件
        FileMetadata savedMeta = dbFile.saveFile(content, meta);
        assertNotNull(savedMeta.globalFileId);
        assertNotNull(savedMeta.basic.contentHash);
        assertEquals(content.length, savedMeta.basic.fileSize);
        
        // 2. 获取完整 Metadata
        FileMetadata retrievedMeta = dbFile.getMetadata(savedMeta.globalFileId);
        assertNotNull(retrievedMeta);
        assertEquals(savedMeta.globalFileId, retrievedMeta.globalFileId);
        assertEquals(savedMeta.basic.fileName, retrievedMeta.basic.fileName);
        assertEquals("Test File", retrievedMeta.basic.displayName); // 验证 displayName
        assertEquals("user1", retrievedMeta.security.ownerUid);
        assertEquals("微信", retrievedMeta.ext.source);
        assertEquals("DbFileTest", retrievedMeta.tags.tags.get("project")); // 验证 Tags
        
        // 2.1 仅获取基础 Metadata
        FileMetadata.BasicMeta basicMeta = dbFile.getBasicMetadata(savedMeta.globalFileId);
        assertNotNull(basicMeta);
        assertEquals(savedMeta.basic.fileName, basicMeta.fileName);
        assertEquals("Test File", basicMeta.displayName); // 验证 displayName
        assertEquals(savedMeta.basic.contentHash, basicMeta.contentHash);
        
        // 3. 获取内容
        byte[] retrievedContent = dbFile.getFileContentById(savedMeta.globalFileId);
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
        
        dbFile.saveFile(content, meta);
        
        // 1. 根据 Tag 查询
        List<FileMetadata.BasicMeta> results = dbFile.getFilesByTag("category", "important");
        assertNotNull(results);
        assertEquals(1, results.size());
        assertEquals("tagged_file.txt", results.get(0).fileName);
        
        // 2. 查询不存在的 Tag
        List<FileMetadata.BasicMeta> emptyResults = dbFile.getFilesByTag("category", "none");
        assertNotNull(emptyResults);
        assertTrue(emptyResults.isEmpty());
        
        // 3. 多个文件同 Tag
        FileMetadata meta2 = new FileMetadata();
        meta2.basic.fileName = "tagged_file_2.txt";
        meta2.tags.tags.put("category", "important");
        dbFile.saveFile(content, meta2);
        
        List<FileMetadata.BasicMeta> multipleResults = dbFile.getFilesByTag("category", "important");
        assertEquals(2, multipleResults.size());
    }
    
    @Test
    public void testDeduplication() throws Exception {
        String contentStr = "Duplicate Content";
        byte[] content = contentStr.getBytes(StandardCharsets.UTF_8);
        
        // 文件 1
        FileMetadata meta1 = new FileMetadata();
        meta1.basic.fileName = "file1.txt";
        FileMetadata savedMeta1 = dbFile.saveFile(content, meta1);
        
        // 文件 2 (内容相同)
        FileMetadata meta2 = new FileMetadata();
        meta2.basic.fileName = "file2.txt";
        FileMetadata savedMeta2 = dbFile.saveFile(content, meta2);
        
        // 验证 Hash 相同
        assertEquals(savedMeta1.basic.contentHash, savedMeta2.basic.contentHash);
        
        // 验证 ID 不同
        assertNotEquals(savedMeta1.globalFileId, savedMeta2.globalFileId);
        
        // 验证内容都能获取
        assertArrayEquals(content, dbFile.getFileContentById(savedMeta1.globalFileId));
        assertArrayEquals(content, dbFile.getFileContentById(savedMeta2.globalFileId));
    }
}
