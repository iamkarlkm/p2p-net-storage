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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/** P57 T1：props 驱动 N 节点 roster（parseMembers/parseLocalPeer + 3 参 create + 2 参等价委托）。 */
public class P57_T1_RosterFactoryTest {

    private final List<RaftServer> created = new ArrayList<>();

    @After
    public void cleanup() {
        for (RaftServer s : created) {
            try { s.stop(); } catch (Exception ignored) {}
        }
        created.clear();
        RaftServer.PEERS.clear();
    }

    private RaftServer create(File root, MetaMode mode, Map<String, String> props) {
        RaftServer s = RaftServer.create(root, mode, props);
        if (s != null) created.add(s);
        return s;
    }

    private RaftServer create2(File root, MetaMode mode) {
        RaftServer s = RaftServer.create(root, mode);
        if (s != null) created.add(s);
        return s;
    }

    private static Map<String, String> props(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static void assertLocal1Peers2(RaftServer s) {
        assertNotNull("raft enabled", s);
        assertEquals(1L, s.getLocalPeerId());
        assertNotNull("peer 2 present", s.peerState(2L));
        assertNull("peer 1 filtered (local)", s.peerState(1L));
        assertNull("peer 3 absent", s.peerState(3L));
    }

    @Test(timeout = 20000)
    public void t01_members_csv_3node() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "p57-t1-t01");
        root.mkdirs();
        RaftServer s = create(root, MetaMode.REGISTRY,
                props("p2p.orm.raft.members", "1,2,3", "p2p.orm.raft.local", "2"));
        assertNotNull(s);
        assertEquals(2L, s.getLocalPeerId());
        assertNotNull("peer 1 present", s.peerState(1L));
        assertNotNull("peer 3 present", s.peerState(3L));
        assertNull("local peer 2 filtered", s.peerState(2L));
    }

    @Test(timeout = 20000)
    public void t02_default_backward_compat() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "p57-t1-t02");
        root.mkdirs();
        RaftServer viaPropsNull = create(root, MetaMode.REGISTRY, null);
        RaftServer viaTwoArg = create2(root, MetaMode.REGISTRY);
        RaftServer viaEmptyProps = create(root, MetaMode.REGISTRY, new HashMap<>());
        assertLocal1Peers2(viaPropsNull);
        assertLocal1Peers2(viaTwoArg);
        assertLocal1Peers2(viaEmptyProps);
    }

    @Test(timeout = 20000)
    public void t03_invalid_iae() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "p57-t1-t03");
        root.mkdirs();
        assertIae(root, props("p2p.orm.raft.members", "1,x,3"), "p2p.orm.raft.members");
        assertIae(root, props("p2p.orm.raft.members", "1,,3"), "p2p.orm.raft.members");
        assertIae(root, props("p2p.orm.raft.members", "2,2"), "p2p.orm.raft.members");
        assertIae(root, props("p2p.orm.raft.local", "zz"), "p2p.orm.raft.local");
    }

    private void assertIae(File root, Map<String, String> props, String key) {
        try {
            RaftServer s = RaftServer.create(root, MetaMode.REGISTRY, props);
            if (s != null) created.add(s);
            fail("expected IllegalArgumentException for " + props);
        } catch (IllegalArgumentException iae) {
            assertNotNull(iae.getMessage());
            if (!iae.getMessage().contains(key)) {
                fail("message '" + iae.getMessage() + "' should contain key " + key);
            }
        }
    }
}
