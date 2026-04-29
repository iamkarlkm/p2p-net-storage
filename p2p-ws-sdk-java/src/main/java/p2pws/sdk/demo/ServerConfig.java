package p2pws.sdk.demo;

public record ServerConfig(
    int listenPort,
    String wsPath,
    String keyfilePath,
    int magic,
    int version,
    int flagsPlain,
    int flagsEncrypted,
    int maxFramePayload,
    java.util.Map<Integer, String> storageLocations,
    java.util.Map<Integer, String> imStorageLocations
) {
}
