package javax.net.p2p.model;

import java.util.List;

public class OrmFindRangeAck {
    public int sourceServerId = -1;
    public int sourcePartitionId = -1;
    public int targetServerId = -1;
    public int targetPartitionId = -1;
    public List<byte[]> entityBytesList;
    public List<byte[]> relationsList;
    public String error;

    public OrmFindRangeAck() {}

    public OrmFindRangeAck(int sourceServerId, int sourcePartitionId,
                        int targetServerId, int targetPartitionId,
                        List<byte[]> entityBytesList, List<byte[]> relationsList, String error) {
        this.sourceServerId = sourceServerId;
        this.sourcePartitionId = sourcePartitionId;
        this.targetServerId = targetServerId;
        this.targetPartitionId = targetPartitionId;
        this.entityBytesList = entityBytesList;
        this.relationsList = relationsList;
        this.error = error;
    }
}
