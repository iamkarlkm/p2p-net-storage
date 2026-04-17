package p2pws.sdk;

public interface KeyFileProvider {
    byte[] read(byte[] keyId32, long offset, int len);

    long length(byte[] keyId32);
}

