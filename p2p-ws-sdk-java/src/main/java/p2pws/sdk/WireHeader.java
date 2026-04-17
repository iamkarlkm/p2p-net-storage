package p2pws.sdk;

public record WireHeader(long length, int magic, int version, int flags) {
    public static WireHeader ofCipherPayload(int magic, int version, int flags, byte[] cipherPayload) {
        return new WireHeader(cipherPayload == null ? 0 : cipherPayload.length, magic, version, flags);
    }
}
