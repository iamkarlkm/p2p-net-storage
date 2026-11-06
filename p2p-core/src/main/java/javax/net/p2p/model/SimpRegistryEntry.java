package javax.net.p2p.model;

public class SimpRegistryEntry {
    public String key;
    public boolean isDirectory;
    public int dataTypeCode;
    public long inlineValue;
    public String stringValue;
    public byte[] bytesValue;
    public long nodeId;

    public SimpRegistryEntry() {}

    public SimpRegistryEntry(String key, boolean isDirectory, int dataTypeCode,
                             long inlineValue, String stringValue, byte[] bytesValue, long nodeId) {
        this.key = key;
        this.isDirectory = isDirectory;
        this.dataTypeCode = dataTypeCode;
        this.inlineValue = inlineValue;
        this.stringValue = stringValue;
        this.bytesValue = bytesValue;
        this.nodeId = nodeId;
    }
}
