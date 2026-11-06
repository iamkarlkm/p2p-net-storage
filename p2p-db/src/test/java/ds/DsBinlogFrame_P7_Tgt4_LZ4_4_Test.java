package ds;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.binlog.DsBinlogLayout;
import com.q3lives.ds.binlog.DsBinlogPosition;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.columnar.ColumnCompression;
import com.q3lives.ds.database.integration.GenericManager;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.model.DbEntityBinlogFrameAck;
import javax.net.p2p.model.DbEntityBinlogFrameRequest;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.server.handler.DbEntityBinlogFrameServerHandler;

import static ds.BinlogFrameCompressHelper.LZ4_THRESHOLD;
import static ds.BinlogFrameCompressHelper.maybeCompress;
import static ds.BinlogFrameCompressHelper.maybeCompressAll;

public class DsBinlogFrame_P7_Tgt4_LZ4_4_Test {

    public static class UserTgt4 extends DsTableAdapter {
        @DsField(name = "name", length = 256)
        public String name;
        @DsField(name = "age", length = 4)
        public int age;
        public UserTgt4() {}
    }

    private static File tmpDir(String prefix) throws Exception {
        File f = Files.createTempDirectory(prefix).toFile();
        f.deleteOnExit();
        return f;
    }

    @Test
    public void test_T01_LZ4_roundTrip_highRedundancy_100KB() throws Exception {
        byte[] data = new byte[100_000];
        Arrays.fill(data, (byte) 'A');
        byte[] compressed = ColumnCompression.compress(data, LZ4_THRESHOLD);
        Assertions.assertNotNull(compressed, "100KB redundant should be compressed");
        Assertions.assertTrue(ColumnCompression.isCompressed(compressed), "isCompressed=true");
        Assertions.assertTrue(compressed.length <= 50_000,
                "压缩率>=2.0x: compressed=" + compressed.length + " <= 50000");
        byte[] restored = ColumnCompression.decompress(compressed);
        Assertions.assertNotNull(restored, "decompress non-null");
        Assertions.assertEquals(100_000, restored.length, "restored length");
        Assertions.assertTrue(Arrays.equals(data, restored), "round-trip byte-equals");

        byte[] smallRand = new byte[32768];
        new Random(42L).nextBytes(smallRand);
        byte[] cSmall = ColumnCompression.compress(smallRand, LZ4_THRESHOLD);
        Assertions.assertNull(cSmall, "<64KB random no compress attempt");

        byte[] bigRand = new byte[131072];
        new Random(1234L).nextBytes(bigRand);
        byte[] cBigRand = ColumnCompression.compress(bigRand, LZ4_THRESHOLD);
        Assertions.assertNull(cBigRand, "已压缩随机字节 100% 回退 null 防膨胀");
    }

    @Test
    public void test_T02_belowThreshold_32KB_zeroDegrade_baseline() throws Exception {
        File rootA = tmpDir("dsdb_p7_tgt4_T02_A_");
        File rootB = tmpDir("dsdb_p7_tgt4_T02_B_");
        final int sA = 11, pA = 0, sB = 12, pB = 0;
        DsDatabaseLocal dbA = new DsDatabaseLocal(rootA, sA, pA);
        DsDatabaseLocal dbB = new DsDatabaseLocal(rootB, sB, pB);
        try {
            GenericManager<UserTgt4> gmA = new GenericManager<>(UserTgt4.class, dbA, sA, pA);
            GenericManager<UserTgt4> gmB = new GenericManager<>(UserTgt4.class, dbB, sB, pB);
            Set<String> expectedNames = new HashSet<>();
            for (int i = 0; i < 10; i++) {
                UserTgt4 u = new UserTgt4();
                u.name = "tgt4_T02_user_" + i;
                u.age = 20 + i;
                Assertions.assertEquals(1, gmA.saveOrUpdate(u));
                expectedNames.add(u.name);
            }
            dbA.getBinlogStore().forceFlushAll();
            UserTgt4 warm = new UserTgt4();
            warm.name = "tgt4_T02_warm_dummy";
            warm.age = 9999;
            gmB.saveOrUpdate(warm);
            gmB.removeById(warm.getId());
            Assertions.assertEquals(0, gmB.findList(gmB.buildQueryWrapper()).size());

            DsBinlogPosition endPos = dbA.getBinlogStore().getCurrentPosition();
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
            Assertions.assertTrue(fixedBytes.length > 0);

            DbEntityBinlogFrameRequest req = new DbEntityBinlogFrameRequest(
                    sA, pA, sB, pB,
                    endDayKey, 0L, 0L,
                    endDayKey, endPos.fixedOffset(), endPos.sequence(),
                    fixedBytes, dynBytes, new byte[0]);

            P2PWrapper<DbEntityBinlogFrameRequest> wrapper = P2PWrapper.build(2, P2PCommand.DB_ENTITY_BINLOG_FRAME, req);
            DbEntityBinlogFrameServerHandler handler = new DbEntityBinlogFrameServerHandler();
            P2PWrapper<Object> resp;
            try {
                DsDatabaseLocal.setCurrent(dbB);
                resp = handler.process(wrapper);
            } finally {
                DsDatabaseLocal.setCurrent(null);
            }
            Assertions.assertNotNull(resp);
            Assertions.assertEquals(P2PCommand.R_OK_DB_ENTITY_BINLOG_FRAME_ACK, resp.getCommand());
            DbEntityBinlogFrameAck ack = (DbEntityBinlogFrameAck) resp.getData();
            Assertions.assertTrue(ack.appliedFrames >= 10, "applied=" + ack.appliedFrames + " >=10");
            Assertions.assertNull(ack.error, "error null");
            List<UserTgt4> all = gmB.findList(gmB.buildQueryWrapper());
            Assertions.assertEquals(10, all.size());
            Set<String> actual = new HashSet<>();
            for (UserTgt4 u : all) actual.add(u.name);
            Assertions.assertEquals(expectedNames, actual);
        } finally {
            dbA.close();
            dbB.close();
        }
    }

    @Test
    public void test_T03_Handler_50rows_bigFields_compressed_applied50() throws Exception {
        File rootA = tmpDir("dsdb_p7_tgt4_T03_A_");
        File rootB = tmpDir("dsdb_p7_tgt4_T03_B_");
        final int sA = 21, pA = 0, sB = 22, pB = 0;
        DsDatabaseLocal dbA = new DsDatabaseLocal(rootA, sA, pA);
        DsDatabaseLocal dbB = new DsDatabaseLocal(rootB, sB, pB);
        try {
            GenericManager<UserTgt4> gmA = new GenericManager<>(UserTgt4.class, dbA, sA, pA);
            GenericManager<UserTgt4> gmB = new GenericManager<>(UserTgt4.class, dbB, sB, pB);
            StringBuilder huge = new StringBuilder(256);
            for (int k = 0; k < 50; k++) huge.append('a');
            Set<String> expectedNames = new HashSet<>();
            for (int i = 0; i < 50; i++) {
                UserTgt4 u = new UserTgt4();
                u.name = huge.toString() + "_" + i;
                u.age = 20 + (i % 30);
                Assertions.assertEquals(1, gmA.saveOrUpdate(u));
                expectedNames.add(u.name);
            }
            dbA.getBinlogStore().forceFlushAll();

            UserTgt4 warm = new UserTgt4();
            warm.name = "tgt4_T03_warm";
            warm.age = -1;
            gmB.saveOrUpdate(warm);
            gmB.removeById(warm.getId());
            Assertions.assertEquals(0, gmB.findList(gmB.buildQueryWrapper()).size());

            DsBinlogPosition endPos = dbA.getBinlogStore().getCurrentPosition();
            String endDayKey = endPos.dayKey();
            if (endDayKey == null || endDayKey.isEmpty()) {
                List<String> days = dbA.getBinlogStore().listDayKeys();
                if (!days.isEmpty()) endDayKey = days.get(days.size() - 1);
            }
            File aStoreDir = new File(rootA, "_binlog");
            File fixedFile = new File(aStoreDir, DsBinlogLayout.FILE_PREFIX + endDayKey + DsBinlogLayout.FIXED_SUFFIX);
            File dynFile = new File(aStoreDir, DsBinlogLayout.FILE_PREFIX + endDayKey + DsBinlogLayout.DYN_SUFFIX);
            byte[] rawFixed = fixedFile.exists() ? Files.readAllBytes(fixedFile.toPath()) : new byte[0];
            byte[] rawDyn = dynFile.exists() ? Files.readAllBytes(dynFile.toPath()) : new byte[0];
            Assertions.assertTrue(rawFixed.length + rawDyn.length >= 1000, "total payload non-trivial: " + (rawFixed.length + rawDyn.length));
            byte[][] packed = maybeCompressAll(rawFixed, rawDyn, new byte[0], LZ4_THRESHOLD);

            DbEntityBinlogFrameRequest req = new DbEntityBinlogFrameRequest(
                    sA, pA, sB, pB,
                    endDayKey, 0L, 0L,
                    endDayKey, endPos.fixedOffset(), endPos.sequence(),
                    packed[0], packed[1], packed[2]);
            if (rawFixed.length >= LZ4_THRESHOLD) {
                Assertions.assertTrue(ColumnCompression.isCompressed(packed[0]),
                        "fixed compressed, rawLen=" + rawFixed.length + " packedLen=" + packed[0].length);
                Assertions.assertTrue(packed[0].length < rawFixed.length,
                        "compressed smaller: " + packed[0].length + " < " + rawFixed.length);
            }
            P2PWrapper<DbEntityBinlogFrameRequest> wrapper = P2PWrapper.build(3, P2PCommand.DB_ENTITY_BINLOG_FRAME, req);
            DbEntityBinlogFrameServerHandler handler = new DbEntityBinlogFrameServerHandler();
            P2PWrapper<Object> resp;
            try {
                DsDatabaseLocal.setCurrent(dbB);
                resp = handler.process(wrapper);
            } finally {
                DsDatabaseLocal.setCurrent(null);
            }
            Assertions.assertNotNull(resp);
            Assertions.assertEquals(P2PCommand.R_OK_DB_ENTITY_BINLOG_FRAME_ACK, resp.getCommand());
            DbEntityBinlogFrameAck ack = (DbEntityBinlogFrameAck) resp.getData();
            Assertions.assertTrue(ack.appliedFrames >= 50, "appliedFrames=" + ack.appliedFrames + " >=50");
            Assertions.assertNull(ack.error, "error null");
            List<UserTgt4> all = gmB.findList(gmB.buildQueryWrapper());
            Assertions.assertEquals(50, all.size(), "findList size=50, got=" + all.size());
            Set<String> actual = new HashSet<>();
            for (UserTgt4 u : all) actual.add(u.name);
            Assertions.assertEquals(expectedNames, actual);
        } finally {
            dbA.close();
            dbB.close();
        }
    }

    @Test
    public void test_T04_mixedMode_fixedCompressed_dynUncompressed_bytesEmpty() throws Exception {
        File rootA = tmpDir("dsdb_p7_tgt4_T04_A_");
        File rootB = tmpDir("dsdb_p7_tgt4_T04_B_");
        final int sA = 31, pA = 0, sB = 32, pB = 0;
        DsDatabaseLocal dbA = new DsDatabaseLocal(rootA, sA, pA);
        DsDatabaseLocal dbB = new DsDatabaseLocal(rootB, sB, pB);
        try {
            GenericManager<UserTgt4> gmA = new GenericManager<>(UserTgt4.class, dbA, sA, pA);
            GenericManager<UserTgt4> gmB = new GenericManager<>(UserTgt4.class, dbB, sB, pB);
            final int N = 8;
            Set<String> expectedNames = new HashSet<>();
            for (int i = 0; i < N; i++) {
                UserTgt4 u = new UserTgt4();
                u.name = "tgt4_T04_u_" + i;
                u.age = 100 + i;
                Assertions.assertEquals(1, gmA.saveOrUpdate(u));
                expectedNames.add(u.name);
            }
            dbA.getBinlogStore().forceFlushAll();
            UserTgt4 warm = new UserTgt4();
            warm.name = "tgt4_T04_warm";
            warm.age = -2;
            gmB.saveOrUpdate(warm);
            gmB.removeById(warm.getId());

            DsBinlogPosition endPos = dbA.getBinlogStore().getCurrentPosition();
            String endDayKey = endPos.dayKey();
            if (endDayKey == null || endDayKey.isEmpty()) {
                List<String> days = dbA.getBinlogStore().listDayKeys();
                if (!days.isEmpty()) endDayKey = days.get(days.size() - 1);
            }
            File aStoreDir = new File(rootA, "_binlog");
            File fixedFile = new File(aStoreDir, DsBinlogLayout.FILE_PREFIX + endDayKey + DsBinlogLayout.FIXED_SUFFIX);
            File dynFile = new File(aStoreDir, DsBinlogLayout.FILE_PREFIX + endDayKey + DsBinlogLayout.DYN_SUFFIX);
            byte[] rawFixed = fixedFile.exists() ? Files.readAllBytes(fixedFile.toPath()) : new byte[0];
            byte[] rawDyn = dynFile.exists() ? Files.readAllBytes(dynFile.toPath()) : new byte[0];

            byte[] bigPad = new byte[131072];
            Arrays.fill(bigPad, (byte) 'Z');
            byte[] fixedWithPad = new byte[rawFixed.length + bigPad.length];
            System.arraycopy(rawFixed, 0, fixedWithPad, 0, rawFixed.length);
            System.arraycopy(bigPad, 0, fixedWithPad, rawFixed.length, bigPad.length);
            byte[] fixedCompressed = maybeCompress(fixedWithPad, LZ4_THRESHOLD);
            Assertions.assertTrue(ColumnCompression.isCompressed(fixedCompressed),
                    "padded fixed must be compressed");

            byte[] dynSmall = new byte[Math.max(rawDyn.length, 4096)];
            new Random(77L).nextBytes(dynSmall);
            if (rawDyn.length > 0) System.arraycopy(rawDyn, 0, dynSmall, 0, rawDyn.length);
            byte[] dynAsIs = maybeCompress(dynSmall, LZ4_THRESHOLD);

            DbEntityBinlogFrameRequest req = new DbEntityBinlogFrameRequest(
                    sA, pA, sB, pB,
                    endDayKey, 0L, 0L,
                    endDayKey, endPos.fixedOffset(), endPos.sequence(),
                    fixedCompressed, dynAsIs, new byte[0]);
            Assertions.assertTrue(ColumnCompression.isCompressed(req.fixedFrames));
            P2PWrapper<DbEntityBinlogFrameRequest> wrapper = P2PWrapper.build(4, P2PCommand.DB_ENTITY_BINLOG_FRAME, req);
            DbEntityBinlogFrameServerHandler handler = new DbEntityBinlogFrameServerHandler();
            P2PWrapper<Object> resp;
            try {
                DsDatabaseLocal.setCurrent(dbB);
                resp = handler.process(wrapper);
            } finally {
                DsDatabaseLocal.setCurrent(null);
            }
            Assertions.assertNotNull(resp);
            Assertions.assertEquals(P2PCommand.R_OK_DB_ENTITY_BINLOG_FRAME_ACK, resp.getCommand());
            DbEntityBinlogFrameAck ack = (DbEntityBinlogFrameAck) resp.getData();
            Assertions.assertTrue(ack.appliedFrames >= N, "appliedFrames=" + ack.appliedFrames + " >=N=" + N);
            Assertions.assertNull(ack.error);
            List<UserTgt4> all = gmB.findList(gmB.buildQueryWrapper());
            Set<String> actual = new HashSet<>();
            for (UserTgt4 u : all) actual.add(u.name);
            Assertions.assertTrue(actual.containsAll(expectedNames),
                    "all expected names present, expected=" + expectedNames + " actual=" + actual);
        } finally {
            dbA.close();
            dbB.close();
        }
    }
}
