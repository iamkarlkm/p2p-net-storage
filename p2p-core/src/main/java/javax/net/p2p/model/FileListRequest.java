package javax.net.p2p.model;

public class FileListRequest {
    public int storeId;
    public String path;
    public int page;
    public int pageSize;

    public FileListRequest() {
    }

    public FileListRequest(int storeId, String path, int page, int pageSize) {
        this.storeId = storeId;
        this.path = path;
        this.page = page;
        this.pageSize = pageSize;
    }
}

