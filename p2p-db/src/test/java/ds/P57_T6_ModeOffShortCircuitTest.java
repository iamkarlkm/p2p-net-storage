package ds;

import com.q3lives.ds.registry.meta.MetaMode;
import com.q3lives.ds.registry.meta.raft.RaftServer;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** P57 T6：`raft.mode=off` / YAML 短路复核（TR-T6-R3 子断言）。 */
public class P57_T6_ModeOffShortCircuitTest {

    private final List<RaftServer> created = new ArrayList<>();

    @After
    public void cleanup() {
        for (RaftServer s : created) {
            try { s.stop(); } catch (Exception ignored) {}
        }
        created.clear();
        RaftServer.PEERS.clear();
    }

    private RaftServer track(RaftServer s) {
        if (s != null) created.add(s);
        return s;
    }

    @Test(timeout = 20000)
    public void t01_mode_off_and_yaml_short_circuit_null() {
        File root = new File(System.getProperty("java.io.tmpdir"), "p57-t6-t01");
        root.mkdirs();

        Map<String, String> off = new HashMap<>();
        off.put("p2p.orm.raft.mode", "off");
        assertNull("3 参 create + mode=off → null",
                track(RaftServer.create(root, MetaMode.REGISTRY, off)));
        assertNull("5 参 create + mode=off → null",
                track(RaftServer.create(root, MetaMode.REGISTRY, off, 1L, new long[]{2L})));
        assertNull("YAML 模式短路 → null",
                track(RaftServer.create(root, MetaMode.YAML)));
        assertEquals("无副作用：PEERS 未残留", 0, RaftServer.PEERS.size());
    }
}
