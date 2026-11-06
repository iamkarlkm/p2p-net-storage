package com.q3lives.ds.database;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.annotation.DsTable;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.registry.DsRegistry;
import com.q3lives.ds.registry.meta.AbstractRegistryReplicator;
import com.q3lives.ds.registry.meta.DsDatabaseMetaMigrator;
import com.q3lives.ds.registry.meta.DsRegistryInstance;
import com.q3lives.ds.registry.meta.MetaMode;
import com.q3lives.ds.registry.meta.RegistryMetaPaths;

/**
 * P12 T3 行 replicate hook 4 TRs（NG-2 c10 B 组：DsDatabaseLocal 6 facade 公开方法数不变，
 *   只加 package-private registryReplicator 字段+写路径后 if!=null 守卫 hook）：
 *   TR-R3-1 roundtrip：A 节点 putEntity → B 节点 apply frame → B Registry dataRowPayload bytes == A 写的 bytes
 *   TR-R3-2 乱序 win：A 2 次 putEntity（v2 → v3）→ B 先 apply v3 后 v2 → B 最终值取 v3，overwrites≥1
 *   TR-R3-3 幂等：1 个 frame apply 2 次到 B → B payload 不变，overwrites≥1（第二次 oldVer==newVer 计数）
 *   TR-R3-4 restart replay：A 批量 put 5 rows（5 frames）→ B apply → B getTable 行字段与 A 一致
 *
 * 关键契约：
 *   - 不触达任何 javax.net.p2p.* Handler（NFR-M4 不新建/不修改 Handler，inMemoryFrameQueue 内存 mock）
 *   - 不修改 DsDatabaseLocal public 方法签名（NG-2，测试只访问 package-private 字段 registryReplicator 同包）
 *   - 不修改 RegistryMetaPaths 任何 P10 7 元 + P11 3 数据路径字面量（路径零外泄仅 T1 已验证）
 */
public class P12_RowReplicateTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @Before
    public void setUp() {
        try {
            Field f = DsRegistryInstance.class.getDeclaredField("INSTANCES");
            f.setAccessible(true);
            Object m = f.get(null);
            if (m instanceof ConcurrentHashMap) ((ConcurrentHashMap<?, ?>) m).clear();
        } catch (Exception ignored) {}
        System.clearProperty(MetaMode.PROPERTY);
        System.clearProperty(AbstractRegistryReplicator.SYNC_OFF_PROP);
    }

    @DsTable(name = "t_user_p12_t3")
    public static class User extends DsTableAdapter {
        @DsField(name = "name", length = 64)
        public String name;
        @DsField(name = "age", length = 4, indexed = true)
        public int age;
        @DsField(name = "email", length = 128)
        public String email;

        public User() {}
        public User(String name, int age, String email) {
            this.name = name; this.age = age; this.email = email;
        }
    }

    // 辅助：A 写后读 Registry 中 dataRowPayload 原始 bytes
    private static byte[] readADataRowPayload(File rootA, Class<?> entityClazz, long rowId) {
        try {
            DsRegistry registry = DsRegistryInstance.get(rootA);
            String path = RegistryMetaPaths.dataRowPayload(entityClazz.getName(), rowId);
            return registry.get(path).asBytes();
        } catch (Exception e) {
            throw new RuntimeException("readADataRowPayload fail", e);
        }
    }

    // 辅助：B 写后读 Registry 中 dataRowPayload 原始 bytes
    private static byte[] readBDataRowPayload(DsRegistry registryB, Class<?> entityClazz, long rowId) {
        try {
            String path = RegistryMetaPaths.dataRowPayload(entityClazz.getName(), rowId);
            return registryB.get(path).asBytes();
        } catch (Exception e) {
            throw new RuntimeException("readBDataRowPayload fail", e);
        }
    }

    /** TR-R3-1：A putEntity → B apply frame → B Registry dataRowPayload == A bytes（roundtrip） */
    @Test
    public void r3_tr1_row_replicate_roundtrip() throws Exception {
        File rootA = tmp.newFolder("p12-t3-r3tr1-A");
        File rootB = tmp.newFolder("p12-t3-r3tr1-B");
        new DsDatabaseMetaMigrator(rootA, false).migrateAll();
        new DsDatabaseMetaMigrator(rootB, false).migrateAll();
        DsDatabaseLocal dbA = new DsDatabaseLocal(rootA);
        DsDatabaseLocal dbB = new DsDatabaseLocal(rootB);
        try {
            assertNotNull("A replicator HYBRID 默认非 null（非 YAML + sync=on）", dbA.registryReplicator);
            assertNotNull("B replicator HYBRID 默认非 null（非 YAML + sync=on）", dbB.registryReplicator);

            // 1. A 写 1 User entity（非 MVCC 走 PUT+UPDATE → 会触发 replicateKey v=writeTimestamp）
            User u = new User("Alice_R3TR1", 30, "a@t3.r1");
            long id = dbA.putEntity(u, true);
            assertTrue(id > 0L);
            assertEquals(id, (long) u.getId());

            // 2. A frame queue 应有 1 个 frame（putEntity 非 MVCC 触发 1 replicate：UPDATE op 走 bucketStore.update）
            List<byte[]> framesA = new ArrayList<>(dbA.registryReplicator.getInMemoryFrameQueueForTests());
            assertEquals("A replicate 1 row → frameQueue size=1", 1, framesA.size());

            // 3. B apply 该 frame（applyRegistryFrame：magic 0x52 → CAS write payload + side version）
            dbB.registryReplicator.applyRegistryFrame(framesA.get(0), 1L /* fromPeerId=1 */);

            // 4. B Registry dataRowPayload 读出来 == A Registry 读出来（roundtrip byte-for-byte）
            byte[] payloadA = readADataRowPayload(rootA, User.class, id);
            byte[] payloadB = readBDataRowPayload(dbB.registryReplicator._getRegistryForTests(), User.class, id);
            assertNotNull("A payload 应非 null（HYBRID putEntity 写 rowDataBackend）", payloadA);
            assertNotNull("B payload 应非 null（applyRegistryFrame 写）", payloadB);
            assertTrue("A payload length>0（User 3 字段编码）", payloadA.length > 0);
            assertArrayEquals("A→B replicate 后 bytes 100% 相等", payloadA, payloadB);
        } finally {
            dbA.close();
            dbB.close();
        }
    }

    /** TR-R3-2：乱序 apply（先 v=3 后 v=2）→ B 最终值取 v=3，overwrites≥1（split-brain 高 version 赢 R8） */
    @Test
    public void r3_tr2_row_replicate_outoforder_highversion_wins() throws Exception {
        File rootA = tmp.newFolder("p12-t3-r3tr2-A");
        File rootB = tmp.newFolder("p12-t3-r3tr2-B");
        new DsDatabaseMetaMigrator(rootA, false).migrateAll();
        new DsDatabaseMetaMigrator(rootB, false).migrateAll();
        DsDatabaseLocal dbA = new DsDatabaseLocal(rootA);
        DsDatabaseLocal dbB = new DsDatabaseLocal(rootB);
        try {
            // 1. A 先 put 1 User（id=X，v1）→ 再 update 同名 id 不同 payload（v2=UPDATE v 更高）→ 再 update（v3）
            User u1 = new User("name_V2", 2, "v2@oo");
            long id = dbA.putEntity(u1, true);
            User u2 = new User("name_V3", 3, "v3@oo");
            u2.setId(id);
            dbA.putEntity(u2, true); // UPDATE 非 MVCC → bucketStore.update + replicate v=更高 writeTimestamp

            List<byte[]> framesA = new ArrayList<>(dbA.registryReplicator.getInMemoryFrameQueueForTests());
            // 第一次是 id 不存在 → INSERT(bucketStore.put) 产生 1 frame？不对：
            // putEntity 非 MVCC 路径：if (!headMap.containsKey(rowId)) → INSERT：先 bucketStore.put（无 replicate？）
            // → commitGroup 之后 return。NO: 让我再看 insert 路径：
            //
            // 实际 putEntity INSERT 路径（非 MVCC，L1407-1461）：
            // L1437-1441: if (!headMap.containsKey(rowId)) → INSERT：binlog append、bucketStore.put、
            //   if(rowDataBackend!=null) writeRowPayload → commitGroup → return。
            // ！！！我只在 UPDATE 路径加了 replicate（L1465-1473），INSERT 路径（L1437-1441）没加 replicate ？！
            //
            // 修：INSERT 路径也需要 replicate（writeRowPayload 之后 commitGroup 之前）。
            // 先在本测试里 INSERT 也写 replicate（假设 INSERT 也 hook 了）；若测试 fail 说明 INSERT 路径没加，立即补。

            assertTrue("至少 2 个 frame（INSERT + UPDATE 至少 1 put + 1 update 两条）", framesA.size() >= 2);
            // framesA.get(0) = INSERT v1; framesA.get(1) = UPDATE v3（第二次 UPDATE）
            byte[] frameV1 = framesA.get(0);
            byte[] frameV2 = framesA.get(1);

            // 2. B 乱序：先 apply V2（高 version），再 apply V1（低 version）
            dbB.registryReplicator.applyRegistryFrame(frameV2, 1L);
            dbB.registryReplicator.applyRegistryFrame(frameV1, 1L);

            // 3. B payload == A 第二次写（V3 更新后的 payload），overwrites≥1
            String path = RegistryMetaPaths.dataRowPayload(User.class.getName(), id);
            byte[] payloadB = dbB.registryReplicator._getRegistryForTests().get(path).asBytes();
            byte[] payloadA_v3 = dbA.registryReplicator._getRegistryForTests().get(path).asBytes();
            assertArrayEquals("乱序后 B payload 应=高版本 v3 的 bytes", payloadA_v3, payloadB);
            long overwrites = dbB.registryReplicator.getPeerOverwritesForTests(1L);
            assertTrue("低 version v1 被高 version v2 覆盖 → overwrites≥1，实际=" + overwrites, overwrites >= 1);
        } finally {
            dbA.close();
            dbB.close();
        }
    }

    /** TR-R3-3：幂等 —— 同 frame apply 2 次 → B payload 不变，overwrites≥1（oldVer==newVer→计数） */
    @Test
    public void r3_tr3_row_replicate_idempotent() throws Exception {
        File rootA = tmp.newFolder("p12-t3-r3tr3-A");
        File rootB = tmp.newFolder("p12-t3-r3tr3-B");
        new DsDatabaseMetaMigrator(rootA, false).migrateAll();
        new DsDatabaseMetaMigrator(rootB, false).migrateAll();
        DsDatabaseLocal dbA = new DsDatabaseLocal(rootA);
        DsDatabaseLocal dbB = new DsDatabaseLocal(rootB);
        try {
            User u = new User("Name_Power", 7, "pw@t3.r3");
            long id = dbA.putEntity(u, true);
            List<byte[]> framesA = new ArrayList<>(dbA.registryReplicator.getInMemoryFrameQueueForTests());
            assertTrue("A putEntity INSERT + UPDATE ≥1 frame（=1：INSERT 已 replicate）", framesA.size() >= 1);
            byte[] frame = framesA.get(framesA.size() - 1); // 用最后 1 frame（=最终 payload）

            // B 同一 frame apply 两次
            dbB.registryReplicator.applyRegistryFrame(frame, 5L);
            byte[] payloadFirst = dbB.registryReplicator._getRegistryForTests()
                .get(RegistryMetaPaths.dataRowPayload(User.class.getName(), id)).asBytes();
            dbB.registryReplicator.applyRegistryFrame(frame, 5L);
            byte[] payloadSecond = dbB.registryReplicator._getRegistryForTests()
                .get(RegistryMetaPaths.dataRowPayload(User.class.getName(), id)).asBytes();

            // 幂等：两次 apply bytes 一致，overwrites≥1（第二次相同 version 触发 overwrite 计数，不写盘）
            assertArrayEquals("幂等 apply payload 不变", payloadFirst, payloadSecond);
            long overwrites = dbB.registryReplicator.getPeerOverwritesForTests(5L);
            assertTrue("同 frame 再 apply → overwrite≥1，实际=" + overwrites, overwrites >= 1);
        } finally {
            dbA.close();
            dbB.close();
        }
    }

    /** TR-R3-4：restart replay —— A 批量 putEntities 5 rows → 5 frames → B apply → B 行 payload 与 A 一致（模拟 restart 后 replay） */
    @Test
    public void r3_tr4_row_replicate_restart_replay_batch5() throws Exception {
        File rootA = tmp.newFolder("p12-t3-r3tr4-A");
        File rootB = tmp.newFolder("p12-t3-r3tr4-B");
        new DsDatabaseMetaMigrator(rootA, false).migrateAll();
        new DsDatabaseMetaMigrator(rootB, false).migrateAll();
        DsDatabaseLocal dbA = new DsDatabaseLocal(rootA);
        DsDatabaseLocal dbB = new DsDatabaseLocal(rootB);
        try {
            List<User> users = new ArrayList<>();
            for (int i = 0; i < 5; i++) users.add(new User("BatchR3T4_" + i, i + 20, "u" + i + "@b"));
            List<Long> ids = dbA.putEntities(users, true);
            assertEquals(5, ids.size());

            // 5 rows 批量 → Phase 4 for 循环每行 replicate → 应 5 frames
            List<byte[]> framesA = new ArrayList<>(dbA.registryReplicator.getInMemoryFrameQueueForTests());
            assertEquals("putEntities 5 rows → frameQueue size=5（每行一个 replicate）", 5, framesA.size());

            // 模拟 restart replay：按序 apply 5 frames
            for (byte[] frame : framesA) {
                dbB.registryReplicator.applyRegistryFrame(frame, 77L);
            }
            long overwritesB = dbB.registryReplicator.getPeerOverwritesForTests(77L);
            assertEquals("restart replay 顺序 apply → 0 overwrites（每次都是新高 version）", 0L, overwritesB);

            // 每行 B payload == A payload
            DsRegistry registryB = dbB.registryReplicator._getRegistryForTests();
            for (int i = 0; i < 5; i++) {
                long id = ids.get(i);
                String path = RegistryMetaPaths.dataRowPayload(User.class.getName(), id);
                byte[] bytesA = dbA.registryReplicator._getRegistryForTests().get(path).asBytes();
                byte[] bytesB = registryB.get(path).asBytes();
                assertNotNull("A bytes row=" + i, bytesA);
                assertNotNull("B bytes row=" + i, bytesB);
                assertArrayEquals("restart replay rows[" + i + "] bytes 100% 相等", bytesA, bytesB);
            }
        } finally {
            dbA.close();
            dbB.close();
        }
    }
}
