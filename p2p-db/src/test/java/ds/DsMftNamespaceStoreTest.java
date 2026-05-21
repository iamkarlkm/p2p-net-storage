package ds;

import com.q3lives.ds.fs.mft.DsMftFileSystemConfigLoader;
import com.q3lives.ds.fs.mft.DsMftNamespaceStore;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import static org.junit.Assert.*;

public class DsMftNamespaceStoreTest {

    @Test
    public void shouldLoadFromYamlAndCreateNamespaceDirs() throws Exception {
        File dir = new File("target/ds-mft-ns-test-" + System.nanoTime());
        dir.mkdirs();

        Path ns = dir.toPath().resolve("ns1");
        Path yaml = dir.toPath().resolve("dsfs.yaml");
        Files.writeString(yaml, ""
            + "namespaceDir: \"./ns1\"\n"
            + "atimeEnabled: true\n"
            + "tagsInitialRingCap: 8\n");

        String old = System.getProperty("ds.fs.yaml");
        System.setProperty("ds.fs.yaml", yaml.toAbsolutePath().toString());
        try {
            DsMftFileSystemConfigLoader.LoadedConfig loaded = DsMftFileSystemConfigLoader.load();
            assertEquals(ns.toAbsolutePath().normalize().toString(), loaded.config.getNamespaceDir());
            assertTrue(loaded.config.isAtimeEnabled());
            assertEquals(8, loaded.config.getTagsInitialRingCap());

            try (DsMftNamespaceStore store = DsMftNamespaceStore.openFromYaml()) {
                assertEquals(ns.toAbsolutePath().normalize(), store.getNamespaceDir());
                assertTrue(store.isAtimeEnabled());
                assertNotNull(store.fileIdToAtimeMillisMap());
            }
        } finally {
            if (old == null) {
                System.clearProperty("ds.fs.yaml");
            } else {
                System.setProperty("ds.fs.yaml", old);
            }
        }
    }
}

