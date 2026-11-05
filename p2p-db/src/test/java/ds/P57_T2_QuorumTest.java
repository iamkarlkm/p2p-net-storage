package ds;

import com.q3lives.ds.registry.meta.raft.RaftServer;
import org.junit.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;

/** P57 T2：quorum 单一出处（quorumOf 泛化 + 两处内联公式去重）。 */
public class P57_T2_QuorumTest {

    @Test(timeout = 10000)
    public void t01_quorum_scale() throws Exception {
        Method m = RaftServer.class.getDeclaredMethod("quorumOf", int.class);
        m.setAccessible(true);
        assertEquals(1, ((Number) m.invoke(null, 1)).intValue());
        assertEquals(2, ((Number) m.invoke(null, 2)).intValue());
        assertEquals(2, ((Number) m.invoke(null, 3)).intValue());
        assertEquals(3, ((Number) m.invoke(null, 4)).intValue());
        assertEquals(3, ((Number) m.invoke(null, 5)).intValue());
    }

    @Test(timeout = 10000)
    public void t02_single_source() throws Exception {
        String src = readSource("src/main/java/com/q3lives/ds/registry/meta/raft/RaftServer.java");
        assertEquals("old election/commit inline formula must be gone", 0, count(src, "(total / 2) + 1"));
        assertEquals("old election/commit guard must be gone", 0, count(src, "total == 1 ? 1"));
        assertEquals("quorum formula must have a single source", 1, count(src, "(clusterSize / 2) + 1"));
    }

    private static int count(String src, String needle) {
        int n = 0;
        int i = 0;
        while ((i = src.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    private static String readSource(String rel) throws Exception {
        Path[] candidates = {
                Paths.get(rel),
                Paths.get(System.getProperty("user.dir"), rel),
                Paths.get(System.getProperty("user.dir"), "p2p-db", rel)
        };
        for (Path p : candidates) {
            if (Files.exists(p)) {
                return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("RaftServer.java source not found from " + System.getProperty("user.dir"));
    }
}
