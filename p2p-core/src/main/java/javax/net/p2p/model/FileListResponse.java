package javax.net.p2p.model;

import java.util.List;

public class FileListResponse {
    public int page;
    public int pageSize;
    public int total;
    public List<FileListEntry> items;

    public FileListResponse() {
    }

    public FileListResponse(int page, int pageSize, int total, List<FileListEntry> items) {
        this.page = page;
        this.pageSize = pageSize;
        this.total = total;
        this.items = items;
    }
}

