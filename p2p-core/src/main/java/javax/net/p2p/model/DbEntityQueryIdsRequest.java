package javax.net.p2p.model;

public class DbEntityQueryIdsRequest {
    public String className;
    public byte[] queryWrapperBytes;
    public int start;
    public int end;

    public DbEntityQueryIdsRequest() {
    }

    public DbEntityQueryIdsRequest(String className, byte[] queryWrapperBytes, int start, int end) {
        this.className = className;
        this.queryWrapperBytes = queryWrapperBytes;
        this.start = start;
        this.end = end;
    }
}

