package javax.net.p2p.model;

public class DbEntityBinlogFrameRequest {
    public int sourceServerId;
    public int sourcePartitionId;
    public int targetServerId;
    public int targetPartitionId;
    public String startDayKey;
    public long startOffset;
    public long startSequence;
    public String endDayKey;
    public long endOffset;
    public long endSequence;
    public byte[] fixedFrames;
    public byte[] dynFrames;
    public byte[] bytesFrames;

    public DbEntityBinlogFrameRequest() {}

    public DbEntityBinlogFrameRequest(int sourceServerId, int sourcePartitionId,
                                      int targetServerId, int targetPartitionId,
                                      String startDayKey,
                                      long startOffset, long startSequence,
                                      String endDayKey,
                                      long endOffset, long endSequence,
                                      byte[] fixedFrames, byte[] dynFrames, byte[] bytesFrames) {
        this.sourceServerId = sourceServerId;
        this.sourcePartitionId = sourcePartitionId;
        this.targetServerId = targetServerId;
        this.targetPartitionId = targetPartitionId;
        this.startDayKey = startDayKey;
        this.startOffset = startOffset;
        this.startSequence = startSequence;
        this.endDayKey = endDayKey;
        this.endOffset = endOffset;
        this.endSequence = endSequence;
        this.fixedFrames = fixedFrames;
        this.dynFrames = dynFrames;
        this.bytesFrames = bytesFrames;
    }
}
