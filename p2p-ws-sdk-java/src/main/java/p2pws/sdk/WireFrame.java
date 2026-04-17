package p2pws.sdk;

public record WireFrame(WireHeader header, byte[] cipherPayload) {
}

