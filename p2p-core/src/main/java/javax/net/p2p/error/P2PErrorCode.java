package javax.net.p2p.error;

public enum P2PErrorCode {
    UNKNOWN(1000, "common.unknown", "unknown error", false),
    INTERNAL_ERROR(1001, "common.internal_error", "internal error", false),
    INVALID_REQUEST(1002, "common.invalid_request", "invalid request", false),
    DEADLINE_EXCEEDED(1003, "common.deadline_exceeded", "deadline exceeded", false),
    NOT_FOUND(1004, "common.not_found", "not found", false),
    UNSUPPORTED(1005, "common.unsupported", "unsupported", false),

    AUTH_HANDSHAKE_REQUIRED(1100, "auth.handshake_required", "handshake required", false),
    AUTH_LOGIN_REQUIRED(1101, "auth.login_required", "login required", false),
    AUTH_MISSING_USER_ID(1102, "auth.missing_user_id", "missing userId", false),
    AUTH_PERMISSION_DENIED(1103, "auth.permission_denied", "permission denied", false),

    SERVICE_UNAVAILABLE(1200, "service.unavailable", "service unavailable", true),
    SERVICE_BACKEND_NOT_REGISTERED(1201, "service.backend_not_registered", "backend not registered", true),

    ROUTING_UNKNOWN_COMMAND(1300, "routing.unknown_command", "unknown command", false),
    ROUTING_HANDLER_MISMATCH(1301, "routing.handler_mismatch", "handler mismatch", false),
    TASK_NOT_FOUND(1400, "task.not_found", "task not found", false),

    POLICY_REJECTED(1500, "policy.rejected", "policy rejected", false),

    FILE_NOT_FOUND(1600, "file.not_found", "file not found", false),
    FILE_NOT_A_DIRECTORY(1601, "file.not_a_directory", "not a directory", false),
    FILE_IO_ERROR(1602, "file.io_error", "file io error", false),
    FILE_OPERATION_FAILED(1603, "file.operation_failed", "file operation failed", false),
    FILE_MD5_MISMATCH(1604, "file.md5_mismatch", "md5 mismatch", false),
    FILE_LENGTH_MISMATCH(1605, "file.length_mismatch", "length mismatch", false),
    
    DB_INVALID_ENTITY_CLASS(1700, "db.invalid_entity_class", "invalid entity class", false),
    DB_ENTITY_NOT_FOUND(1701, "db.entity_not_found", "entity not found", false),
    DB_ENTITY_DECODE_FAILED(1702, "db.entity_decode_failed", "entity decode failed", false);

    private final int code;
    private final String key;
    private final String defaultMessage;
    private final boolean retriable;

    P2PErrorCode(int code, String key, String defaultMessage, boolean retriable) {
        this.code = code;
        this.key = key == null ? "" : key;
        this.defaultMessage = defaultMessage == null ? "" : defaultMessage;
        this.retriable = retriable;
    }

    public int code() {
        return code;
    }

    public String key() {
        return key;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    public boolean retriable() {
        return retriable;
    }
}
