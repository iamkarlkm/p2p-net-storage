package javax.net.p2p.auth.model;

public class HandshakeResponse {
    private boolean ok;
    private String error;
    private String userId;
    private long serverTime;
    private byte[] nonce;
    private int xorKeyLength;
    private byte[] encryptedSeed;
    private byte[] signature;
    private int sigVersion;
    private int cryptoMode;
    private String keyFileId;
    private int keyFileOffset;

    public HandshakeResponse() {
    }

    public boolean isOk() {
        return ok;
    }

    public void setOk(boolean ok) {
        this.ok = ok;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public long getServerTime() {
        return serverTime;
    }

    public void setServerTime(long serverTime) {
        this.serverTime = serverTime;
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

    public byte[] getEncryptedSeed() {
        return encryptedSeed;
    }

    public void setEncryptedSeed(byte[] encryptedSeed) {
        this.encryptedSeed = encryptedSeed;
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

    public int getKeyFileOffset() {
        return keyFileOffset;
    }

    public void setKeyFileOffset(int keyFileOffset) {
        this.keyFileOffset = keyFileOffset;
    }
}

