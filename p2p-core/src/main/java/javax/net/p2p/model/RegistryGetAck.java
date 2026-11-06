package javax.net.p2p.model;

public class RegistryGetAck {
    public int sourceServerId = -1;
    public int sourcePartitionId = -1;
    public int targetServerId = -1;
    public int targetPartitionId = -1;
    public boolean present;
    public boolean isDirectory;
    public int dataTypeCode;
    public long inlineValue;
    public String stringValue;
    public byte[] bytesValue;
    public long nodeId;
    public String error;

    public RegistryGetAck() {}

    public RegistryGetAck(int sourceServerId, int sourcePartitionId,
                          int targetServerId, int targetPartitionId,
                          boolean present, boolean isDirectory, int dataTypeCode,
                          long inlineValue, String stringValue, byte[] bytesValue,
                          long nodeId, String error) {
        this.sourceServerId = sourceServerId;
        this.sourcePartitionId = sourcePartitionId;
        this.targetServerId = targetServerId;
        this.targetPartitionId = targetPartitionId;
        this.present = present;
        this.isDirectory = isDirectory;
        this.dataTypeCode = dataTypeCode;
        this.inlineValue = inlineValue;
        this.stringValue = stringValue;
        this.bytesValue = bytesValue;
        this.nodeId = nodeId;
        this.error = error;
    }
}
