package javax.net.p2p.model;

/**
 * 分布式节点复制帧请求。
 *
 * <p>主节点在本地写入成功后，将同一列写入封装为复制帧发送给副本节点。
 * 副本节点在 {@code DsBinlogContext.setInReplay(true)} 线程上下文中应用该帧，
 * 直接写入本地 {@code ColumnarStore} 且不写本地 binlog。</p>
 */
public class DbNodeReplicateFrame {

    public String entityClassName;
    public long rowId;
    public String logicalName;
    public byte[] valueBytes;
    public boolean upsertRow;
    public boolean remove;

    public DbNodeReplicateFrame() {
    }

    public DbNodeReplicateFrame(String entityClassName, long rowId, String logicalName, byte[] valueBytes, boolean upsertRow, boolean remove) {
        this.entityClassName = entityClassName;
        this.rowId = rowId;
        this.logicalName = logicalName;
        this.valueBytes = valueBytes;
        this.upsertRow = upsertRow;
        this.remove = remove;
    }
}
