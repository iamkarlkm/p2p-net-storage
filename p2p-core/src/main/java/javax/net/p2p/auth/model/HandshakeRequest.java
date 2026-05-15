package javax.net.p2p.auth.model;

public class HandshakeRequest {
    private String userId;
    private long timestamp;
    private byte[] nonce;
    private int xorKeyLength;
    private byte[] encryptedXorKey;
    private byte[] signature;
    private int sigVersion;
    private int cryptoMode;
    private String keyFileId;

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

    public int getSigVersion() {
        return sigVersion;
    }

    public void setSigVersion(int sigVersion) {
        this.sigVersion = sigVersion;
    }

    public int getCryptoMode() {
        return cryptoMode;
    }

    public void setCryptoMode(int cryptoMode) {
        this.cryptoMode = cryptoMode;
    }

    public String getKeyFileId() {
        return keyFileId;
    }

    public void setKeyFileId(String keyFileId) {
        this.keyFileId = keyFileId;
    }
}
