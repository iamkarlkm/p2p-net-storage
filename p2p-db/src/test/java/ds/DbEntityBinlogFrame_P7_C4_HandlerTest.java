package ds;

import java.io.File;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.binlog.DsBinlogLayout;
import com.q3lives.ds.binlog.DsBinlogPosition;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.integration.GenericManager;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.model.DbEntityBinlogFrameAck;
import javax.net.p2p.model.DbEntityBinlogFrameRequest;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.server.handler.DbEntityBinlogFrameServerHandler;

public class DbEntityBinlogFrame_P7_C4_HandlerTest {

    public static class UserC4 extends DsTableAdapter {
        @DsField(name = "name", length = 48)
        public String name;
        @DsField(name = "age", length = 4)
        public int age;
        public UserC4() {}
    }

    private static File tmpDir(String prefix) throws Exception {
        File f = Files.createTempDirectory(prefix).toFile();
        f.deleteOnExit();
        return f;
    }

    @Test
    public void test_Tgt1_Handler_Process_10Rows_AppliedFrames_GE10() throws Exception {
        File rootA = tmpDir("dsdb_p7_tgt1_nodeA_");
        File rootB = tmpDir("dsdb_p7_tgt1_nodeB_");
        final int sA = 1, pA = 0, sB = 2, pB = 0;

        DsDatabaseLocal dbA = new DsDatabaseLocal(rootA, sA, pA);
        DsDatabaseLocal dbB = new DsDatabaseLocal(rootB, sB, pB);

        GenericManager<UserC4> gmA = new GenericManager<>(UserC4.class, dbA, sA, pA);
        GenericManager<UserC4> gmB = new GenericManager<>(UserC4.class, dbB, sB, pB);

        Set<String> expectedNames = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            UserC4 u = new UserC4();
            u.name = "tgt1_user_" + i;
            u.age = 20 + i;
            Assertions.assertEquals(1, gmA.saveOrUpdate(u));
            expectedNames.add(u.name);
        }
        dbA.getBinlogStore().forceFlushAll();
        long aSeqBefore = dbA.getBinlogStore().lastSequence();
        Assertions.assertTrue(aSeqBefore >= 10L);

        UserC4 warm = new UserC4();
        warm.name = "tgt1_warm_dummy";
        warm.age = 999;
        gmB.saveOrUpdate(warm);
        gmB.removeById(warm.getId());
        Assertions.assertEquals(0, gmB.findList(gmB.buildQueryWrapper()).size());

        DsBinlogPosition startPos = new DsBinlogPosition("", 0, 0);
        DsBinlogPosition endPos = dbA.getBinlogStore().getCurrentPosition();
        String startDayKey = startPos.dayKey();
        if (startDayKey == null || startDayKey.isEmpty()) {
            List<String> days = dbA.getBinlogStore().listDayKeys();
            if (!days.isEmpty()) startDayKey = days.get(0);
        }
        String endDayKey = endPos.dayKey();
        if (endDayKey == null || endDayKey.isEmpty()) {
            List<String> days = dbA.getBinlogStore().listDayKeys();
            if (!days.isEmpty()) endDayKey = days.get(days.size() - 1);
        }
        File aStoreDir = new File(rootA, "_binlog");
        File fixedFile = new File(aStoreDir, DsBinlogLayout.FILE_PREFIX + endDayKey + DsBinlogLayout.FIXED_SUFFIX);
        File dynFile = new File(aStoreDir, DsBinlogLayout.FILE_PREFIX + endDayKey + DsBinlogLayout.DYN_SUFFIX);
        byte[] fixedBytes = fixedFile.exists() ? Files.readAllBytes(fixedFile.toPath()) : new byte[0];
        byte[] dynBytes = dynFile.exists() ? Files.readAllBytes(dynFile.toPath()) : new byte[0];
        byte[] bytesBytes = new byte[0];
        Assertions.assertTrue(fixedBytes.length > 0, "fixed bytes non-empty");

        DbEntityBinlogFrameRequest req = new DbEntityBinlogFrameRequest(
                sA, pA, sB, pB,
                startDayKey, startPos.fixedOffset(), startPos.sequence(),
                endDayKey, endPos.fixedOffset(), endPos.sequence(),
                fixedBytes, dynBytes, bytesBytes);

        P2PWrapper<DbEntityBinlogFrameRequest> wrapper = P2PWrapper.build(1, P2PCommand.DB_ENTITY_BINLOG_FRAME, req);
        DbEntityBinlogFrameServerHandler handler = new DbEntityBinlogFrameServerHandler();
        P2PWrapper<Object> resp;
        try {
            DsDatabaseLocal.setCurrent(dbB);
            resp = handler.process(wrapper);
        } finally {
            DsDatabaseLocal.setCurrent(null);
        }
        Assertions.assertNotNull(resp);
        Assertions.assertEquals(P2PCommand.R_OK_DB_ENTITY_BINLOG_FRAME_ACK, resp.getCommand(),
                "resp command=ACK, got " + resp.getCommand() + ", innerErr=" + (resp.getData() instanceof Object[] ? java.util.Arrays.toString((Object[]) resp.getData()) : String.valueOf(resp.getData())));
        Object d = resp.getData();
        Assertions.assertTrue(d instanceof DbEntityBinlogFrameAck, "ack instanceof, got " + (d == null ? "null" : d.getClass()));
        DbEntityBinlogFrameAck ack = (DbEntityBinlogFrameAck) d;
        Assertions.assertTrue(ack.appliedFrames >= 10, "appliedFrames=" + ack.appliedFrames + " >=10");

        List<UserC4> all = gmB.findList(gmB.buildQueryWrapper());
        Assertions.assertEquals(10, all.size(), "gmB.findList size=10, got=" + all.size());
        Set<String> actualNames = new HashSet<>();
        for (UserC4 u : all) actualNames.add(u.name);
        Assertions.assertEquals(expectedNames, actualNames);

        dbA.close();
        dbB.close();
    }
}
