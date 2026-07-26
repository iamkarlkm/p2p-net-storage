package javax.net.p2p.filesync.sync;

public final class FileSyncEventCodec {

    private static final long TYPE_MASK = 0b11L;
    private static final int TYPE_BITS = 2;

    private FileSyncEventCodec() {
    }

    public static long encode(long fileId, FileSyncEventType type) {
        if (fileId < 0) {
            throw new IllegalArgumentException("fileId must be non-negative");
        }
        return (fileId << TYPE_BITS) | (type.getId() & TYPE_MASK);
    }

    public static long decodeFileId(long encoded) {
        return encoded >>> TYPE_BITS;
    }

    public static FileSyncEventType decodeType(long encoded) {
        int id = (int) (encoded & TYPE_MASK);
        return FileSyncEventType.fromId(id);
    }
}

