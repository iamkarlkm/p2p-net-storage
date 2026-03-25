package javax.net.p2p.model;

/**
 * 消息类型枚举
 *
 * @author IM System
 * @version 1.0
 * @since 2026
 */
public enum MessageType {
    /** 普通文本消息 */
    TEXT("TEXT", "普通文本消息"),

    /** 图片消息 */
    IMAGE("IMAGE", "图片消息"),

    /** 文件消息 */
    FILE("FILE", "文件消息"),

    /** 语音消息 */
    VOICE("VOICE", "语音消息"),

    /** 视频消息 */
    VIDEO("VIDEO", "视频消息"),

    /** 系统通知 */
    SYSTEM("SYSTEM", "系统通知");

    private final String code;
    private final String description;

    MessageType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 根据代码获取枚举类型
     */
    public static MessageType fromCode(String code) {
        if (code == null) {
            return TEXT;
        }
        for (MessageType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return TEXT;
    }
}
