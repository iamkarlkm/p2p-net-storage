package javax.net.p2p.model;

public class RegistryPutStringRequest {
    public int sourceServerId = -1;
    public int sourcePartitionId = -1;
    public int targetServerId = -1;
    public int targetPartitionId = -1;
    public long keyGlobalNodeId = 0L;
    public String keyPath;
    public int dataTypeCode;
    public String value;

    public RegistryPutStringRequest() {}

    public RegistryPutStringRequest(int sourceServerId, int sourcePartitionId,
                                     int targetServerId, int targetPartitionId,
                                     long keyGlobalNodeId, String keyPath,
                                     int dataTypeCode, String value) {
        this.sourceServerId = sourceServerId;
        this.sourcePartitionId = sourcePartitionId;
        this.targetServerId = targetServerId;
        this.targetPartitionId = targetPartitionId;
        this.keyGlobalNodeId = keyGlobalNodeId;
        this.keyPath = keyPath;
        this.dataTypeCode = dataTypeCode;
        this.value = value;
    }
}
