package javax.net.p2p.model;

public class FileListEntry {
    public String name;
    public String path;
    public boolean isDir;
    public long size;
    public long modifiedMs;

    public FileListEntry() {
    }

    public FileListEntry(String name, String path, boolean isDir, long size, long modifiedMs) {
        this.name = name;
        this.path = path;
        this.isDir = isDir;
        this.size = size;
        this.modifiedMs = modifiedMs;
    }
}

