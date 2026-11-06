package javax.net.p2p.model;

/**
 * 分布式节点复制帧响应。
 */
public class DbNodeReplicateAck {

    public boolean ok;
    public String message;

    public DbNodeReplicateAck() {
    }

    public DbNodeReplicateAck(boolean ok, String message) {
        this.ok = ok;
        this.message = message;
    }
}
