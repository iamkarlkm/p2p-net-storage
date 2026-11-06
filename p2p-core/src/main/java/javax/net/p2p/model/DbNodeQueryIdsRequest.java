package javax.net.p2p.model;

/**
 * 分布式节点分区查询请求。
 *
 * <p>使用 anchorRowId 高 12 位 serverId 与低 8 位 partitionId 定位目标节点与分区，
 * 在该分区的 ids 集合上执行查询（offset/limit）。</p>
 */
public class DbNodeQueryIdsRequest {
    public String entityClassName;
    public long anchorRowId;
    public DbQuery query;
    public int offset;
    public int limit;

    public DbNodeQueryIdsRequest() {
    }

    public DbNodeQueryIdsRequest(String entityClassName, long anchorRowId, DbQuery query, int offset, int limit) {
        this.entityClassName = entityClassName;
        this.anchorRowId = anchorRowId;
        this.query = query;
        this.offset = offset;
        this.limit = limit;
    }
}
