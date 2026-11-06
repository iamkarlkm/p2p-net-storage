package javax.net.p2p.model;

import java.util.ArrayList;
import java.util.List;

public class RegistryFindByPrefixAck {
    public int sourceServerId = -1;
    public int sourcePartitionId = -1;
    public int targetServerId = -1;
    public int targetPartitionId = -1;
    public List<SimpRegistryEntry> entries = new ArrayList<>();
    public String error;

    public RegistryFindByPrefixAck() {}

    public RegistryFindByPrefixAck(int sourceServerId, int sourcePartitionId,
                                   int targetServerId, int targetPartitionId,
                                   List<SimpRegistryEntry> entries, String error) {
        this.sourceServerId = sourceServerId;
        this.sourcePartitionId = sourcePartitionId;
        this.targetServerId = targetServerId;
        this.targetPartitionId = targetPartitionId;
        if (entries != null) this.entries = entries;
        this.error = error;
    }
}
