package javax.net.p2p.model;

import java.util.ArrayList;
import java.util.List;

public class RegistryListChildrenAck {
    public int sourceServerId = -1;
    public int sourcePartitionId = -1;
    public int targetServerId = -1;
    public int targetPartitionId = -1;
    public List<String> children = new ArrayList<>();
    public String error;

    public RegistryListChildrenAck() {}

    public RegistryListChildrenAck(int sourceServerId, int sourcePartitionId,
                                   int targetServerId, int targetPartitionId,
                                   List<String> children, String error) {
        this.sourceServerId = sourceServerId;
        this.sourcePartitionId = sourcePartitionId;
        this.targetServerId = targetServerId;
        this.targetPartitionId = targetPartitionId;
        if (children != null) this.children = children;
        this.error = error;
    }
}
