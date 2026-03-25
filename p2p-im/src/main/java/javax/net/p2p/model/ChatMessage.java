package javax.net.p2p.model;

import java.io.Serializable;
import java.util.Objects;

/**
 * 聊天消息模型
 *
 * 功能说明：
 * 1. 存储聊天消息的基本信息（发送者、接收者、内容、时间等）
 * 2. 支持多种消息类型（文本、图片、文件等）
 * 3. 用于点对点聊天和群聊消息传输
 *
 * 消息类型说明：
 * - TEXT: 普通文本消息
 * - IMAGE: 图片消息
 * - FILE: 文件消息
 * - VOICE: 语音消息
 * - VIDEO: 视频消息
 * - SYSTEM: 系统通知
 *
 * 技术特点：
 * - 实现Serializable接口，支持Java序列化
 * - 使用标准JavaBean规范
 * - 支持消息状态跟踪（发送中、已发送、已读等）
 *
 * 使用场景：
 * - 点对点聊天消息传输
 * - 群聊消息广播
 * - 消息历史记录存储
 *
 * @author IM System
 * @version 1.0
 * @since 2026
 */
public class ChatMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 消息ID（全局唯一） */
    private String messageId;

    /** 发送者用户ID */
    private String senderId;

    /** 接收者用户ID（点对点聊天时使用） */
    private String receiverId;

    /** 群组ID（群聊时使用） */
    private String groupId;

    /** 消息内容 */
    private String content;

    /** 非文本信息 */
    private String contentId;

    /** 消息类型 */
    private MessageType messageType;

    /** 消息时间戳 */
    private long timestamp;

    /** 消息状态：0-发送中，1-已发送，2-已送达，3-已读 */
    private int status;



    /** 消息扩展数据（JSON格式） */
    private String extraData;

    public ChatMessage() {
        this.timestamp = System.currentTimeMillis();
        this.status = 0; // 默认发送中
        this.messageType = MessageType.TEXT;
    }
    
    public ChatMessage(String senderId, String receiverId, String content) {
        this();
        this.senderId = senderId;
        this.receiverId = receiverId;
        this.content = content;
        // 生成消息ID：时间戳 + 随机数
        this.messageId = "msg_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 10000);
    }
    
    public ChatMessage(String senderId, String groupId, String content, boolean isGroup) {
        this();
        this.senderId = senderId;
        if (isGroup) {
            this.groupId = groupId;
        } else {
            this.receiverId = groupId;
        }
        this.content = content;
        this.messageId = "msg_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 10000);
    }
    
    // Getter和Setter方法
    
    public String getMessageId() {
        return messageId;
    }
    
    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }
    
    public String getSenderId() {
        return senderId;
    }
    
    public void setSenderId(String senderId) {
        this.senderId = senderId;
    }
    
    public String getReceiverId() {
        return receiverId;
    }
    
    public void setReceiverId(String receiverId) {
        this.receiverId = receiverId;
    }
    
    public String getGroupId() {
        return groupId;
    }
    
    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }
    
    public String getContent() {
        return content;
    }
    
    public void setContent(String content) {
        this.content = content;
    }
    
    public String getMessageType() {
        return messageType != null ? messageType.getCode() : null;
    }

    public MessageType getMessageTypeEnum() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = MessageType.fromCode(messageType);
    }

    public void setMessageTypeEnum(MessageType messageType) {
        this.messageType = messageType;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
    
    public int getStatus() {
        return status;
    }
    
    public void setStatus(int status) {
        this.status = status;
    }

    public String getContentId() {
        return contentId;
    }

    public void setContentId(String contentId) {
        this.contentId = contentId;
    }
 
    
    
    public String getExtraData() {
        return extraData;
    }
    
    public void setExtraData(String extraData) {
        this.extraData = extraData;
    }
    
    /**
     * 判断是否为群聊消息
     */
    public boolean isGroupMessage() {
        return groupId != null && !groupId.isEmpty();
    }
    
    /**
     * 判断是否为文件消息
     */
    public boolean isFileMessage() {
        return messageType == MessageType.FILE;
    }

    /**
     * 判断是否为图片消息
     */
    public boolean isImageMessage() {
        return messageType == MessageType.IMAGE;
    }

    /**
     * 判断是否为文本消息
     */
    public boolean isTextMessage() {
        return messageType == MessageType.TEXT;
    }

    /**
     * 判断是否为语音消息
     */
    public boolean isVoiceMessage() {
        return messageType == MessageType.VOICE;
    }

    /**
     * 判断是否为视频消息
     */
    public boolean isVideoMessage() {
        return messageType == MessageType.VIDEO;
    }

    /**
     * 判断是否为系统消息
     */
    public boolean isSystemMessage() {
        return messageType == MessageType.SYSTEM;
    }
    
    /**
     * 更新消息状态
     */
    public void updateStatus(int newStatus) {
        this.status = newStatus;
    }

    @Override
    public int hashCode() {
        int hash = 5;
        hash = 29 * hash + Objects.hashCode(this.messageId);
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final ChatMessage other = (ChatMessage) obj;
        return Objects.equals(this.messageId, other.messageId);
    }
    
    
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ChatMessage{");
        sb.append("messageId='").append(messageId).append('\'');
        sb.append(", senderId='").append(senderId).append('\'');
        if (receiverId != null) {
            sb.append(", receiverId='").append(receiverId).append('\'');
        }
        if (groupId != null) {
            sb.append(", groupId='").append(groupId).append('\'');
        }
        sb.append(", content='").append(content).append('\'');
        sb.append(", messageType='").append(messageType != null ? messageType.getCode() : "null").append('\'');
        sb.append(", timestamp=").append(timestamp);
        sb.append(", status=").append(status);
        if (contentId != null) {
            sb.append(", contentId=").append(contentId);
        }
        sb.append('}');
        return sb.toString();
    }
}