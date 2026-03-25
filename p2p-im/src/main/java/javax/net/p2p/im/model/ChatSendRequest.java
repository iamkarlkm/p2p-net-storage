package javax.net.p2p.im.model;

import java.io.Serializable;

/**
 * 聊天消息发送请求模型
 * 
 * @author IM System
 * @version 1.0
 * @since 2026
 */
public class ChatSendRequest implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /** 聊天消息（使用ChatMessage） */
    private javax.net.p2p.model.ChatMessage message;
    
    /** 发送时间戳 */
    private long timestamp;
    
    public ChatSendRequest() {
        this.timestamp = System.currentTimeMillis();
    }
    
    public ChatSendRequest(javax.net.p2p.model.ChatMessage message) {
        this.message = message;
        this.timestamp = System.currentTimeMillis();
    }
    
    // Getter和Setter
    public javax.net.p2p.model.ChatMessage getMessage() {
        return message;
    }
    
    public void setMessage(javax.net.p2p.model.ChatMessage message) {
        this.message = message;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
