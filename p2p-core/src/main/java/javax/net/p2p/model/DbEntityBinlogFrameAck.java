package javax.net.p2p.model;

public class DbEntityBinlogFrameAck {
    public int sourceServerId;
    public int sourcePartitionId;
    public int targetServerId;
    public int targetPartitionId;
    public long appliedFrames;
    public String endDayKey;
    public long endOffset;
    public long endSequence;
    public String error;

    public DbEntityBinlogFrameAck() {}

    public DbEntityBinlogFrameAck(int sourceServerId, int sourcePartitionId,
                                  int targetServerId, int targetPartitionId,
                                  long appliedFrames,
                                  String endDayKey, long endOffset, long endSequence,
                                  String error) {
        this.sourceServerId = sourceServerId;
        this.sourcePartitionId = sourcePartitionId;
        this.targetServerId = targetServerId;
        this.targetPartitionId = targetPartitionId;
        this.appliedFrames = appliedFrames;
        this.endDayKey = endDayKey;
        this.endOffset = endOffset;
        this.endSequence = endSequence;
        this.error = error;
    }
}
