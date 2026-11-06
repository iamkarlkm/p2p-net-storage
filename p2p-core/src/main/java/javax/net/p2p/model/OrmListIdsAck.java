package javax.net.p2p.model;

import java.util.List;

public class OrmListIdsAck {
    public int sourceServerId = -1;
    public int sourcePartitionId = -1;
    public int targetServerId = -1;
    public int targetPartitionId = -1;
    public List<Long> ids;
    public String error;

    public OrmListIdsAck() {}

    public OrmListIdsAck(int sourceServerId, int sourcePartitionId,
                        int targetServerId, int targetPartitionId,
                        List<Long> ids, String error) {
        this.sourceServerId = sourceServerId;
        this.sourcePartitionId = sourcePartitionId;
        this.targetServerId = targetServerId;
        this.targetPartitionId = targetPartitionId;
        this.ids = ids;
        this.error = error;
    }
}
