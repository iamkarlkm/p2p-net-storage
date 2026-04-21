package javax.net.p2p.dfsmap;

import javax.net.p2p.dfsmap.model.DfsMapExecKvReq;
import javax.net.p2p.dfsmap.model.DfsMapExecKvResp;
import javax.net.p2p.dfsmap.model.DfsMapGetReq;
import javax.net.p2p.dfsmap.model.DfsMapGetResp;
import javax.net.p2p.dfsmap.model.DfsMapPutReq;
import javax.net.p2p.dfsmap.model.DfsMapPutResp;
import javax.net.p2p.dfsmap.model.DfsMapPingReq;
import javax.net.p2p.dfsmap.model.DfsMapPingResp;
import javax.net.p2p.dfsmap.model.DfsMapRangeLocalReq;
import javax.net.p2p.dfsmap.model.DfsMapRangeLocalResp;
import javax.net.p2p.dfsmap.model.DfsMapRangeReq;
import javax.net.p2p.dfsmap.model.DfsMapRangeResp;
import javax.net.p2p.dfsmap.model.DfsMapRemoveReq;
import javax.net.p2p.dfsmap.model.DfsMapRemoveResp;

public interface DfsMapBackend {
    DfsMapGetResp handleGet(DfsMapGetReq req);

    DfsMapPutResp handlePut(DfsMapPutReq req);

    DfsMapRemoveResp handleRemove(DfsMapRemoveReq req);

    DfsMapRangeResp handleRange(DfsMapRangeReq req);

    DfsMapExecKvResp handleExecKv(DfsMapExecKvReq req);

    DfsMapRangeLocalResp handleRangeLocal(DfsMapRangeLocalReq req);

    DfsMapPingResp handlePing(DfsMapPingReq req);
}
