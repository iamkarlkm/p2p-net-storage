package p2pws.sdk.center;

public record CenterConfig(
    int listenPort,
    String wsPath,
    String keyfilePath,
    String registeredUsersPath,
    int ttlSeconds,
    int magic,
    int version,
    int flagsPlain,
    int flagsEncrypted,
    int maxFramePayload
) {
}

