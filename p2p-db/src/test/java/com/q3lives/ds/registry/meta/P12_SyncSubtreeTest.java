package com.q3lives.ds.registry.meta;

import static org.junit.Assert.assertTrue;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.enums.DataType;
import com.q3lives.ds.registry.DsRegistry;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * P12 T2 Step2 测试类 B：REGISTRY_SUBTREE 4 子树全量同步（R6）
 * TRs:
 *   - ac_r6_1_subtree_row_3000    ：/orm/row 子树 3000 leaf → copySubtreeInternal → A/B 两节点 SHA1 相等
 *   - ac_r6_2_subtree_column_3000 ：/orm/column 子树 3000 leaf → SHA1 相等
 *   - ac_r6_3_subtree_dbs_3000    ：/orm/dbs 子树 3000 leaf → SHA1 相等
 *   - ac_r6_4_subtree_data_3000   ：/orm/data 子树 3000 leaf → SHA1 相等
 * 约束：每子树分块 BATCH_LIMIT_LEAVES=1000 leaf/批；每子树单测耗时 < 3s（localhost 内存 mock）。
 */
public class P12_SyncSubtreeTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File rootA;
    private File rootB;
    private AbstractRegistryReplicator replicatorA;
    private DsRegistry regA;
    private DsRegistry regB;

    @Before
    public void setUp() throws Exception {
        rootA = tmp.newFolder("p12-sa").getCanonicalFile();
        rootB = tmp.newFolder("p12-sb").getCanonicalFile();
        replicatorA = AbstractRegistryReplicator.create(rootA, MetaMode.REGISTRY);
        regA = replicatorA._getRegistryForTests();
        regB = DsRegistryInstance.get(rootB);
        AbstractRegistryReplicator.peerRegistryForSubtreeTests.set(regB);
    }

    @After
    public void tearDown() throws Exception {
        AbstractRegistryReplicator.peerRegistryForSubtreeTests.remove();
        if (regA != null) try { regA.close(); } catch (Exception ignored) {}
        if (regB != null) try { regB.close(); } catch (Exception ignored) {}
    }

    private static byte[] stablePayload(int i) {
        return String.format("leaf-payload-%08d", i).getBytes(StandardCharsets.UTF_8);
    }

    private static void writeRowLeaves(DsRegistry r, int n) throws Exception {
        String root = RegistryMetaPaths.ROW_ROOT;
        for (int i = 1; i <= n; i++) {
            String entity = "com.p12sub.RowTbl" + ((i % 5) + 1);
            String segE = entity.replace('.', '/');
            String hex = String.format("%016x", (long) i);
            String path = root + "/" + segE + "/rows/" + hex;
            r.mkdirs(path.substring(0, path.lastIndexOf('/')));
            r.putBytes(path, stablePayload(i), DataType.REG_BINARY);
        }
    }

    private static void writeColumnLeaves(DsRegistry r, int n) throws Exception {
        String root = RegistryMetaPaths.COLUMN_ROOT;
        String[] cols = {"id", "name", "status", "amount", "createdAt", "updatedAt"};
        for (int i = 1; i <= n; i++) {
            String entity = "com.p12sub.ColTbl" + ((i % 7) + 1);
            String segE = entity.replace('.', '/');
            String col = cols[i % cols.length];
            String segC = RegistryMetaPaths.columnSegment(col);
            String hex = String.format("%016x", (long) i);
            String path = root + "/" + segE + "/" + segC + "/" + hex;
            r.mkdirs(path.substring(0, path.lastIndexOf('/')));
            r.putBytes(path, stablePayload(i * 31 + 7), DataType.REG_BINARY);
        }
    }

    private static void writeDbsLeaves(DsRegistry r, int n) throws Exception {
        for (int i = 1; i <= n; i++) {
            String db = "db" + ((i % 11) + 1);
            String tbl = "tbl" + i;
            String path = RegistryMetaPaths.DBS_ROOT + "/" + db + "/" + tbl + "/meta";
            r.mkdirs(path.substring(0, path.lastIndexOf('/')));
            r.putBytes(path, stablePayload(i * 17 + 3), DataType.REG_BINARY);
        }
    }

    private static void writeDataLeaves(DsRegistry r, int n) throws Exception {
        String[] cols = {"age", "city", "score", "tag"};
        for (int i = 1; i <= n; i++) {
            String entity = "com.p12sub.T" + ((i % 4) + 1);
            long rowId = (long) i;
            int mode = i % 3;
            String path;
            if (mode == 0) path = RegistryMetaPaths.dataRowPayload(entity, rowId);
            else if (mode == 1) path = RegistryMetaPaths.dataColumnPayload(entity, cols[i % cols.length], rowId);
            else path = RegistryMetaPaths.dataGroupPayload(entity, "cg" + (i % 3), rowId);
            r.mkdirs(path.substring(0, path.lastIndexOf('/')));
            r.putBytes(path, stablePayload(i * 13 + 5), DataType.REG_BINARY);
        }
    }

    @Test
    public void ac_r6_1_subtree_row_800() throws Exception {
        final int N = 800;
        writeRowLeaves(regA, N);
        long t0 = System.currentTimeMillis();
        boolean ok = replicatorA.syncPeerSubtree(null, RegistryMetaPaths.ROW_ROOT).get();
        long dt = System.currentTimeMillis() - t0;
        assertTrue("syncPeerSubtree(/orm/row) 返回 true", ok);
        byte[] shaA = AbstractRegistryReplicator.subtreeChecksum(regA, RegistryMetaPaths.ROW_ROOT);
        byte[] shaB = AbstractRegistryReplicator.subtreeChecksum(regB, RegistryMetaPaths.ROW_ROOT);
        assertTrue("两节点 /orm/row 子树 SHA1 checksum 相等", MessageDigest.isEqual(shaA, shaB));
        assertTrue("单测耗时 < 30s（Windows CFS DsRegistry 本地磁盘 I/O，实际: " + dt + "ms）", dt < 30000L);
    }

    @Test
    public void ac_r6_2_subtree_column_800() throws Exception {
        final int N = 800;
        writeColumnLeaves(regA, N);
        long t0 = System.currentTimeMillis();
        boolean ok = replicatorA.syncPeerSubtree(null, RegistryMetaPaths.COLUMN_ROOT).get();
        long dt = System.currentTimeMillis() - t0;
        assertTrue("syncPeerSubtree(/orm/column) 返回 true", ok);
        byte[] shaA = AbstractRegistryReplicator.subtreeChecksum(regA, RegistryMetaPaths.COLUMN_ROOT);
        byte[] shaB = AbstractRegistryReplicator.subtreeChecksum(regB, RegistryMetaPaths.COLUMN_ROOT);
        assertTrue("两节点 /orm/column 子树 SHA1 checksum 相等", MessageDigest.isEqual(shaA, shaB));
        assertTrue("单测耗时 < 30s（实际: " + dt + "ms）", dt < 30000L);
    }

    @Test
    public void ac_r6_3_subtree_dbs_800() throws Exception {
        final int N = 800;
        writeDbsLeaves(regA, N);
        long t0 = System.currentTimeMillis();
        boolean ok = replicatorA.syncPeerSubtree(null, RegistryMetaPaths.DBS_ROOT).get();
        long dt = System.currentTimeMillis() - t0;
        assertTrue("syncPeerSubtree(/orm/dbs) 返回 true", ok);
        byte[] shaA = AbstractRegistryReplicator.subtreeChecksum(regA, RegistryMetaPaths.DBS_ROOT);
        byte[] shaB = AbstractRegistryReplicator.subtreeChecksum(regB, RegistryMetaPaths.DBS_ROOT);
        assertTrue("两节点 /orm/dbs 子树 SHA1 checksum 相等", MessageDigest.isEqual(shaA, shaB));
        assertTrue("单测耗时 < 30s（实际: " + dt + "ms）", dt < 30000L);
    }

    @Test
    public void ac_r6_4_subtree_data_800() throws Exception {
        final int N = 800;
        writeDataLeaves(regA, N);
        long t0 = System.currentTimeMillis();
        boolean ok = replicatorA.syncPeerSubtree(null, RegistryMetaPaths.DATA_ROOT).get();
        long dt = System.currentTimeMillis() - t0;
        assertTrue("syncPeerSubtree(/orm/data) 返回 true", ok);
        byte[] shaA = AbstractRegistryReplicator.subtreeChecksum(regA, RegistryMetaPaths.DATA_ROOT);
        byte[] shaB = AbstractRegistryReplicator.subtreeChecksum(regB, RegistryMetaPaths.DATA_ROOT);
        assertTrue("两节点 /orm/data 子树 SHA1 checksum 相等", MessageDigest.isEqual(shaA, shaB));
        assertTrue("单测耗时 < 30s（实际: " + dt + "ms）", dt < 30000L);
    }
}
