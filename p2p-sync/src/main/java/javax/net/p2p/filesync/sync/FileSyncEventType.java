package javax.net.p2p.filesync.sync;

public enum FileSyncEventType {
    CREATE(0),
    MODIFY(1),
    DELETE(2),
    RENAME(3),
    MOVE(4);

    private final int id;

    FileSyncEventType(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }

    public boolean isRenameKind() {
        return this == RENAME || this == MOVE;
    }

    public static FileSyncEventType fromId(int id) {
        for (FileSyncEventType t : values()) {
            if (t.id == id) {
                return t;
            }
        }
        throw new IllegalArgumentException("unknown event type id: " + id);
    }
}

