package ds;

import com.q3lives.ds.registry.DsRegistryDbMetaHelper;
import com.q3lives.ds.registry.DsRegistryDbMetaHelper.DbMetaAttr;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DsRegistry_Phase2_V2_CountAttrs255_Test {

    @TempDir
    Path tmpDir;

    @BeforeEach
    void setUp() {
    }

    @AfterEach
    void tearDown() {
    }

    @Test
    void t07_01_v2_header_present_when_count_gt_15_transport() {
        Map<DbMetaAttr, Object> attrs = new EnumMap<>(DbMetaAttr.class);
        attrs.put(DbMetaAttr.NAME, "ds-t0701");
        attrs.put(DbMetaAttr.DESC, "T0701 V2 header round-trip 16 fields");
        attrs.put(DbMetaAttr.OWNER, "owner-0701");
        attrs.put(DbMetaAttr.ENV, "PROD");
        attrs.put(DbMetaAttr.SOURCE, "unit-test-v2");
        attrs.put(DbMetaAttr.REGION, "cn-north");
        attrs.put(DbMetaAttr.ZONE, "zone-a");
        attrs.put(DbMetaAttr.CLUSTER, "c1");
        attrs.put(DbMetaAttr.RACK, "r1");
        attrs.put(DbMetaAttr.LABELS, "lbl");
        attrs.put(DbMetaAttr.BACKUP_POLICY, "DAILY");
        attrs.put(DbMetaAttr.TTL_SEC, 3600L);
        attrs.put(DbMetaAttr.RETENTION_DAYS, 90L);
        attrs.put(DbMetaAttr.VERSION, 123456789L);
        attrs.put(DbMetaAttr.PARTITION_COUNT, 64L);
        attrs.put(DbMetaAttr.REPLICA_COUNT, 3L);
        assertEquals(16, attrs.size());
        byte[] encoded = DsRegistryDbMetaHelper.encodeTransport(attrs);
        assertNotNull(encoded);
        assertTrue(encoded.length >= 4, "header 至少 4B");
        assertEquals((byte) 0xA7, encoded[0], "MAGIC B0");
        assertEquals((byte) 0x02, encoded[1], "V2 Header B1=0x02");
        Map<DbMetaAttr, Object> out = DsRegistryDbMetaHelper.decodeTransport(encoded);
        assertEquals(16, out.size());
        for (Map.Entry<DbMetaAttr, Object> e : attrs.entrySet()) {
            assertTrue(out.containsKey(e.getKey()), "缺少 key " + e.getKey());
            assertEquals(e.getValue(), out.get(e.getKey()), "值不一致 key=" + e.getKey());
        }
    }

    @Test
    void t07_02_v1_header_when_count_le_15_transport() {
        Map<DbMetaAttr, Object> attrs = new EnumMap<>(DbMetaAttr.class);
        attrs.put(DbMetaAttr.NAME, "ds-t0702");
        attrs.put(DbMetaAttr.DESC, "T0702 V1 header ≤15 fields");
        attrs.put(DbMetaAttr.OWNER, "owner-0702");
        attrs.put(DbMetaAttr.ENV, "PROD");
        attrs.put(DbMetaAttr.TTL_SEC, 3600L);
        attrs.put(DbMetaAttr.RETENTION_DAYS, 90L);
        attrs.put(DbMetaAttr.VERSION, 123456789L);
        attrs.put(DbMetaAttr.PARTITION_COUNT, 64L);
        attrs.put(DbMetaAttr.IS_PARTITIONED, Boolean.TRUE);
        assertTrue(attrs.size() <= 15, "必须 V1 路径 ≤15 字段，实际 " + attrs.size());
        byte[] encoded = DsRegistryDbMetaHelper.encodeTransport(attrs);
        assertNotNull(encoded);
        assertTrue(encoded.length >= 4, "header 至少 4B");
        assertEquals((byte) 0xA7, encoded[0], "MAGIC B0");
        assertEquals((byte) 0x01, encoded[1], "V1 Header B1=0x01");
        Map<DbMetaAttr, Object> out = DsRegistryDbMetaHelper.decodeTransport(encoded);
        assertEquals(attrs.size(), out.size());
        for (Map.Entry<DbMetaAttr, Object> e : attrs.entrySet()) {
            assertTrue(out.containsKey(e.getKey()), "缺少 key " + e.getKey());
            assertEquals(e.getValue(), out.get(e.getKey()), "值不一致 key=" + e.getKey());
        }
    }

    @Test
    void t07_03_v2_all_25_enum_attrs_round_trip() {
        Map<DbMetaAttr, Object> attrs = new EnumMap<>(DbMetaAttr.class);
        for (DbMetaAttr a : DbMetaAttr.values()) attrs.put(a, sampleValueFor(a));
        assertEquals(25, attrs.size(), "必须覆盖 25 DbMetaAttr 全量");
        byte[] encoded = DsRegistryDbMetaHelper.encodeTransport(attrs);
        assertNotNull(encoded);
        assertTrue(encoded.length >= 4);
        assertEquals((byte) 0xA7, encoded[0]);
        assertEquals((byte) 0x02, encoded[1], "全 25 项必须走 V2 路径");
        Map<DbMetaAttr, Object> out = DsRegistryDbMetaHelper.decodeTransport(encoded);
        assertEquals(attrs.size(), out.size(), "全 25 项回读 size 不一致");
        for (Map.Entry<DbMetaAttr, Object> e : attrs.entrySet()) {
            assertTrue(out.containsKey(e.getKey()), "缺少 key " + e.getKey());
            assertEquals(e.getValue(), out.get(e.getKey()), "值不一致 key=" + e.getKey());
        }
    }

    @Test
    void t07_04_corrupt_magic_rejected() {
        Map<DbMetaAttr, Object> attrs = new EnumMap<>(DbMetaAttr.class);
        attrs.put(DbMetaAttr.NAME, "n-corrupt");
        attrs.put(DbMetaAttr.DESC, "T0704 corrupt magic");
        attrs.put(DbMetaAttr.OWNER, "o-corrupt");
        attrs.put(DbMetaAttr.ENV, "DEV");
        attrs.put(DbMetaAttr.SOURCE, "src1");
        attrs.put(DbMetaAttr.REGION, "cn-north");
        attrs.put(DbMetaAttr.ZONE, "zone-a");
        attrs.put(DbMetaAttr.CLUSTER, "c1");
        attrs.put(DbMetaAttr.RACK, "r1");
        attrs.put(DbMetaAttr.LABELS, "lbl");
        attrs.put(DbMetaAttr.BACKUP_POLICY, "DAILY");
        attrs.put(DbMetaAttr.TTL_SEC, 3600L);
        attrs.put(DbMetaAttr.RETENTION_DAYS, 90L);
        attrs.put(DbMetaAttr.VERSION, 123456789L);
        attrs.put(DbMetaAttr.PARTITION_COUNT, 64L);
        attrs.put(DbMetaAttr.REPLICA_COUNT, 3L);
        byte[] encoded = DsRegistryDbMetaHelper.encodeTransport(attrs);
        encoded[0] = (byte) 0x00;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DsRegistryDbMetaHelper.decodeTransport(encoded));
        assertTrue(ex.getMessage().contains("magic"), "异常应含 magic: " + ex.getMessage());
    }

    @Test
    void t07_05_unsupported_version_rejected() {
        Map<DbMetaAttr, Object> attrs = new EnumMap<>(DbMetaAttr.class);
        attrs.put(DbMetaAttr.NAME, "n-badver");
        attrs.put(DbMetaAttr.DESC, "T0705 bad version 0xFF");
        attrs.put(DbMetaAttr.OWNER, "o-badver");
        attrs.put(DbMetaAttr.ENV, "STAGE");
        attrs.put(DbMetaAttr.SOURCE, "src1");
        attrs.put(DbMetaAttr.REGION, "cn-north");
        attrs.put(DbMetaAttr.ZONE, "zone-a");
        attrs.put(DbMetaAttr.CLUSTER, "c1");
        attrs.put(DbMetaAttr.RACK, "r1");
        attrs.put(DbMetaAttr.LABELS, "lbl");
        attrs.put(DbMetaAttr.BACKUP_POLICY, "DAILY");
        attrs.put(DbMetaAttr.TTL_SEC, 3600L);
        attrs.put(DbMetaAttr.RETENTION_DAYS, 90L);
        attrs.put(DbMetaAttr.VERSION, 123456789L);
        attrs.put(DbMetaAttr.PARTITION_COUNT, 64L);
        attrs.put(DbMetaAttr.REPLICA_COUNT, 3L);
        byte[] encoded = DsRegistryDbMetaHelper.encodeTransport(attrs);
        encoded[1] = (byte) 0xFF;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DsRegistryDbMetaHelper.decodeTransport(encoded));
        assertTrue(ex.getMessage().contains("版本不支持") || ex.getMessage().contains("期望"),
                "异常应含版本不支持: " + ex.getMessage());
    }

    @Test
    void t07_06_cross_version_v1_read_v2_write_ok() {
        Map<DbMetaAttr, Object> v1Attrs = new EnumMap<>(DbMetaAttr.class);
        v1Attrs.put(DbMetaAttr.NAME, "ds-cross-v1");
        v1Attrs.put(DbMetaAttr.DESC, "Cross ver baseline");
        v1Attrs.put(DbMetaAttr.OWNER, "owner-cross");
        v1Attrs.put(DbMetaAttr.ENV, "PROD");
        v1Attrs.put(DbMetaAttr.TTL_SEC, 7200L);
        v1Attrs.put(DbMetaAttr.RETENTION_DAYS, 30L);
        v1Attrs.put(DbMetaAttr.VERSION, 5000L);
        v1Attrs.put(DbMetaAttr.PARTITION_COUNT, 32L);
        v1Attrs.put(DbMetaAttr.IS_PARTITIONED, Boolean.TRUE);
        assertTrue(v1Attrs.size() <= 15);
        byte[] v1enc = DsRegistryDbMetaHelper.encodeTransport(v1Attrs);
        assertEquals((byte) 0x01, v1enc[1]);
        Map<DbMetaAttr, Object> v1out = DsRegistryDbMetaHelper.decodeTransport(v1enc);
        assertEquals(v1Attrs.size(), v1out.size());

        Map<DbMetaAttr, Object> v2Attrs = new EnumMap<>(v1Attrs);
        v2Attrs.put(DbMetaAttr.SOURCE, "cross-ver-src");
        v2Attrs.put(DbMetaAttr.REGION, "eu-west");
        v2Attrs.put(DbMetaAttr.ZONE, "zone-9");
        v2Attrs.put(DbMetaAttr.CLUSTER, "cluster-cross");
        v2Attrs.put(DbMetaAttr.RACK, "rack-88");
        v2Attrs.put(DbMetaAttr.LABELS, "cross=yes");
        v2Attrs.put(DbMetaAttr.BACKUP_POLICY, "MONTHLY");
        assertEquals(16, v2Attrs.size());
        byte[] v2enc = DsRegistryDbMetaHelper.encodeTransport(v2Attrs);
        assertEquals((byte) 0x02, v2enc[1]);
        Map<DbMetaAttr, Object> v2out = DsRegistryDbMetaHelper.decodeTransport(v2enc);
        assertEquals(v2Attrs.size(), v2out.size());
        for (Map.Entry<DbMetaAttr, Object> e : v2Attrs.entrySet()) {
            assertTrue(v2out.containsKey(e.getKey()), "V2 缺少 key " + e.getKey());
            assertEquals(e.getValue(), v2out.get(e.getKey()), "V2 值不一致 key=" + e.getKey());
        }
    }

    @Test
    void t07_07_count_attrs_25_boundary_ok_header_v2() {
        Map<DbMetaAttr, Object> attrs = new EnumMap<>(DbMetaAttr.class);
        for (DbMetaAttr a : DbMetaAttr.values()) attrs.put(a, sampleValueFor(a));
        assertEquals(25, attrs.size(), "countAttrs=25 ≤255 合法");
        byte[] encoded = DsRegistryDbMetaHelper.encodeTransport(attrs);
        assertEquals((byte) 0x02, encoded[1], "V2 Header 正常");
        Map<DbMetaAttr, Object> out = DsRegistryDbMetaHelper.decodeTransport(encoded);
        assertEquals(25, out.size(), "全 25 项回读 size");
        for (Map.Entry<DbMetaAttr, Object> e : attrs.entrySet()) {
            assertEquals(e.getValue(), out.get(e.getKey()), "值不一致 key=" + e.getKey());
        }
    }

    private static Object sampleValueFor(DbMetaAttr a) {
        switch (a.vt) {
            case STR8:
                return "str_" + a.name().toLowerCase();
            case ENUM8:
                return "ENUM_" + a.name();
            case U32:
                return ((long) a.ordinal() + 1) * 7L;
            case U64:
                return ((long) a.ordinal() + 1) * 1000001L;
            case BOOL8:
                return Boolean.valueOf((a.ordinal() & 1) == 0);
            default:
                throw new IllegalStateException("未知 vt " + a.vt);
        }
    }
}
