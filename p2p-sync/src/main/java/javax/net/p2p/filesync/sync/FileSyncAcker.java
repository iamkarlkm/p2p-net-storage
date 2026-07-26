package javax.net.p2p.filesync.sync;

public interface FileSyncAcker {
    void ack();

    void retry();

    default void fail(String reason) {
        retry();
    }
}
