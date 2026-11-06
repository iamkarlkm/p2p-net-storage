package ds;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import com.q3lives.ds.enums.DataType;
import com.q3lives.ds.registry.DsRegistry;
import com.q3lives.ds.registry.RegistryEntry;

public class DsRegistry_P24_MultiSzListTest {

    @Test
    public void testCodec_RoundTrip_ShortAndEmpty_ThroughRegistry() throws Exception {
        File tmp = Files.createTempDirectory("dsregp24-").toFile();
        tmp.deleteOnExit();
        List<String> src = Arrays.asList("a", "bb", "ccc", "", "ddddddddddddddddddddddddddddddddddd");
        try (DsRegistry r = new DsRegistry(new File(tmp, "s0").getAbsolutePath())) {
            r.putList("/t/a", src, DataType.REG_MULTI_SZ);
            assertEquals(src, r.get("/t/a").asList());
            r.putList("/t/empty", Collections.emptyList(), DataType.REG_LIST);
            assertEquals(Collections.emptyList(), r.get("/t/empty").asList());
        }
    }

    @Test
    public void testCodec_AllowsNulAndUnicodeInsideElement_ThroughRegistry() throws Exception {
        File tmp = Files.createTempDirectory("dsregp24-").toFile();
        tmp.deleteOnExit();
        List<String> src = Arrays.asList("x\0y", "", "\u1234\u5678\u9ABC", "ab\0\0\0cd");
        try (DsRegistry r = new DsRegistry(new File(tmp, "s0b").getAbsolutePath())) {
            r.putList("/t/nul", src, DataType.REG_MULTI_SZ);
            assertEquals(src, r.get("/t/nul").asList(), "NUL/Unicode 元素损坏");
        }
    }

    @Test
    public void testPutList_RoundTrip_RegMultiSz_ShortInline() throws Exception {
        File tmp = Files.createTempDirectory("dsregp24-").toFile();
        tmp.deleteOnExit();
        try (DsRegistry r = new DsRegistry(new File(tmp, "s1").getAbsolutePath())) {
            List<String> src = Arrays.asList("a", "b", "c");
            r.putList("/short/m", src, DataType.REG_MULTI_SZ);
            RegistryEntry e = r.get("/short/m");
            assertEquals(DataType.REG_MULTI_SZ, e.dataType());
            assertEquals(src, e.asList());
        }
    }

    @Test
    public void testPutList_RoundTrip_RegList_LargeExternal() throws Exception {
        File tmp = Files.createTempDirectory("dsregp24-").toFile();
        tmp.deleteOnExit();
        Path dir = new File(tmp, "s2").toPath().toAbsolutePath();
        List<String> big = new ArrayList<>();
        for (int i = 0; i < 1000; i++) big.add("item-number-" + i + "-value-" + "x".repeat(10));
        try (DsRegistry r = new DsRegistry(dir.toString())) {
            r.putList("/big/list", big, DataType.REG_LIST);
            RegistryEntry e = r.get("/big/list");
            assertEquals(DataType.REG_LIST, e.dataType());
            assertEquals(big, e.asList());
        }
        try (DsRegistry r = new DsRegistry(dir.toString())) {
            RegistryEntry e = r.get("/big/list");
            assertTrue(e.isPresent());
            assertEquals(big, e.asList(), "close/reopen 后列表不一致");
        }
    }

    @Test
    public void testPutList_RejectsInvalidType_AndNullSafe() throws Exception {
        File tmp = Files.createTempDirectory("dsregp24-").toFile();
        tmp.deleteOnExit();
        try (DsRegistry r = new DsRegistry(new File(tmp, "s3").getAbsolutePath())) {
            assertThrows(IllegalArgumentException.class, () -> r.putList("/bad", Collections.singletonList("x"), DataType.REG_SZ));
            assertThrows(IllegalArgumentException.class, () -> r.putList("/bad2", Collections.singletonList("x"), DataType.REG_BINARY));
            r.putList("/nullval", null, DataType.REG_LIST);
            RegistryEntry e = r.get("/nullval");
            assertTrue(e.isPresent());
            assertEquals(Collections.emptyList(), e.asList());
        }
    }
}
