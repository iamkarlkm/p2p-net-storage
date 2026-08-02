package javax.net.p2p.utils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.apache.commons.lang3.tuple.Triple;
import org.junit.Assert;
import org.junit.Test;

public class FileUtilUpInfoTmpTest {

    @Test
    public void shouldUseDifferentTmpIdxFilePerStoreId() throws Exception {
        String path = "a/b/c.bin";
        Triple<File, File, java.util.Set<Integer>> store1 = FileUtil.getUpInfoTmp(1, path);
        Triple<File, File, java.util.Set<Integer>> store2 = FileUtil.getUpInfoTmp(2, path);
        Assert.assertNotEquals(store1.getMiddle().getAbsolutePath(), store2.getMiddle().getAbsolutePath());
    }

    @Test
    public void shouldNotShareUploadedSegmentIndexesAcrossStoreId() throws Exception {
        String path = "resume.bin";
        Triple<File, File, java.util.Set<Integer>> store1 = FileUtil.getUpInfoTmp(11, path);
        Triple<File, File, java.util.Set<Integer>> store2 = FileUtil.getUpInfoTmp(22, path);
        File idx1 = store1.getMiddle();
        try {
            Files.write(idx1.toPath(), "0\n1\n".getBytes(StandardCharsets.UTF_8));
            Assert.assertTrue(FileUtil.getUpInfoTmp(11, path).getRight().contains(Integer.valueOf(0)));
            Assert.assertTrue(FileUtil.getUpInfoTmp(11, path).getRight().contains(Integer.valueOf(1)));
            Assert.assertTrue(FileUtil.getUpInfoTmp(22, path).getRight().isEmpty());
        } finally {
            idx1.delete();
            store2.getMiddle().delete();
        }
    }

    @Test
    public void shouldUseDifferentTmpIdxFilePerNamespace() throws Exception {
        String key = "p2p.up.namespace";
        String previous = System.getProperty(key);
        String path = "namespace.bin";
        try {
            System.setProperty(key, "task-a");
            File idx1 = FileUtil.getUpInfoTmp(1, path).getMiddle();
            System.setProperty(key, "task-b");
            File idx2 = FileUtil.getUpInfoTmp(1, path).getMiddle();
            Assert.assertNotEquals(idx1.getAbsolutePath(), idx2.getAbsolutePath());
            idx1.delete();
            idx2.delete();
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }
}
