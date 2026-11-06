package ds;

import com.q3lives.ds.cluster.DsIdLayout;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.integration.QueryWrapper;
import com.q3lives.utils.SerializationUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.model.*;
import javax.net.p2p.server.handler.*;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Orm_P7_Tgt2_Handler_6_Test {

    static final int SA = 12, PA = 0, SB = 42, PB = 0;

    private static File tmpDir(String prefix) throws Exception {
        File f = Files.createTempDirectory(prefix).toFile();
        f.deleteOnExit();
        return f;
    }

    @AfterEach
    void clear() {
        Tgt3RegistryResolver.clearForTest();
    }

    @Test
    void T01_CmdContinuity_And_Q2_C2_rowId_local() throws Exception {
        File tmpA = tmpDir("orm_tgt2_A1_");
        File tmpB = tmpDir("orm_tgt2_B1_");
        try (DsDatabaseLocal dbA = new DsDatabaseLocal(new File(tmpA, SA + "_" + PA), SA, PA);
             DsDatabaseLocal dbB = new DsDatabaseLocal(new File(tmpB, SB + "_" + PB), SB, PB)) {
            Tgt3RegistryResolver.injectOrmForTest(SA, PA, dbA);
            Tgt3RegistryResolver.injectOrmForTest(SB, PB, dbB);

            Assertions.assertEquals(0, P2PCommand.ORM_PUT.getValue() - 24336);
            Assertions.assertEquals(8, P2PCommand.ORM_SAVE_OR_UPDATE.getValue() - P2PCommand.ORM_PUT.getValue());
            Assertions.assertEquals(0, P2PCommand.R_OK_ORM_PUT_ACK.getValue() - 24436);
            Assertions.assertEquals(0, P2PCommand.EXTA_REMOVE_ALL_COMMENTS.getValue() + 1 - 24336);

            Orm_P7_Tgt2_UserEntity user = new Orm_P7_Tgt2_UserEntity("Alice", 25, System.currentTimeMillis());
            byte[] entityBytes = SerializationUtil.serialize(user);
            OrmPutRequest putReq = new OrmPutRequest(SA, PA, SB, PB, 0L,
                    Orm_P7_Tgt2_UserEntity.class.getName(), entityBytes, false, null);
            P2PWrapper putResp = new OrmPutServerHandler().process(
                    P2PWrapper.build(1, P2PCommand.ORM_PUT, putReq));
            OrmPutAck putAck = (OrmPutAck) putResp.getData();
            long rowId = putAck.rowId;
            Assertions.assertTrue(rowId > 0, "rowId > 0");
            Assertions.assertEquals(0, DsIdLayout.serverId(rowId), "Q2=C2: DsIdLayout.serverId(rowId)=0, no serverId=SB embedded, got=" + DsIdLayout.serverId(rowId));
        }
    }

    @Test
    void T02_4tuple_PUT_GET_EXISTS_REMOVE_idempotent() throws Exception {
        File tmpA = tmpDir("orm_tgt2_A2_");
        File tmpB = tmpDir("orm_tgt2_B2_");
        try (DsDatabaseLocal dbA = new DsDatabaseLocal(new File(tmpA, SA + "_" + PA), SA, PA);
             DsDatabaseLocal dbB = new DsDatabaseLocal(new File(tmpB, SB + "_" + PB), SB, PB)) {
            Tgt3RegistryResolver.injectOrmForTest(SA, PA, dbA);
            Tgt3RegistryResolver.injectOrmForTest(SB, PB, dbB);

            Orm_P7_Tgt2_UserEntity user1 = new Orm_P7_Tgt2_UserEntity("Bob", 30, 1000L);
            byte[] entityBytes = SerializationUtil.serialize(user1);
            OrmPutRequest putReq = new OrmPutRequest(SA, PA, SB, PB, 0L,
                    Orm_P7_Tgt2_UserEntity.class.getName(), entityBytes, false, null);
            P2PWrapper putResp = new OrmPutServerHandler().process(
                    P2PWrapper.build(1, P2PCommand.ORM_PUT, putReq));
            OrmPutAck putAck = (OrmPutAck) putResp.getData();
            long id1 = putAck.rowId;

            OrmGetRequest getReq = new OrmGetRequest(SA, PA, SB, PB, 0L,
                    Orm_P7_Tgt2_UserEntity.class.getName(), id1, false);
            P2PWrapper getResp = new OrmGetServerHandler().process(
                    P2PWrapper.build(2, P2PCommand.ORM_GET, getReq));
            OrmGetAck getAck = (OrmGetAck) getResp.getData();
            Assertions.assertTrue(getAck.present, "GET present=true");
            Orm_P7_Tgt2_UserEntity loaded = new Orm_P7_Tgt2_UserEntity();
            loaded.load(ByteBuffer.wrap(getAck.entityBytes));
            Assertions.assertEquals(user1.name, loaded.name, "name equals");
            Assertions.assertEquals(user1.age, loaded.age, "age equals");

            OrmExistsRequest existsReq = new OrmExistsRequest(SA, PA, SB, PB, 0L,
                    Orm_P7_Tgt2_UserEntity.class.getName(), id1);
            P2PWrapper existsResp = new OrmExistsServerHandler().process(
                    P2PWrapper.build(3, P2PCommand.ORM_EXISTS, existsReq));
            OrmExistsAck existsAck = (OrmExistsAck) existsResp.getData();
            Assertions.assertTrue(existsAck.present, "EXISTS present=true");

            OrmRemoveRequest removeReq = new OrmRemoveRequest(SA, PA, SB, PB, 0L,
                    Orm_P7_Tgt2_UserEntity.class.getName(), id1, false);
            P2PWrapper removeResp = new OrmRemoveServerHandler().process(
                    P2PWrapper.build(4, P2PCommand.ORM_REMOVE, removeReq));
            OrmRemoveAck removeAck = (OrmRemoveAck) removeResp.getData();
            Assertions.assertTrue(removeAck.ok, "REMOVE ok=true");

            P2PWrapper existsResp2 = new OrmExistsServerHandler().process(
                    P2PWrapper.build(5, P2PCommand.ORM_EXISTS, existsReq));
            OrmExistsAck existsAck2 = (OrmExistsAck) existsResp2.getData();
            Assertions.assertFalse(existsAck2.present, "EXISTS after remove present=false");

            P2PWrapper removeResp2 = new OrmRemoveServerHandler().process(
                    P2PWrapper.build(6, P2PCommand.ORM_REMOVE, removeReq));
            OrmRemoveAck removeAck2 = (OrmRemoveAck) removeResp2.getData();
            Assertions.assertFalse(removeAck2.ok, "REMOVE 2nd time ok=false (idempotent)");
        }
    }

    @Test
    void T03_ThreeStdErrorScenarios() throws Exception {
        File tmpA = tmpDir("orm_tgt2_A3_");
        File tmpB = tmpDir("orm_tgt2_B3_");
        try (DsDatabaseLocal dbA = new DsDatabaseLocal(new File(tmpA, SA + "_" + PA), SA, PA);
             DsDatabaseLocal dbB = new DsDatabaseLocal(new File(tmpB, SB + "_" + PB), SB, PB)) {
            Tgt3RegistryResolver.injectOrmForTest(SA, PA, dbA);
            Tgt3RegistryResolver.injectOrmForTest(SB, PB, dbB);

            Orm_P7_Tgt2_UserEntity user = new Orm_P7_Tgt2_UserEntity("X", 1, 1L);
            byte[] ebytes = SerializationUtil.serialize(user);

            OrmPutRequest req1 = new OrmPutRequest(SA, PA, -1, -1, 0L,
                    Orm_P7_Tgt2_UserEntity.class.getName(), ebytes, false, null);
            P2PWrapper resp1 = new OrmPutServerHandler().process(
                    P2PWrapper.build(1, P2PCommand.ORM_PUT, req1));
            Assertions.assertEquals(P2PCommand.STD_ERROR, resp1.getCommand(),
                    "scenario 1: target=-1,-1 globalRowId=0 → STD_ERROR");

            OrmPutRequest req2 = new OrmPutRequest(SA, PA, 999, 999, 0L,
                    Orm_P7_Tgt2_UserEntity.class.getName(), ebytes, false, null);
            P2PWrapper resp2 = new OrmPutServerHandler().process(
                    P2PWrapper.build(2, P2PCommand.ORM_PUT, req2));
            Assertions.assertEquals(P2PCommand.STD_ERROR, resp2.getCommand(),
                    "scenario 2: not injected s/p → IAE → STD_ERROR");

            OrmPutRequest req3 = new OrmPutRequest(SA, PA, SB, PB, 0L,
                    "no.such.ClassNotFound", ebytes, false, null);
            P2PWrapper resp3 = new OrmPutServerHandler().process(
                    P2PWrapper.build(3, P2PCommand.ORM_PUT, req3));
            Assertions.assertEquals(P2PCommand.STD_ERROR, resp3.getCommand(),
                    "scenario 3: ClassNotFound → STD_ERROR");
        }
    }

    @Test
    void T04_ClassIdSetWarmUp_FindRange_size_equals_2() throws Exception {
        File tmpA = tmpDir("orm_tgt2_A4_");
        File tmpB = tmpDir("orm_tgt2_B4_");
        try (DsDatabaseLocal dbA = new DsDatabaseLocal(new File(tmpA, SA + "_" + PA), SA, PA);
             DsDatabaseLocal dbB = new DsDatabaseLocal(new File(tmpB, SB + "_" + PB), SB, PB)) {
            Tgt3RegistryResolver.injectOrmForTest(SA, PA, dbA);
            Tgt3RegistryResolver.injectOrmForTest(SB, PB, dbB);

            Orm_P7_Tgt2_UserEntity entA = new Orm_P7_Tgt2_UserEntity("A", 20, 1L);
            Orm_P7_Tgt2_UserEntity entB = new Orm_P7_Tgt2_UserEntity("B", 30, 2L);
            byte[] bytesA = SerializationUtil.serialize(entA);
            byte[] bytesB = SerializationUtil.serialize(entB);

            OrmSaveOrUpdateRequest saveReqA = new OrmSaveOrUpdateRequest(SA, PA, SB, PB, 0L,
                    Orm_P7_Tgt2_UserEntity.class.getName(), bytesA, false, null);
            P2PWrapper saveRespA = new OrmSaveOrUpdateServerHandler().process(
                    P2PWrapper.build(1, P2PCommand.ORM_SAVE_OR_UPDATE, saveReqA));
            OrmSaveOrUpdateAck saveAckA = (OrmSaveOrUpdateAck) saveRespA.getData();
            long idA = saveAckA.rowId;

            OrmSaveOrUpdateRequest saveReqB = new OrmSaveOrUpdateRequest(SA, PA, SB, PB, 0L,
                    Orm_P7_Tgt2_UserEntity.class.getName(), bytesB, false, null);
            P2PWrapper saveRespB = new OrmSaveOrUpdateServerHandler().process(
                    P2PWrapper.build(2, P2PCommand.ORM_SAVE_OR_UPDATE, saveReqB));
            OrmSaveOrUpdateAck saveAckB = (OrmSaveOrUpdateAck) saveRespB.getData();
            long idB = saveAckB.rowId;
            Assertions.assertNotEquals(idA, idB, "two entities rowId different");

            QueryWrapper<Orm_P7_Tgt2_UserEntity> w = new QueryWrapper<>();
            byte[] wb = SerializationUtil.serialize(w);
            OrmFindRangeRequest frReq = new OrmFindRangeRequest(SA, PA, SB, PB, 0L,
                    Orm_P7_Tgt2_UserEntity.class.getName(), wb, 0, 0);
            P2PWrapper frResp = new OrmFindRangeServerHandler().process(
                    P2PWrapper.build(3, P2PCommand.ORM_FIND_RANGE, frReq));
            OrmFindRangeAck frAck = (OrmFindRangeAck) frResp.getData();
            List<byte[]> ebl = frAck.entityBytesList;
            Assertions.assertEquals(2, ebl.size(), "findRange fullScan size=2");

            Set<Integer> ages = new HashSet<>();
            for (byte[] eb : ebl) {
                Orm_P7_Tgt2_UserEntity e = new Orm_P7_Tgt2_UserEntity();
                e.load(ByteBuffer.wrap(eb));
                ages.add(e.age);
            }
            Set<Integer> expected = new HashSet<>();
            expected.add(20);
            expected.add(30);
            Assertions.assertEquals(expected, ages, "age set {20,30}");
        }
    }

    @Test
    void T05_QueryWrapperSerialize_withRelations() throws Exception {
        File tmpA = tmpDir("orm_tgt2_A5_");
        File tmpB = tmpDir("orm_tgt2_B5_");
        try (DsDatabaseLocal dbA = new DsDatabaseLocal(new File(tmpA, SA + "_" + PA), SA, PA);
             DsDatabaseLocal dbB = new DsDatabaseLocal(new File(tmpB, SB + "_" + PB), SB, PB)) {
            Tgt3RegistryResolver.injectOrmForTest(SA, PA, dbA);
            Tgt3RegistryResolver.injectOrmForTest(SB, PB, dbB);

            long ts = System.currentTimeMillis();
            Orm_P7_Tgt2_UserEntity u22 = new Orm_P7_Tgt2_UserEntity("U22", 22, ts);
            Orm_P7_Tgt2_UserEntity u28 = new Orm_P7_Tgt2_UserEntity("U28", 28, ts + 1);
            Orm_P7_Tgt2_UserEntity u35 = new Orm_P7_Tgt2_UserEntity("U35", 35, ts + 2);
            for (Orm_P7_Tgt2_UserEntity u : new Orm_P7_Tgt2_UserEntity[]{u22, u28, u35}) {
                byte[] ub = SerializationUtil.serialize(u);
                OrmPutRequest pr = new OrmPutRequest(SA, PA, SB, PB, 0L,
                        Orm_P7_Tgt2_UserEntity.class.getName(), ub, false, null);
                new OrmPutServerHandler().process(P2PWrapper.build(1, P2PCommand.ORM_PUT, pr));
            }

            QueryWrapper<Orm_P7_Tgt2_UserEntity> qw = new QueryWrapper<>();
            qw.ge("age", 25);
            qw.lt("age", 35);
            byte[] wrapperBytes = SerializationUtil.serialize(qw);
            OrmFindRangeRequest frReq = new OrmFindRangeRequest(SA, PA, SB, PB, 0L,
                    Orm_P7_Tgt2_UserEntity.class.getName(), wrapperBytes, 0, 0);
            P2PWrapper frResp = new OrmFindRangeServerHandler().process(
                    P2PWrapper.build(2, P2PCommand.ORM_FIND_RANGE, frReq));
            OrmFindRangeAck frAck = (OrmFindRangeAck) frResp.getData();
            Assertions.assertEquals(1, frAck.entityBytesList.size(), "query age in [25,35) size=1");
            Orm_P7_Tgt2_UserEntity hit = new Orm_P7_Tgt2_UserEntity();
            hit.load(ByteBuffer.wrap(frAck.entityBytesList.get(0)));
            Assertions.assertEquals(28, hit.age, "hit age=28");

            OrmPutRequest putIdReq = new OrmPutRequest(SA, PA, SB, PB, 0L,
                    Orm_P7_Tgt2_UserEntity.class.getName(),
                    SerializationUtil.serialize(u28), false, null);
            P2PWrapper putIdResp = new OrmPutServerHandler().process(
                    P2PWrapper.build(3, P2PCommand.ORM_PUT, putIdReq));
            long rowId28 = ((OrmPutAck) putIdResp.getData()).rowId;

            OrmGetRequest getReq = new OrmGetRequest(SA, PA, SB, PB, 0L,
                    Orm_P7_Tgt2_UserEntity.class.getName(), rowId28, true);
            P2PWrapper getResp = new OrmGetServerHandler().process(
                    P2PWrapper.build(4, P2PCommand.ORM_GET, getReq));
            OrmGetAck getAck = (OrmGetAck) getResp.getData();
            Assertions.assertTrue(getAck.present, "GET withRelations=true present=true");
            Orm_P7_Tgt2_UserEntity rl = new Orm_P7_Tgt2_UserEntity();
            rl.load(ByteBuffer.wrap(getAck.entityBytes));
            Assertions.assertEquals("U28", rl.name, "withRelations name eq");
        }
    }

    @Test
    void T06_globalRowId_fallback_routing() throws Exception {
        File tmpA = tmpDir("orm_tgt2_A6_");
        File tmpB = tmpDir("orm_tgt2_B6_");
        try (DsDatabaseLocal dbA = new DsDatabaseLocal(new File(tmpA, SA + "_" + PA), SA, PA);
             DsDatabaseLocal dbB = new DsDatabaseLocal(new File(tmpB, SB + "_" + PB), SB, PB)) {
            Tgt3RegistryResolver.injectOrmForTest(SA, PA, dbA);
            Tgt3RegistryResolver.injectOrmForTest(SB, PB, dbB);

            Orm_P7_Tgt2_UserEntity u = new Orm_P7_Tgt2_UserEntity("fallbackUser", 99, 777L);
            byte[] ub = SerializationUtil.serialize(u);
            OrmPutRequest putReq = new OrmPutRequest(SA, PA, SB, PB, 0L,
                    Orm_P7_Tgt2_UserEntity.class.getName(), ub, false, null);
            P2PWrapper putResp = new OrmPutServerHandler().process(
                    P2PWrapper.build(1, P2PCommand.ORM_PUT, putReq));
            long rowId = ((OrmPutAck) putResp.getData()).rowId;
            Assertions.assertEquals(0, DsIdLayout.serverId(rowId), "Q2=C2: DsIdLayout.serverId(rowId)=0, pure local");

            long local = rowId & DsIdLayout.LOCAL_ADDRESS_MASK;
            if (local == 0) local = 1L;
            long globalRowId = DsIdLayout.compose(SB, PB, local);
            Assertions.assertEquals(SB, DsIdLayout.serverId(globalRowId), "composed serverId=SB");
            Assertions.assertEquals(PB, DsIdLayout.partitionId(globalRowId), "composed partId=PB");
            Assertions.assertEquals(local, DsIdLayout.localAddress(globalRowId), "composed local=masked rowId");

            OrmGetRequest getReq = new OrmGetRequest(SA, PA, -1, -1, globalRowId,
                    Orm_P7_Tgt2_UserEntity.class.getName(), rowId, false);
            P2PWrapper getResp = new OrmGetServerHandler().process(
                    P2PWrapper.build(2, P2PCommand.ORM_GET, getReq));
            Assertions.assertEquals(P2PCommand.R_OK_ORM_GET_ACK, getResp.getCommand(),
                    "fallback routing → R_OK_ORM_GET_ACK");
            OrmGetAck getAck = (OrmGetAck) getResp.getData();
            Assertions.assertTrue(getAck.present, "fallback present=true");
            Orm_P7_Tgt2_UserEntity loaded = new Orm_P7_Tgt2_UserEntity();
            loaded.load(ByteBuffer.wrap(getAck.entityBytes));
            Assertions.assertEquals(u.name, loaded.name, "fallback name eq");
            Assertions.assertEquals(u.age, loaded.age, "fallback age eq");
            Assertions.assertEquals(u.createdAt, loaded.createdAt, "fallback createdAt eq");
        }
    }
}
