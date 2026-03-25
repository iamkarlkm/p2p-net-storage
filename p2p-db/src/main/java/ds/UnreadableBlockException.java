package ds;

import java.io.IOException;

public class UnreadableBlockException extends IOException {
    private final long offset;

    public UnreadableBlockException(String message, long offset) {
        super(message);
        this.offset = offset;
    }

    public long getOffset() {
        return offset;
    }
}
