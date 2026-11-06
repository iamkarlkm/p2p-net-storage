package ds;

import com.q3lives.ds.enums.DataType;
import com.q3lives.ds.registry.DsRegistry;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.model.RegistryFindByPrefixAck;
import javax.net.p2p.model.RegistryFindByPrefixKeysAck;
import javax.net.p2p.model.RegistryFindByPrefixKeysRequest;
import javax.net.p2p.model.RegistryFindByPrefixRequest;
import javax.net.p2p.model.RegistryGetAck;
import javax.net.p2p.model.RegistryGetRequest;
import javax.net.p2p.model.RegistryListChildrenAck;
import javax.net.p2p.model.RegistryListChildrenRequest;
import javax.net.p2p.model.RegistryMkdirsRequest;
import javax.net.p2p.model.RegistryPutBytesRequest;
import javax.net.p2p.model.RegistryPutLongRequest;
import javax.net.p2p.model.RegistryPutStringRequest;
import javax.net.p2p.model.RegistryRemoveRequest;
import javax.net.p2p.model.RegistryWriteAck;
import javax.net.p2p.model.SimpRegistryEntry;
import javax.net.p2p.server.handler.RegistryFindByPrefixKeysServerHandler;
import javax.net.p2p.server.handler.RegistryFindByPrefixServerHandler;
import javax.net.p2p.server.handler.RegistryGetServerHandler;
import javax.net.p2p.server.handler.RegistryListChildrenServerHandler;
import javax.net.p2p.server.handler.RegistryMkdirsServerHandler;
import javax.net.p2p.server.handler.RegistryPutBytesServerHandler;
import javax.net.p2p.server.handler.RegistryPutLongServerHandler;
import javax.net.p2p.server.handler.RegistryPutStringServerHandler;
import javax.net.p2p.server.handler.RegistryRemoveServerHandler;
import javax.net.p2p.server.handler.Tgt3RegistryResolver;

public class DsRegistry_P7_Tgt3_HandlerTest {

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
    void T01_MkdirsPutString_Get_4tupleRoute_ok() throws Exception {
        File tmpA = tmpDir("dsreg_tgt3_A_");
        File tmpB = tmpDir("dsreg_tgt3_B_");
        try (DsRegistry rA = new DsRegistry(new File(tmpA, SA + "_" + PA).getAbsolutePath(), SA, PA);
             DsRegistry rB = new DsRegistry(new File(tmpB, SB + "_" + PB).getAbsolutePath(), SB, PB)) {
            Tgt3RegistryResolver.injectForTest(SA, PA, rA);
            Tgt3RegistryResolver.injectForTest(SB, PB, rB);

            RegistryMkdirsRequest mk = new RegistryMkdirsRequest(SA, PA, SB, PB, 0L, "/cfg");
            P2PWrapper w1 = P2PWrapper.build(1, P2PCommand.REGISTRY_MKDIRS, mk);
            P2PWrapper a1 = new RegistryMkdirsServerHandler().process(w1);
            Assertions.assertEquals(P2PCommand.R_OK_REGISTRY_MKDIRS_ACK, a1.getCommand());
            Assertions.assertTrue(((RegistryWriteAck) a1.getData()).ok, "mkdirs /cfg ok");

            RegistryPutStringRequest ps = new RegistryPutStringRequest(SA, PA, SB, PB, 0L,
                    "/cfg/x", DataType.REG_SZ.getCode(), "v1");
            P2PWrapper w2 = P2PWrapper.build(2, P2PCommand.REGISTRY_PUT_STRING, ps);
            P2PWrapper a2 = new RegistryPutStringServerHandler().process(w2);
            Assertions.assertEquals(P2PCommand.R_OK_REGISTRY_PUT_STRING_ACK, a2.getCommand());
            Assertions.assertTrue(((RegistryWriteAck) a2.getData()).ok, "putString ok");
            Assertions.assertTrue(((RegistryWriteAck) a2.getData()).nodeIdOrRemoved != 0L, "putString nodeId!=0");

            RegistryGetRequest gr = new RegistryGetRequest(SA, PA, SB, PB, 0L, "/cfg/x");
            P2PWrapper w3 = P2PWrapper.build(3, P2PCommand.REGISTRY_GET, gr);
            P2PWrapper a3 = new RegistryGetServerHandler().process(w3);
            Assertions.assertEquals(P2PCommand.R_OK_REGISTRY_GET_ACK, a3.getCommand());
            RegistryGetAck ga = (RegistryGetAck) a3.getData();
            Assertions.assertTrue(ga.present, "get present");
            Assertions.assertFalse(ga.isDirectory, "not dir");
            Assertions.assertEquals(DataType.REG_SZ.getCode(), ga.dataTypeCode, "REG_SZ");
            Assertions.assertEquals("v1", ga.stringValue, "stringValue=v1, got=" + ga.stringValue);

            // 交叉：A 上没写 /cfg/x → getA 空
            RegistryGetRequest grA = new RegistryGetRequest(SB, PB, SA, PA, 0L, "/cfg/x");
            P2PWrapper w3A = P2PWrapper.build(4, P2PCommand.REGISTRY_GET, grA);
            RegistryGetAck gA = (RegistryGetAck) new RegistryGetServerHandler().process(w3A).getData();
            Assertions.assertFalse(gA.present, "A上没写→empty");
        }
    }

    @Test
    void T02_PutLongBytes_Remove_Recursive_ok() throws Exception {
        File tmpA = tmpDir("dsreg_tgt3_A2_");
        File tmpB = tmpDir("dsreg_tgt3_B2_");
        try (DsRegistry rA = new DsRegistry(new File(tmpA, SA + "_" + PA).getAbsolutePath(), SA, PA);
             DsRegistry rB = new DsRegistry(new File(tmpB, SB + "_" + PB).getAbsolutePath(), SB, PB)) {
            Tgt3RegistryResolver.injectForTest(SA, PA, rA);
            Tgt3RegistryResolver.injectForTest(SB, PB, rB);

            byte[] payload = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
            RegistryPutLongRequest pl = new RegistryPutLongRequest(SA, PA, SB, PB, 0L,
                    "/cfg/num", DataType.REG_QWORD.getCode(), 998877665544332211L);
            RegistryPutBytesRequest pb = new RegistryPutBytesRequest(SA, PA, SB, PB, 0L,
                    "/cfg/blob", DataType.REG_BINARY.getCode(), payload);
            new RegistryPutLongServerHandler().process(P2PWrapper.build(1, P2PCommand.REGISTRY_PUT_LONG, pl));
            new RegistryPutBytesServerHandler().process(P2PWrapper.build(2, P2PCommand.REGISTRY_PUT_BYTES, pb));

            RegistryGetAck gNum = (RegistryGetAck) new RegistryGetServerHandler().process(
                    P2PWrapper.build(3, P2PCommand.REGISTRY_GET, new RegistryGetRequest(SA, PA, SB, PB, 0L, "/cfg/num"))).getData();
            RegistryGetAck gBlob = (RegistryGetAck) new RegistryGetServerHandler().process(
                    P2PWrapper.build(4, P2PCommand.REGISTRY_GET, new RegistryGetRequest(SA, PA, SB, PB, 0L, "/cfg/blob"))).getData();
            Assertions.assertEquals(998877665544332211L, gNum.inlineValue, "REG_QWORD inline");
            Assertions.assertArrayEquals(payload, gBlob.bytesValue, "blob bytes");

            // remove("/cfg",false) → 非空抛错
            RegistryRemoveRequest rFalse = new RegistryRemoveRequest(SA, PA, SB, PB, 0L, "/cfg", false);
            P2PWrapper aFalse = new RegistryRemoveServerHandler().process(P2PWrapper.build(5, P2PCommand.REGISTRY_REMOVE, rFalse));
            Assertions.assertEquals(P2PCommand.STD_ERROR, aFalse.getCommand(), "非空目录 recursive=false→error");

            // remove("/cfg",true) → success，后续 get("/cfg/num") 空
            RegistryRemoveRequest rTrue = new RegistryRemoveRequest(SA, PA, SB, PB, 0L, "/cfg", true);
            P2PWrapper aTrue = new RegistryRemoveServerHandler().process(P2PWrapper.build(6, P2PCommand.REGISTRY_REMOVE, rTrue));
            Assertions.assertTrue(((RegistryWriteAck) aTrue.getData()).ok, "recursive remove ok");
            RegistryGetAck gGone = (RegistryGetAck) new RegistryGetServerHandler().process(
                    P2PWrapper.build(7, P2PCommand.REGISTRY_GET, new RegistryGetRequest(SA, PA, SB, PB, 0L, "/cfg/num"))).getData();
            Assertions.assertFalse(gGone.present, "recursive remove后num不存在");
        }
    }

    @Test
    void T03_ListChildren_3items_setEqual() throws Exception {
        File tmpA = tmpDir("dsreg_tgt3_A3_");
        File tmpB = tmpDir("dsreg_tgt3_B3_");
        try (DsRegistry rA = new DsRegistry(new File(tmpA, SA + "_" + PA).getAbsolutePath(), SA, PA);
             DsRegistry rB = new DsRegistry(new File(tmpB, SB + "_" + PB).getAbsolutePath(), SB, PB)) {
            Tgt3RegistryResolver.injectForTest(SA, PA, rA);
            Tgt3RegistryResolver.injectForTest(SB, PB, rB);
            for (String d : Arrays.asList("/apps/a1", "/apps/a2", "/apps/b1")) {
                new RegistryMkdirsServerHandler().process(P2PWrapper.build(1, P2PCommand.REGISTRY_MKDIRS,
                        new RegistryMkdirsRequest(SA, PA, SB, PB, 0L, d)));
            }
            P2PWrapper w = new RegistryListChildrenServerHandler().process(P2PWrapper.build(10, P2PCommand.REGISTRY_LIST_CHILDREN,
                    new RegistryListChildrenRequest(SA, PA, SB, PB, 0L, "/apps")));
            Assertions.assertEquals(P2PCommand.R_OK_REGISTRY_LIST_CHILDREN_ACK, w.getCommand());
            List<String> ch = ((RegistryListChildrenAck) w.getData()).children;
            Assertions.assertEquals(3, ch.size(), "children=3, got=" + ch);
            Assertions.assertEquals(new HashSet<>(Arrays.asList("a1", "a2", "b1")), new HashSet<>(ch), "Set 相等忽略顺序");
        }
    }

    @Test
    void T04_FindByPrefix_Keys_dualRoute_L1325_ok() throws Exception {
        File tmpA = tmpDir("dsreg_tgt3_A4_");
        File tmpB = tmpDir("dsreg_tgt3_B4_");
        try (DsRegistry rA = new DsRegistry(new File(tmpA, SA + "_" + PA).getAbsolutePath(), SA, PA);
             DsRegistry rB = new DsRegistry(new File(tmpB, SB + "_" + PB).getAbsolutePath(), SB, PB)) {
            Tgt3RegistryResolver.injectForTest(SA, PA, rA);
            Tgt3RegistryResolver.injectForTest(SB, PB, rB);

            for (int i = 1; i <= 3; i++) {
                new RegistryPutStringServerHandler().process(P2PWrapper.build(i, P2PCommand.REGISTRY_PUT_STRING,
                        new RegistryPutStringRequest(SA, PA, SB, PB, 0L,
                                "/items/0" + i, DataType.REG_SZ.getCode(), "v" + i)));
            }
            for (int i = 1; i <= 2; i++) {
                new RegistryPutStringServerHandler().process(P2PWrapper.build(100 + i, P2PCommand.REGISTRY_PUT_STRING,
                        new RegistryPutStringRequest(SA, PA, SB, PB, 0L,
                                "/docs/0" + i, DataType.REG_SZ.getCode(), "d" + i)));
            }

            // 4-tuple 路由 findByPrefix("/items")
            RegistryFindByPrefixRequest fb = new RegistryFindByPrefixRequest(SA, PA, SB, PB, 0L,
                    "/items", 2, 100);
            P2PWrapper wa = new RegistryFindByPrefixServerHandler().process(P2PWrapper.build(11, P2PCommand.REGISTRY_FIND_BY_PREFIX, fb));
            Assertions.assertEquals(P2PCommand.R_OK_REGISTRY_FIND_BY_PREFIX_ACK, wa.getCommand());
            List<SimpRegistryEntry> entries = ((RegistryFindByPrefixAck) wa.getData()).entries;
            Assertions.assertEquals(3, entries.size(), "findByPrefix(/items)=3, got=" + entries.size());
            Set<String> keys = new HashSet<>();
            for (SimpRegistryEntry e : entries) {
                Assertions.assertFalse(e.isDirectory, "all leaves");
                Assertions.assertEquals(DataType.REG_SZ.getCode(), e.dataTypeCode);
                keys.add(e.key);
            }
            Assertions.assertEquals(new HashSet<>(Arrays.asList("/items/01", "/items/02", "/items/03")), keys);

            // findByPrefixKeys("/docs") L1325
            RegistryFindByPrefixKeysRequest fk = new RegistryFindByPrefixKeysRequest(SA, PA, SB, PB, 0L,
                    "/docs", 2, 100);
            P2PWrapper wk = new RegistryFindByPrefixKeysServerHandler().process(P2PWrapper.build(12, P2PCommand.REGISTRY_FIND_BY_PREFIX_KEYS, fk));
            Assertions.assertEquals(P2PCommand.R_OK_REGISTRY_FIND_BY_PREFIX_KEYS_ACK, wk.getCommand());
            List<String> klist = new ArrayList<>(((RegistryFindByPrefixKeysAck) wk.getData()).keys);
            klist.sort(String::compareTo);
            Assertions.assertEquals(Arrays.asList("/docs/01", "/docs/02"), klist, "L1325 findByPrefixKeys 顺序一致");

            // globalNodeId fallback：targetSP=(-1,-1) + keyGlobalNodeId = rB.toGlobal(root或某个dir) → 路由到 SB/PB
            long dirNodeId = rB.mkdirs("/tmp/g");
            long g = rB.toGlobalNodeId(dirNodeId);
            Assertions.assertTrue(g > 0L, "globalNodeId>0");
            RegistryFindByPrefixKeysRequest fk2 = new RegistryFindByPrefixKeysRequest(SA, PA, -1, -1, g,
                    "/docs", 2, 100);
            P2PWrapper wk2 = new RegistryFindByPrefixKeysServerHandler().process(P2PWrapper.build(13, P2PCommand.REGISTRY_FIND_BY_PREFIX_KEYS, fk2));
            Assertions.assertEquals(P2PCommand.R_OK_REGISTRY_FIND_BY_PREFIX_KEYS_ACK, wk2.getCommand());
            List<String> klist2 = new ArrayList<>(((RegistryFindByPrefixKeysAck) wk2.getData()).keys);
            klist2.sort(String::compareTo);
            Assertions.assertEquals(Arrays.asList("/docs/01", "/docs/02"), klist2, "globalNodeId fallback 路由到SB/PB一致");

            // 全错：targetSP=-1 且 globalNodeId=0 → 双模式失败 STD_ERROR
            RegistryFindByPrefixKeysRequest fk3 = new RegistryFindByPrefixKeysRequest(SA, PA, -1, -1, 0L,
                    "/docs", 2, 100);
            P2PWrapper wk3 = new RegistryFindByPrefixKeysServerHandler().process(P2PWrapper.build(14, P2PCommand.REGISTRY_FIND_BY_PREFIX_KEYS, fk3));
            Assertions.assertEquals(P2PCommand.STD_ERROR, wk3.getCommand(), "双模式失败→STD_ERROR");
        }
    }
}
