package javax.net.p2p.dfsmap;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.dfsmap.model.DfsMapGetReq;
import javax.net.p2p.dfsmap.model.DfsMapGetResp;
import javax.net.p2p.dfsmap.model.DfsMapGetTopologyReq;
import javax.net.p2p.dfsmap.model.DfsMapGetTopologyResp;
import javax.net.p2p.dfsmap.model.DfsMapPutReq;
import javax.net.p2p.dfsmap.model.DfsMapPutResp;
import javax.net.p2p.dfsmap.model.DfsMapPingReq;
import javax.net.p2p.dfsmap.model.DfsMapPingResp;
import javax.net.p2p.dfsmap.model.DfsMapRangeReq;
import javax.net.p2p.dfsmap.model.DfsMapRangeResp;
import javax.net.p2p.dfsmap.model.DfsMapRemoveReq;
import javax.net.p2p.dfsmap.model.DfsMapRemoveResp;
import javax.net.p2p.dfsmap.model.DfsMapStatusCodes;
import javax.net.p2p.dfsmap.model.DfsMapTablesEnableReq;
import javax.net.p2p.dfsmap.model.DfsMapTablesEnableResp;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.server.handler.DfsMapGetServerHandler;
import javax.net.p2p.server.handler.DfsMapGetTopologyServerHandler;
import javax.net.p2p.server.handler.DfsMapPutServerHandler;
import javax.net.p2p.server.handler.DfsMapRangeServerHandler;
import javax.net.p2p.server.handler.DfsMapRemoveServerHandler;
import javax.net.p2p.server.handler.DfsMapTablesEnableBeginServerHandler;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

public class DfsMapHandlerTest {
    @After
    public void tearDown() {
        DfsMapRegistry.setBackend(null);
    }

    @Test
    public void testGetPutRemoveHandlers() {
        DfsMapRegistry.setBackend(new InMemoryBackend());

        DfsMapPutReq putReq = new DfsMapPutReq();
        putReq.setKey(1L);
        putReq.setValue(10L);
        putReq.setReturnOldValue(true);
        P2PWrapper putWrapper = P2PWrapper.build(1, P2PCommand.DFS_MAP_PUT, putReq);
        P2PWrapper putRespWrapper = new DfsMapPutServerHandler().process(putWrapper);
        assertEquals(P2PCommand.DFS_MAP_PUT, putRespWrapper.getCommand());
        assertTrue(putRespWrapper.getData() instanceof DfsMapPutResp);

        DfsMapGetReq getReq = new DfsMapGetReq();
        getReq.setKey(1L);
        P2PWrapper getWrapper = P2PWrapper.build(2, P2PCommand.DFS_MAP_GET, getReq);
        P2PWrapper getRespWrapper = new DfsMapGetServerHandler().process(getWrapper);
        assertEquals(P2PCommand.DFS_MAP_GET, getRespWrapper.getCommand());
        assertTrue(getRespWrapper.getData() instanceof DfsMapGetResp);
        DfsMapGetResp getResp = (DfsMapGetResp) getRespWrapper.getData();
        assertEquals(DfsMapStatusCodes.OK, getResp.getStatus());
        assertTrue(getResp.isFound());
        assertEquals(10L, getResp.getValue());

        DfsMapRemoveReq removeReq = new DfsMapRemoveReq();
        removeReq.setKey(1L);
        removeReq.setReturnOldValue(true);
        P2PWrapper removeWrapper = P2PWrapper.build(3, P2PCommand.DFS_MAP_REMOVE, removeReq);
        P2PWrapper removeRespWrapper = new DfsMapRemoveServerHandler().process(removeWrapper);
        assertEquals(P2PCommand.DFS_MAP_REMOVE, removeRespWrapper.getCommand());
        assertTrue(removeRespWrapper.getData() instanceof DfsMapRemoveResp);
        DfsMapRemoveResp removeResp = (DfsMapRemoveResp) removeRespWrapper.getData();
        assertEquals(DfsMapStatusCodes.OK, removeResp.getStatus());
        assertTrue(removeResp.isRemoved());
        assertEquals(10L, removeResp.getOldValue());
    }

    @Test
    public void testRangeHandler() {
        DfsMapRegistry.setBackend(new InMemoryBackend());

        DfsMapRangeReq req = new DfsMapRangeReq();
        req.setStart(0L);
        req.setCount(10);
        P2PWrapper wrapper = P2PWrapper.build(4, P2PCommand.DFS_MAP_RANGE, req);
        P2PWrapper respWrapper = new DfsMapRangeServerHandler().process(wrapper);
        assertEquals(P2PCommand.DFS_MAP_RANGE, respWrapper.getCommand());
        assertTrue(respWrapper.getData() instanceof DfsMapRangeResp);
    }

    @Test
    public void testGetTopologyHandler() {
        DfsMapRegistry.setBackend(new InMemoryBackend());

        DfsMapGetTopologyReq req = new DfsMapGetTopologyReq();
        req.setApiVersion(1);
        req.setEpoch(0L);
        P2PWrapper wrapper = P2PWrapper.build(5, P2PCommand.DFS_MAP_INT_GET_TOPOLOGY, req);
        P2PWrapper respWrapper = new DfsMapGetTopologyServerHandler().process(wrapper);
        assertEquals(P2PCommand.DFS_MAP_INT_GET_TOPOLOGY, respWrapper.getCommand());
        assertTrue(respWrapper.getData() instanceof DfsMapGetTopologyResp);
        DfsMapGetTopologyResp resp = (DfsMapGetTopologyResp) respWrapper.getData();
        assertEquals(DfsMapStatusCodes.OK, resp.getStatus());
        assertEquals(0, resp.getServerId());
        assertNotNull(resp.getServerIds());
        assertEquals(1, resp.getServerIds().length);
        assertEquals(0, resp.getServerIds()[0]);
    }

    @Test
    public void testTablesEnableBeginHandler() {
        DfsMapRegistry.setBackend(new InMemoryBackend());

        DfsMapTablesEnableReq req = new DfsMapTablesEnableReq();
        req.setApiVersion(1);
        req.setEpoch(0L);
        req.setMigrationId(123L);
        req.setCoordinatorServerId(0);
        req.setForwarded(false);
        req.setForce(false);
        P2PWrapper wrapper = P2PWrapper.build(6, P2PCommand.DFS_MAP_INT_TABLES_ENABLE_BEGIN, req);
        P2PWrapper respWrapper = new DfsMapTablesEnableBeginServerHandler().process(wrapper);
        assertEquals(P2PCommand.DFS_MAP_INT_TABLES_ENABLE_BEGIN, respWrapper.getCommand());
        assertTrue(respWrapper.getData() instanceof DfsMapTablesEnableResp);
        DfsMapTablesEnableResp resp = (DfsMapTablesEnableResp) respWrapper.getData();
        assertEquals(DfsMapStatusCodes.OK, resp.getStatus());
        assertEquals(0, resp.getServerId());
    }

    private static final class InMemoryBackend implements DfsMapBackend {
        private long value = 0L;
        private boolean hasValue = false;

        @Override
        public DfsMapGetResp handleGet(DfsMapGetReq req) {
            DfsMapGetResp resp = new DfsMapGetResp();
            resp.setStatus(hasValue ? DfsMapStatusCodes.OK : DfsMapStatusCodes.NOT_FOUND);
            resp.setKey(req.getKey());
            resp.setFound(hasValue);
            resp.setValue(value);
            return resp;
        }

        @Override
        public DfsMapPutResp handlePut(DfsMapPutReq req) {
            DfsMapPutResp resp = new DfsMapPutResp();
            resp.setStatus(DfsMapStatusCodes.OK);
            resp.setKey(req.getKey());
            resp.setHadOld(hasValue);
            resp.setOldValue(value);
            hasValue = true;
            value = req.getValue();
            return resp;
        }

        @Override
        public DfsMapRemoveResp handleRemove(DfsMapRemoveReq req) {
            DfsMapRemoveResp resp = new DfsMapRemoveResp();
            resp.setStatus(DfsMapStatusCodes.OK);
            resp.setKey(req.getKey());
            resp.setRemoved(hasValue);
            resp.setOldValue(value);
            hasValue = false;
            value = 0L;
            return resp;
        }

        @Override
        public DfsMapRangeResp handleRange(DfsMapRangeReq req) {
            DfsMapRangeResp resp = new DfsMapRangeResp();
            resp.setStatus(DfsMapStatusCodes.OK);
            resp.setStart(req.getStart());
            resp.setRequestedCount(req.getCount());
            resp.setEmitted(0);
            resp.setKeys(new long[0]);
            resp.setValues(new long[0]);
            return resp;
        }

        @Override
        public javax.net.p2p.dfsmap.model.DfsMapExecKvResp handleExecKv(javax.net.p2p.dfsmap.model.DfsMapExecKvReq req) {
            throw new UnsupportedOperationException();
        }

        @Override
        public javax.net.p2p.dfsmap.model.DfsMapRangeLocalResp handleRangeLocal(javax.net.p2p.dfsmap.model.DfsMapRangeLocalReq req) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DfsMapPingResp handlePing(DfsMapPingReq req) {
            DfsMapPingResp resp = new DfsMapPingResp();
            resp.setStatus(DfsMapStatusCodes.OK);
            resp.setServerId(0);
            resp.setTablesEnabled(false);
            resp.setTotalSize(hasValue ? 1L : 0L);
            return resp;
        }

        @Override
        public DfsMapGetTopologyResp handleGetTopology(DfsMapGetTopologyReq req) {
            DfsMapGetTopologyResp resp = new DfsMapGetTopologyResp();
            resp.setStatus(DfsMapStatusCodes.OK);
            resp.setEpoch(0L);
            resp.setServerId(0);
            resp.setTablesEnabled(false);
            resp.setServerIds(new int[]{0});
            return resp;
        }

        @Override
        public DfsMapTablesEnableResp handleTablesEnable(P2PCommand command, DfsMapTablesEnableReq req) {
            DfsMapTablesEnableResp resp = new DfsMapTablesEnableResp();
            resp.setStatus(DfsMapStatusCodes.OK);
            resp.setEpoch(0L);
            resp.setServerId(0);
            resp.setMigrationId(req == null ? 0L : req.getMigrationId());
            resp.setTablesEnabled(false);
            return resp;
        }
    }
}
