package javax.net.p2p.model;

import java.util.ArrayList;
import java.util.List;

public class RegistryFindByPrefixKeysAck {
    public int sourceServerId = -1;
    public int sourcePartitionId = -1;
    public int targetServerId = -1;
    public int targetPartitionId = -1;
    public List<String> keys = new ArrayList<>();
    public String error;

    public RegistryFindByPrefixKeysAck() {}

    public RegistryFindByPrefixKeysAck(int sourceServerId, int sourcePartitionId,
                                       int targetServerId, int targetPartitionId,
                                       List<String> keys, String error) {
        this.sourceServerId = sourceServerId;
        this.sourcePartitionId = sourcePartitionId;
        this.targetServerId = targetServerId;
        this.targetPartitionId = targetPartitionId;
        if (keys != null) this.keys = keys;
        this.error = error;
    }
}
