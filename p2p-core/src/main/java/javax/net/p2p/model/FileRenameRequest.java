package javax.net.p2p.model;

public class FileRenameRequest {
    public int storeId;
    public String srcPath;
    public String dstPath;

    public FileRenameRequest() {
    }

    public FileRenameRequest(int storeId, String srcPath, String dstPath) {
        this.storeId = storeId;
        this.srcPath = srcPath;
        this.dstPath = dstPath;
    }
}

