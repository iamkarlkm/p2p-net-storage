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
}

