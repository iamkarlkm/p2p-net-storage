package javax.net.p2p.dfsmap.model;

public final class DfsMapStatusCodes {
    public static final int OK = 0;
    public static final int NOT_FOUND = 1;
    public static final int RETRY = 2;
    public static final int BAD_REQUEST = 3;
    public static final int NOT_READY = 4;
    public static final int STALE_EPOCH = 5;
    public static final int NOT_OWNER = 6;
    public static final int ERROR = 100;

    private DfsMapStatusCodes() {
    }
}
