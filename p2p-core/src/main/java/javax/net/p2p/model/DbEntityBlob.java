package javax.net.p2p.model;

public class DbEntityBlob {
    public String className;
    public byte[] bytes;
    
    public DbEntityBlob() {
    }
    
    public DbEntityBlob(String className, byte[] bytes) {
        this.className = className;
        this.bytes = bytes;
    }
}

