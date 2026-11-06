package javax.net.p2p.model;

import java.util.ArrayList;
import java.util.List;

public class ExtaRemoveTagsRequest {
    public int sourceServerId = -1;
    public int sourcePartitionId = -1;
    public int targetServerId = -1;
    public int targetPartitionId = -1;
    public long keyGlobalNodeId = 0L;
    public String entryKey;
    public List<String> tags = new ArrayList<>();

    public ExtaRemoveTagsRequest() {}

    public ExtaRemoveTagsRequest(int sourceServerId, int sourcePartitionId,
                                 int targetServerId, int targetPartitionId,
                                 long keyGlobalNodeId, String entryKey, List<String> tags) {
        this.sourceServerId = sourceServerId;
        this.sourcePartitionId = sourcePartitionId;
        this.targetServerId = targetServerId;
        this.targetPartitionId = targetPartitionId;
        this.keyGlobalNodeId = keyGlobalNodeId;
        this.entryKey = entryKey;
        this.tags = tags;
    }
}
