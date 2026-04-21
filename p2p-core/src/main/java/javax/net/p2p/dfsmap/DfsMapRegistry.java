package javax.net.p2p.dfsmap;

public final class DfsMapRegistry {
    private static volatile DfsMapBackend backend;

    private DfsMapRegistry() {
    }

    public static DfsMapBackend getBackend() {
        return backend;
    }

    public static void setBackend(DfsMapBackend backend) {
        DfsMapRegistry.backend = backend;
    }
}
