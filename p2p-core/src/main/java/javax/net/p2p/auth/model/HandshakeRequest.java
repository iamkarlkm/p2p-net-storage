package javax.net.p2p.auth.model;

public class HandshakeRequest {
    private String userId;
    private long timestamp;
    private byte[] nonce;
    private int xorKeyLength;
    private byte[] encryptedXorKey;
    private byte[] signature;

    public HandshakeRequest() {
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public byte[] getNonce() {
        return nonce;
    }

    public void setNonce(byte[] nonce) {
        this.nonce = nonce;
    }

    public int getXorKeyLength() {
        return xorKeyLength;
    }

    public void setXorKeyLength(int xorKeyLength) {
        this.xorKeyLength = xorKeyLength;
    }

    public byte[] getEncryptedXorKey() {
        return encryptedXorKey;
    }

    public void setEncryptedXorKey(byte[] encryptedXorKey) {
        this.encryptedXorKey = encryptedXorKey;
    }

    public byte[] getSignature() {
        return signature;
    }

    public void setSignature(byte[] signature) {
        this.signature = signature;
    }
}
